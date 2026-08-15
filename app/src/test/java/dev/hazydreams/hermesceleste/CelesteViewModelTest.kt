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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
            """{"session_id":"runtime-resumed","resumed":"stored-new-2","running":false,"status":"idle","info":{"profile_name":"work"},"inflight":null,"messages":[]}""",
        ) as JsonObject
        gateway.disconnect("after first prompt")
        advanceUntilIdle()

        assertEquals(2, gateway.methods.count { it == "session.create" })
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        viewModel.leaveConversation()
    }

    @Test
    fun uncertainFirstPromptInNewConversationResolvesAcceptedWithoutResending() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.createNewConversation()
        advanceUntilIdle()

        gateway.failNext("prompt.submit", IOException("first prompt response lost"))
        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = true,
            runtimeSessionId = "runtime-first-resumed",
            storedSessionId = "stored-new-1",
            inflightUserText = "first prompt",
            inflightAssistantText = "working",
            inflightStreaming = true,
        )
        viewModel.updateDraft("first prompt")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        assertEquals("", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Accepted, viewModel.state.value.deliveryStatus)
        assertEquals(TurnState.Running, viewModel.state.value.turnState)
        assertTrue(viewModel.state.value.messages.any { it.text == "first prompt" })
        viewModel.leaveConversation()
    }

    @Test
    fun uncertainFirstPromptWithoutAdmissionAuthorityIsRejectedWithoutResending() = runTest {
        val gateway = FakeGateway()
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.createNewConversation()
        advanceUntilIdle()

        gateway.failNext("prompt.submit", IOException("first prompt response lost"))
        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = false,
            storedSessionId = "stored-new-1",
        )
        viewModel.updateDraft("first prompt rejected")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(1, gateway.methods.count { it == "session.resume" })
        assertEquals("first prompt rejected", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Rejected, viewModel.state.value.deliveryStatus)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertTrue(viewModel.state.value.messages.isEmpty())
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
    fun uncertainSteerResolvesFromAuthoritativeCorrectionsWithoutResending() = runTest {
        val gateway = FakeGateway()
        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = true,
            inflightAssistantText = "working",
            inflightStreaming = true,
        )
        val viewModel = openConversation(gateway)
        gateway.failNext("session.steer", IOException("socket lost after write"))
        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = true,
            inflightAssistantText = "working",
            inflightCorrections = listOf("guide the next step"),
            inflightStreaming = true,
        )

        viewModel.updateDraft("guide the next step")
        viewModel.steerMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.steer" })
        assertEquals("", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Accepted, viewModel.state.value.deliveryStatus)
        assertEquals(TurnState.Running, viewModel.state.value.turnState)
        assertTrue(viewModel.state.value.messages.any { it.text == "guide the next step" })
        viewModel.leaveConversation()
    }

    @Test
    fun uncertainRedirectResolvesFromAuthoritativeCorrectionsWithoutFallingBackToStop() = runTest {
        val gateway = FakeGateway()
        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = true,
            supportsRedirect = true,
        )
        val viewModel = openConversation(gateway)
        gateway.failNext("session.redirect", IOException("redirect response lost"))
        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = true,
            inflightAssistantText = "working",
            inflightCorrections = listOf("change direction"),
            inflightStreaming = true,
            supportsRedirect = true,
        )

        viewModel.updateDraft("change direction")
        viewModel.redirectMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.redirect" })
        assertEquals(0, gateway.methods.count { it == "session.interrupt" })
        assertEquals(0, gateway.methods.count { it == "session.steer" })
        assertEquals("", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Accepted, viewModel.state.value.deliveryStatus)
        assertTrue(viewModel.state.value.messages.any { it.text == "change direction" })
        viewModel.leaveConversation()
    }

    @Test
    fun uncertainQueueResolutionPreservesGatewayFifoOrderWithoutResending() = runTest {
        val gateway = FakeGateway()
        gateway.resumePayload = resumePayload(messages = emptyList(), running = true)
        val viewModel = openConversation(gateway)
        gateway.failNext("prompt.submit", IOException("queue response lost"))
        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = true,
            queuedUserTexts = listOf("queue first", "queue second"),
        )

        viewModel.updateDraft("queue first")
        viewModel.queueMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals(
            listOf("queue first", "queue second"),
            viewModel.state.value.messages.map { it.text },
        )
        assertEquals("", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Accepted, viewModel.state.value.deliveryStatus)
        viewModel.leaveConversation()
    }

    @Test
    fun uncertainSteerDoesNotMatchAnOlderIdenticalUserMessage() = runTest {
        val gateway = FakeGateway()
        val existing = ConversationMessage(role = "user", text = "same guidance", id = "old-user")
        gateway.resumePayload = resumePayload(messages = listOf(existing), running = true)
        val viewModel = openConversation(gateway)
        gateway.failNext("session.steer", IOException("steer response lost"))
        gateway.resumePayload = resumePayload(messages = listOf(existing), running = true)

        viewModel.updateDraft("same guidance")
        viewModel.steerMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.steer" })
        assertEquals("same guidance", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Uncertain, viewModel.state.value.deliveryStatus)
        viewModel.leaveConversation()
    }

    @Test
    fun uncertainRedirectDoesNotMatchAnOlderIdenticalUserMessage() = runTest {
        val gateway = FakeGateway()
        val existing = ConversationMessage(role = "user", text = "same redirect", id = "old-user")
        gateway.resumePayload = resumePayload(
            messages = listOf(existing),
            running = true,
            supportsRedirect = true,
        )
        val viewModel = openConversation(gateway)
        gateway.failNext("session.redirect", IOException("redirect response lost"))
        gateway.resumePayload = resumePayload(
            messages = listOf(existing),
            running = true,
            supportsRedirect = true,
        )

        viewModel.updateDraft("same redirect")
        viewModel.redirectMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.redirect" })
        assertEquals("same redirect", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Uncertain, viewModel.state.value.deliveryStatus)
        viewModel.leaveConversation()
    }

    @Test
    fun uncertainQueueDoesNotMatchAnOlderIdenticalUserMessage() = runTest {
        val gateway = FakeGateway()
        val existing = ConversationMessage(role = "user", text = "same queued text", id = "old-user")
        gateway.resumePayload = resumePayload(messages = listOf(existing), running = true)
        val viewModel = openConversation(gateway)
        gateway.failNext("prompt.submit", IOException("queue response lost"))
        gateway.resumePayload = resumePayload(messages = listOf(existing), running = true)

        viewModel.updateDraft("same queued text")
        viewModel.queueMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals("same queued text", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Uncertain, viewModel.state.value.deliveryStatus)
        viewModel.leaveConversation()
    }

    @Test
    fun stopWinsCorrectionRaceAndDoesNotResendTheCorrection() = runTest {
        val gateway = FakeGateway()
        gateway.resumePayload = resumePayload(messages = emptyList(), running = true)
        val viewModel = openConversation(gateway)
        gateway.steerGate = CompletableDeferred()
        viewModel.updateDraft("keep this draft")
        viewModel.steerMessage()
        runCurrent()

        gateway.resumePayload = resumePayload(messages = emptyList(), running = false)
        viewModel.interrupt()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.steer" })
        assertEquals(1, gateway.methods.count { it == "session.interrupt" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        assertEquals("keep this draft", viewModel.state.value.draft)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("correction"))

        gateway.steerGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, gateway.methods.count { it == "session.steer" })
        assertEquals("keep this draft", viewModel.state.value.draft)
        viewModel.leaveConversation()
    }

    @Test
    fun lateCorrectionFromAnOldGatewayGenerationCannotResolveTheNewProjection() = runTest {
        val gateway = FakeGateway()
        gateway.resumePayload = resumePayload(messages = emptyList(), running = true)
        val viewModel = openConversation(gateway)
        gateway.steerGate = CompletableDeferred()
        viewModel.updateDraft("generation-bound guidance")
        viewModel.steerMessage()
        runCurrent()

        gateway.resumePayload = resumePayload(messages = emptyList(), running = false)
        gateway.disconnect("replace this socket")
        advanceUntilIdle()
        gateway.steerGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "session.steer" })
        assertEquals("generation-bound guidance", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Rejected, viewModel.state.value.deliveryStatus)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
        viewModel.leaveConversation()
    }

    @Test
    fun mismatchedResumeProfileCannotReconcileAStoreIdCollision() = runTest {
        val gateway = FakeGateway()
        gateway.resumePayload = resumePayload(messages = emptyList(), running = true)
        val viewModel = openConversation(gateway)
        gateway.failNext("session.steer", IOException("steer response lost"))
        gateway.resumePayloads += resumePayload(
            messages = listOf(ConversationMessage(role = "user", text = "wrong profile")),
            running = true,
            profile = "work",
        )
        gateway.resumePayloads += resumePayload(
            messages = emptyList(),
            running = false,
            profile = "default",
        )

        viewModel.updateDraft("profile-scoped guidance")
        viewModel.steerMessage()
        advanceUntilIdle()

        assertEquals("default", viewModel.state.value.activeSummary?.profile)
        assertFalse(viewModel.state.value.messages.any { it.text == "wrong profile" })
        assertEquals("profile-scoped guidance", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Rejected, viewModel.state.value.deliveryStatus)
        assertEquals(1, gateway.methods.count { it == "session.steer" })
        viewModel.leaveConversation()
    }

    @Test
    fun mismatchedResumeOriginCannotReconcileAStoreIdCollision() = runTest {
        val gateway = FakeGateway()
        gateway.resumePayload = resumePayload(
            messages = emptyList(),
            running = true,
            origin = "http://hermes.test:9119/",
        )
        val viewModel = openConversation(gateway)
        gateway.failNext("session.steer", IOException("steer response lost"))
        gateway.resumePayloads += resumePayload(
            messages = listOf(ConversationMessage(role = "user", text = "wrong origin")),
            running = true,
            origin = "http://other-hermes.test:9119",
        )
        gateway.resumePayloads += resumePayload(
            messages = emptyList(),
            running = false,
        )

        viewModel.updateDraft("origin-scoped guidance")
        viewModel.steerMessage()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.messages.any { it.text == "wrong origin" })
        assertEquals("origin-scoped guidance", viewModel.state.value.draft)
        assertEquals(DeliveryStatus.Rejected, viewModel.state.value.deliveryStatus)
        assertEquals(1, gateway.methods.count { it == "session.steer" })
        viewModel.leaveConversation()
    }

    @Test
    fun lateResultFromAReplacedGatewayOriginCannotMutateAReopenedCollision() = runTest {
        val firstGateway = FakeGateway()
        firstGateway.resumePayload = resumePayload(messages = emptyList(), running = true)
        val dashboard = FakeDashboard(firstGateway)
        val viewModel = openConversation(dashboard)
        firstGateway.steerGate = CompletableDeferred()
        viewModel.updateDraft("old gateway guidance")
        viewModel.steerMessage()
        runCurrent()

        val secondGateway = FakeGateway()
        secondGateway.resumePayload = resumePayload(
            messages = listOf(ConversationMessage(role = "user", text = "new gateway")),
            running = false,
        )
        dashboard.gateway = secondGateway
        viewModel.leaveConversation()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        firstGateway.steerGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals("new gateway", viewModel.state.value.messages.single().text)
        assertFalse(viewModel.state.value.messages.any { it.text == "old gateway guidance" })
        assertEquals(0, secondGateway.methods.count { it == "session.steer" })
        viewModel.leaveConversation()
    }

    private suspend fun openConversation(gateway: FakeGateway): CelesteViewModel {
        return openConversation(FakeDashboard(gateway))
    }

    private suspend fun openConversation(dashboard: FakeDashboard): CelesteViewModel {
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

        val methods = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        private val failures = mutableMapOf<String, MutableList<Throwable>>()
        var connectCount = 0
        var createCount = 0
        var failHealthCheck = false
        var connectFailure: Throwable? = null
        var resumePayload: JsonObject = CelesteViewModelTest.resumePayload(messages = emptyList(), running = false)
        val resumePayloads = mutableListOf<JsonObject>()
        var steerGate: CompletableDeferred<Unit>? = null
        var redirectGate: CompletableDeferred<Unit>? = null
        var promptGate: CompletableDeferred<Unit>? = null
        var eventSessionId = "runtime-7"

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
            failures[method]?.let { queuedFailures ->
                if (queuedFailures.isNotEmpty()) throw queuedFailures.removeAt(0)
            }
            return when (method) {
                "session.resume" -> {
                    val payload = if (resumePayloads.isEmpty()) {
                        resumePayload
                    } else {
                        resumePayloads.removeAt(0)
                    }
                    eventSessionId = payload["session_id"]?.jsonPrimitive?.content ?: eventSessionId
                    payload
                }
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
                "prompt.submit" -> {
                    promptGate?.await()
                    buildJsonObject { put("status", "streaming") }
                }
                "session.steer" -> {
                    steerGate?.await()
                    buildJsonObject { put("status", "queued") }
                }
                "session.redirect" -> {
                    redirectGate?.await()
                    buildJsonObject { put("status", "redirected") }
                }
                "session.interrupt" -> buildJsonObject { put("status", "interrupted") }
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
                    sessionId = eventSessionId,
                    payload = Json.parseToJsonElement(payload) as JsonObject,
                ),
            )
        }

        fun failNext(method: String, error: Throwable) {
            failures.getOrPut(method) { mutableListOf() } += error
        }

        fun disconnect(reason: String) {
            mutableState.value = GatewayConnectionState.Disconnected(reason)
        }
    }

    companion object {
        private fun resumePayload(
            messages: List<ConversationMessage>,
            running: Boolean,
            profile: String = "default",
            runtimeSessionId: String = "runtime-7",
            storedSessionId: String = "stored-42",
            inflightUserText: String = "",
            inflightAssistantText: String = "",
            inflightCorrections: List<String> = emptyList(),
            queuedUserTexts: List<String> = emptyList(),
            inflightStreaming: Boolean = false,
            supportsRedirect: Boolean = false,
            origin: String? = null,
        ): JsonObject = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("resumed", storedSessionId)
            put("running", running)
            put("status", if (running) "streaming" else "idle")
            put("info", buildJsonObject {
                put("profile_name", profile)
                origin?.let { put("origin", it) }
            })
            val hasInflight = inflightUserText.isNotBlank() ||
                inflightAssistantText.isNotBlank() ||
                inflightCorrections.isNotEmpty() ||
                inflightStreaming
            put("inflight", if (hasInflight) {
                buildJsonObject {
                    if (inflightUserText.isNotBlank()) put("user", inflightUserText)
                    if (inflightAssistantText.isNotBlank()) put("assistant", inflightAssistantText)
                    if (inflightStreaming) put("streaming", true)
                    if (inflightCorrections.isNotEmpty()) {
                        put("corrections", buildJsonArray {
                            inflightCorrections.forEach { add(JsonPrimitive(it)) }
                        })
                    }
                }
            } else JsonNull)
            if (queuedUserTexts.isNotEmpty()) {
                put("queued", buildJsonArray {
                    queuedUserTexts.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (supportsRedirect) {
                put("capabilities", buildJsonObject {
                    put("supports_active_turn_redirect", true)
                })
            }
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("id", message.id.orEmpty())
                        put("role", message.role)
                        put("text", message.text)
                    })
                }
            })
        }
    }
}
