package dev.hazydreams.hermesceleste

import java.io.IOException

import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.ConversationStep
import dev.hazydreams.hermesceleste.network.ConversationStepKind
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.SessionCatalogPage
import dev.hazydreams.hermesceleste.network.StoredSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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
    fun openingConversationUsesPersistedTranscriptReasoning() = runTest {
        val gateway = FakeGateway().apply {
            resumePayload = resumePayload(
                messages = listOf(
                    ConversationMessage(role = "user", text = "Inspect this", id = "resume-user"),
                    ConversationMessage(role = "assistant", text = "Done", id = "resume-final"),
                ),
                running = false,
            )
        }
        val dashboard = FakeDashboard(gateway).apply {
            sessionMessages = listOf(
                ConversationMessage(role = "user", text = "Inspect this", id = "stored-user"),
                ConversationMessage(
                    role = "steps",
                    text = "",
                    id = "stored-steps",
                    steps = listOf(
                        ConversationStep(
                            id = "stored-reasoning",
                            kind = ConversationStepKind.Reasoning,
                            detail = "I should read the file first.",
                        ),
                    ),
                ),
                ConversationMessage(role = "assistant", text = "Done", id = "stored-final"),
            )
        }
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        val messages = viewModel.state.value.messages
        assertEquals(listOf("user", "steps", "assistant"), messages.map { it.role })
        assertEquals("I should read the file first.", messages[1].steps.single().detail)
        assertEquals(listOf(Triple("stored-42", "default", 500)), dashboard.sessionMessageRequests)
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        viewModel.controller.close()
    }

    @Test
    fun taskProgressBelongsOnlyToTheSessionThatPublishedIt() = runTest {
        val gateway = FakeGateway().apply {
            resumePayload = Json.parseToJsonElement(
                """{
                    "session_id":"runtime-7",
                    "resumed":"stored-42",
                    "running":false,
                    "status":"idle",
                    "inflight":null,
                    "messages":[
                        {"role":"user","text":"Build this"},
                        {"role":"assistant","tool_calls":[{"id":"todo-1","function":{"name":"todo","arguments":"{}"}}]},
                        {"role":"tool","tool_call_id":"todo-1","tool_name":"todo","content":"{\"todos\":[{\"id\":\"build\",\"content\":\"Build this\",\"status\":\"in_progress\"}]}"}
                    ]
                }""".trimIndent(),
            ) as JsonObject
        }
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        assertEquals(listOf("build"), viewModel.state.value.taskProgress?.items?.map { it.id })

        val secondSession = dashboard.session.copy(id = "stored-43", title = "Another conversation")
        gateway.resumePayload = Json.parseToJsonElement(
            """{"session_id":"runtime-8","resumed":"stored-43","running":false,"status":"idle","inflight":null,"messages":[{"role":"user","text":"Another prompt"}]}""",
        ) as JsonObject
        viewModel.openSession(secondSession)
        advanceUntilIdle()

        assertEquals("stored-43", viewModel.state.value.activeSummary?.id)
        assertNull(viewModel.state.value.taskProgress)
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
        assertEquals(2, gateway.connectCount)
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(listOf("Do this once", "Finished exactly once"), state.messages.map { it.text })
        assertEquals("", state.streamingText)
        assertEquals(TurnState.Idle, state.turnState)
        viewModel.controller.close()
    }

    @Test
    fun transientReconnectKeepsTheDraftAndTransportDetailsOutOfTheConversation() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 1_000L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        viewModel.updateDraft("Keep this draft")
        try {
            gateway.connectFailure = IOException("socket exploded")
            gateway.disconnect("StandaloneCoroutine was cancelled")

            assertEquals(TurnState.Reconnecting, viewModel.state.value.turnState)
            assertEquals("Keep this draft", viewModel.state.value.draft)
            assertNull(viewModel.state.value.errorMessage)

            mainDispatcher.scheduler.advanceTimeBy(1_000L)
            mainDispatcher.scheduler.runCurrent()
            assertEquals(TurnState.Reconnecting, viewModel.state.value.turnState)
            assertNull(viewModel.state.value.errorMessage)

            gateway.connectFailure = null
            mainDispatcher.scheduler.advanceTimeBy(1_000L)
            mainDispatcher.scheduler.runCurrent()

            assertEquals(TurnState.Idle, viewModel.state.value.turnState)
            assertEquals("Keep this draft", viewModel.state.value.draft)
            assertNull(viewModel.state.value.errorMessage)
        } finally {
            viewModel.controller.close()
        }
    }

    @Test
    fun reconnectContinuesAfterARequestTimeoutCancellation() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 1_000L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        try {
            gateway.connectFailure = CancellationException("Timed out waiting for Hermes")
            gateway.disconnect("connection lost")

            repeat(6) {
                mainDispatcher.scheduler.advanceTimeBy(1_000L)
                mainDispatcher.scheduler.runCurrent()
            }
            assertEquals(TurnState.Reconnecting, viewModel.state.value.turnState)
            assertFalse(viewModel.state.value.resumeExhausted)

            gateway.connectFailure = null
            mainDispatcher.scheduler.advanceTimeBy(1_000L)
            mainDispatcher.scheduler.runCurrent()

            assertEquals(TurnState.Idle, viewModel.state.value.turnState)
            assertNull(viewModel.state.value.errorMessage)
        } finally {
            viewModel.controller.close()
        }
    }

    @Test
    fun repeatedResumeFailuresShowHistoryAndWaitForAnExplicitRetry() = runTest {
        val gateway = FakeGateway().apply {
            resumeFailure = IOException("resume unavailable")
        }
        val dashboard = FakeDashboard(gateway).apply {
            sessionMessages = listOf(
                ConversationMessage(
                    role = "assistant",
                    text = "Persisted history remains readable.",
                    id = "persisted-assistant",
                ),
            )
        }
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        assertEquals(5, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Reconnecting, viewModel.state.value.turnState)
        assertTrue(viewModel.state.value.resumeExhausted)
        assertEquals(
            listOf("Persisted history remains readable."),
            viewModel.state.value.messages.map(ConversationMessage::text),
        )
        assertNull(viewModel.state.value.errorMessage)

        gateway.resumeFailure = null
        viewModel.controller.reconnectNow()
        advanceUntilIdle()

        assertEquals(6, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertFalse(viewModel.state.value.resumeExhausted)
        viewModel.controller.close()
    }

    @Test
    fun promptTimeoutReconcilesQuietlyWithoutLeakingTransportDetails() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        advanceUntilIdle()
        gateway.promptFailure = CancellationException("StandaloneCoroutine was cancelled")

        viewModel.updateDraft("Send this once")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertNull(viewModel.state.value.errorMessage)
        viewModel.controller.close()
    }

    @Test
    fun definitivePromptRejectionRestoresTheExactDraft() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        advanceUntilIdle()
        gateway.promptFailure = GatewayRpcException(
            code = 409,
            message = "Hermes rejected this prompt.",
        )

        viewModel.updateDraft("  Send this exactly once  ")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        assertTrue(viewModel.state.value.messages.none { it.role == "user" && it.pending })
        assertEquals("  Send this exactly once  ", viewModel.state.value.draft)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals("Hermes rejected this prompt.", viewModel.state.value.errorMessage)
        viewModel.controller.close()
    }

    @Test
    fun interruptTimeoutReconcilesQuietlyWithoutLeakingTransportDetails() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        advanceUntilIdle()
        viewModel.updateDraft("Start a long turn")
        viewModel.sendMessage()
        advanceUntilIdle()
        gateway.interruptFailure = CancellationException("request timed out")

        viewModel.interrupt()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.interrupt" })
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertNull(viewModel.state.value.errorMessage)
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
    fun openingUnreadSearchResultClearsItsSearchProjection() = runTest {
        val dashboard = FakeDashboard(FakeGateway())
        val unreadResult = dashboard.session.copy(
            id = "remote-unread",
            title = "Unread search result",
            preview = "The matching conversation snippet",
            unread = true,
        )
        dashboard.searchResults["unread"] = listOf(unreadResult)
        val viewModel = CelesteViewModel(dashboard = dashboard)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.updateSessionSearchQuery("unread")
        mainDispatcher.scheduler.advanceTimeBy(200)
        mainDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.state.value.sessionSearchResults.single().unread)

        viewModel.openSession(unreadResult)

        assertFalse(viewModel.state.value.sessionSearchResults.single().unread)
        advanceUntilIdle()
        assertEquals(listOf("remote-unread" to "default"), dashboard.markReadRequests)
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
        viewModel.openSession(dashboard.session)
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

        assertEquals(2, gateway.connectCount)
        assertEquals(1, gateway.methods.count { it == "session.list" })
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.controller.close()
    }

    @Test
    fun localDraftCreatesAndPublishesASessionOnlyOnTheFirstPrompt() = runTest {
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

        assertNull(viewModel.state.value.activeSummary)
        assertEquals(listOf("stored-42"), viewModel.state.value.sessions?.map { it.id })
        assertEquals(0, gateway.methods.count { it == "session.create" })

        viewModel.selectProfile("work")
        viewModel.createNewConversation()
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeSummary)
        assertEquals("work", viewModel.state.value.selectedProfile)
        assertEquals(listOf("stored-42"), viewModel.state.value.sessions?.map { it.id })
        assertEquals(0, gateway.methods.count { it == "session.create" })
        assertEquals(0, gateway.methods.count { it == "session.resume" })

        viewModel.updateDraft("Persist this conversation")
        viewModel.sendMessage()
        advanceUntilIdle()
        val createParams = gateway.requests.last { it.first == "session.create" }.second
        assertEquals("work", createParams["profile"]?.jsonPrimitive?.content)
        assertEquals("android", createParams["source"]?.jsonPrimitive?.content)
        val promptParams = gateway.requests.last { it.first == "prompt.submit" }.second
        assertEquals("runtime-new-1", promptParams["session_id"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("stored-new-1", "stored-42"),
            viewModel.state.value.sessions?.map { it.id },
        )
        assertEquals("Persist this conversation", viewModel.state.value.sessions?.first()?.preview)
        assertEquals(1, viewModel.state.value.sessions?.first()?.messageCount)

        gateway.resumePayload = Json.parseToJsonElement(
            """{"session_id":"runtime-resumed","resumed":"stored-new-1","running":false,"status":"idle","inflight":null,"messages":[]}""",
        ) as JsonObject
        gateway.disconnect("after first prompt")
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.create" })
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        viewModel.controller.close()
    }

    @Test
    fun failedFirstPromptCreationKeepsTheLocalDraftReadyToSendAgain() = runTest {
        val gateway = FakeGateway().apply {
            createFailure = IOException("socket exploded")
        }
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.updateDraft("  Keep this exact draft  ")

        viewModel.sendMessage()
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeSummary)
        assertEquals("  Keep this exact draft  ", viewModel.state.value.draft)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals(
            "Could not start the conversation. Your message is ready to send again.",
            viewModel.state.value.errorMessage,
        )
        assertEquals(1, gateway.methods.count { it == "session.create" })
        assertEquals(0, gateway.methods.count { it == "prompt.submit" })

        gateway.createFailure = null
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("stored-new-1", viewModel.state.value.activeSummary?.id)
        assertEquals("", viewModel.state.value.draft)
        assertEquals(2, gateway.methods.count { it == "session.create" })
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertNull(viewModel.state.value.errorMessage)
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

        assertNull(viewModel.state.value.activeSummary)
        viewModel.updateDraft("Persist conversation A")
        viewModel.sendMessage()
        runCurrent()

        viewModel.createNewConversation()
        advanceUntilIdle()
        assertNull(viewModel.state.value.activeSummary)
        assertEquals(listOf("stored-42"), viewModel.state.value.sessions?.map { it.id })

        firstGateway.promptGate?.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeSummary)
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
    fun staleFirstSendCreationCannotOverwriteASelectedConversation() = runTest {
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
        advanceUntilIdle()
        viewModel.updateDraft("Start this later")
        viewModel.sendMessage()
        runCurrent()

        assertEquals(TurnState.Synchronizing, viewModel.state.value.turnState)
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
        controller.updateDraft("Create from iOS")
        controller.sendMessage()
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
        var returnSearchAfterCancellation = false
        var markReadGate: CompletableDeferred<Unit>? = null
        var markReadFailure: Throwable? = null
        val sessionPages = mutableMapOf<Int, SessionCatalogPage>()
        val sessionPageFailures = mutableMapOf<Int, Throwable>()
        val sessionPageRequests = mutableListOf<Pair<Int, Int>>()
        val searchResults = mutableMapOf<String, List<StoredSession>>()
        val searchGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val searchRequests = mutableListOf<Triple<String, String, Int>>()
        val sessionMessageRequests = mutableListOf<Triple<String, String, Int>>()
        var sessionMessages = emptyList<ConversationMessage>()
        val markReadRequests = mutableListOf<Pair<String, String>>()
        val pinRequests = mutableListOf<Triple<String, String, Boolean>>()
        val pinGates = mutableMapOf<Boolean, CompletableDeferred<Unit>>()
        val pinFailures = mutableMapOf<Boolean, Throwable>()
        val renameRequests = mutableListOf<Triple<String, String, String>>()
        val renameGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val renameFailures = mutableMapOf<String, Throwable>()

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
        ): List<ConversationMessage> {
            sessionMessageRequests += Triple(sessionId, profile, limit)
            return sessionMessages
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
        var resumeFailure: Throwable? = null
        var createFailure: Throwable? = null
        var promptFailure: Throwable? = null
        var interruptFailure: Throwable? = null
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
                "session.resume" -> {
                    resumeFailure?.let { throw it }
                    resumePayload
                }
                "session.create" -> {
                    createGate?.await()
                    createFailure?.let { throw it }
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
                    promptFailure?.let { throw it }
                    buildJsonObject { put("status", "streaming") }
                }
                "session.interrupt" -> {
                    interruptFailure?.let { throw it }
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
