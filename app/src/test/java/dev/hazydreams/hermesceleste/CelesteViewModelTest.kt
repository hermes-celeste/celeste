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
import dev.hazydreams.hermesceleste.network.SessionListPage
import dev.hazydreams.hermesceleste.network.SessionListCompatibilityFailure
import dev.hazydreams.hermesceleste.network.SessionOrdering
import dev.hazydreams.hermesceleste.network.StoredSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
        viewModel.leaveConversation()
        viewModel.onBackground()
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
        viewModel.leaveConversation()
        viewModel.onBackground()
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
        assertEquals(2, gateway.connectCount)
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(listOf("Do this once", "Finished exactly once"), state.messages.map { it.text })
        assertEquals("", state.streamingText)
        assertEquals(TurnState.Idle, state.turnState)
        viewModel.leaveConversation()
        viewModel.onBackground()
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
    fun refreshPublishesTheNewestProfileScopedActivityPage() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway).apply {
            sessionPage = SessionListPage(
                sessions = listOf(
                    session.copy(id = "older", title = "Older", lastActive = 100.0),
                    session.copy(id = "newer", title = "Newer", lastActive = 200.0),
                ),
                total = 2,
                ordering = SessionOrdering.AUTHORITATIVE_RECENCY,
            )
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(listOf("newer", "older"), viewModel.state.value.sessions?.map(StoredSession::id))

        dashboard.sessionPage = dashboard.sessionPage?.copy(
            sessions = listOf(
                dashboard.session.copy(id = "older", title = "Older", lastActive = 300.0),
                dashboard.session.copy(id = "newer", title = "Newer", lastActive = 200.0),
            ),
        )
        viewModel.onForeground()
        runCurrent()
        viewModel.onBackground()

        assertEquals(listOf("older", "newer"), viewModel.state.value.sessions?.map(StoredSession::id))
        assertTrue(dashboard.listPageCalls >= 2)
    }

    @Test
    fun staleProfilePageCannotOverwriteANewerProfileContext() = runTest {
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

        val pageGate = CompletableDeferred<SessionListPage>()
        dashboard.listPageGate = pageGate
        viewModel.onForeground()
        runCurrent()
        viewModel.selectProfile("work")
        pageGate.complete(
            SessionListPage(
                sessions = listOf(dashboard.session.copy(profile = "work")),
                ordering = SessionOrdering.AUTHORITATIVE_RECENCY,
            ),
        )
        viewModel.onBackground()
        advanceUntilIdle()

        assertEquals("work", viewModel.state.value.selectedProfile)
        assertEquals(listOf("work"), viewModel.state.value.sessions?.map(StoredSession::profile))
    }

    @Test
    fun localSubmitMovesTheConversationImmediatelyAndServerCatchUpClearsTheOverlay() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway).apply {
            sessionPage = SessionListPage(
                sessions = listOf(
                    session.copy(id = "other", lastActive = 200.0),
                    session.copy(lastActive = 100.0),
                ),
                ordering = SessionOrdering.AUTHORITATIVE_RECENCY,
            )
        }
        val viewModel = openConversation(gateway, dashboard, clockSeconds = { 300.0 })

        viewModel.updateDraft("Move this conversation")
        viewModel.sendMessage()

        assertEquals(listOf("stored-42", "other"), viewModel.state.value.sessions?.map(StoredSession::id))
        advanceUntilIdle()
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(listOf("stored-42", "other"), viewModel.state.value.sessions?.map(StoredSession::id))

        dashboard.sessionPage = dashboard.sessionPage?.copy(
            sessions = listOf(
                session.copy(id = "other", lastActive = 200.0),
                session.copy(lastActive = 300.0),
            ),
        )
        viewModel.leaveConversation()
        runCurrent()
        viewModel.onBackground()
        advanceUntilIdle()

        assertEquals(listOf("stored-42", "other"), viewModel.state.value.sessions?.map(StoredSession::id))
    }

    @Test
    fun uncertainSubmitReconcilesByStoredIdWithoutResending() = runTest {
        val gateway = FakeGateway().apply {
            submitFailure = IOException("socket lost")
            resumePayload = resumePayload(
                messages = listOf(ConversationMessage(role = "user", text = "Once", id = "server-user")),
                running = true,
            )
        }
        val viewModel = openConversation(gateway)

        viewModel.updateDraft("Once")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        assertEquals(listOf("Once"), viewModel.state.value.messages.map(ConversationMessage::text))
        assertEquals(TurnState.Running, viewModel.state.value.turnState)
    }

    @Test
    fun definitivePromptRejectionRollsBackTheOptimisticMessageAndActivity() = runTest {
        val gateway = FakeGateway().apply {
            submitResponse = buildJsonObject {
                put("status", "rejected")
                put("error", "synthetic rejection")
            }
        }
        val viewModel = openConversation(gateway)

        viewModel.updateDraft("Reject this")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertTrue(viewModel.state.value.messages.none { it.text == "Reject this" })
        assertEquals("synthetic rejection", viewModel.state.value.errorMessage)
    }

    @Test
    fun stalePromptResultCannotMutateTheConversationListContext() = runTest {
        val gate = CompletableDeferred<JsonObject>()
        val gateway = FakeGateway().apply { submitGate = gate }
        val dashboard = FakeDashboard(gateway)
        val viewModel = openConversation(gateway, dashboard)

        viewModel.updateDraft("Do not apply after leaving")
        viewModel.sendMessage()
        runCurrent()
        viewModel.leaveConversation()
        viewModel.openSession(dashboard.session)
        runCurrent()
        gate.complete(buildJsonObject { put("status", "streaming") })
        advanceUntilIdle()

        assertEquals("stored-42", viewModel.state.value.activeSummary?.id)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
    }

    @Test
    fun repeatedSessionInvalidationsCoalesceIntoOneRefresh() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = openConversation(gateway, dashboard)
        val callsBefore = dashboard.listPageCalls

        gateway.emit("sessions.changed")
        gateway.emit("sessions.changed")
        runCurrent()
        assertEquals(callsBefore, dashboard.listPageCalls)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(callsBefore, dashboard.listPageCalls)
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(callsBefore + 1, dashboard.listPageCalls)
    }

    @Test
    fun invalidationArrivingDuringRefreshSchedulesOneFollowUpGeneration() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = openConversation(gateway, dashboard)
        advanceUntilIdle()
        val callsBefore = dashboard.listPageCalls
        val gate = CompletableDeferred<SessionListPage>()
        dashboard.listPageGate = gate

        viewModel.onForeground()
        runCurrent()
        assertEquals(callsBefore + 1, dashboard.listPageCalls)

        gateway.emit("sessions.changed")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(callsBefore + 1, dashboard.listPageCalls)

        gate.complete(SessionListPage(sessions = listOf(dashboard.session), ordering = SessionOrdering.SERVER_ORDER))
        advanceUntilIdle()

        assertEquals(callsBefore + 2, dashboard.listPageCalls)
        viewModel.leaveConversation()
        viewModel.onBackground()
    }

    @Test
    fun visibleConversationListPollsAndBackgroundStopsThePoll() = runTest {
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

        viewModel.onForeground()
        runCurrent()
        val callsBeforePoll = dashboard.listPageCalls
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(dashboard.listPageCalls > callsBeforePoll)

        viewModel.setConversationsDestinationVisible(false)
        val callsAfterSettings = dashboard.listPageCalls
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(callsAfterSettings, dashboard.listPageCalls)

        viewModel.setConversationsDestinationVisible(true)
        viewModel.onForeground()
        runCurrent()
        viewModel.onBackground()
        val callsAfterBackground = dashboard.listPageCalls
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(callsAfterBackground, dashboard.listPageCalls)
    }

    @Test
    fun syntheticConversationSurvivesAnOmittedPageOnlyWhileItIsOpen() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway).apply {
            sessionPage = SessionListPage(sessions = emptyList(), ordering = SessionOrdering.SERVER_ORDER)
        }
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.createNewConversation()
        advanceUntilIdle()
        assertEquals(listOf("stored-new-1"), viewModel.state.value.sessions?.map(StoredSession::id))

        viewModel.leaveConversation()
        runCurrent()
        viewModel.onBackground()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.sessions.orEmpty().none { it.id == "stored-new-1" })
    }

    @Test
    fun foregroundRefreshUsesResumeHealthCheckInsteadOfGatewaySessionList() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        gateway.failHealthCheck = true

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(1, gateway.connectCount)
        assertEquals(0, gateway.methods.count { it == "session.list" })
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.leaveConversation()
        viewModel.onBackground()
    }

    @Test
    fun transientRestRefreshRetainsRowsAndErrorWithoutUsingGatewayList() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = openConversation(gateway, dashboard)
        advanceUntilIdle()

        dashboard.listPageFailure = IOException("REST temporarily unavailable")

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals("Shared conversation", viewModel.state.value.sessions?.single()?.title)
        assertEquals("REST temporarily unavailable", viewModel.state.value.errorMessage)
        assertEquals(0, gateway.methods.count { it == "session.list" })
        viewModel.leaveConversation()
        viewModel.onBackground()
    }

    @Test
    fun onlyPermanentListCompatibilityUsesTheGatewayFallbackPage() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = openConversation(gateway, dashboard)
        advanceUntilIdle()

        dashboard.listPageFailure = SessionListCompatibilityFailure("legacy list only")
        gateway.sessionListPayload = Json.parseToJsonElement(
            """{"sessions":[{"id":"stored-42","title":"Gateway fallback","started_at":1,"last_active":200,"message_count":3,"source":"desktop","profile":"default"}]}""",
        ) as JsonObject

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals("Gateway fallback", viewModel.state.value.sessions?.single()?.title)
        assertEquals(1, gateway.methods.count { it == "session.list" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.leaveConversation()
        viewModel.onBackground()
    }

    @Test
    fun sessionRefreshAnnouncesVisibleChangesButNotConversationUpdates() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = openConversation(gateway, dashboard)
        advanceUntilIdle()
        assertEquals(0L, viewModel.state.value.sessionRefreshAnnouncementToken)

        dashboard.sessionPage = dashboard.sessionPage?.copy(
            sessions = listOf(dashboard.session.copy(title = "Updated while open", lastActive = 2.0)),
        )
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(0L, viewModel.state.value.sessionRefreshAnnouncementToken)
        assertEquals("Updated while open", viewModel.state.value.sessions?.single()?.title)

        dashboard.sessionPage = dashboard.sessionPage?.copy(
            sessions = listOf(dashboard.session.copy(title = "Updated in list", lastActive = 3.0)),
        )
        viewModel.leaveConversation()
        runCurrent()

        assertEquals(1L, viewModel.state.value.sessionRefreshAnnouncementToken)
        assertEquals("Updated in list", viewModel.state.value.sessions?.single()?.title)
        viewModel.onBackground()
    }

    @Test
    fun heartbeatOnlyRefreshDoesNotAnnounceAndDestinationChangeConsumesEvents() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = openConversation(gateway, dashboard)
        advanceUntilIdle()

        dashboard.sessionPage = SessionListPage(
            sessions = listOf(dashboard.session.copy(lastActive = 2.0)),
            ordering = SessionOrdering.AUTHORITATIVE_RECENCY,
        )
        viewModel.leaveConversation()
        runCurrent()
        viewModel.setConversationsDestinationVisible(false)
        assertEquals(0L, viewModel.state.value.sessionRefreshAnnouncementToken)

        dashboard.sessionPage = dashboard.sessionPage?.copy(
            sessions = listOf(dashboard.session.copy(lastActive = 3.0)),
        )
        viewModel.setConversationsDestinationVisible(true)
        runCurrent()
        assertEquals(0L, viewModel.state.value.sessionRefreshAnnouncementToken)
        viewModel.setConversationsDestinationVisible(false)

        dashboard.sessionPage = dashboard.sessionPage?.copy(
            sessions = listOf(dashboard.session.copy(title = "Meaningful update", lastActive = 4.0)),
        )
        viewModel.setConversationsDestinationVisible(true)
        runCurrent()
        assertEquals(1L, viewModel.state.value.sessionRefreshAnnouncementToken)
        viewModel.setConversationsDestinationVisible(false)

        assertEquals(0L, viewModel.state.value.sessionRefreshAnnouncementToken)
    }

    @Test
    fun createsInTheSelectedProfileAndRecreatesOnlyAnUntouchedDraftSession() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.selectProfile("work")

        viewModel.createNewConversation()
        advanceUntilIdle()

        assertEquals("work", viewModel.state.value.activeSummary?.profile)
        assertEquals("stored-new-1", viewModel.state.value.activeSummary?.id)
        assertEquals(1, gateway.methods.count { it == "session.create" })
        assertEquals(0, gateway.methods.count { it == "session.resume" })
        val createParams = gateway.requests.first { it.first == "session.create" }.second
        assertEquals("work", createParams["profile"]?.jsonPrimitive?.content)
        assertEquals("android", createParams["source"]?.jsonPrimitive?.content)

        gateway.disconnect("blank session socket died")
        advanceUntilIdle()

        assertEquals(2, gateway.methods.count { it == "session.create" })
        assertEquals(0, gateway.methods.count { it == "session.resume" })
        assertEquals("stored-new-2", viewModel.state.value.activeSummary?.id)

        viewModel.updateDraft("Persist this conversation")
        viewModel.sendMessage()
        advanceUntilIdle()
        val promptParams = gateway.requests.last { it.first == "prompt.submit" }.second
        assertEquals("runtime-new-2", promptParams["session_id"]?.jsonPrimitive?.content)

        gateway.resumePayload = Json.parseToJsonElement(
            """{"session_id":"runtime-resumed","resumed":"stored-new-2","running":false,"status":"idle","inflight":null,"messages":[]}""",
        ) as JsonObject
        gateway.disconnect("after first prompt")
        advanceUntilIdle()

        assertEquals(2, gateway.methods.count { it == "session.create" })
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        viewModel.leaveConversation()
        viewModel.onBackground()
    }

    @Test
    fun removesPersistedPrefixFromInflightProjection() {
        val suffix = CelesteViewModel.unpersistedInflightText(
            inflight = "Already stored and still arriving",
            messages = listOf(ConversationMessage(role = "assistant", text = "Already stored")),
        )

        assertEquals("and still arriving", suffix)
    }

    private suspend fun openConversation(
        gateway: FakeGateway,
        dashboard: FakeDashboard = FakeDashboard(gateway),
        clockSeconds: () -> Double = { 1_000.0 },
    ): CelesteViewModel {
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
            clockSeconds = clockSeconds,
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
        var sessionPage: SessionListPage? = null
        var listPageGate: CompletableDeferred<SessionListPage>? = null
        var listPageFailure: Throwable? = null
        var listPageCalls = 0

        val session = StoredSession(
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
        ): List<StoredSession> = listOf(session)

        override suspend fun listSessionPage(
            baseUrl: String,
            credential: GatewayCredential,
            profile: String,
            limit: Int,
            offset: Int,
        ): SessionListPage {
            listPageCalls += 1
            listPageFailure?.let { failure ->
                listPageFailure = null
                throw failure
            }
            val gate = listPageGate
            if (gate != null) return gate.await()
            return sessionPage ?: SessionListPage(
                sessions = listOf(session),
                ordering = SessionOrdering.SERVER_ORDER,
            )
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

        override fun createGateway(
            baseUrl: String,
            credential: GatewayCredential,
        ): GatewayConnection = gateway
    }

    private class FakeGateway : GatewayConnection {
        private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
        private val mutableEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 32)
        override val state: StateFlow<GatewayConnectionState> = mutableState
        override val events: SharedFlow<GatewayEvent> = mutableEvents
        override val supportsSessionChangeEvents: Boolean
            get() = sessionChangeEventsSupported

        val methods = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var connectCount = 0
        var createCount = 0
        var failHealthCheck = false
        var connectFailure: Throwable? = null
        var submitFailure: Throwable? = null
        var submitGate: CompletableDeferred<JsonObject>? = null
        var submitResponse: JsonObject = buildJsonObject { put("status", "streaming") }
        var sessionChangeEventsSupported = false
        var sessionListPayload: JsonObject = Json.parseToJsonElement(
            """{"sessions":[{"id":"stored-42","title":"Shared conversation","started_at":1,"last_active":1,"message_count":0,"source":"desktop","profile":"default"}]}""",
        ) as JsonObject
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
                    createCount += 1
                    buildJsonObject {
                        put("session_id", "runtime-new-$createCount")
                        put("stored_session_id", "stored-new-$createCount")
                        put("profile", params["profile"]?.jsonPrimitive?.content ?: "default")
                    }
                }
                "session.list" -> {
                    if (failHealthCheck) {
                        failHealthCheck = false
                        throw IOException("stale socket")
                    }
                    sessionListPayload
                }
                "prompt.submit" -> {
                    submitFailure?.let { throw it }
                    submitGate?.await() ?: submitResponse
                }
                "session.interrupt" -> buildJsonObject { put("status", "interrupting") }
                else -> buildJsonObject {}
            }
        }

        override fun close() {
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
