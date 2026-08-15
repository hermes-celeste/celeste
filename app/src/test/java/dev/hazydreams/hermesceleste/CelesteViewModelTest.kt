package dev.hazydreams.hermesceleste

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import java.io.IOException

import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.ActivityCapabilityState
import dev.hazydreams.hermesceleste.network.ActivityDisclosurePreferenceStore
import dev.hazydreams.hermesceleste.network.ActivityDisclosureScope
import dev.hazydreams.hermesceleste.network.ActivityPresentationState
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.CorrelationQuality
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.InMemoryActivityDisclosurePreferenceStore
import dev.hazydreams.hermesceleste.network.ServerReasoningActivity
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.ToolActivity
import dev.hazydreams.hermesceleste.network.ToolPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
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
    private lateinit var viewModelStore: ViewModelStore
    private val trackedViewModels = mutableListOf<CelesteViewModel>()
    private var viewModelKey = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        viewModelStore = ViewModelStore()
        trackedViewModels.clear()
        viewModelKey = 0
    }

    @After
    fun tearDown() {
        val scopeJobs = trackedViewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        viewModelStore.clear()
        runBlocking { scopeJobs.joinAll() }
        mainDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun track(viewModel: CelesteViewModel) {
        viewModelStore.put("celeste-view-model-${viewModelKey++}", viewModel)
        trackedViewModels += viewModel
    }

    @Test
    fun emptyActivitySnapshotTimesOutToUnavailableWithoutBlockingTheConversation() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(
            gateway = gateway,
            activityDiscoveryTimeoutMillis = 100L,
        )
        runCurrent()

        assertEquals(
            ActivityPresentationState.Discovering,
            viewModel.state.value.agentActivity?.presentation,
        )

        advanceTimeBy(100L)
        runCurrent()
        viewModel.state.first {
            it.agentActivity?.presentation == ActivityPresentationState.Unavailable
        }

        assertEquals(
            ActivityPresentationState.Unavailable,
            viewModel.state.value.agentActivity?.presentation,
        )
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.leaveConversation()
    }

    @Test
    fun reasoningDeltasAreCoalescedIntoOneProjectionUpdate() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(
            gateway = gateway,
            reasoningCoalescingWindowMillis = 100L,
        )
        runCurrent()

        gateway.emit(
            "reasoning.delta",
            """{"text":"<server-summary-one>","verbose":true}""",
        )
        gateway.emit(
            "reasoning.delta",
            """{"text":"<server-summary-two>","verbose":true}""",
        )
        runCurrent()
        assertTrue(
            viewModel.state.value.agentActivity?.items.orEmpty()
                .filterIsInstance<ServerReasoningActivity>().isEmpty(),
        )

        advanceTimeBy(100L)
        runCurrent()

        viewModel.state.first { state ->
            state.agentActivity?.items
                ?.filterIsInstance<ServerReasoningActivity>()
                ?.size == 1
        }

        val reasoning = viewModel.state.value.agentActivity?.items.orEmpty()
            .filterIsInstance<ServerReasoningActivity>()
            .single()
        assertEquals("<server-summary-one><server-summary-two>", reasoning.text.text)
        assertEquals(1, viewModel.state.value.agentActivity?.items
            ?.filterIsInstance<ServerReasoningActivity>()?.size)
        viewModel.leaveConversation()
    }

    @Test
    fun showReasoningFalseSuppressesVerboseDeltaBeforeCoalescing() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(
            gateway = gateway,
            reasoningCoalescingWindowMillis = 100L,
        )
        runCurrent()

        gateway.emit(
            "reasoning.delta",
            """{"text":"<hidden-delta>","show_reasoning":false,"verbose":true}""",
        )
        advanceTimeBy(100L)
        runCurrent()

        assertTrue(
            viewModel.state.value.agentActivity?.items.orEmpty()
                .none { it is ServerReasoningActivity },
        )
        assertEquals(
            ActivityCapabilityState.Unknown,
            viewModel.state.value.agentActivity?.capability,
        )
        viewModel.leaveConversation()
    }

    @Test
    fun projectsToolAndServerReasoningSeparatelyAndHonorsDeviceDisclosure() = runTest {
        val gateway = FakeGateway()
        val preferences = InMemoryActivityDisclosurePreferenceStore()
        val viewModel = openConversation(gateway, preferences)

        gateway.emit(
            "tool.start",
            """{"name":"terminal","tool_call_id":"call-1","args":"<tool-input>"}""",
        )
        gateway.emit(
            "reasoning.available",
            """{"text":"<server-summary>","label":"Server summary","verbose":true}""",
        )
        gateway.emit(
            "tool.complete",
            """{"name":"terminal","tool_call_id":"call-1","output":"<tool-output>"}""",
        )
        advanceUntilIdle()
        viewModel.state.first { state ->
            val activity = state.agentActivity ?: return@first false
            activity.items.size == 2 &&
                activity.items.filterIsInstance<ToolActivity>().singleOrNull()?.phase == ToolPhase.Completed &&
                activity.items.filterIsInstance<ServerReasoningActivity>().size == 1
        }

        val activity = requireNotNull(viewModel.state.value.agentActivity)
        assertEquals(2, activity.items.size)
        assertEquals(1, activity.items.filterIsInstance<ToolActivity>().size)
        assertEquals(1, activity.items.filterIsInstance<ServerReasoningActivity>().size)
        assertEquals(
            ToolPhase.Completed,
            activity.items.filterIsInstance<ToolActivity>().single().phase,
        )
        assertEquals(
            CorrelationQuality.ExactId,
            activity.items.filterIsInstance<ToolActivity>().single().correlation,
        )
        assertEquals(ActivityCapabilityState.ToolAndServerReasoning, activity.capability)

        viewModel.setActivityReasoningDisclosureEnabled(false)
        assertFalse(viewModel.state.value.agentActivityReasoningDisclosureEnabled)
        assertFalse(
            preferences.isServerReasoningDisclosureEnabled(
                ActivityDisclosureScope(
                    originKey = "http://hermes.test:9119",
                    profile = "default",
                    storedSessionId = "stored-42",
                ),
            ),
        )
        assertTrue(viewModel.state.value.agentActivity?.items.orEmpty().none { it is ServerReasoningActivity })
        assertEquals(
            ActivityCapabilityState.ToolAndServerReasoning,
            viewModel.state.value.agentActivity?.capability,
        )
        assertTrue(gateway.methods.none { it.contains("activity", ignoreCase = true) })
        viewModel.leaveConversation()
    }

    @Test
    fun finalAssistantContentDoesNotRepeatServerReasoningSummary() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)

        gateway.emit(
            "reasoning.available",
            """{"text":"<server-summary>","verbose":true}""",
        )
        gateway.emit(
            "message.complete",
            """{"content":"<server-summary>\n<assistant-content>","status":"complete"}""",
        )
        advanceUntilIdle()
        viewModel.state.first { state ->
            state.messages.any { it.role == "assistant" && it.text == "<assistant-content>" } &&
                state.agentActivity?.items?.filterIsInstance<ServerReasoningActivity>()?.size == 1
        }

        val state = viewModel.state.value
        assertEquals("<assistant-content>", state.messages.single { it.role == "assistant" }.text)
        assertEquals(
            "<server-summary>",
            state.agentActivity?.items
                ?.filterIsInstance<ServerReasoningActivity>()
                ?.single()
                ?.text
                ?.text,
        )
        viewModel.leaveConversation()
    }

    @Test
    fun reconnectRestoresActivityProjectionAndFallsBackWhenSnapshotIsEmpty() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(
            gateway = gateway,
            activityDiscoveryTimeoutMillis = 100L,
        )
        gateway.emit(
            "tool.start",
            """{"name":"terminal","tool_call_id":"call-1"}""",
        )
        advanceUntilIdle()
        viewModel.state.first {
            it.agentActivity?.presentation == ActivityPresentationState.Running
        }
        assertEquals(
            dev.hazydreams.hermesceleste.network.ActivityPresentationState.Running,
            viewModel.state.value.agentActivity?.presentation,
        )

        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = false,
        )
        gateway.disconnect("reconnect for activity")
        gateway.resumeRequests.first { it >= 2 }
        viewModel.state.first { state ->
            state.agentActivity?.presentation == ActivityPresentationState.Discovering &&
                state.turnState == TurnState.Idle
        }
        advanceTimeBy(100L)
        runCurrent()
        viewModel.state.first { state ->
            state.agentActivity?.presentation == ActivityPresentationState.Unavailable &&
                state.turnState == TurnState.Idle
        }

        assertEquals(
            ActivityPresentationState.Unavailable,
            viewModel.state.value.agentActivity?.presentation,
        )
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.leaveConversation()
    }

    @Test
    fun finalAssistantContentRemovesEmbeddedLongServerReasoning() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        val disclosed = "<long-server-summary>" + "x".repeat(13_000)

        gateway.emit(
            "reasoning.available",
            buildJsonObject {
                put("text", disclosed)
                put("verbose", true)
            }.toString(),
        )
        gateway.emit(
            "message.complete",
            buildJsonObject {
                put("content", "<assistant-before>\n$disclosed\n<assistant-after>")
                put("status", "complete")
            }.toString(),
        )
        advanceUntilIdle()
        viewModel.state.first { state ->
            state.messages.any {
                it.role == "assistant" &&
                    it.text == "<assistant-before>\n<assistant-after>"
            } && state.agentActivity?.items?.filterIsInstance<ServerReasoningActivity>()?.size == 1
        }

        val state = viewModel.state.value
        assertEquals(
            "<assistant-before>\n<assistant-after>",
            state.messages.single { it.role == "assistant" }.text,
        )
        val reasoning = state.agentActivity?.items
            ?.filterIsInstance<ServerReasoningActivity>()
            ?.single()
        assertTrue(reasoning?.text?.wasTruncated == true)
        assertTrue(reasoning?.text?.text?.contains("<long-server-summary>") == true)
        viewModel.leaveConversation()
    }

    @Test
    fun embeddedReasoningInInterimContentIsRemovedWhenFinalSettles() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)

        gateway.emit(
            "reasoning.available",
            """{"text":"<server-summary>","verbose":true}""",
        )
        gateway.emit(
            "message.interim",
            """{"text":"<server-summary>\n<assistant-content>"}""",
        )
        gateway.emit(
            "message.complete",
            """{"content":"<server-summary>\n<assistant-content>","status":"complete"}""",
        )
        advanceUntilIdle()
        viewModel.state.first { state ->
            state.messages.filter { it.role == "assistant" }.map { it.text } ==
                listOf("<assistant-content>") &&
                state.agentActivity?.items?.filterIsInstance<ServerReasoningActivity>()?.size == 1
        }

        assertEquals(
            listOf("<assistant-content>"),
            viewModel.state.value.messages.filter { it.role == "assistant" }.map { it.text },
        )
        assertEquals(
            1,
            viewModel.state.value.agentActivity?.items
                ?.filterIsInstance<ServerReasoningActivity>()
                ?.size,
        )
        viewModel.leaveConversation()
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
        viewModel.state.first { state ->
            state.turnState == TurnState.Idle &&
                state.streamingText.isEmpty() &&
                state.messages.map { it.role } == listOf("user", "assistant") &&
                state.messages.singleOrNull { it.role == "assistant" }?.text == "Hello continued" &&
                state.messages.singleOrNull { it.role == "user" }?.pending == false
        }

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
        viewModel.state.first { state ->
            state.turnState == TurnState.Running &&
                state.streamingText == "Partial work"
        }

        gateway.resumePayload = resumePayload(
            messages = listOf(
                ConversationMessage(role = "user", text = "Please do a long task", id = "user-1"),
                ConversationMessage(role = "assistant", text = "Partial work", id = "assistant-1"),
            ),
            running = false,
        )
        viewModel.interrupt()
        gateway.interruptRequests.first { it >= 1 }
        viewModel.state.first { state ->
            state.turnState == TurnState.Idle &&
                state.messages.lastOrNull()?.text == "Partial work"
        }

        assertTrue(gateway.methods.contains("session.interrupt"))
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals("Partial work", viewModel.state.value.messages.last().text)
        viewModel.leaveConversation()
        advanceUntilIdle()
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

        viewModel.state.first { state ->
            state.messages.map(ConversationMessage::text) ==
                listOf("Do this once", "Finished exactly once") &&
                state.streamingText.isEmpty() &&
                state.turnState == TurnState.Idle
        }

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
    fun overlappingRecoverySerializesResumeAndReplaysEventsAfterItsSnapshot() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        val firstResume = CompletableDeferred<JsonObject>()
        val secondResume = CompletableDeferred<JsonObject>()
        gateway.resumeGates += firstResume
        gateway.resumeGates += secondResume
        gateway.failPrompt = true

        viewModel.updateDraft("Recover this turn")
        viewModel.sendMessage()
        runCurrent()
        assertEquals(2, gateway.resumeRequestCount)

        viewModel.interrupt()
        runCurrent()
        assertEquals(1, gateway.methods.count { it == "session.interrupt" })
        assertEquals(2, gateway.resumeRequestCount)

        gateway.emit("message.delta", """{"text":"buffered-after-snapshot"}""")
        runCurrent()
        firstResume.complete(resumePayload(messages = emptyList(), running = true))
        runCurrent()

        viewModel.state.first { state ->
            state.streamingText == "buffered-after-snapshot"
        }
        gateway.resumeRequests.first { it >= 3 }
        assertEquals(3, gateway.resumeRequestCount)

        secondResume.complete(
            resumePayload(
                messages = listOf(
                    ConversationMessage(role = "assistant", text = "new-authoritative-state", id = "new-state"),
                ),
                running = false,
            ),
        )
        advanceUntilIdle()

        viewModel.state.first { state ->
            state.messages.map(ConversationMessage::text) ==
                listOf("new-authoritative-state") &&
                state.streamingText.isEmpty() &&
                state.turnState == TurnState.Idle
        }

        assertEquals(
            listOf("new-authoritative-state"),
            viewModel.state.value.messages.map(ConversationMessage::text),
        )
        assertEquals("", viewModel.state.value.streamingText)
        viewModel.leaveConversation()
        advanceUntilIdle()
    }

    @Test
    fun lateResumeFromAnOlderGatewayGenerationCannotOverwriteTheSelectedSession() = runTest {
        val oldGateway = FakeGateway()
        val dashboard = FakeDashboard(oldGateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        ).also(::track)
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        viewModel.state.first {
            it.agentActivity?.runtimeSessionId?.isNotBlank() == true &&
                it.loadingMessage == null
        }

        val oldResume = CompletableDeferred<JsonObject>()
        oldGateway.resumeGates += oldResume
        oldGateway.failPrompt = true
        viewModel.updateDraft("This must not leak")
        viewModel.sendMessage()
        oldGateway.resumeRequests.first { it >= 2 }
        assertEquals(2, oldGateway.resumeRequestCount)

        val newGateway = FakeGateway()
        newGateway.resumePayload = resumePayload(
            messages = listOf(
                ConversationMessage(role = "assistant", text = "selected-session", id = "selected-state"),
            ),
            running = false,
            storedSessionId = "stored-new",
            runtimeSessionId = "runtime-new",
        )
        dashboard.gateway = newGateway
        viewModel.openSession(dashboard.session.copy(id = "stored-new", title = "Selected conversation"))

        oldResume.complete(
            resumePayload(
                messages = listOf(
                    ConversationMessage(role = "assistant", text = "stale-old-session", id = "stale-state"),
                ),
                running = false,
            ),
        )
        viewModel.state.first { state ->
            state.activeSummary?.id == "stored-new" &&
                state.messages.map(ConversationMessage::text) == listOf("selected-session") &&
                state.agentActivity?.presentation == ActivityPresentationState.Unavailable
        }

        assertEquals("stored-new", viewModel.state.value.activeSummary?.id)
        assertEquals(
            listOf("selected-session"),
            viewModel.state.value.messages.map(ConversationMessage::text),
        )
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
        ).also(::track)
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
        val viewModel = CelesteViewModel(dashboard = dashboard).also(::track)
        advanceUntilIdle()

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.ManualSetup, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.sessions)
        assertEquals(UiNoticeCategory.AuthenticationRequired, viewModel.state.value.notice?.category)
        assertEquals("Your Hermes sign-in has expired. Sign in again.", viewModel.state.value.notice?.message)
    }

    @Test
    fun foregroundHealthCheckReplacesAStaleSocketAndResumes() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        gateway.failHealthCheck = true

        viewModel.onForeground()
        advanceUntilIdle()
        viewModel.state.first { state ->
            state.turnState == TurnState.Idle &&
                state.agentActivity?.presentation == ActivityPresentationState.Unavailable
        }

        assertEquals(2, gateway.connectCount)
        assertEquals(1, gateway.methods.count { it == "session.list" })
        assertEquals(2, gateway.methods.count { it == "session.resume" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.leaveConversation()
    }

    @Test
    fun createsInTheSelectedProfileAndRecreatesOnlyAnUntouchedDraftSession() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        ).also(::track)
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
    }

    @Test
    fun closingGatewayCancelsAnInFlightOpenOperationBeforeItsResumeReturns() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        ).also(::track)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        advanceUntilIdle()
        viewModel.loadSessions()
        advanceUntilIdle()

        val resumeGate = CompletableDeferred<JsonObject>()
        gateway.resumeGates += resumeGate
        viewModel.openSession(dashboard.session)
        runCurrent()
        assertEquals(1, gateway.resumeRequestCount)

        viewModel.leaveConversation()
        resumeGate.complete(resumePayload(messages = emptyList(), running = false))
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeSummary)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun closingGatewayCancelsAnInFlightCreateOperationBeforeItsResponseReturns() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        advanceUntilIdle()
        val createGate = CompletableDeferred<Unit>()
        gateway.createGates += createGate

        viewModel.createNewConversation()
        runCurrent()
        assertEquals(1, gateway.createCount)

        viewModel.leaveConversation()
        createGate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeSummary)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun closingGatewayCancelsAnInFlightSendOperationBeforeItsResponseReturns() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        advanceUntilIdle()
        val promptGate = CompletableDeferred<Unit>()
        gateway.promptGates += promptGate

        viewModel.updateDraft("<synthetic-prompt>")
        viewModel.sendMessage()
        runCurrent()
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })

        viewModel.leaveConversation()
        promptGate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeSummary)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun closingGatewayCancelsAnInFlightInterruptOperationBeforeItsResponseReturns() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        advanceUntilIdle()
        viewModel.updateDraft("<synthetic-prompt>")
        viewModel.sendMessage()
        advanceUntilIdle()
        gateway.emit("message.start")
        viewModel.state.first { it.turnState == TurnState.Running }

        val interruptGate = CompletableDeferred<Unit>()
        gateway.interruptGates += interruptGate
        viewModel.interrupt()
        gateway.interruptRequests.first { it >= 1 }
        assertEquals(1, gateway.methods.count { it == "session.interrupt" })

        viewModel.leaveConversation()
        interruptGate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeSummary)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun removesPersistedPrefixFromInflightProjection() {
        val suffix = CelesteViewModel.unpersistedInflightText(
            inflight = "Already stored and still arriving",
            messages = listOf(ConversationMessage(role = "assistant", text = "Already stored")),
        )

        assertEquals("and still arriving", suffix)
    }

    @Test
    fun disclosurePreferenceIsScopedToOriginProfileAndStoredSession() = runTest {
        val preferences = InMemoryActivityDisclosurePreferenceStore()
        val first = ActivityDisclosureScope(
            originKey = "https://hermes.test/",
            profile = "default",
            storedSessionId = "stored-42",
        )
        val second = first.copy(profile = "work")

        preferences.setServerReasoningDisclosureEnabled(first, false)

        assertFalse(preferences.isServerReasoningDisclosureEnabled(first))
        assertTrue(preferences.isServerReasoningDisclosureEnabled(second))
        assertTrue(preferences.isServerReasoningDisclosureEnabled())
    }

    @Test
    fun viewModelReloadsDisclosureChoiceWhenSwitchingActivityScope() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val preferences = InMemoryActivityDisclosurePreferenceStore()
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
            activityDisclosurePreferences = preferences,
        ).also(::track)
        advanceUntilIdle()
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        viewModel.setActivityReasoningDisclosureEnabled(false)
        viewModel.leaveConversation()

        viewModel.openSession(dashboard.session.copy(profile = "work"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.agentActivityReasoningDisclosureEnabled)
        viewModel.leaveConversation()

        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.agentActivityReasoningDisclosureEnabled)
        viewModel.leaveConversation()
    }

    @Test
    fun disclosurePreferenceFailureRollsBackTheProjectionAndShowsRetryNotice() = runTest {
        val gateway = FakeGateway()
        val preferences = FailingPreferenceStore()
        val viewModel = openConversation(gateway, preferences)

        viewModel.setActivityReasoningDisclosureEnabled(false)

        assertTrue(viewModel.state.value.agentActivityReasoningDisclosureEnabled)
        assertEquals(UiNoticeCategory.PreferencePersistence, viewModel.state.value.notice?.category)
        assertEquals(UiRecoveryAction.Retry, viewModel.state.value.notice?.recovery)
        assertTrue(
            preferences.isServerReasoningDisclosureEnabled(
                ActivityDisclosureScope(
                    originKey = "http://hermes.test:9119",
                    profile = "default",
                    storedSessionId = "stored-42",
                ),
            ),
        )
        viewModel.leaveConversation()
    }

    @Test
    fun messageFailureReasonSurfacesAsAUserSafeErrorAndSettlesTheTurn() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        viewModel.updateDraft("Trigger a synthetic failure")
        viewModel.sendMessage()
        gateway.emit(
            "message.complete",
            """{"status":"complete","failure_reason":"Synthetic failure detail"}""",
        )
        advanceUntilIdle()
        viewModel.state.first { state ->
            state.turnState == TurnState.Idle &&
                state.notice?.category == UiNoticeCategory.ServerTurnFailure
        }

        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals(UiNoticeCategory.ServerTurnFailure, viewModel.state.value.notice?.category)
        assertEquals("Hermes couldn’t finish that response.", viewModel.state.value.notice?.message)
        viewModel.leaveConversation()
    }

    @Test
    fun messageCompletionSettlesAnUnfinishedToolWithoutBlockingTheTurn() = runTest {
        val gateway = FakeGateway()
        val viewModel = openConversation(gateway)
        gateway.emit(
            "tool.start",
            """{"name":"terminal","tool_call_id":"call-incomplete"}""",
        )
        gateway.emit("message.complete", """{"status":"complete"}""")
        advanceUntilIdle()
        viewModel.state.first { state ->
            state.agentActivity?.items.orEmpty()
                .filterIsInstance<ToolActivity>()
                .singleOrNull()
                ?.phase == ToolPhase.Interrupted &&
                state.turnState == TurnState.Idle
        }

        assertEquals(
            ToolPhase.Interrupted,
            viewModel.state.value.agentActivity?.items
                ?.filterIsInstance<ToolActivity>()
                ?.single()
                ?.phase,
        )
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.leaveConversation()
    }

    private suspend fun openConversation(
        gateway: FakeGateway,
        activityDisclosurePreferences: ActivityDisclosurePreferenceStore =
            InMemoryActivityDisclosurePreferenceStore(),
        activityDiscoveryTimeoutMillis: Long = 1_000L,
        reasoningCoalescingWindowMillis: Long = 75L,
    ): CelesteViewModel {
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
            activityDisclosurePreferences = activityDisclosurePreferences,
            activityDiscoveryTimeoutMillis = activityDiscoveryTimeoutMillis,
            reasoningCoalescingWindowMillis = reasoningCoalescingWindowMillis,
        ).also(::track)
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        viewModel.state.first {
            it.agentActivity?.runtimeSessionId?.isNotBlank() == true &&
                it.loadingMessage == null
        }
        gateway.eventSubscribers.first { it > 0 }
        return viewModel
    }

    private class FailingPreferenceStore : ActivityDisclosurePreferenceStore {
        private var enabled = true
        private val scopedValues = mutableMapOf<String, Boolean>()
        private var failNextWrite = true

        override fun isServerReasoningDisclosureEnabled(): Boolean = enabled

        override fun setServerReasoningDisclosureEnabled(enabled: Boolean): Boolean {
            if (failNextWrite) {
                failNextWrite = false
                return false
            }
            this.enabled = enabled
            return true
        }

        override fun isServerReasoningDisclosureEnabled(scope: ActivityDisclosureScope): Boolean =
            scopedValues[scope.stablePreferenceKey()] ?: enabled

        override fun setServerReasoningDisclosureEnabled(
            scope: ActivityDisclosureScope,
            enabled: Boolean,
        ): Boolean {
            scopedValues[scope.stablePreferenceKey()] = enabled
            if (failNextWrite) {
                failNextWrite = false
                return false
            }
            return true
        }
    }

    private class FakeDashboard(
        var gateway: FakeGateway,
        private val authRequired: Boolean = false,
    ) : DashboardService {
        var profileFailure: Throwable? = null

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
        val eventSubscribers: StateFlow<Int> = mutableEvents.subscriptionCount

        val methods = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var connectCount = 0
        var createCount = 0
        var failHealthCheck = false
        var failPrompt = false
        var connectFailure: Throwable? = null
        var resumePayload: JsonObject = resumePayload(messages = emptyList(), running = false)
        val connectGates = mutableListOf<CompletableDeferred<Unit>>()
        val createGates = mutableListOf<CompletableDeferred<Unit>>()
        val promptGates = mutableListOf<CompletableDeferred<Unit>>()
        val interruptGates = mutableListOf<CompletableDeferred<Unit>>()
        val resumeGates = mutableListOf<CompletableDeferred<JsonObject>>()
        var resumeRequestCount = 0
        private val mutableResumeRequests = MutableStateFlow(0)
        val resumeRequests: StateFlow<Int> = mutableResumeRequests
        private val mutableInterruptRequests = MutableStateFlow(0)
        val interruptRequests: StateFlow<Int> = mutableInterruptRequests

        override suspend fun connect() {
            connectCount += 1
            connectFailure?.let { throw it }
            if (connectGates.isNotEmpty()) connectGates.removeAt(0).await()
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
                    resumeRequestCount += 1
                    mutableResumeRequests.value = resumeRequestCount
                    if (resumeGates.isEmpty()) resumePayload else resumeGates.removeAt(0).await()
                }
                "session.create" -> {
                    createCount += 1
                    if (createGates.isNotEmpty()) createGates.removeAt(0).await()
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
                    if (promptGates.isNotEmpty()) promptGates.removeAt(0).await()
                    if (failPrompt) {
                        failPrompt = false
                        throw IOException("prompt delivery failed")
                    }
                    buildJsonObject { put("status", "streaming") }
                }
                "session.interrupt" -> {
                    mutableInterruptRequests.value += 1
                    if (interruptGates.isNotEmpty()) interruptGates.removeAt(0).await()
                    buildJsonObject { put("status", "interrupting") }
                }
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
            storedSessionId: String = "stored-42",
            runtimeSessionId: String = "runtime-7",
        ): JsonObject {
            val encodedMessages = messages.joinToString(",") { message ->
                """{"id":${Json.encodeToString(message.id ?: "")},"role":${Json.encodeToString(message.role)},"text":${Json.encodeToString(message.text)}}"""
            }
            return Json.parseToJsonElement(
                """{"session_id":"$runtimeSessionId","resumed":"$storedSessionId","running":$running,"status":"${if (running) "streaming" else "idle"}","inflight":null,"messages":[$encodedMessages]}""",
            ) as JsonObject
        }
    }
}
