package dev.hazydreams.hermesceleste

import java.io.IOException

import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.SessionCatalogPage
import dev.hazydreams.hermesceleste.network.StoredSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CelesteViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sendsAndReducesStreamingEventsWithoutDuplicatingCompletion() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)

        viewModel.updateDraft("Hello from Android")
        viewModel.sendMessage()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"Hel"}""")
        gateway.emit("message.delta", """{"text":"lo"}""")
        gateway.emit("message.interim", """{"text":"Hello","already_streamed":true}""")
        gateway.emit("message.delta", """{"text":" continued"}""")
        gateway.emit("message.complete", """{"content":"Hello continued","status":"complete"}""")
        gateway.emit("message.complete", """{"content":"Hello continued","status":"complete"}""")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TurnState.Idle, state.turnState)
        assertEquals("", state.streamingText)
        assertEquals(listOf("user", "assistant"), state.messages.map { it.role })
        assertEquals("Hello continued", state.messages.single { it.role == "assistant" }.text)
        assertFalse(state.messages.single { it.role == "user" }.pending)
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        viewModel.controller.close()
    }

    @Test
    fun interruptUsesOfficialRpcThenReconcilesAuthoritativeHistory() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        viewModel.updateDraft("Please do a long task")
        viewModel.sendMessage()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"Partial work"}""")
        advanceUntilIdle()

        gateway.resumePayload = resumePayload(
            messages = listOf(
                ConversationMessage(role = "user", text = "Please do a long task", id = "user-1"),
                ConversationMessage(role = "assistant", text = "Partial work", id = "assistant-1"),
            ),
            running = false,
        )
        viewModel.interrupt()
        advanceUntilIdle()

        assertTrue(gateway.methods.contains("session.interrupt"))
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals("Partial work", viewModel.state.value.messages.last().text)
        viewModel.controller.close()
    }

    @Test
    fun reconnectResumesTheServerSnapshotAndNeverResendsAnUncertainPrompt() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        viewModel.updateDraft("Do this once")
        viewModel.sendMessage()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"Half"}""")
        advanceUntilIdle()

        gateway.resumePayload = resumePayload(
            messages = listOf(
                ConversationMessage(role = "user", text = "Do this once", id = "server-user"),
                ConversationMessage(role = "assistant", text = "Finished exactly once", id = "server-assistant"),
            ),
            running = false,
        )
        gateway.disconnect("dashboard restarted")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(3, gateway.connectCount)
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(listOf("Do this once", "Finished exactly once"), state.messages.map { it.text })
        assertEquals("", state.streamingText)
        assertEquals(TurnState.Idle, state.turnState)
        viewModel.controller.close()
    }

    @Test
    fun revokedProviderSessionStopsReconnectAndDeletesReusableAuthentication() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway, authRequired = true)
        val store = InMemoryConnectionStore()
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            connectionStore = store,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        advanceUntilIdle()
        viewModel.updateDashboardUrl("https://hermes.test")
        viewModel.findDashboard()
        advanceUntilIdle()
        viewModel.updateUsername("celeste")
        viewModel.updatePassword("synthetic-password")
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        val connectsBeforeRejection = gateway.connectCount

        gateway.connectFailure = AuthenticationRejected("Hermes rejected the saved session.")
        gateway.disconnect("session expired")
        advanceUntilIdle()

        assertEquals(connectsBeforeRejection + 1, gateway.connectCount)
        assertEquals(ConnectionPhase.AuthenticationRequired, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.activeSummary)
        assertEquals("https://hermes.test", viewModel.state.value.dashboardUrl)
        assertEquals("celeste", viewModel.state.value.username)
        assertNull(store.load()?.secret)
        assertFalse(store.load()?.descriptor?.autoLoginEnabled ?: true)

        advanceUntilIdle()
        assertEquals(connectsBeforeRejection + 1, gateway.connectCount)
    }

    @Test
    fun profileCatalogFailureDoesNotBecomeAConnectedDefaultProfile() = runTest {
        val dashboard = FakeDashboard(FakeGateway()).apply {
            profileFailure = AuthenticationRejected("Hermes rejected profile access.")
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.ManualSetup, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.sessions)
        assertEquals("Hermes rejected profile access.", viewModel.state.value.errorMessage)
    }

    @Test
    fun loadsNextSessionPageOnceAndDeduplicatesPinnedBackfill() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val recent = dashboard.session.copy(id = "recent-1", title = "Recent")
        val pinned = dashboard.session.copy(
            id = "pinned-old",
            title = "Pinned old",
            pinned = true,
            unread = true,
        )
        dashboard.sessionPages[0] = SessionCatalogPage(
            sessions = listOf(recent, pinned),
            total = 30,
            limit = 15,
            offset = 0,
        )
        dashboard.sessionPages[15] = SessionCatalogPage(
            sessions = listOf(
                dashboard.session.copy(id = "recent-16", title = "Older"),
                pinned.copy(title = "Pinned refreshed", unread = false),
            ),
            total = 30,
            limit = 15,
            offset = 15,
        )
        dashboard.nextPageGate = CompletableDeferred()
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(listOf(15 to 0), dashboard.sessionPageRequests)
        assertEquals(15, viewModel.state.value.nextSessionOffset)
        assertTrue(viewModel.state.value.hasMoreSessions)

        viewModel.loadMoreSessions()
        runCurrent()
        assertTrue(viewModel.state.value.isLoadingMoreSessions)
        viewModel.loadMoreSessions()
        dashboard.nextPageGate?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(15 to 0, 15 to 15), dashboard.sessionPageRequests)
        assertEquals(listOf("recent-1", "pinned-old", "recent-16"), state.sessions?.map { it.id })
        assertEquals("Pinned refreshed", state.sessions?.first { it.id == "pinned-old" }?.title)
        assertFalse(state.sessions?.first { it.id == "pinned-old" }?.unread ?: true)
        assertEquals(30, state.nextSessionOffset)
        assertFalse(state.hasMoreSessions)
        assertFalse(state.isLoadingMoreSessions)
        assertNull(state.sessionPageError)
    }

    @Test
    fun failedNextPageKeepsLoadedRowsAndCanRetry() = runTest {
        val dashboard = FakeDashboard(FakeGateway())
        val first = dashboard.session.copy(id = "recent-1")
        val older = dashboard.session.copy(id = "recent-16")
        dashboard.sessionPages[0] = SessionCatalogPage(
            sessions = listOf(first),
            total = 30,
            limit = 15,
            offset = 0,
        )
        dashboard.sessionPages[15] = SessionCatalogPage(
            sessions = listOf(older),
            total = 30,
            limit = 15,
            offset = 15,
        )
        dashboard.sessionPageFailures[15] = IOException("synthetic older-page failure")
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(listOf("recent-1"), viewModel.state.value.sessions?.map { it.id })
        assertEquals("synthetic older-page failure", viewModel.state.value.sessionPageError)
        assertTrue(viewModel.state.value.hasMoreSessions)
        assertFalse(viewModel.state.value.isLoadingMoreSessions)

        dashboard.sessionPageFailures.remove(15)
        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(listOf("recent-1", "recent-16"), viewModel.state.value.sessions?.map { it.id })
        assertEquals(listOf(15 to 0, 15 to 15, 15 to 15), dashboard.sessionPageRequests)
        assertNull(viewModel.state.value.sessionPageError)
        assertFalse(viewModel.state.value.hasMoreSessions)
    }

    @Test
    fun rejectedNextPageInvalidatesReusableAuthentication() = runTest {
        val dashboard = FakeDashboard(FakeGateway()).apply {
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 30,
                limit = 15,
                offset = 0,
            )
            sessionPageFailures[15] = AuthenticationRejected("expired")
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.AuthenticationRequired, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.sessions)
        assertNull(viewModel.state.value.sessionPageError)
    }

    @Test
    fun newDraftPreservesAnInFlightPageAndPreventsDuplicateRequest() = runTest {
        val dashboard = FakeDashboard(FakeGateway()).apply {
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 30,
                limit = 15,
                offset = 0,
            )
            sessionPages[15] = SessionCatalogPage(
                sessions = listOf(session.copy(id = "older")),
                total = 30,
                limit = 15,
                offset = 15,
            )
            nextPageGate = CompletableDeferred()
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.loadMoreSessions()
        runCurrent()
        viewModel.createNewConversation()
        runCurrent()

        assertTrue(viewModel.state.value.isLoadingMoreSessions)
        viewModel.loadMoreSessions()
        assertEquals(listOf(15 to 0, 15 to 15), dashboard.sessionPageRequests)

        dashboard.nextPageGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("stored-42", "older"), viewModel.state.value.sessions?.map { it.id })
        assertFalse(viewModel.state.value.isLoadingMoreSessions)
    }

    @Test
    fun firstPromptPublicationSerializesWithAnInFlightPage() = runTest {
        val gateway = FakeGateway().apply {
            promptGate = CompletableDeferred()
        }
        val dashboard = FakeDashboard(gateway).apply {
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 30,
                limit = 15,
                offset = 0,
            )
            sessionPages[15] = SessionCatalogPage(
                sessions = listOf(session.copy(id = "stale-older")),
                total = 31,
                limit = 15,
                offset = 15,
            )
            sessionPages[16] = SessionCatalogPage(
                sessions = listOf(session.copy(id = "correct-older")),
                total = 31,
                limit = 15,
                offset = 16,
            )
            nextPageGate = CompletableDeferred()
            returnPageAfterCancellation = true
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.loadMoreSessions()
        runCurrent()
        viewModel.updateDraft("Publish while paging")
        viewModel.sendMessage()
        runCurrent()

        assertFalse(viewModel.state.value.isLoadingMoreSessions)
        viewModel.loadMoreSessions()
        assertEquals(listOf(15 to 0, 15 to 15), dashboard.sessionPageRequests)

        dashboard.nextPageGate?.complete(Unit)
        runCurrent()
        assertFalse(viewModel.state.value.sessions.orEmpty().any { it.id == "stale-older" })

        gateway.promptGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(31, viewModel.state.value.sessionCatalogTotal)
        assertEquals(16, viewModel.state.value.nextSessionOffset)

        viewModel.loadMoreSessions()
        advanceUntilIdle()
        assertEquals(listOf(15 to 0, 15 to 15, 15 to 16), dashboard.sessionPageRequests)
        assertTrue(viewModel.state.value.sessions.orEmpty().any { it.id == "correct-older" })
    }

    @Test
    fun openingUnreadSessionClearsTheDotAndAcknowledgesHermesWithoutBlocking() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway).apply {
            session = session.copy(profile = "work", unread = true)
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 1,
                limit = 15,
                offset = 0,
            )
            markReadFailure = IOException("synthetic read acknowledgement failure")
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.openSession(dashboard.session)
        assertFalse(viewModel.state.value.sessions?.single()?.unread ?: true)
        advanceUntilIdle()

        assertEquals(listOf("stored-42" to "work"), dashboard.markReadRequests)
        assertEquals("stored-42", viewModel.state.value.activeSummary?.id)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun rejectedReadAcknowledgementInvalidatesReusableAuthentication() = runTest {
        val dashboard = FakeDashboard(FakeGateway()).apply {
            session = session.copy(unread = true)
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 1,
                limit = 15,
                offset = 0,
            )
            markReadFailure = AuthenticationRejected("expired")
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        val authenticationClearsBeforeRejection = dashboard.clearAuthenticationCount

        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.AuthenticationRequired, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.sessions)
        assertEquals(authenticationClearsBeforeRejection + 1, dashboard.clearAuthenticationCount)
    }

    @Test
    fun pendingReadAcknowledgementBlocksPagingUntilItCompletes() = runTest {
        val dashboard = FakeDashboard(FakeGateway()).apply {
            session = session.copy(unread = true)
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 30,
                limit = 15,
                offset = 0,
            )
            sessionPages[15] = SessionCatalogPage(
                sessions = listOf(session.copy(unread = false), session.copy(id = "older")),
                total = 30,
                limit = 15,
                offset = 15,
            )
            markReadGate = CompletableDeferred()
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.openSession(dashboard.session)
        runCurrent()
        viewModel.loadMoreSessions()
        assertEquals(listOf(15 to 0), dashboard.sessionPageRequests)

        dashboard.markReadGate?.complete(Unit)
        advanceUntilIdle()
        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(listOf(15 to 0, 15 to 15), dashboard.sessionPageRequests)
        assertFalse(viewModel.state.value.sessions?.first { it.id == "stored-42" }?.unread ?: true)
    }

    @Test
    fun pageBackfillRefreshesTheMatchingActiveSummary() = runTest {
        val dashboard = FakeDashboard(FakeGateway()).apply {
            session = session.copy(title = "Old title", pinned = true)
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 30,
                limit = 15,
                offset = 0,
            )
            sessionPages[15] = SessionCatalogPage(
                sessions = listOf(session.copy(title = "Refreshed title")),
                total = 30,
                limit = 15,
                offset = 15,
            )
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals("Refreshed title", viewModel.state.value.sessions?.single()?.title)
        assertEquals("Refreshed title", viewModel.state.value.activeSummary?.title)
    }

    @Test
    fun openingSessionCancelsAStalePageBeforeItCanRestoreUnread() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway).apply {
            session = session.copy(unread = true)
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 30,
                limit = 15,
                offset = 0,
            )
            sessionPages[15] = SessionCatalogPage(
                sessions = listOf(session, session.copy(id = "older")),
                total = 30,
                limit = 15,
                offset = 15,
            )
            nextPageGate = CompletableDeferred()
            returnPageAfterCancellation = true
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.loadMoreSessions()
        runCurrent()
        viewModel.openSession(dashboard.session)
        dashboard.nextPageGate?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.sessions?.single()?.unread ?: true)
        assertFalse(state.isLoadingMoreSessions)
        assertNull(state.sessionPageError)
    }

    @Test
    fun authenticationInvalidationCannotPublishAnInFlightPage() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway).apply {
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 30,
                limit = 15,
                offset = 0,
            )
            sessionPages[15] = SessionCatalogPage(
                sessions = listOf(session.copy(id = "older")),
                total = 30,
                limit = 15,
                offset = 15,
            )
            nextPageGate = CompletableDeferred()
        }
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.loadMoreSessions()
        runCurrent()
        gateway.connectFailure = AuthenticationRejected("expired")
        gateway.disconnect("expired")
        advanceUntilIdle()

        assertEquals(ConnectionPhase.AuthenticationRequired, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.sessions)
        dashboard.nextPageGate?.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.state.value.sessions)
    }

    @Test
    fun changingConnectionsCancelsPendingReadAcknowledgement() = runTest {
        val dashboard = FakeDashboard(FakeGateway()).apply {
            session = session.copy(unread = true)
            sessionPages[0] = SessionCatalogPage(
                sessions = listOf(session),
                total = 1,
                limit = 15,
                offset = 0,
            )
            markReadGate = CompletableDeferred()
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.openSession(dashboard.session)
        runCurrent()
        viewModel.useAnotherConnection()
        dashboard.markReadGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(dashboard.markReadRequests.isEmpty())
        assertEquals(ConnectionPhase.ManualSetup, viewModel.state.value.connectionPhase)
    }

    @Test
    fun foregroundHealthCheckReplacesAStaleSocketAndResumes() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        gateway.failHealthCheck = true

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(3, gateway.connectCount)
        assertEquals(1, gateway.methods.count { it == "session.list" })
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.controller.close()
    }

    @Test
    fun draftSessionsStayOutOfTheCatalogUntilTheFirstPrompt() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals("stored-new-1", viewModel.state.value.activeSummary?.id)
        assertEquals(listOf("stored-42"), viewModel.state.value.sessions?.map { it.id })
        assertEquals(1, gateway.methods.count { it == "session.create" })

        viewModel.selectProfile("work")
        viewModel.createNewConversation()
        advanceUntilIdle()

        assertEquals("work", viewModel.state.value.activeSummary?.profile)
        assertEquals("stored-new-2", viewModel.state.value.activeSummary?.id)
        assertEquals(listOf("stored-42"), viewModel.state.value.sessions?.map { it.id })
        assertEquals(2, gateway.methods.count { it == "session.create" })
        assertEquals(0, gateway.methods.count { it == "session.resume" })
        val createParams = gateway.requests.last { it.first == "session.create" }.second
        assertEquals("work", createParams["profile"]?.jsonPrimitive?.content)
        assertEquals("android", createParams["source"]?.jsonPrimitive?.content)

        gateway.disconnect("blank session socket died")
        advanceUntilIdle()

        assertEquals(3, gateway.methods.count { it == "session.create" })
        assertEquals(0, gateway.methods.count { it == "session.resume" })
        assertEquals("stored-new-3", viewModel.state.value.activeSummary?.id)
        assertEquals(listOf("stored-42"), viewModel.state.value.sessions?.map { it.id })

        viewModel.updateDraft("Persist this conversation")
        viewModel.sendMessage()
        advanceUntilIdle()
        val promptParams = gateway.requests.last { it.first == "prompt.submit" }.second
        assertEquals("runtime-new-3", promptParams["session_id"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("stored-new-3", "stored-42"),
            viewModel.state.value.sessions?.map { it.id },
        )
        assertEquals("Persist this conversation", viewModel.state.value.sessions?.first()?.preview)
        assertEquals(1, viewModel.state.value.sessions?.first()?.messageCount)
        assertEquals(2, viewModel.state.value.sessionCatalogTotal)
        assertEquals(2, viewModel.state.value.nextSessionOffset)
        assertFalse(viewModel.state.value.hasMoreSessions)

        gateway.resumePayload = Json.parseToJsonElement(
            """{"session_id":"runtime-resumed","resumed":"stored-new-3","running":false,"status":"idle","inflight":null,"messages":[]}""",
        ) as JsonObject
        gateway.disconnect("after first prompt")
        advanceUntilIdle()

        assertEquals(3, gateway.methods.count { it == "session.create" })
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        viewModel.controller.close()
    }

    @Test
    fun firstPromptPublicationStaysWithItsOriginatingSession() = runTest {
        val firstGateway = FakeGateway("a-").apply {
            promptGate = CompletableDeferred()
        }
        val secondGateway = FakeGateway("b-")
        val gateways = ArrayDeque(listOf(firstGateway, secondGateway))
        val dashboard = FakeDashboard(firstGateway).apply {
            gatewayFactory = { gateways.removeFirst() }
        }
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals("stored-a-new-1", viewModel.state.value.activeSummary?.id)
        viewModel.updateDraft("Persist conversation A")
        viewModel.sendMessage()
        runCurrent()

        viewModel.createNewConversation()
        advanceUntilIdle()
        assertEquals("stored-b-new-1", viewModel.state.value.activeSummary?.id)
        assertEquals(listOf("stored-42"), viewModel.state.value.sessions?.map { it.id })

        firstGateway.promptGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals("stored-b-new-1", viewModel.state.value.activeSummary?.id)
        assertEquals(
            listOf("stored-a-new-1", "stored-42"),
            viewModel.state.value.sessions?.map { it.id },
        )
        assertEquals("Persist conversation A", viewModel.state.value.sessions?.first()?.preview)
        assertEquals(1, viewModel.state.value.sessions?.first()?.messageCount)
        assertEquals(emptyList<ConversationMessage>(), viewModel.state.value.messages)
        viewModel.controller.close()
    }

    @Test
    fun staleLaunchDraftCannotOverwriteASelectedConversation() = runTest {
        val launchGateway = FakeGateway("launch-").apply {
            createGate = CompletableDeferred()
        }
        val selectedGateway = FakeGateway("selected-")
        val gateways = ArrayDeque(listOf(launchGateway, selectedGateway))
        val dashboard = FakeDashboard(launchGateway).apply {
            gatewayFactory = { gateways.removeFirst() }
        }
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        runCurrent()

        assertEquals(ConnectionPhase.Restoring, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.sessions)
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        assertEquals("stored-42", viewModel.state.value.activeSummary?.id)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)

        launchGateway.createGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals("stored-42", viewModel.state.value.activeSummary?.id)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertNull(viewModel.state.value.errorMessage)
        viewModel.controller.close()
    }

    @Test
    fun portableControllerUsesTheHostClientSource() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val controller = CelesteController(
            parentScope = CoroutineScope(mainDispatcher),
            dashboard = dashboard,
            connectionStore = InMemoryConnectionStore(),
            clientSource = "ios",
            normalizeDashboardUrl = { it },
            reconnectDelayMillis = { _, _ -> 0L },
        )
        advanceUntilIdle()

        controller.updateDashboardUrl("http://hermes.test:9119")
        controller.findDashboard()
        controller.loadSessions()
        advanceUntilIdle()

        val createParams = gateway.requests.single { it.first == "session.create" }.second
        assertEquals("ios", createParams["source"]?.jsonPrimitive?.content)
        assertEquals("ios", controller.state.value.activeSummary?.source)
        controller.close()
    }

    @Test
    fun portableControllerUsesTheHostClientSourceWhenResuming() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val controller = CelesteController(
            parentScope = CoroutineScope(mainDispatcher),
            dashboard = dashboard,
            connectionStore = InMemoryConnectionStore(),
            clientSource = "ios",
            normalizeDashboardUrl = { it },
            reconnectDelayMillis = { _, _ -> 0L },
        )
        advanceUntilIdle()

        controller.updateDashboardUrl("http://hermes.test:9119")
        controller.findDashboard()
        controller.loadSessions()
        controller.openSession(dashboard.session)
        advanceUntilIdle()

        val resumeParams = gateway.requests.single { it.first == "session.resume" }.second
        assertEquals("stored-42", resumeParams["session_id"]?.jsonPrimitive?.content)
        assertEquals("ios", resumeParams["source"]?.jsonPrimitive?.content)
        controller.close()
    }

    @Test
    fun cancellingTheParentScopeClosesGatewayAndClearsAuthentication() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val parentJob = Job()
        val controller = CelesteController(
            parentScope = CoroutineScope(mainDispatcher + parentJob),
            dashboard = dashboard,
            connectionStore = InMemoryConnectionStore(),
            clientSource = "android",
            normalizeDashboardUrl = { it },
            reconnectDelayMillis = { _, _ -> 0L },
        )
        advanceUntilIdle()

        controller.updateDashboardUrl("http://hermes.test:9119")
        controller.findDashboard()
        controller.loadSessions()
        controller.openSession(dashboard.session)
        advanceUntilIdle()
        val gatewayClosesBeforeCancellation = gateway.closeCount
        val authenticationClearsBeforeCancellation = dashboard.clearAuthenticationCount

        parentJob.cancel()
        advanceUntilIdle()

        assertEquals(gatewayClosesBeforeCancellation + 1, gateway.closeCount)
        assertEquals(GatewayConnectionState.Closed, gateway.state.value)
        assertEquals(authenticationClearsBeforeCancellation + 1, dashboard.clearAuthenticationCount)
    }

    @Test
    fun removesPersistedPrefixFromInflightProjection() {
        val suffix = CelesteController.unpersistedInflightText(
            inflight = "Already stored and still arriving",
            messages = listOf(ConversationMessage(role = "assistant", text = "Already stored")),
        )

        assertEquals("and still arriving", suffix)
    }

    private suspend fun openConversation(gateway: FakeGateway): CelesteViewModel {
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        return viewModel
    }

    private class FakeDashboard(
        private val gateway: FakeGateway,
        private val authRequired: Boolean = false,
    ) : DashboardService {
        var profileFailure: Throwable? = null
        var clearAuthenticationCount = 0
        var gatewayFactory: () -> GatewayConnection = { gateway }
        var nextPageGate: CompletableDeferred<Unit>? = null
        var returnPageAfterCancellation = false
        var markReadGate: CompletableDeferred<Unit>? = null
        var markReadFailure: Throwable? = null
        val sessionPages = mutableMapOf<Int, SessionCatalogPage>()
        val sessionPageFailures = mutableMapOf<Int, Throwable>()
        val sessionPageRequests = mutableListOf<Pair<Int, Int>>()
        val markReadRequests = mutableListOf<Pair<String, String>>()

        var session = StoredSession(
            id = "stored-42",
            title = "Shared conversation",
            preview = "",
            startedAt = 1.0,
            messageCount = 0,
            source = "desktop",
        )

        override suspend fun probe(rawBaseUrl: String): DashboardProbeResult =
            DashboardProbeResult(
                baseUrl = rawBaseUrl,
                authRequired = authRequired,
                providers = if (authRequired) {
                    listOf(AuthProvider("password", "Password", supportsPassword = true))
                } else {
                    emptyList()
                },
                version = "test",
            )

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
            sessionPageRequests += limit to offset
            if (offset > 0) {
                try {
                    nextPageGate?.await()
                } catch (error: CancellationException) {
                    if (!returnPageAfterCancellation) throw error
                }
            }
            sessionPageFailures[offset]?.let { throw it }
            return sessionPages[offset] ?: SessionCatalogPage(
                sessions = listOf(session),
                total = 1,
                limit = limit,
                offset = offset,
            )
        }

        override suspend fun markSessionRead(
            baseUrl: String,
            credential: GatewayCredential,
            sessionId: String,
            profile: String,
        ) {
            markReadGate?.await()
            markReadRequests += sessionId to profile
            markReadFailure?.let { throw it }
        }

        override suspend fun listProfiles(
            baseUrl: String,
            credential: GatewayCredential,
        ): List<DashboardProfile> {
            profileFailure?.let { throw it }
            return listOf(
                DashboardProfile(name = "default", isDefault = true),
                DashboardProfile(name = "work"),
            )
        }

        override fun exportAuthentication(baseUrl: String): AuthenticationMaterial? =
            if (authRequired) AuthenticationMaterial("synthetic-session-cookies") else null

        override fun clearAuthentication() {
            clearAuthenticationCount += 1
        }

        override fun createGateway(
            baseUrl: String,
            credential: GatewayCredential,
        ): GatewayConnection = gatewayFactory()
    }

    private class FakeGateway(
        private val idPrefix: String = "",
    ) : GatewayConnection {
        private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
        private val mutableEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 32)
        override val state: StateFlow<GatewayConnectionState> = mutableState
        override val events: SharedFlow<GatewayEvent> = mutableEvents

        val methods = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var connectCount = 0
        var createCount = 0
        var closeCount = 0
        var failHealthCheck = false
        var connectFailure: Throwable? = null
        var createGate: CompletableDeferred<Unit>? = null
        var promptGate: CompletableDeferred<Unit>? = null
        var resumePayload: JsonObject = resumePayload(messages = emptyList(), running = false)

        override suspend fun connect() {
            connectCount += 1
            connectFailure?.let { throw it }
            mutableState.value = GatewayConnectionState.Connected
        }

        override suspend fun request(
            method: String,
            params: JsonObject,
            timeoutMillis: Long,
        ): JsonElement {
            methods += method
            requests += method to params
            return when (method) {
                "session.resume" -> resumePayload
                "session.create" -> {
                    createGate?.await()
                    createCount += 1
                    buildJsonObject {
                        put("session_id", "runtime-${idPrefix}new-$createCount")
                        put("stored_session_id", "stored-${idPrefix}new-$createCount")
                        put(
                            "info",
                            buildJsonObject {
                                put("profile_name", params["profile"]?.jsonPrimitive?.content ?: "default")
                            },
                        )
                    }
                }
                "session.list" -> {
                    if (failHealthCheck) {
                        failHealthCheck = false
                        throw IOException("stale socket")
                    }
                    buildJsonObject { put("sessions", "healthy") }
                }
                "prompt.submit" -> {
                    promptGate?.await()
                    buildJsonObject { put("status", "streaming") }
                }
                "session.interrupt" -> buildJsonObject { put("status", "interrupting") }
                else -> buildJsonObject {}
            }
        }

        override fun close() {
            closeCount += 1
            mutableState.value = GatewayConnectionState.Closed
        }

        fun emit(type: String, payload: String = "{}") {
            mutableEvents.tryEmit(
                GatewayEvent(
                    type = type,
                    sessionId = "runtime-7",
                    payload = Json.parseToJsonElement(payload) as JsonObject,
                ),
            )
        }

        fun disconnect(reason: String) {
            mutableState.value = GatewayConnectionState.Disconnected(reason)
        }
    }

    companion object {
        private fun resumePayload(
            messages: List<ConversationMessage>,
            running: Boolean,
        ): JsonObject {
            val encodedMessages = messages.joinToString(",") { message ->
                """{"id":${Json.encodeToString(message.id ?: "")},"role":${Json.encodeToString(message.role)},"text":${Json.encodeToString(message.text)}}"""
            }
            return Json.parseToJsonElement(
                """{"session_id":"runtime-7","resumed":"stored-42","running":$running,"status":"${if (running) "streaming" else "idle"}","inflight":null,"messages":[$encodedMessages]}""",
            ) as JsonObject
        }
    }
}
