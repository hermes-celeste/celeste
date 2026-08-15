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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    fun successfulReconnectReconcilesTheConversationCatalog() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = openConversation(gateway, dashboard)
        val callsBeforeDisconnect = dashboard.sessionListCalls

        gateway.disconnect("dashboard restarted")
        advanceUntilIdle()

        assertTrue(dashboard.sessionListCalls > callsBeforeDisconnect)
        assertEquals(SessionCatalogStatus.Ready, viewModel.state.value.sessionCatalog.phase)
        assertEquals(listOf("stored-42", "stored-work"), viewModel.state.value.sessions?.map(StoredSession::id))
        viewModel.leaveConversation()
    }

    @Test
    fun initialCatalogLoadExposesLoadingStateBeforeTheServerResponds() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway).apply {
            sessionListGate = CompletableDeferred()
        }
        val viewModel = CelesteViewModel(dashboard = dashboard)
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        advanceUntilIdle()

        viewModel.loadSessions()

        assertEquals(ConnectionPhase.LoadingSessions, viewModel.state.value.connectionPhase)
        assertTrue(viewModel.state.value.sessions != null)
        assertEquals(SessionCatalogStatus.Loading, viewModel.state.value.sessionCatalog.phase)

        dashboard.sessionListGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(ConnectionPhase.Connected, viewModel.state.value.connectionPhase)
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
        assertTrue(viewModel.state.value.sessions.orEmpty().none { it.id == "stored-new-1" })
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
    fun profileSelectionChangesOnlyTheNextNewConversationProfile() = runTest {
        val dashboard = FakeDashboard(FakeGateway())
        val viewModel = connectedViewModel(dashboard)

        viewModel.selectProfile("work")

        assertEquals("work", viewModel.state.value.selectedProfile)
        assertEquals(
            listOf("stored-42", "stored-work"),
            viewModel.state.value.sessions?.map(StoredSession::id),
        )
        assertEquals(
            listOf("stored-42", "stored-work"),
            viewModel.state.value.sessionCatalog.rows.map(StoredSession::id),
        )
        assertTrue(viewModel.state.value.sessions.orEmpty().any { it.profile == "default" })
        assertTrue(viewModel.state.value.sessions.orEmpty().any { it.profile == "work" })
        assertTrue(viewModel.state.value.sessions.orEmpty().none { it.id == "ambiguous-profile" })
    }

    @Test
    fun catalogAuthenticationFailureClosesGatewayAndClearsTheCatalog() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway, authRequired = true)
        val store = InMemoryConnectionStore()
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            connectionStore = store,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        advanceUntilIdle()
        viewModel.updateUsername("celeste")
        viewModel.updatePassword("synthetic-password")
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        assertTrue(store.load()?.secret != null)
        dashboard.sessionFailure = AuthenticationRejected("Hermes needs sign-in.")

        viewModel.refreshSessionCatalog()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.AuthenticationRequired, viewModel.state.value.connectionPhase)
        assertNull(viewModel.state.value.sessions)
        assertNull(viewModel.state.value.activeSummary)
        assertEquals("Hermes needs sign-in.", viewModel.state.value.errorMessage)
        assertNull(store.load()?.secret)
        assertFalse(store.load()?.descriptor?.autoLoginEnabled ?: true)
        assertEquals(1, gateway.closeCount)
        assertEquals(GatewayConnectionState.Closed, gateway.state.value)
    }

    @Test
    fun refreshFailureRetainsTheAuthoritativeWindowAsStale() = runTest {
        val dashboard = FakeDashboard(FakeGateway())
        val viewModel = connectedViewModel(dashboard)
        dashboard.sessionFailure = IOException("offline")

        viewModel.refreshSessionCatalog()
        advanceUntilIdle()

        assertEquals(SessionCatalogStatus.Stale, viewModel.state.value.sessionCatalog.phase)
        assertEquals(listOf("stored-42", "stored-work"), viewModel.state.value.sessions?.map(StoredSession::id))
        assertEquals("offline", viewModel.state.value.sessionCatalog.errorMessage)
    }

    @Test
    fun noResultsDoesNotMaskRefreshingOrStaleCatalogState() = runTest {
        val dashboard = FakeDashboard(FakeGateway())
        val viewModel = connectedViewModel(dashboard)

        viewModel.updateSessionQuery("does-not-exist")
        advanceUntilIdle()
        assertEquals(SessionCatalogStatus.NoResults, viewModel.state.value.sessionCatalog.status)

        dashboard.sessionFailure = IOException("offline")
        viewModel.refreshSessionCatalog()
        advanceUntilIdle()

        assertEquals(SessionCatalogStatus.Stale, viewModel.state.value.sessionCatalog.status)
        assertEquals(listOf("stored-42", "stored-work"), viewModel.state.value.sessions?.map(StoredSession::id))
    }

    @Test
    fun obsoleteLoadedWindowSearchCannotReplaceTheLatestQuery() = runTest {
        val dashboard = FakeDashboard(FakeGateway())
        val viewModel = connectedViewModel(dashboard)

        viewModel.updateSessionQuery("work")
        viewModel.updateSessionQuery("shared")
        advanceUntilIdle()

        assertEquals("shared", viewModel.state.value.sessionQuery)
        assertEquals(listOf("stored-42"), viewModel.state.value.sessionCatalog.filteredRows.map(StoredSession::id))
    }

    @Test
    fun newConversationFromTheListPublishesActionProgressAndThenAStaleError() = runTest {
        val gateway = FakeGateway().apply {
            createGate = CompletableDeferred()
            createFailure = IOException("new conversation unavailable")
        }
        val dashboard = FakeDashboard(gateway)
        val viewModel = connectedViewModel(dashboard)
        val loadedRows = viewModel.state.value.sessionCatalog.rows

        viewModel.createNewConversation()

        assertEquals(SessionCatalogStatus.ActionInFlight, viewModel.state.value.sessionCatalog.phase)
        assertEquals(loadedRows, viewModel.state.value.sessionCatalog.rows)

        gateway.createGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(SessionCatalogStatus.Stale, viewModel.state.value.sessionCatalog.phase)
        assertEquals(loadedRows, viewModel.state.value.sessionCatalog.rows)
        assertEquals("new conversation unavailable", viewModel.state.value.sessionCatalog.errorMessage)
    }

    @Test
    fun failedNewConversationRestoresThePreviousTranscriptAndDraft() = runTest {
        val gateway = FakeGateway().apply {
            resumePayload = resumePayload(
                messages = listOf(
                    ConversationMessage(role = "user", text = "Previous prompt", id = "user-1"),
                    ConversationMessage(role = "assistant", text = "Previous answer", id = "assistant-1"),
                ),
                running = false,
            )
            createFailure = IOException("new conversation unavailable")
        }
        val viewModel = openConversation(gateway)
        viewModel.updateDraft("Keep this unsent draft")

        viewModel.createNewConversation()
        advanceUntilIdle()

        assertEquals("stored-42", viewModel.state.value.activeSummary?.id)
        assertEquals(
            listOf("Previous prompt", "Previous answer"),
            viewModel.state.value.messages.map(ConversationMessage::text),
        )
        assertEquals("Keep this unsent draft", viewModel.state.value.draft)
        assertEquals("new conversation unavailable", viewModel.state.value.errorMessage)
        assertEquals(1, gateway.methods.count { it == "session.create" })
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

    private suspend fun openConversation(
        gateway: FakeGateway,
        dashboard: FakeDashboard = FakeDashboard(gateway),
    ): CelesteViewModel {
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

    private suspend fun connectedViewModel(dashboard: FakeDashboard): CelesteViewModel {
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        advanceUntilIdle()
        return viewModel
    }

    private class FakeDashboard(
        private val gateway: FakeGateway,
        private val authRequired: Boolean = false,
    ) : DashboardService {
        var profileFailure: Throwable? = null
        var sessionFailure: Throwable? = null
        var sessionListGate: CompletableDeferred<Unit>? = null
        var sessionListCalls = 0

        val session = StoredSession(
            id = "stored-42",
            title = "Shared conversation",
            preview = "",
            startedAt = 1.0,
            messageCount = 0,
            source = "desktop",
        )

        private val workSession = StoredSession(
            id = "stored-work",
            title = "Work conversation",
            preview = "",
            startedAt = 2.0,
            messageCount = 0,
            source = "desktop",
            profile = "work",
        )

        private val ambiguousProfileSession = StoredSession(
            id = "ambiguous-profile",
            title = "Ambiguous profile",
            preview = "",
            startedAt = 3.0,
            messageCount = 0,
            source = "desktop",
            profile = "",
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
        ): List<StoredSession> {
            sessionListCalls += 1
            sessionListGate?.await()
            sessionFailure?.let { throw it }
            return listOf(session, workSession, ambiguousProfileSession)
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

        val methods = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var connectCount = 0
        var createCount = 0
        var createFailure: Throwable? = null
        var createGate: CompletableDeferred<Unit>? = null
        var failHealthCheck = false
        var connectFailure: Throwable? = null
        var closeCount = 0
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
                    createGate?.await()
                    createFailure?.let { throw it }
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
