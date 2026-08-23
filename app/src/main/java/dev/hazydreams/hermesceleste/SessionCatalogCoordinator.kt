package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.StoredSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val SESSION_PAGE_SIZE = 15

internal data class SessionCatalogAccess(
    val baseUrl: String,
    val credential: GatewayCredential,
)

internal data class SessionCatalogState(
    val sessions: List<StoredSession>?,
    val total: Int,
    val nextOffset: Int,
    val hasMore: Boolean,
    val isLoadingMore: Boolean,
    val pageError: String?,
    val searchQuery: String,
    val searchResults: List<StoredSession>,
    val isSearching: Boolean,
    val searchError: String?,
    val actionError: String?,
    val activeSummary: StoredSession?,
)

internal fun CelesteUiState.sessionCatalogState(): SessionCatalogState = SessionCatalogState(
    sessions = sessions,
    total = sessionCatalogTotal,
    nextOffset = nextSessionOffset,
    hasMore = hasMoreSessions,
    isLoadingMore = isLoadingMoreSessions,
    pageError = sessionPageError,
    searchQuery = sessionSearchQuery,
    searchResults = sessionSearchResults,
    isSearching = isSearchingSessions,
    searchError = sessionSearchError,
    actionError = sessionActionError,
    activeSummary = activeSummary,
)

internal fun CelesteUiState.withSessionCatalogState(catalog: SessionCatalogState): CelesteUiState = copy(
    sessions = catalog.sessions,
    sessionCatalogTotal = catalog.total,
    nextSessionOffset = catalog.nextOffset,
    hasMoreSessions = catalog.hasMore,
    isLoadingMoreSessions = catalog.isLoadingMore,
    sessionPageError = catalog.pageError,
    sessionSearchQuery = catalog.searchQuery,
    sessionSearchResults = catalog.searchResults,
    isSearchingSessions = catalog.isSearching,
    sessionSearchError = catalog.searchError,
    sessionActionError = catalog.actionError,
    activeSummary = catalog.activeSummary,
)

internal class SessionCatalogCoordinator(
    private val scope: CoroutineScope,
    private val dashboard: DashboardService,
    private val readState: () -> SessionCatalogState,
    private val writeState: (SessionCatalogState) -> Unit,
) {
    private var pageJob: Job? = null
    private var searchJob: Job? = null
    private val metadataJobs = mutableSetOf<Job>()
    private var generation = 0L
    private var pageAttempt = 0L
    private var searchAttempt = 0L
    private var actionAttempt = 0L
    private val pinAttempts = mutableMapOf<String, Long>()
    private val renameAttempts = mutableMapOf<String, Long>()

    fun updateSearchQuery(
        value: String,
        access: SessionCatalogAccess?,
        profile: String,
    ) {
        searchJob?.cancel()
        searchJob = null
        val requestAttempt = ++searchAttempt
        val trimmedQuery = value.trim()
        val snapshot = readState()
        writeState(
            snapshot.copy(
                searchQuery = value,
                searchResults = searchLoadedSessions(snapshot.sessions.orEmpty(), trimmedQuery),
                isSearching = trimmedQuery.isNotEmpty(),
                searchError = null,
            ),
        )
        if (trimmedQuery.isEmpty()) return
        if (access == null) {
            writeState(readState().copy(isSearching = false))
            return
        }

        val requestGeneration = generation
        searchJob = scope.launch {
            delay(SESSION_SEARCH_DEBOUNCE_MILLIS)
            try {
                val remoteMatches = dashboard.searchSessions(
                    baseUrl = access.baseUrl,
                    credential = access.credential,
                    query = trimmedQuery,
                    profile = profile,
                    limit = SESSION_SEARCH_LIMIT,
                )
                if (!isCurrent(requestGeneration) || searchAttempt != requestAttempt) return@launch
                val current = readState()
                val catalog = current.sessions.orEmpty()
                writeState(
                    current.copy(
                        searchResults = mergeSessionSearchResults(
                            loaded = searchLoadedSessions(catalog, trimmedQuery),
                            catalog = catalog,
                            remote = remoteMatches,
                        ),
                        isSearching = false,
                        searchError = null,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(requestGeneration) || searchAttempt != requestAttempt) return@launch
                writeState(
                    readState().copy(
                        isSearching = false,
                        searchError = error.message ?: "Could not search conversations.",
                    ),
                )
            } finally {
                if (searchAttempt == requestAttempt) searchJob = null
            }
        }
    }

    fun setPinned(
        summary: StoredSession,
        pinned: Boolean,
        access: SessionCatalogAccess,
    ) {
        val sessionId = summary.id
        val previousPinned = currentSession(sessionId)?.pinned ?: summary.pinned
        val requestAttempt = ++actionAttempt
        val requestGeneration = generation
        pinAttempts[sessionId] = requestAttempt
        updateSession(sessionId, actionError = null) { it.copy(pinned = pinned) }

        scope.launch {
            try {
                val confirmedPinned = dashboard.setSessionPinned(
                    baseUrl = access.baseUrl,
                    credential = access.credential,
                    sessionId = sessionId,
                    profile = summary.profile,
                    pinned = pinned,
                )
                if (!isCurrentAction(pinAttempts, sessionId, requestAttempt, requestGeneration)) return@launch
                updateSession(sessionId, actionError = null) { it.copy(pinned = confirmedPinned) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentAction(pinAttempts, sessionId, requestAttempt, requestGeneration)) return@launch
                updateSession(
                    sessionId = sessionId,
                    actionError = boundedActionError(error, "Could not update that pin."),
                ) { it.copy(pinned = previousPinned) }
            } finally {
                if (pinAttempts[sessionId] == requestAttempt) pinAttempts.remove(sessionId)
            }
        }
    }

    fun rename(
        summary: StoredSession,
        title: String,
        access: SessionCatalogAccess?,
        onComplete: (String?) -> Unit,
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            onComplete("Enter a conversation name.")
            return
        }
        if (access == null) {
            onComplete("Hermes is not connected.")
            return
        }
        val sessionId = summary.id
        val requestAttempt = ++actionAttempt
        val requestGeneration = generation
        renameAttempts[sessionId] = requestAttempt

        scope.launch {
            try {
                val confirmedTitle = dashboard.renameSession(
                    baseUrl = access.baseUrl,
                    credential = access.credential,
                    sessionId = sessionId,
                    profile = summary.profile,
                    title = trimmedTitle,
                )
                if (!isCurrentAction(renameAttempts, sessionId, requestAttempt, requestGeneration)) return@launch
                updateSession(sessionId, actionError = null) { it.copy(title = confirmedTitle) }
                onComplete(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentAction(renameAttempts, sessionId, requestAttempt, requestGeneration)) return@launch
                onComplete(boundedActionError(error, "Could not rename that conversation."))
            } finally {
                if (renameAttempts[sessionId] == requestAttempt) renameAttempts.remove(sessionId)
            }
        }
    }

    fun prepareSessionOpen(
        summary: StoredSession,
        access: SessionCatalogAccess,
    ): StoredSession {
        cancelPageLoad()
        val visibleSummary = if (summary.unread) summary.copy(unread = false) else summary
        val current = readState()
        writeState(
            current.copy(
                sessions = current.sessions?.map { session ->
                    if (session.id == summary.id) visibleSummary else session
                },
                searchResults = current.searchResults.map { session ->
                    if (session.id == summary.id) visibleSummary else session
                },
                isLoadingMore = false,
            ),
        )
        if (summary.unread) {
            launchMetadataUpdate {
                dashboard.markSessionRead(
                    baseUrl = access.baseUrl,
                    credential = access.credential,
                    sessionId = summary.id,
                    profile = summary.profile,
                )
            }
        }
        return visibleSummary
    }

    fun loadMore(
        access: SessionCatalogAccess,
        conversationIsLoading: Boolean,
    ) {
        val snapshot = readState()
        if (
            snapshot.sessions == null ||
            snapshot.searchQuery.isNotBlank() ||
            !snapshot.hasMore ||
            snapshot.isLoadingMore ||
            conversationIsLoading
        ) {
            return
        }
        val requestedOffset = snapshot.nextOffset
        val requestGeneration = generation
        val requestAttempt = pageAttempt
        writeState(snapshot.copy(isLoadingMore = true, pageError = null))
        pageJob = scope.launch {
            try {
                val page = dashboard.listSessions(
                    baseUrl = access.baseUrl,
                    credential = access.credential,
                    limit = SESSION_PAGE_SIZE,
                    offset = requestedOffset,
                )
                if (!isCurrent(requestGeneration) || pageAttempt != requestAttempt) return@launch
                val current = readState()
                val nextOffset = page.nextOffset
                writeState(
                    current.copy(
                        sessions = mergeSessionCatalog(current.sessions.orEmpty(), page.sessions),
                        total = page.total,
                        nextOffset = nextOffset,
                        hasMore = page.hasMore && nextOffset > requestedOffset,
                        isLoadingMore = false,
                        pageError = null,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(requestGeneration) || pageAttempt != requestAttempt) return@launch
                writeState(
                    readState().copy(
                        isLoadingMore = false,
                        pageError = error.message ?: "Could not load older conversations.",
                    ),
                )
            } finally {
                if (pageAttempt == requestAttempt) pageJob = null
            }
        }
    }

    fun invalidateConnection() {
        generation += 1
        cancelPageLoad()
        cancelSearch()
        metadataJobs.toList().forEach(Job::cancel)
        metadataJobs.clear()
        pinAttempts.clear()
        renameAttempts.clear()
    }

    private fun cancelPageLoad() {
        pageAttempt += 1
        pageJob?.cancel()
        pageJob = null
    }

    private fun cancelSearch() {
        searchAttempt += 1
        searchJob?.cancel()
        searchJob = null
    }

    private fun launchMetadataUpdate(block: suspend () -> Unit) {
        val requestGeneration = generation
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (isCurrent(requestGeneration)) block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Metadata acknowledgement never blocks opening the conversation.
            } finally {
                metadataJobs.remove(job)
            }
        }
        metadataJobs += job
        job.start()
    }

    private fun currentSession(sessionId: String): StoredSession? {
        val current = readState()
        return current.sessions?.firstOrNull { it.id == sessionId }
            ?: current.searchResults.firstOrNull { it.id == sessionId }
            ?: current.activeSummary?.takeIf { it.id == sessionId }
    }

    private fun updateSession(
        sessionId: String,
        actionError: String?,
        transform: (StoredSession) -> StoredSession,
    ) {
        val current = readState()
        writeState(
            current.copy(
                sessions = current.sessions?.map { session ->
                    if (session.id == sessionId) transform(session) else session
                },
                searchResults = current.searchResults.map { session ->
                    if (session.id == sessionId) transform(session) else session
                },
                activeSummary = current.activeSummary?.let { session ->
                    if (session.id == sessionId) transform(session) else session
                },
                actionError = actionError,
            ),
        )
    }

    private fun isCurrent(requestGeneration: Long): Boolean = generation == requestGeneration

    private fun isCurrentAction(
        attempts: Map<String, Long>,
        sessionId: String,
        requestAttempt: Long,
        requestGeneration: Long,
    ): Boolean = attempts[sessionId] == requestAttempt && isCurrent(requestGeneration)
}

internal fun mergeSessionCatalog(
    existing: List<StoredSession>,
    incoming: List<StoredSession>,
): List<StoredSession> {
    val remaining = LinkedHashMap<String, StoredSession>()
    incoming.forEach { session -> remaining[session.id] = session }
    return buildList {
        existing.forEach { session -> add(remaining.remove(session.id) ?: session) }
        addAll(remaining.values)
    }
}

private fun searchLoadedSessions(
    sessions: List<StoredSession>,
    query: String,
): List<StoredSession> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return sessions.filter { session ->
        listOfNotNull(
            session.id,
            session.title,
            session.preview,
            session.source,
            session.profile,
            session.model,
        ).any { value -> needle in value.lowercase() }
    }
}

private fun mergeSessionSearchResults(
    loaded: List<StoredSession>,
    catalog: List<StoredSession>,
    remote: List<StoredSession>,
): List<StoredSession> = buildList {
    val catalogById = catalog.associateBy(StoredSession::id)
    val seen = mutableSetOf<String>()
    val reconciledRemote = remote.map { match ->
        catalogById[match.id]?.let { catalogSession ->
            catalogSession.copy(preview = match.preview.ifBlank { catalogSession.preview })
        } ?: match
    }
    (loaded + reconciledRemote).forEach { session ->
        if (seen.add(session.id)) add(session)
    }
}

private fun boundedActionError(error: Throwable, fallback: String): String =
    error.message?.takeIf(String::isNotBlank)?.take(MAX_SESSION_ACTION_ERROR_LENGTH) ?: fallback

private const val SESSION_SEARCH_LIMIT = 20
private const val SESSION_SEARCH_DEBOUNCE_MILLIS = 200L
private const val MAX_SESSION_ACTION_ERROR_LENGTH = 160
