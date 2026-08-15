package dev.hazydreams.hermesceleste

import java.io.IOException

import dev.hazydreams.hermesceleste.connection.ConnectionStore
import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.connection.ReusableSecret
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.connection.SavedConnectionDescriptor
import dev.hazydreams.hermesceleste.connection.StoredConnection
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
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.StoredSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    }

    @Test
    fun errorCompletionRetainsAuthoritativePartialAssistantContent() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)

        viewModel.updateDraft("Keep the partial response")
        viewModel.sendMessage()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"streamed prefix"}""")
        gateway.emit(
            "message.complete",
            """{"content":"authoritative partial response","status":"error","partial":true,"error":"synthetic provider failure"}""",
        )
        advanceUntilIdle()

        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals("authoritative partial response", viewModel.state.value.messages.last().text)
        assertEquals(UiNoticeCategory.ServerTurnFailure, viewModel.state.value.notice?.category)
        viewModel.leaveConversation()
    }

    @Test
    fun nonPartialErrorCompletionNeverBecomesAssistantContent() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)

        viewModel.updateDraft("Do not render the failure payload")
        viewModel.sendMessage()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"discarded streamed text"}""")
        gateway.emit(
            "message.complete",
            """{"text":"raw error payload","content":"raw error payload","status":"error","partial":false,"error":"raw error payload"}""",
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TurnState.Idle, state.turnState)
        assertEquals("", state.streamingText)
        assertEquals(listOf("user"), state.messages.map { it.role })
        assertTrue(state.messages.none { it.text.contains("raw error payload") })
        assertEquals(UiNoticeCategory.ServerTurnFailure, state.notice?.category)
        viewModel.leaveConversation()
    }

    @Test
    fun interruptFailureStillReconcilesBeforeProjectingRecovery() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        viewModel.updateDraft("Stop this turn")
        viewModel.sendMessage()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"safe partial"}""")
        advanceUntilIdle()

        gateway.interruptFailure = IOException("interrupt delivery failed")
        gateway.resumePayload = resumePayload(
            messages = listOf(
                ConversationMessage(role = "user", text = "Stop this turn", id = "server-user"),
                ConversationMessage(role = "assistant", text = "authoritative partial", id = "server-assistant"),
            ),
            running = false,
        )
        viewModel.interrupt()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.interrupt" })
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals("authoritative partial", viewModel.state.value.messages.last().text)
        assertNull(viewModel.state.value.notice)
        viewModel.leaveConversation()
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
        val notice = requireNotNull(viewModel.state.value.notice)
        assertEquals(UiNoticeCategory.AuthenticationRequired, notice.category)
        assertEquals(UiRecoveryAction.SignIn, notice.recovery)
        assertEquals("Your Hermes sign-in has expired. Sign in again.", notice.message)
    }

    @Test
    fun jsonRpcAuthenticationFailuresDuringLoadRequireSignInAndClearReusableAuth() = runTest {
        listOf(401, 403).forEach { code ->
            val descriptor = SavedConnectionDescriptor(
                baseUrl = "http://hermes.test:9119",
                authMode = SavedAuthMode.StaticToken,
                expectsSecret = true,
            )
            val store = InMemoryConnectionStore(
                StoredConnection(descriptor, ReusableSecret("synthetic-session-token")),
            )
            val dashboard = FakeDashboard(FakeGateway()).apply {
                sessionFailure = GatewayRpcException(
                    code = code,
                    message = "raw JSON-RPC auth failure at https://private.example/path",
                )
            }
            val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
            viewModel.updateDashboardUrl("http://hermes.test:9119")
            viewModel.findDashboard()
            advanceUntilIdle()
            viewModel.loadSessions()
            advanceUntilIdle()

            val state = viewModel.state.value
            val notice = requireNotNull(state.notice)
            assertEquals(ConnectionPhase.AuthenticationRequired, state.connectionPhase)
            assertEquals(UiNoticeCategory.AuthenticationRequired, notice.category)
            assertEquals(UiRecoveryAction.SignIn, notice.recovery)
            assertEquals("Your Hermes sign-in has expired. Sign in again.", notice.message)
            assertEquals("http://hermes.test:9119", state.dashboardUrl)
            assertEquals("", state.sessionToken)
            assertNull(store.load()?.secret)
            assertFalse(store.load()?.descriptor?.autoLoginEnabled ?: true)
            assertFalse(notice.message.contains("private.example"))
        }
    }

    @Test
    fun jsonRpcAuthenticationFailureDuringProfileReloadRequiresSignIn() = runTest {
        val dashboard = FakeDashboard(FakeGateway())
        val store = InMemoryConnectionStore()
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        dashboard.profileFailure = GatewayRpcException(
            code = 403,
            message = "raw JSON-RPC auth failure at https://private.example/path",
        )
        viewModel.selectProfile("work")
        advanceUntilIdle()

        val state = viewModel.state.value
        val notice = requireNotNull(state.notice)
        assertEquals(ConnectionPhase.AuthenticationRequired, state.connectionPhase)
        assertEquals(UiNoticeCategory.AuthenticationRequired, notice.category)
        assertEquals(UiRecoveryAction.SignIn, notice.recovery)
        assertEquals("Your Hermes sign-in has expired. Sign in again.", notice.message)
        assertNull(state.sessions)
        assertFalse(notice.message.contains("private.example"))
    }

    @Test
    fun staleAuthenticationCannotPublishAfterBackgroundStoreWait() = runTest {
        val clearGate = CompletableDeferred<Unit>()
        val store = BlockingClearConnectionStore(clearGate)
        val dashboard = FakeDashboard(FakeGateway()).apply {
            sessionFailure = AuthenticationRejected("synthetic auth rejection")
        }
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        advanceUntilIdle()
        viewModel.loadSessions()
        store.clearEntered.await()

        viewModel.onBackground()
        clearGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.connectionPhase != ConnectionPhase.AuthenticationRequired)
        assertNull(viewModel.state.value.notice)
    }


    @Test
    fun foregroundHealthCheckReplacesAStaleSocketAndResumes() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        gateway.failHealthCheck = true

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(2, gateway.connectCount)
        assertEquals(1, gateway.methods.count { it == "session.list" })
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.leaveConversation()
    }

    @Test
    fun foregroundReattachesGatewayCollectorsAfterBackgrounding() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)

        viewModel.onBackground()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"stale background event"}""")
        runCurrent()

        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals("", viewModel.state.value.streamingText)

        viewModel.onForeground()
        advanceUntilIdle()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"after foreground"}""")
        advanceUntilIdle()

        assertEquals(TurnState.Running, viewModel.state.value.turnState)
        assertEquals("after foreground", viewModel.state.value.streamingText)
        viewModel.leaveConversation()
    }

    @Test
    fun backgroundDuringRestoreIsCanceledAndForegroundRetriesWithNoGateway() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "http://hermes.test:9119",
            authMode = SavedAuthMode.Open,
            expectsSecret = false,
        )
        val probeGate = CompletableDeferred<Unit>()
        val dashboard = FakeDashboard(FakeGateway()).apply {
            this.probeGate = probeGate
            probeEntered = CompletableDeferred()
        }
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            connectionStore = InMemoryConnectionStore(StoredConnection(descriptor, null)),
        )
        dashboard.probeEntered?.await()

        viewModel.onBackground()
        runCurrent()
        viewModel.onForeground()
        runCurrent()

        assertTrue(dashboard.probeCalls >= 2)
        probeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.Connected, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.activeSummary)
    }

    @Test
    fun backgroundDuringProbeIsCanceledAndForegroundRetriesTheProbe() = runTest {
        val probeGate = CompletableDeferred<Unit>()
        val dashboard = FakeDashboard(FakeGateway()).apply {
            this.probeGate = probeGate
            probeEntered = CompletableDeferred()
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        dashboard.probeEntered?.await()

        viewModel.onBackground()
        runCurrent()
        viewModel.onForeground()
        runCurrent()

        assertTrue(dashboard.probeCalls >= 2)
        probeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.ManualSetup, viewModel.state.value.connectionPhase)
        assertNotNull(viewModel.state.value.probe)
        assertNull(viewModel.state.value.loadingMessage)
    }

    @Test
    fun backgroundDuringSessionListIsCanceledAndForegroundReloadsSessions() = runTest {
        val dashboard = FakeDashboard(FakeGateway())
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        advanceUntilIdle()

        val sessionsGate = CompletableDeferred<Unit>()
        dashboard.sessionsGate = sessionsGate
        dashboard.sessionsEntered = CompletableDeferred()
        viewModel.loadSessions()
        dashboard.sessionsEntered?.await()

        viewModel.onBackground()
        runCurrent()
        viewModel.onForeground()
        runCurrent()

        assertTrue(dashboard.sessionCalls >= 2)
        sessionsGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.Connected, viewModel.state.value.connectionPhase)
        assertNotNull(viewModel.state.value.sessions)
        assertNull(viewModel.state.value.loadingMessage)
    }

    @Test
    fun backgroundDuringCreateBeforeSessionClosesGatewayAndForegroundCreatesAgain() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(dashboard = dashboard)
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        val createGate = CompletableDeferred<Unit>()
        gateway.createGate = createGate
        gateway.createEntered = CompletableDeferred()
        viewModel.createNewConversation()
        gateway.createEntered?.await()

        viewModel.onBackground()
        runCurrent()
        assertTrue(gateway.closeCount > 0)

        val secondCreateEntered = CompletableDeferred<Unit>()
        gateway.createEntered = secondCreateEntered
        viewModel.onForeground()
        runCurrent()
        secondCreateEntered.await()
        createGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, gateway.methods.count { it == "session.create" })
        assertNotNull(viewModel.state.value.activeSummary)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertNull(viewModel.state.value.notice)
        viewModel.leaveConversation()
    }


    @Test
    fun cancelledReconciliationReleasesItsGlobalGateForForegroundRecovery() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        val resumeGate = CompletableDeferred<Unit>()
        val resumeEntered = CompletableDeferred<Unit>()
        gateway.resumeGate = resumeGate
        gateway.resumeEntered = resumeEntered

        gateway.disconnect("reconnect for cancellation test")
        resumeEntered.await()

        viewModel.onBackground()
        runCurrent()
        resumeGate.complete(Unit)

        viewModel.onForeground()
        advanceUntilIdle()

        assertTrue(gateway.methods.count { it == "session.resume" } >= 3)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertNull(viewModel.state.value.notice)
        viewModel.leaveConversation()
    }

    @Test
    fun newerReconciliationEpochWinsWhenAnOlderSnapshotReturnsLate() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        val olderResumeGate = CompletableDeferred<Unit>()
        val newerResumeGate = CompletableDeferred<Unit>()
        val olderResumeEntered = CompletableDeferred<Unit>()
        val newerResumeEntered = CompletableDeferred<Unit>()
        gateway.promptFailure = IOException("prompt delivery became uncertain")
        gateway.resumePayloads += resumePayload(
            messages = listOf(ConversationMessage(role = "user", text = "stale snapshot")),
            running = false,
        )
        gateway.resumePayloads += resumePayload(
            messages = listOf(ConversationMessage(role = "user", text = "fresh snapshot")),
            running = false,
        )
        gateway.resumeGates += olderResumeGate
        gateway.resumeGates += newerResumeGate
        gateway.resumeEnteredSignals += olderResumeEntered
        gateway.resumeEnteredSignals += newerResumeEntered

        viewModel.updateDraft("uncertain prompt")
        viewModel.sendMessage()
        olderResumeEntered.await()

        gateway.disconnect("reconnect while the first reconciliation is pending")
        runCurrent()
        newerResumeEntered.await()

        newerResumeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("fresh snapshot", viewModel.state.value.messages.single().text)

        olderResumeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("fresh snapshot", viewModel.state.value.messages.single().text)
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        viewModel.leaveConversation()
    }

    @Test
    fun staleStoppedTurnEventsCannotRearmTheConversation() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        viewModel.updateDraft("Stop this turn")
        viewModel.sendMessage()
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"safe partial"}""")
        advanceUntilIdle()

        gateway.resumePayload = resumePayload(
            messages = listOf(
                ConversationMessage(role = "user", text = "Stop this turn", id = "server-user"),
                ConversationMessage(role = "assistant", text = "safe partial", id = "server-assistant"),
            ),
            running = false,
        )
        viewModel.interrupt()
        advanceUntilIdle()

        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"stale response"}""")
        gateway.emit("session.busy", """{"busy":true}""")
        gateway.emit("session.info", """{"running":true}""")
        advanceUntilIdle()

        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals("", viewModel.state.value.streamingText)
        assertEquals(listOf("user", "assistant"), viewModel.state.value.messages.map { it.role })
        assertEquals("safe partial", viewModel.state.value.messages.last().text)
        viewModel.leaveConversation()
    }

    @Test
    fun runningFalseSessionEventsSettlePartialOutputWithoutMessageComplete() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)

        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"settled by busy=false"}""")
        gateway.emit("session.busy", """{"busy":false}""")
        gateway.emit("message.start")
        gateway.emit("message.delta", """{"text":"settled by running=false"}""")
        gateway.emit("session.info", """{"running":false}""")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TurnState.Idle, state.turnState)
        assertEquals("", state.streamingText)
        assertEquals(
            listOf("settled by busy=false", "settled by running=false"),
            state.messages.map { it.text },
        )
        viewModel.leaveConversation()
    }

    @Test
    fun runningFalseResumeSettlesAuthoritativeInflightOutputWithoutCompletion() = runTest {
        val gateway = FakeGateway()
        gateway.resumePayload = resumePayload(
            messages = listOf(
                ConversationMessage(role = "user", text = "Recover this turn", id = "user-1"),
                ConversationMessage(role = "assistant", text = "Stored prefix", id = "assistant-1"),
            ),
            running = false,
            inflightText = "Stored prefix and recovered suffix",
        )
        val viewModel = openConversation(gateway)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TurnState.Idle, state.turnState)
        assertEquals("", state.streamingText)
        assertEquals(
            listOf("Recover this turn", "Stored prefix and recovered suffix"),
            state.messages.map { it.text },
        )
        viewModel.leaveConversation()
    }

    @Test
    fun profileAndOriginChangesClearGatewayBoundStateAndOldEvents() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://first.hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        viewModel.selectProfile("work")
        advanceUntilIdle()

        assertEquals("work", viewModel.state.value.selectedProfile)
        assertNotNull(viewModel.state.value.sessions)
        assertNull(viewModel.state.value.activeSummary)
        assertTrue(gateway.closeCount > 0)

        gateway.emit("message.error", """{"message":"old profile failure"}""")
        runCurrent()
        assertNull(viewModel.state.value.notice)

        dashboard.profileCatalog = listOf(DashboardProfile(name = "default", isDefault = true))
        viewModel.updateDashboardUrl("http://second.hermes.test:9119")
        viewModel.findDashboard()
        advanceUntilIdle()

        assertEquals("http://second.hermes.test:9119", viewModel.state.value.dashboardUrl)
        assertNull(viewModel.state.value.sessions)
        assertNull(viewModel.state.value.activeSummary)
        assertNull(viewModel.state.value.notice)

        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.Connected, viewModel.state.value.connectionPhase)
        assertEquals("default", viewModel.state.value.selectedProfile)
        assertEquals("http://second.hermes.test:9119", viewModel.state.value.probe?.baseUrl)
        viewModel.leaveConversation()
    }

    @Test
    fun authRpcFailureKeepsAExplicitSignInRecoveryAction() = runTest {
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

        gateway.connectFailure = GatewayRpcException(
            code = 401,
            message = "raw auth failure at https://private.example/path",
        )
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        val notice = requireNotNull(viewModel.state.value.notice)
        assertEquals(ConnectionPhase.AuthenticationRequired, viewModel.state.value.connectionPhase)
        assertEquals(UiNoticeCategory.AuthenticationRequired, notice.category)
        assertEquals(UiRecoveryAction.SignIn, notice.recovery)
        assertEquals("Your Hermes sign-in has expired. Sign in again.", notice.message)
        assertNull(viewModel.state.value.activeSummary)
    }

    @Test
    fun protocolRpcFailureKeepsTypedRetryCopyAndRedactsRawErrorText() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val diagnostics = mutableListOf<SanitizedDiagnostic>()
        val rawError = "StandaloneCoroutine was cancelled at https://private.example/path /srv/private prompt"
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 60_000L },
            diagnosticsSink = DiagnosticsSink { diagnostics += it },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        gateway.resumeFailure = GatewayRpcException(code = -32602, message = rawError)
        viewModel.openSession(dashboard.session)
        runCurrent()

        val state = viewModel.state.value
        val notice = requireNotNull(state.notice)
        assertEquals(UiNoticeCategory.InvalidResponse, notice.category)
        assertEquals(UiRecoveryAction.Retry, notice.recovery)
        assertEquals("Hermes returned an unexpected response. Try again.", notice.message)
        assertFalse(state.toString().contains(rawError))
        assertFalse(state.toString().contains("StandaloneCoroutine"))
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.none { it.toString().contains(rawError) })
        assertTrue(diagnostics.none { it.toString().contains("private.example") })
        assertTrue(diagnostics.none { it.toString().contains("/srv/private") })
        assertTrue(diagnostics.none { it.toString().contains("prompt") })
        viewModel.leaveConversation()
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
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertNull(viewModel.state.value.notice)

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
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertNull(viewModel.state.value.notice)
        viewModel.leaveConversation()
    }

    @Test
    fun removesPersistedPrefixFromInflightProjection() {
        val suffix = CelesteViewModel.unpersistedInflightText(
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

    private class BlockingClearConnectionStore(
        private val clearGate: CompletableDeferred<Unit>,
    ) : ConnectionStore {
        val clearEntered = CompletableDeferred<Unit>()

        override suspend fun load(): StoredConnection? = null

        override suspend fun replace(
            descriptor: SavedConnectionDescriptor,
            secret: ReusableSecret?,
        ) = Unit

        override suspend fun clearSecret() {
            withContext(NonCancellable) {
                clearEntered.complete(Unit)
                clearGate.await()
            }
        }

        override suspend fun forget() = Unit
    }

    private class FakeDashboard(
        private val gateway: FakeGateway,
        private val authRequired: Boolean = false,
    ) : DashboardService {
        var profileFailure: Throwable? = null
        var sessionFailure: Throwable? = null
        var probeGate: CompletableDeferred<Unit>? = null
        var probeEntered: CompletableDeferred<Unit>? = null
        var sessionsGate: CompletableDeferred<Unit>? = null
        var sessionsEntered: CompletableDeferred<Unit>? = null
        var probeCalls = 0
        var sessionCalls = 0
        var profileCatalog = listOf(
            DashboardProfile(name = "default", isDefault = true),
            DashboardProfile(name = "work"),
        )

        val session = StoredSession(
            id = "stored-42",
            title = "Shared conversation",
            preview = "",
            startedAt = 1.0,
            messageCount = 0,
            source = "desktop",
        )

        override suspend fun probe(rawBaseUrl: String): DashboardProbeResult {
            probeCalls += 1
            probeEntered?.complete(Unit)
            probeGate?.await()
            return DashboardProbeResult(
                baseUrl = rawBaseUrl,
                authRequired = authRequired,
                providers = if (authRequired) {
                    listOf(AuthProvider("password", "Password", supportsPassword = true))
                } else {
                    emptyList()
                },
                version = "test",
            )
        }

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
        ): List<StoredSession> {
            sessionCalls += 1
            sessionsEntered?.complete(Unit)
            sessionsGate?.await()
            sessionFailure?.let { throw it }
            return listOf(session)
        }

        override suspend fun listProfiles(
            baseUrl: String,
            credential: GatewayCredential,
        ): List<DashboardProfile> {
            profileFailure?.let { throw it }
            return profileCatalog
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

        val methods = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var connectCount = 0
        var createCount = 0
        var failHealthCheck = false
        var connectFailure: Throwable? = null
        var promptFailure: Throwable? = null
        var interruptFailure: Throwable? = null
        var createGate: CompletableDeferred<Unit>? = null
        var createEntered: CompletableDeferred<Unit>? = null
        var resumePayload: JsonObject = resumePayload(messages = emptyList(), running = false)
        val resumePayloads = mutableListOf<JsonObject>()
        val resumeGates = mutableListOf<CompletableDeferred<Unit>>()
        val resumeEnteredSignals = mutableListOf<CompletableDeferred<Unit>>()
        var resumeFailure: Throwable? = null
        var resumeGate: CompletableDeferred<Unit>? = null
        var resumeEntered: CompletableDeferred<Unit>? = null
        var closeCount = 0

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
                "session.resume" -> {
                    resumeEntered?.complete(Unit)
                    resumeEnteredSignals.firstOrNull()?.let { entered ->
                        entered.complete(Unit)
                        resumeEnteredSignals.removeAt(0)
                    }
                    val gate = if (resumeGates.isNotEmpty()) {
                        resumeGates.removeAt(0)
                    } else {
                        resumeGate
                    }
                    val payload = if (resumePayloads.isNotEmpty()) {
                        resumePayloads.removeAt(0)
                    } else {
                        resumePayload
                    }
                    gate?.await()
                    resumeFailure?.let { throw it }
                    payload
                }
                "session.create" -> {
                    createCount += 1
                    createEntered?.complete(Unit)
                    createGate?.await()
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
                    buildJsonObject { put("sessions", "healthy") }
                }
                "prompt.submit" -> {
                    promptFailure?.let { failure ->
                        promptFailure = null
                        throw failure
                    }
                    buildJsonObject { put("status", "streaming") }
                }
                "session.interrupt" -> {
                    interruptFailure?.let { failure ->
                        interruptFailure = null
                        throw failure
                    }
                    buildJsonObject { put("status", "interrupting") }
                }
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
            inflightText: String? = null,
        ): JsonObject {
            val encodedMessages = messages.joinToString(",") { message ->
                """{"id":${Json.encodeToString(message.id ?: "")},"role":${Json.encodeToString(message.role)},"text":${Json.encodeToString(message.text)}}"""
            }
            val encodedInflight = inflightText?.let { text ->
                """{"text":${Json.encodeToString(text)}}"""
            } ?: "null"
            return Json.parseToJsonElement(
                """{"session_id":"runtime-7","resumed":"stored-42","running":$running,"status":"${if (running) "streaming" else "idle"}","inflight":$encodedInflight,"messages":[$encodedMessages]}""",
            ) as JsonObject
        }
    }
}
