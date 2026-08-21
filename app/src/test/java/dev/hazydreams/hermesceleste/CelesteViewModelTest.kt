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
import dev.hazydreams.hermesceleste.network.StoredSession
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
            currentEpochSeconds = { 1.0 },
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
            currentEpochSeconds = { 1.0 },
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
            currentEpochSeconds = { 0.0 },
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
            currentEpochSeconds = { 0.0 },
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
            currentEpochSeconds = { 0.0 },
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
