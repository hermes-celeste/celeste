package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.StoredSession
import java.util.Locale

/**
 * The identity used by the conversation catalog. A runtime gateway id is
 * deliberately absent: it is only valid for one live attachment.
 */
internal data class SessionKey(
    val originKey: String,
    val profile: String,
    val durableId: String,
) {
    init {
        require(originKey.isNotBlank()) { "A session key needs an origin." }
        require(profile.isNotBlank()) { "A session key needs a profile." }
        require(durableId.isNotBlank()) { "A session key needs a durable session id." }
    }

    companion object {
        fun from(origin: String, profile: String, durableId: String): SessionKey? {
            val normalizedOrigin = normalizeOrigin(origin)
            val normalizedProfile = normalizeProfile(profile)
            val normalizedId = durableId.trim()
            if (normalizedOrigin.isBlank() || normalizedProfile.isBlank() || normalizedId.isBlank()) {
                return null
            }
            return SessionKey(normalizedOrigin, normalizedProfile, normalizedId)
        }
    }
}

internal data class SessionScope(
    val originKey: String,
    val profile: String? = null,
) {
    init {
        require(originKey.isNotBlank()) { "A catalog scope needs an origin." }
    }

    val isProfileScoped: Boolean
        get() = !profile.isNullOrBlank()

    companion object {
        fun from(origin: String, profile: String): SessionScope? {
            val normalizedOrigin = normalizeOrigin(origin)
            val normalizedProfile = normalizeProfile(profile)
            if (normalizedOrigin.isBlank() || normalizedProfile.isBlank()) return null
            return SessionScope(normalizedOrigin, normalizedProfile)
        }

        fun unscoped(origin: String): SessionScope? {
            val normalizedOrigin = normalizeOrigin(origin)
            if (normalizedOrigin.isBlank()) return null
            return SessionScope(normalizedOrigin)
        }
    }

    fun accepts(key: SessionKey): Boolean =
        key.originKey == originKey && (profile == null || key.profile == profile)
}

/** Captures every generation that must still be current before a list publishes. */
internal data class SessionCatalogRequest(
    val scope: SessionScope,
    val originGeneration: Long,
    val requestGeneration: Long,
    val connectionAttempt: Long,
)

internal enum class SessionCatalogStatus {
    NotReady,
    Loading,
    Ready,
    Refreshing,
    Empty,
    NoResults,
    Stale,
    Error,
    Reconnecting,
    Opening,
    ActionInFlight,
}

/**
 * In-memory authoritative projection state. It is intentionally not
 * serializable or persisted; Hermes remains the catalog and transcript owner.
 */
internal data class SessionCatalogState(
    val phase: SessionCatalogStatus = SessionCatalogStatus.NotReady,
    val scope: SessionScope? = null,
    val rows: List<StoredSession> = emptyList(),
    val notice: UiNotice? = null,
    val query: String = "",
    val request: SessionCatalogRequest? = null,
    val queryResults: List<StoredSession>? = null,
    val queryInFlight: Boolean = false,
    val openingKey: SessionKey? = null,
) {
    val filteredRows: List<StoredSession>
        get() = queryResults ?: searchLoadedSessions(rows, query)

    /** No-results is a presentation state over a still-authoritative window. */
    val status: SessionCatalogStatus
        get() = if (
            query.isNotBlank() &&
            !queryInFlight &&
            filteredRows.isEmpty() &&
            phase == SessionCatalogStatus.Ready
        ) {
            SessionCatalogStatus.NoResults
        } else {
            phase
        }

    fun withQuery(value: String): SessionCatalogState = if (query == value) {
        this
    } else {
        copy(
            query = value,
            queryResults = if (value.isBlank()) null else emptyList(),
            queryInFlight = value.isNotBlank(),
        )
    }

    fun withSearchResults(value: String, results: List<StoredSession>): SessionCatalogState =
        if (query != value) {
            this
        } else {
            copy(
                queryResults = results,
                queryInFlight = false,
            )
        }
}

/**
 * Pure reducer for catalog transitions. ViewModel/network code supplies the
 * generation token; stale responses are rejected here as well as at the call
 * site so a future mutation or alternative caller cannot publish across scope.
 */
internal object SessionCatalogReducer {
    fun begin(
        state: SessionCatalogState,
        request: SessionCatalogRequest,
        refreshing: Boolean,
    ): SessionCatalogState {
        val keepRows = refreshing && state.scope == request.scope
        val nextRows = if (keepRows) state.rows else emptyList()
        return state.copy(
            phase = if (refreshing) SessionCatalogStatus.Refreshing else SessionCatalogStatus.Loading,
            scope = request.scope,
            rows = nextRows,
            notice = null,
            request = request,
            queryResults = if (state.query.isBlank()) null else if (keepRows) state.filteredRows else emptyList(),
            queryInFlight = false,
            openingKey = null,
        )
    }

    fun reconnecting(
        state: SessionCatalogState,
        scope: SessionScope,
        keepRows: Boolean,
    ): SessionCatalogState = state.copy(
        phase = SessionCatalogStatus.Reconnecting,
        scope = scope,
        rows = if (keepRows && state.scope == scope) state.rows else emptyList(),
        notice = null,
        request = null,
        queryResults = if (state.query.isBlank()) null else if (keepRows && state.scope == scope) state.filteredRows else emptyList(),
        queryInFlight = false,
        openingKey = null,
    )

    fun succeeded(
        state: SessionCatalogState,
        request: SessionCatalogRequest,
        rows: List<StoredSession>,
    ): SessionCatalogState {
        if (state.request != request || state.scope != request.scope) return state
        val filtered = filterAuthoritativeRows(request.scope, rows)
        return state.copy(
            phase = if (filtered.isEmpty()) SessionCatalogStatus.Empty else SessionCatalogStatus.Ready,
            rows = filtered,
            notice = null,
            request = null,
            queryResults = if (state.query.isBlank()) null else searchLoadedSessions(filtered, state.query),
            queryInFlight = false,
            openingKey = null,
        )
    }

    fun failed(
        state: SessionCatalogState,
        request: SessionCatalogRequest,
        notice: UiNotice,
    ): SessionCatalogState {
        if (state.request != request || state.scope != request.scope) return state
        return state.copy(
            phase = if (state.rows.isEmpty()) SessionCatalogStatus.Error else SessionCatalogStatus.Stale,
            notice = notice,
            request = null,
            queryInFlight = false,
            openingKey = null,
        )
    }

    fun opening(state: SessionCatalogState, key: SessionKey): SessionCatalogState = state.copy(
        phase = SessionCatalogStatus.Opening,
        notice = null,
        request = null,
        queryInFlight = false,
        openingKey = key,
    )

    fun openingSucceeded(state: SessionCatalogState): SessionCatalogState = state.copy(
        phase = if (state.rows.isEmpty()) SessionCatalogStatus.Empty else SessionCatalogStatus.Ready,
        notice = null,
        request = null,
        queryInFlight = false,
        openingKey = null,
    )

    fun openingFailed(
        state: SessionCatalogState,
        notice: UiNotice,
    ): SessionCatalogState = state.copy(
        phase = if (state.rows.isEmpty()) SessionCatalogStatus.Error else SessionCatalogStatus.Stale,
        notice = notice,
        request = null,
        queryInFlight = false,
        openingKey = null,
    )

    fun openingCancelled(state: SessionCatalogState): SessionCatalogState = state.copy(
        phase = if (state.rows.isEmpty()) SessionCatalogStatus.Empty else SessionCatalogStatus.Ready,
        notice = null,
        request = null,
        queryInFlight = false,
        openingKey = null,
    )

    fun actionStarted(state: SessionCatalogState): SessionCatalogState = state.copy(
        phase = SessionCatalogStatus.ActionInFlight,
        notice = null,
        request = null,
        queryInFlight = false,
        openingKey = null,
    )

    fun actionFailed(
        state: SessionCatalogState,
        notice: UiNotice,
    ): SessionCatalogState = state.copy(
        phase = if (state.rows.isEmpty()) SessionCatalogStatus.Error else SessionCatalogStatus.Stale,
        notice = notice,
        request = null,
        queryInFlight = false,
        openingKey = null,
    )

    fun actionCancelled(state: SessionCatalogState): SessionCatalogState = state.copy(
        phase = if (state.rows.isEmpty()) SessionCatalogStatus.Empty else SessionCatalogStatus.Ready,
        notice = null,
        request = null,
        queryInFlight = false,
        openingKey = null,
    )

    /**
     * Hermes session.list is origin-scoped but currently does not identify the
     * owning profile. Keep every durable row in server order for an unscoped
     * response, including rows with unknown ownership. A genuinely
     * profile-scoped response may publish only rows with matching verified
     * ownership. Unknown rows are never reassigned to the creation target.
     */
    internal fun filterAuthoritativeRows(
        scope: SessionScope,
        rows: List<StoredSession>,
    ): List<StoredSession> {
        val origin = normalizeOrigin(scope.originKey)
        if (origin.isBlank()) return emptyList()
        val seen = linkedSetOf<String>()
        return rows.filter { row ->
            val rowId = row.id.trim()
            if (rowId.isBlank()) return@filter false
            if (scope.isProfileScoped) {
                val key = row.keyFor(origin) ?: return@filter false
                if (!scope.accepts(key)) return@filter false
            }
            val profileIdentity = normalizeProfile(row.profile).ifBlank { UNKNOWN_PROFILE }
            seen.add("$origin\u0000$profileIdentity\u0000$rowId")
        }
    }
}

/** Only server-supplied alternate identities may be registered here. */
internal data class VerifiedSessionIdentity(
    val key: SessionKey,
    val aliases: Set<String> = emptySet(),
)

internal class SessionAliasIndex {
    private val aliasesByKey = linkedMapOf<SessionKey, Set<String>>()

    fun replace(scope: SessionScope, identities: Iterable<VerifiedSessionIdentity>) {
        aliasesByKey.keys.removeAll { key -> scope.accepts(key) }
        identities.forEach { identity ->
            if (scope.accepts(identity.key)) {
                aliasesByKey[identity.key] = identity.aliases
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
            }
        }
    }

    fun resolve(scope: SessionScope, value: String): SessionKey? {
        val candidate = value.trim()
        if (candidate.isBlank()) return null
        return aliasesByKey.entries.firstOrNull { (key, aliases) ->
            scope.accepts(key) &&
                (key.durableId == candidate || candidate in aliases)
        }?.key
    }

    fun clear() = aliasesByKey.clear()
}

internal fun StoredSession.keyFor(origin: String): SessionKey? =
    SessionKey.from(origin, profile, id)

internal fun StoredSession.catalogRowKey(origin: String): String? {
    val normalizedOrigin = normalizeOrigin(origin)
    val normalizedId = id.trim()
    if (normalizedOrigin.isBlank() || normalizedId.isBlank()) return null
    val profileIdentity = normalizeProfile(profile).ifBlank { UNKNOWN_PROFILE }
    return "$normalizedOrigin\u0000$profileIdentity\u0000$normalizedId"
}

private const val UNKNOWN_PROFILE = "<unknown-profile>"

internal fun normalizeProfile(value: String): String =
    value.trim().lowercase(Locale.ROOT)

internal fun normalizeOrigin(value: String): String =
    value.trim().trimEnd('/').lowercase(Locale.ROOT)

internal fun searchLoadedSessions(
    rows: List<StoredSession>,
    query: String,
): List<StoredSession> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isBlank()) return rows
    return rows.filter { row ->
        sequenceOf(row.title, row.preview, row.id, row.profile, row.source)
            .any { it.lowercase(Locale.ROOT).contains(needle) }
    }
}
