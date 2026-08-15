package dev.hazydreams.hermesceleste

import java.io.IOException

import dev.hazydreams.hermesceleste.connection.ConnectionStore
import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.connection.ReusableSecret
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
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.presentation.AssistantNameDiagnostic
import dev.hazydreams.hermesceleste.presentation.AssistantNameDiagnostics
import dev.hazydreams.hermesceleste.presentation.AssistantNameKey
import dev.hazydreams.hermesceleste.presentation.AssistantNameStore
import dev.hazydreams.hermesceleste.presentation.InMemoryAssistantNameStore
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
        assertEquals("Hermes rejected profile access.", viewModel.state.value.errorMessage)
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
        assertFalse(createParams.containsKey("assistant_name"))

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
        val resumeParams = gateway.requests.last { it.first == "session.resume" }.second
        assertFalse(resumeParams.containsKey("assistant_name"))
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

    @Test
    fun assistantNameLoadsForTheActiveProfileAndSavesWithoutGatewayMutation() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = InMemoryAssistantNameStore()
        assistantNames.write("http://hermes.test:9119", "default", "Juno")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals("Juno", viewModel.state.value.assistantDisplayName)
        assertEquals(
            AssistantNameKey("http://hermes.test:9119", "default"),
            viewModel.state.value.assistantNameKey,
        )
        val gatewayMethodsBeforeSave = gateway.methods.toList()

        viewModel.openAssistantNameEditor()
        viewModel.updateAssistantNameDraft("Nova")
        viewModel.saveAssistantName()
        advanceUntilIdle()

        assertEquals("Nova", viewModel.state.value.assistantDisplayName)
        assertFalse(viewModel.state.value.assistantNameEditor.isOpen)
        assertEquals(gatewayMethodsBeforeSave, gateway.methods)
        assertEquals("Nova", assistantNames.read("http://hermes.test:9119", "default"))
        viewModel.leaveConversation()
    }

    @Test
    fun profileSwitchPublishesHermesAndRejectsAStaleRead() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = DelayedAssistantNameStore(
            values = mapOf("default" to "Juno", "work" to "Nova"),
            blockedProfile = "work",
        )
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        assertEquals("Juno", viewModel.state.value.assistantDisplayName)

        viewModel.selectProfile("work")
        assertEquals("Hermes", viewModel.state.value.assistantDisplayName)
        viewModel.selectProfile("default")
        advanceUntilIdle()
        assertEquals("Juno", viewModel.state.value.assistantDisplayName)

        assistantNames.releaseBlockedRead()
        advanceUntilIdle()
        assertEquals("Juno", viewModel.state.value.assistantDisplayName)
        viewModel.leaveConversation()
    }

    @Test
    fun failedAssistantNameSaveRetainsTheEffectiveValueAndAllowsRetry() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = FailingAssistantNameStore("Juno")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.openAssistantNameEditor()
        viewModel.updateAssistantNameDraft("Nova")
        viewModel.saveAssistantName()
        advanceUntilIdle()

        assertEquals("Juno", viewModel.state.value.assistantDisplayName)
        assertTrue(viewModel.state.value.assistantNameEditor.isOpen)
        assertEquals(
            "Couldn’t save assistant name on this device. Try again.",
            viewModel.state.value.assistantNameEditor.errorMessage,
        )
        assertTrue(viewModel.state.value.assistantNameEditor.canSave)
        assistantNames.failWrites = false
        viewModel.saveAssistantName()
        advanceUntilIdle()
        assertEquals("Nova", viewModel.state.value.assistantDisplayName)
        assertFalse(viewModel.state.value.assistantNameEditor.isOpen)
        assertEquals("Nova", assistantNames.read("http://hermes.test:9119", "default"))
        viewModel.leaveConversation()
    }

    @Test
    fun foregroundReloadDoesNotCancelAnInFlightAssistantNameSave() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = BlockingWriteAssistantNameStore("Juno")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.openAssistantNameEditor()
        viewModel.updateAssistantNameDraft("Nova")

        assistantNames.blockNextWrite()
        viewModel.saveAssistantName()
        runCurrent()
        assertTrue(viewModel.state.value.assistantNameEditor.isSaving)

        viewModel.onForeground()
        runCurrent()
        assertTrue(viewModel.state.value.assistantNameEditor.isSaving)

        assistantNames.releaseWrite()
        advanceUntilIdle()

        assertEquals("Nova", viewModel.state.value.assistantDisplayName)
        assertFalse(viewModel.state.value.assistantNameEditor.isOpen)
        assertEquals("Nova", assistantNames.read("http://hermes.test:9119", "default"))
    }

    @Test
    fun signOutRetainsAliasesButForgetClearsOnlyTheCurrentOrigin() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = ClearFailingAssistantNameStore()
        assistantNames.write("http://hermes.test:9119", "default", "Juno")
        assistantNames.write("https://other.example", "default", "Atlas")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.signOut()
        advanceUntilIdle()

        assertEquals("Juno", assistantNames.read("http://hermes.test:9119", "default"))
        assistantNames.failClears = true
        viewModel.forgetConnection()
        advanceUntilIdle()

        assertEquals(
            "Celeste could not remove the local assistant name. Try again.",
            viewModel.state.value.errorMessage,
        )
        assertEquals("Juno", assistantNames.read("http://hermes.test:9119", "default"))

        assistantNames.failClears = false
        viewModel.retryAssistantNameCleanup()
        advanceUntilIdle()

        assertNull(assistantNames.read("http://hermes.test:9119", "default"))
        assertEquals("Atlas", assistantNames.read("https://other.example", "default"))
    }

    @Test
    fun failedForgetCleanupStaysScopedAcrossOriginSwitchAndExposesRetry() = runTest {
        val firstOrigin = "http://hermes.test:9119"
        val secondOrigin = "https://other.example"
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = ClearFailingAssistantNameStore()
        assistantNames.write(firstOrigin, "default", "Juno")
        assistantNames.write(secondOrigin, "default", "Atlas")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl(firstOrigin)
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assistantNames.failingOrigin = firstOrigin
        viewModel.forgetConnection()
        advanceUntilIdle()

        assertEquals(firstOrigin, viewModel.state.value.assistantNameCleanupRetryOrigin)
        assertEquals("Juno", assistantNames.read(firstOrigin, "default"))
        assertEquals("Atlas", assistantNames.read(secondOrigin, "default"))

        assistantNames.failingOrigin = null
        viewModel.updateDashboardUrl(secondOrigin)
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        assertEquals(secondOrigin, viewModel.state.value.assistantNameKey?.origin)
        assertEquals("Atlas", viewModel.state.value.assistantDisplayName)

        viewModel.forgetConnection()
        advanceUntilIdle()

        assertNull(assistantNames.read(secondOrigin, "default"))
        assertEquals("Juno", assistantNames.read(firstOrigin, "default"))
        assertEquals(firstOrigin, viewModel.state.value.assistantNameCleanupRetryOrigin)

        viewModel.retryAssistantNameCleanup()
        advanceUntilIdle()

        assertNull(assistantNames.read(firstOrigin, "default"))
        assertNull(viewModel.state.value.assistantNameCleanupRetryOrigin)
    }

    @Test
    fun connectionForgetFailureRemainsRetryableAfterLocalAliasCleanup() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val connectionStore = FailingForgetConnectionStore()
        val assistantNames = InMemoryAssistantNameStore()
        assistantNames.write("http://hermes.test:9119", "default", "Juno")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            connectionStore = connectionStore,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        viewModel.forgetConnection()
        advanceUntilIdle()

        assertNull(assistantNames.read("http://hermes.test:9119", "default"))
        assertTrue(viewModel.state.value.connectionForgetRetryPending)
        assertEquals(
            "Celeste could not remove the saved connection. Try again.",
            viewModel.state.value.errorMessage,
        )
        assertTrue(connectionStore.load() != null)

        connectionStore.failForget = false
        viewModel.retryConnectionCleanup()
        advanceUntilIdle()

        assertNull(connectionStore.load())
        assertFalse(viewModel.state.value.connectionForgetRetryPending)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun sameKeySavedReconnectKeepsAliasWhileTheLocalReadIsPending() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = BlockingAssistantNameStore("Juno")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        assertEquals("Juno", viewModel.state.value.assistantDisplayName)

        assistantNames.blockNextRead()
        viewModel.retrySavedConnection()
        assertEquals("Juno", viewModel.state.value.assistantDisplayName)

        assistantNames.releaseRead()
        advanceUntilIdle()
        assertEquals("Juno", viewModel.state.value.assistantDisplayName)
    }

    @Test
    fun originSwitchLoadsOnlyTheNewOriginAlias() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = InMemoryAssistantNameStore()
        assistantNames.write("http://hermes.test:9119", "default", "Juno")
        assistantNames.write("https://other.example", "default", "Atlas")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        assertEquals("Juno", viewModel.state.value.assistantDisplayName)

        viewModel.updateDashboardUrl("https://other.example")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals("Atlas", viewModel.state.value.assistantDisplayName)
        assertEquals("https://other.example", viewModel.state.value.assistantNameKey?.origin)
    }

    @Test
    fun removedProfileFallsBackToItsOwnAliasInsteadOfRetargetingTheRemovedProfile() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = InMemoryAssistantNameStore()
        assistantNames.write("http://hermes.test:9119", "default", "Juno")
        assistantNames.write("http://hermes.test:9119", "work", "Nova")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.selectProfile("work")
        advanceUntilIdle()
        assertEquals("Nova", viewModel.state.value.assistantDisplayName)

        dashboard.profiles = listOf(DashboardProfile(name = "default", isDefault = true))
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals("default", viewModel.state.value.selectedProfile)
        assertEquals("Juno", viewModel.state.value.assistantDisplayName)
    }

    @Test
    fun blankAssistantNameClearsTheOverrideAndReturnsToHermes() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val assistantNames = InMemoryAssistantNameStore()
        assistantNames.write("http://hermes.test:9119", "default", "Juno")
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = assistantNames,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.openAssistantNameEditor()
        viewModel.updateAssistantNameDraft("   ")
        viewModel.saveAssistantName()
        advanceUntilIdle()

        assertEquals("Hermes", viewModel.state.value.assistantDisplayName)
        assertNull(assistantNames.read("http://hermes.test:9119", "default"))
    }

    @Test
    fun localReadFailureFallsBackAndEmitsOnlyAStableRedactedDiagnostic() = runTest {
        val diagnostics = RecordingAssistantNameDiagnostics()
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            assistantNameStore = ReadFailingAssistantNameStore("/private/Juno/records"),
            assistantNameDiagnostics = diagnostics,
            reconnectDelayMillis = { _, _ -> 0L },
        )

        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals("Hermes", viewModel.state.value.assistantDisplayName)
        assertEquals(listOf(AssistantNameDiagnostic.ReadFailure), diagnostics.events)
        assertFalse(diagnostics.events.joinToString().contains("Juno"))
        assertFalse(diagnostics.events.joinToString().contains("/private"))
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

    private class DelayedAssistantNameStore(
        private val values: Map<String, String>,
        private val blockedProfile: String,
    ) : AssistantNameStore {
        private val release = CompletableDeferred<Unit>()

        override suspend fun read(origin: String, profile: String): String? {
            if (profile == blockedProfile) {
                withContext(NonCancellable) { release.await() }
            }
            return values[profile]
        }

        override suspend fun write(origin: String, profile: String, name: String?) = Unit

        override suspend fun clearOrigin(origin: String) = Unit

        fun releaseBlockedRead() {
            release.complete(Unit)
        }
    }

    private class BlockingAssistantNameStore(
        private val value: String,
    ) : AssistantNameStore {
        private var blockReads = false
        private var release = CompletableDeferred<Unit>()

        override suspend fun read(origin: String, profile: String): String? {
            if (blockReads) {
                blockReads = false
                withContext(NonCancellable) { release.await() }
            }
            return value
        }

        override suspend fun write(origin: String, profile: String, name: String?) = Unit

        override suspend fun clearOrigin(origin: String) = Unit

        fun blockNextRead() {
            release = CompletableDeferred()
            blockReads = true
        }

        fun releaseRead() {
            release.complete(Unit)
        }
    }

    private class BlockingWriteAssistantNameStore(
        initialValue: String,
    ) : AssistantNameStore {
        private var storedValue: String? = initialValue
        private var blockWrites = false
        private var release = CompletableDeferred<Unit>()

        override suspend fun read(origin: String, profile: String): String? = storedValue

        override suspend fun write(origin: String, profile: String, name: String?) {
            if (blockWrites) {
                blockWrites = false
                withContext(NonCancellable) { release.await() }
            }
            storedValue = name
        }

        override suspend fun clearOrigin(origin: String) {
            storedValue = null
        }

        fun blockNextWrite() {
            release = CompletableDeferred()
            blockWrites = true
        }

        fun releaseWrite() {
            release.complete(Unit)
        }
    }

    private class ReadFailingAssistantNameStore(
        private val message: String,
    ) : AssistantNameStore {
        override suspend fun read(origin: String, profile: String): String? {
            throw IOException(message)
        }

        override suspend fun write(origin: String, profile: String, name: String?) = Unit

        override suspend fun clearOrigin(origin: String) = Unit
    }

    private class RecordingAssistantNameDiagnostics : AssistantNameDiagnostics {
        val events = mutableListOf<AssistantNameDiagnostic>()

        override fun record(diagnostic: AssistantNameDiagnostic) {
            events += diagnostic
        }
    }

    private class ClearFailingAssistantNameStore : AssistantNameStore {
        private val delegate = InMemoryAssistantNameStore()
        var failClears = false
        var failingOrigin: String? = null

        override suspend fun read(origin: String, profile: String): String? =
            delegate.read(origin, profile)

        override suspend fun write(origin: String, profile: String, name: String?) {
            delegate.write(origin, profile, name)
        }

        override suspend fun clearOrigin(origin: String) {
            if (failClears || origin == failingOrigin) throw IOException("synthetic local cleanup failure")
            delegate.clearOrigin(origin)
        }
    }

    private class FailingForgetConnectionStore : ConnectionStore {
        private val delegate = InMemoryConnectionStore()
        var failForget = true

        override suspend fun load(): StoredConnection? = delegate.load()

        override suspend fun replace(
            descriptor: SavedConnectionDescriptor,
            secret: ReusableSecret?,
        ) {
            delegate.replace(descriptor, secret)
        }

        override suspend fun clearSecret() {
            delegate.clearSecret()
        }

        override suspend fun forget() {
            if (failForget) throw IOException("synthetic saved-connection cleanup failure")
            delegate.forget()
        }
    }

    private class FailingAssistantNameStore(
        initialValue: String,
    ) : AssistantNameStore {
        private var storedValue: String? = initialValue
        var failWrites = true

        override suspend fun read(origin: String, profile: String): String? = storedValue

        override suspend fun write(origin: String, profile: String, name: String?) {
            if (failWrites) throw IOException("synthetic local write failure")
            storedValue = name
        }

        override suspend fun clearOrigin(origin: String) = Unit
    }

    private class FakeDashboard(
        private val gateway: FakeGateway,
        private val authRequired: Boolean = false,
    ) : DashboardService {
        var profileFailure: Throwable? = null
        var profiles: List<DashboardProfile> = listOf(
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
            return profiles
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
                    buildJsonObject { put("sessions", "healthy") }
                }
                "prompt.submit" -> buildJsonObject { put("status", "streaming") }
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
