package dev.hazydreams.hermesceleste

import java.io.IOException

import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.SessionCatalogPage
import dev.hazydreams.hermesceleste.network.StoredSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCatalogCoordinatorTest {
    @Test
    fun pinMovesImmediatelyAndPersistsWithTheOwningProfile() = runTest {
        val dashboard = FakeCatalogDashboard().apply {
            pinGates[true] = CompletableDeferred()
        }
        val harness = harness(dashboard, listOf(dashboard.session.copy(profile = "work", pinned = false)))

        harness.coordinator.setPinned(harness.state.sessions!!.single(), true, ACCESS)

        assertEquals(true, harness.state.sessions?.single()?.pinned)
        runCurrent()
        assertEquals(listOf(Triple("stored-42", "work", true)), dashboard.pinRequests)

        dashboard.pinGates.getValue(true).complete(Unit)
        advanceUntilIdle()

        assertEquals(true, harness.state.sessions?.single()?.pinned)
        assertNull(harness.state.actionError)
    }

    @Test
    fun failedPinRollsBackOnlyThePinFieldAndShowsAnError() = runTest {
        val dashboard = FakeCatalogDashboard().apply {
            pinFailures[true] = IOException("Pin rejected")
        }
        val original = dashboard.session.copy(title = "Original title", pinned = false)
        val harness = harness(dashboard, listOf(original))

        harness.coordinator.setPinned(original, true, ACCESS)
        advanceUntilIdle()

        val restored = harness.state.sessions?.single()
        assertEquals(false, restored?.pinned)
        assertEquals("Original title", restored?.title)
        assertEquals("Pin rejected", harness.state.actionError)
    }

    @Test
    fun stalePinCompletionCannotOverwriteTheLatestIntent() = runTest {
        val dashboard = FakeCatalogDashboard().apply {
            pinGates[true] = CompletableDeferred()
            pinGates[false] = CompletableDeferred()
        }
        val original = dashboard.session.copy(pinned = false)
        val harness = harness(dashboard, listOf(original))

        harness.coordinator.setPinned(original, true, ACCESS)
        runCurrent()
        harness.coordinator.setPinned(original.copy(pinned = true), false, ACCESS)
        runCurrent()
        dashboard.pinGates.getValue(false).complete(Unit)
        runCurrent()
        dashboard.pinGates.getValue(true).complete(Unit)
        advanceUntilIdle()

        assertEquals(false, harness.state.sessions?.single()?.pinned)
        assertEquals(
            listOf(
                Triple("stored-42", "default", true),
                Triple("stored-42", "default", false),
            ),
            dashboard.pinRequests,
        )
    }

    @Test
    fun renameWaitsForHermesAndRejectsStaleCompletions() = runTest {
        val dashboard = FakeCatalogDashboard().apply {
            renameGates["First title"] = CompletableDeferred()
            renameGates["Final title"] = CompletableDeferred()
        }
        val original = dashboard.session.copy(title = "Original title", profile = "work")
        val harness = harness(dashboard, listOf(original))
        var completion: String? = "waiting"

        harness.coordinator.rename(original, "  First title  ", ACCESS) { completion = it }
        runCurrent()
        assertEquals("Original title", harness.state.sessions?.single()?.title)
        assertEquals(listOf(Triple("stored-42", "work", "First title")), dashboard.renameRequests)

        harness.coordinator.rename(original, "Final title", ACCESS) { completion = it }
        runCurrent()
        dashboard.renameGates.getValue("Final title").complete(Unit)
        runCurrent()
        dashboard.renameGates.getValue("First title").complete(Unit)
        advanceUntilIdle()

        assertEquals("Final title", harness.state.sessions?.single()?.title)
        assertNull(completion)
    }

    @Test
    fun renameReturnsValidationAndHermesErrorsWithoutReplacingTheTitle() = runTest {
        val dashboard = FakeCatalogDashboard().apply {
            renameFailures["Rejected title"] = IOException("That title is already in use")
        }
        val original = dashboard.session.copy(title = "Original title")
        val harness = harness(dashboard, listOf(original))
        var failure: String? = null

        harness.coordinator.rename(original, "   ", ACCESS) { failure = it }
        assertEquals("Enter a conversation name.", failure)

        harness.coordinator.rename(original, "Rejected title", ACCESS) { failure = it }
        advanceUntilIdle()

        assertEquals("Original title", harness.state.sessions?.single()?.title)
        assertEquals("That title is already in use", failure)
    }

    @Test
    fun searchMatchesLoadedRowsThenMergesServerHistoryAfterDebounce() = runTest {
        val dashboard = FakeCatalogDashboard()
        val localMatch = dashboard.session.copy(id = "loaded-notes", title = "Dashboard connection notes")
        val other = dashboard.session.copy(id = "other", title = "Weekend plans")
        dashboard.searchResults["notes"] = listOf(
            localMatch.copy(title = "Server copy"),
            dashboard.session.copy(id = "older-notes", title = "Older release notes"),
        )
        val harness = harness(dashboard, listOf(localMatch, other))

        harness.coordinator.updateSearchQuery("notes", ACCESS, profile = "default")

        assertEquals(listOf("loaded-notes"), harness.state.searchResults.map(StoredSession::id))
        assertTrue(harness.state.isSearching)
        assertTrue(dashboard.searchRequests.isEmpty())

        testScheduler.advanceTimeBy(199)
        testScheduler.runCurrent()
        assertTrue(dashboard.searchRequests.isEmpty())
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()

        assertEquals(listOf("loaded-notes", "older-notes"), harness.state.searchResults.map(StoredSession::id))
        assertEquals("Dashboard connection notes", harness.state.searchResults.first().title)
        assertEquals(listOf(Triple("notes", "default", 20)), dashboard.searchRequests)
        assertFalse(harness.state.isSearching)

        harness.coordinator.updateSearchQuery("", ACCESS, profile = "default")
        assertTrue(harness.state.searchResults.isEmpty())
        assertEquals("", harness.state.searchQuery)
    }

    @Test
    fun staleSessionSearchCannotReplaceANewerQuery() = runTest {
        val dashboard = FakeCatalogDashboard().apply {
            returnSearchAfterCancellation = true
            searchGates["first"] = CompletableDeferred()
            searchResults["first"] = listOf(session.copy(id = "first-result", title = "First result"))
            searchResults["second"] = listOf(session.copy(id = "second-result", title = "Second result"))
        }
        val harness = harness(dashboard, listOf(dashboard.session))

        harness.coordinator.updateSearchQuery("first", ACCESS, profile = "default")
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()
        harness.coordinator.updateSearchQuery("second", ACCESS, profile = "default")
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        assertEquals(listOf("second-result"), harness.state.searchResults.map(StoredSession::id))
        dashboard.searchGates.getValue("first").complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("second-result"), harness.state.searchResults.map(StoredSession::id))
        assertEquals("second", harness.state.searchQuery)
    }

    @Test
    fun remoteContentMatchKeepsCatalogMetadataAndUsesTheSnippet() = runTest {
        val dashboard = FakeCatalogDashboard()
        val loaded = dashboard.session.copy(
            id = "loaded-content-match",
            title = "Pinned conversation",
            preview = "Original catalog preview",
            profile = "work",
            model = "catalog-model",
            pinned = true,
            unread = true,
        )
        dashboard.searchResults["needle"] = listOf(
            loaded.copy(
                title = "Sparse remote title",
                preview = "Matched needle in an older message",
                profile = "default",
                model = null,
                pinned = null,
                unread = false,
            ),
        )
        val harness = harness(dashboard, listOf(loaded))

        harness.coordinator.updateSearchQuery("needle", ACCESS, profile = "default")
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        val result = harness.state.searchResults.single()
        assertEquals("Pinned conversation", result.title)
        assertEquals("Matched needle in an older message", result.preview)
        assertEquals("work", result.profile)
        assertEquals("catalog-model", result.model)
        assertEquals(true, result.pinned)
        assertTrue(result.unread)
    }

    @Test
    fun openingUnreadSessionUpdatesTheCatalogAndAcknowledgesHermes() = runTest {
        val dashboard = FakeCatalogDashboard()
        val unread = dashboard.session.copy(profile = "work", unread = true)
        val harness = harness(dashboard, listOf(unread))

        val visible = harness.coordinator.prepareSessionOpen(unread, ACCESS)

        assertFalse(visible.unread)
        assertFalse(harness.state.sessions?.single()?.unread ?: true)
        advanceUntilIdle()
        assertEquals(listOf("stored-42" to "work"), dashboard.markReadRequests)
    }

    @Test
    fun nextPageLoadsOnceAndDeduplicatesPinnedBackfill() = runTest {
        val dashboard = FakeCatalogDashboard()
        val recent = dashboard.session.copy(id = "recent-1", title = "Recent")
        val pinned = dashboard.session.copy(id = "pinned-old", title = "Pinned old", pinned = true, unread = true)
        dashboard.pages[15] = SessionCatalogPage(
            sessions = listOf(
                dashboard.session.copy(id = "recent-16", title = "Older"),
                pinned.copy(title = "Pinned refreshed", unread = false),
            ),
            total = 30,
            limit = 15,
            offset = 15,
        )
        dashboard.pageGate = CompletableDeferred()
        val harness = harness(
            dashboard = dashboard,
            sessions = listOf(recent, pinned),
            total = 30,
            nextOffset = 15,
            hasMore = true,
        )

        harness.coordinator.loadMore(ACCESS, conversationIsLoading = false)
        runCurrent()
        assertTrue(harness.state.isLoadingMore)
        harness.coordinator.loadMore(ACCESS, conversationIsLoading = false)
        dashboard.pageGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(15 to 15), dashboard.pageRequests)
        assertEquals(listOf("recent-1", "pinned-old", "recent-16"), harness.state.sessions?.map(StoredSession::id))
        assertEquals("Pinned refreshed", harness.state.sessions?.first { it.id == "pinned-old" }?.title)
        assertFalse(harness.state.sessions?.first { it.id == "pinned-old" }?.unread ?: true)
        assertEquals(30, harness.state.nextOffset)
        assertFalse(harness.state.hasMore)
        assertFalse(harness.state.isLoadingMore)
    }

    @Test
    fun failedPageKeepsLoadedRowsAndCanRetry() = runTest {
        val dashboard = FakeCatalogDashboard()
        val first = dashboard.session.copy(id = "recent-1")
        val older = dashboard.session.copy(id = "recent-16")
        dashboard.pages[15] = SessionCatalogPage(listOf(older), total = 30, limit = 15, offset = 15)
        dashboard.pageFailures[15] = IOException("synthetic older-page failure")
        val harness = harness(dashboard, listOf(first), total = 30, nextOffset = 15, hasMore = true)

        harness.coordinator.loadMore(ACCESS, conversationIsLoading = false)
        advanceUntilIdle()

        assertEquals(listOf("recent-1"), harness.state.sessions?.map(StoredSession::id))
        assertEquals("synthetic older-page failure", harness.state.pageError)
        assertTrue(harness.state.hasMore)
        assertFalse(harness.state.isLoadingMore)

        dashboard.pageFailures.remove(15)
        harness.coordinator.loadMore(ACCESS, conversationIsLoading = false)
        advanceUntilIdle()

        assertEquals(listOf("recent-1", "recent-16"), harness.state.sessions?.map(StoredSession::id))
        assertEquals(listOf(15 to 15, 15 to 15), dashboard.pageRequests)
        assertNull(harness.state.pageError)
        assertFalse(harness.state.hasMore)
    }

    private fun kotlinx.coroutines.test.TestScope.harness(
        dashboard: FakeCatalogDashboard,
        sessions: List<StoredSession>,
        total: Int = sessions.size,
        nextOffset: Int = sessions.size,
        hasMore: Boolean = false,
    ): Harness {
        lateinit var state: SessionCatalogState
        val coordinator = SessionCatalogCoordinator(
            scope = this,
            dashboard = dashboard,
            readState = { state },
            writeState = { state = it },
        )
        return Harness(
            coordinator = coordinator,
            readState = { state },
            writeInitialState = {
                state = SessionCatalogState(
                    sessions = sessions,
                    total = total,
                    nextOffset = nextOffset,
                    hasMore = hasMore,
                    isLoadingMore = false,
                    pageError = null,
                    searchQuery = "",
                    searchResults = emptyList(),
                    isSearching = false,
                    searchError = null,
                    actionError = null,
                    activeSummary = null,
                )
            },
        ).also(Harness::initialize)
    }

    private class Harness(
        val coordinator: SessionCatalogCoordinator,
        private val readState: () -> SessionCatalogState,
        private val writeInitialState: () -> Unit,
    ) {
        val state: SessionCatalogState get() = readState()
        fun initialize() = writeInitialState()
    }

    private class FakeCatalogDashboard : DashboardService {
        val session = StoredSession(
            id = "stored-42",
            title = "Shared conversation",
            preview = "",
            startedAt = 1.0,
            messageCount = 0,
            source = "desktop",
        )
        val pages = mutableMapOf<Int, SessionCatalogPage>()
        val pageFailures = mutableMapOf<Int, Throwable>()
        val pageRequests = mutableListOf<Pair<Int, Int>>()
        var pageGate: CompletableDeferred<Unit>? = null
        val searchResults = mutableMapOf<String, List<StoredSession>>()
        val searchGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val searchRequests = mutableListOf<Triple<String, String, Int>>()
        var returnSearchAfterCancellation = false
        val pinRequests = mutableListOf<Triple<String, String, Boolean>>()
        val pinGates = mutableMapOf<Boolean, CompletableDeferred<Unit>>()
        val pinFailures = mutableMapOf<Boolean, Throwable>()
        val renameRequests = mutableListOf<Triple<String, String, String>>()
        val renameGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val renameFailures = mutableMapOf<String, Throwable>()
        val markReadRequests = mutableListOf<Pair<String, String>>()

        override suspend fun probe(rawBaseUrl: String): DashboardProbeResult = error("Not used")

        override suspend fun passwordLogin(
            baseUrl: String,
            provider: String,
            username: String,
            password: String,
        ) = Unit

        override suspend fun listSessions(
            baseUrl: String,
            credential: GatewayCredential,
            limit: Int,
            offset: Int,
        ): SessionCatalogPage {
            pageRequests += limit to offset
            pageGate?.await()
            pageFailures[offset]?.let { throw it }
            return pages[offset] ?: SessionCatalogPage(emptyList(), total = 0, limit = limit, offset = offset)
        }

        override suspend fun searchSessions(
            baseUrl: String,
            credential: GatewayCredential,
            query: String,
            profile: String,
            limit: Int,
        ): List<StoredSession> {
            searchRequests += Triple(query, profile, limit)
            try {
                searchGates[query]?.await()
            } catch (error: CancellationException) {
                if (!returnSearchAfterCancellation) throw error
                withContext(NonCancellable) { searchGates[query]?.await() }
            }
            return searchResults[query].orEmpty()
        }

        override suspend fun loadSessionMessages(
            baseUrl: String,
            credential: GatewayCredential,
            sessionId: String,
            profile: String,
            limit: Int,
        ): List<ConversationMessage> = emptyList()

        override suspend fun markSessionRead(
            baseUrl: String,
            credential: GatewayCredential,
            sessionId: String,
            profile: String,
        ) {
            markReadRequests += sessionId to profile
        }

        override suspend fun setSessionPinned(
            baseUrl: String,
            credential: GatewayCredential,
            sessionId: String,
            profile: String,
            pinned: Boolean,
        ): Boolean {
            pinRequests += Triple(sessionId, profile, pinned)
            pinGates[pinned]?.await()
            pinFailures[pinned]?.let { throw it }
            return pinned
        }

        override suspend fun renameSession(
            baseUrl: String,
            credential: GatewayCredential,
            sessionId: String,
            profile: String,
            title: String,
        ): String {
            renameRequests += Triple(sessionId, profile, title)
            renameGates[title]?.await()
            renameFailures[title]?.let { throw it }
            return title
        }

        override suspend fun listProfiles(
            baseUrl: String,
            credential: GatewayCredential,
        ): List<DashboardProfile> = emptyList()

        override fun exportAuthentication(baseUrl: String): AuthenticationMaterial? = null

        override fun createGateway(
            baseUrl: String,
            credential: GatewayCredential,
        ): GatewayConnection = error("Not used")
    }

    companion object {
        private val ACCESS = SessionCatalogAccess("http://hermes.test:9119", GatewayCredential.None)
    }
}
