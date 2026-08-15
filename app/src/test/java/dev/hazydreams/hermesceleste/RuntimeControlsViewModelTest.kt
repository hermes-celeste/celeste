package dev.hazydreams.hermesceleste

import java.io.IOException
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.StoredSession
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
class RuntimeControlsViewModelTest {
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
    fun cancelClosesDraftWithoutSendingConfigSet() = runTest {
        val gateway = ControlGateway()
        val dashboard = ControlDashboard(gateway)
        val viewModel = openConversation(dashboard, gateway)

        viewModel.openRuntimeControls()
        viewModel.cancelRuntimeControls()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.runtimeControls.pickerOpen)
        assertNull(viewModel.state.value.runtimeControls.draft)
        assertEquals(0, gateway.methods.count { it == "config.set" })
        viewModel.leaveConversation()
    }

    @Test
    fun applyKeepsOldEffectiveValueUntilAuthoritativeSessionInfo() = runTest {
        val gateway = ControlGateway()
        val dashboard = ControlDashboard(gateway)
        val viewModel = openConversation(dashboard, gateway)

        viewModel.openRuntimeControls()
        viewModel.selectRuntimeModel("nous", "gpt-5.6-fast")
        viewModel.applyRuntimeControls()
        advanceUntilIdle()

        assertEquals("gpt-5.6-sol", viewModel.state.value.runtimeControls.snapshot?.model)
        assertEquals(RuntimeControlsOperation.Applying, viewModel.state.value.runtimeControls.operation)
        assertEquals(1, gateway.methods.count { it == "config.set" })

        gateway.emit(
            type = "session.info",
            payload = """{"profile_name":"work","model":"gpt-5.6-fast","provider":"nous","reasoning_effort":"high"}""",
        )
        advanceUntilIdle()

        assertEquals("gpt-5.6-fast", viewModel.state.value.runtimeControls.snapshot?.model)
        assertEquals(RuntimeControlsOperation.Idle, viewModel.state.value.runtimeControls.operation)
        assertNull(viewModel.state.value.runtimeControls.draft)
        viewModel.leaveConversation()
    }

    @Test
    fun deferredRunningApplyShowsQueuedAndDoesNotInventAnEffectiveChange() = runTest {
        val gateway = ControlGateway(deferredModelApply = true)
        val dashboard = ControlDashboard(gateway)
        val viewModel = openConversation(dashboard, gateway)
        gateway.emit("session.busy", """{"busy":true}""")
        advanceUntilIdle()

        viewModel.openRuntimeControls()
        viewModel.selectRuntimeModel("nous", "gpt-5.6-fast")
        viewModel.applyRuntimeControls()
        advanceUntilIdle()

        assertEquals(RuntimeControlsOperation.Queued, viewModel.state.value.runtimeControls.operation)
        assertEquals("gpt-5.6-sol", viewModel.state.value.runtimeControls.snapshot?.model)
        assertEquals("Queued for next response", viewModel.state.value.runtimeControls.message)

        gateway.emit(
            type = "session.info",
            payload = """{"profile_name":"work","model":"gpt-5.6-fast","provider":"nous","reasoning_effort":"high"}""",
        )
        advanceUntilIdle()
        assertEquals(RuntimeControlsOperation.Idle, viewModel.state.value.runtimeControls.operation)
        viewModel.leaveConversation()
    }

    @Test
    fun staleRuntimeEventCannotOverwriteTheActivePill() = runTest {
        val gateway = ControlGateway()
        val dashboard = ControlDashboard(gateway)
        val viewModel = openConversation(dashboard, gateway)

        gateway.emit(
            type = "session.info",
            sessionId = "old-runtime",
            payload = """{"profile_name":"work","model":"wrong-model","provider":"wrong-provider"}""",
        )
        advanceUntilIdle()

        assertEquals("gpt-5.6-sol", viewModel.state.value.runtimeControls.snapshot?.model)
        assertEquals("nous", viewModel.state.value.runtimeControls.snapshot?.provider)
        viewModel.leaveConversation()
    }

    @Test
    fun missingEffectiveFieldsRemainUnavailableInsteadOfUsingProfileDefault() = runTest {
        val gateway = ControlGateway(resumeIncludesEffectiveFields = false)
        val dashboard = ControlDashboard(gateway)
        val viewModel = openConversation(dashboard, gateway)

        val snapshot = viewModel.state.value.runtimeControls.snapshot
        assertNull(snapshot?.model)
        assertNull(snapshot?.provider)
        assertEquals("Model unavailable", viewModel.state.value.runtimeControls.modelLabel)
        viewModel.leaveConversation()
    }

    @Test
    fun olderGatewayLeavesEffectiveStateReadableButDisablesPickerApply() = runTest {
        val gateway = ControlGateway(optionsFailure = IOException("unknown method"))
        val dashboard = ControlDashboard(gateway)
        val viewModel = openConversation(dashboard, gateway)

        viewModel.openRuntimeControls()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.runtimeControls.pickerOpen)
        assertFalse(viewModel.state.value.runtimeControls.canApply)
        assertEquals("Model unavailable", viewModel.state.value.runtimeControls.modelLabel)
        assertEquals("Reasoning unavailable", viewModel.state.value.runtimeControls.reasoningLabel)
        viewModel.cancelRuntimeControls()
        viewModel.leaveConversation()
    }

    private suspend fun openConversation(
        dashboard: ControlDashboard,
        gateway: ControlGateway,
    ): CelesteViewModel {
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        return viewModel
    }

    private class ControlDashboard(
        private val gateway: ControlGateway,
    ) : DashboardService {
        val session = StoredSession(
            id = "stored-42",
            title = "Shared conversation",
            preview = "",
            startedAt = 1.0,
            messageCount = 0,
            source = "desktop",
            profile = "work",
        )

        override suspend fun probe(rawBaseUrl: String) = DashboardProbeResult(
            baseUrl = rawBaseUrl,
            authRequired = false,
            providers = emptyList<AuthProvider>(),
            version = "test",
        )

        override suspend fun passwordLogin(baseUrl: String, provider: String, username: String, password: String) = Unit

        override suspend fun listSessions(baseUrl: String, credential: GatewayCredential, limit: Int) = listOf(session)

        override suspend fun listProfiles(baseUrl: String, credential: GatewayCredential) = listOf(
            DashboardProfile(name = "default", isDefault = true, model = "profile-default", provider = "profile-provider"),
            DashboardProfile(name = "work", model = "work-default", provider = "work-provider"),
        )

        override fun createGateway(baseUrl: String, credential: GatewayCredential): GatewayConnection = gateway
    }

    private class ControlGateway(
        private val deferredModelApply: Boolean = false,
        private val resumeIncludesEffectiveFields: Boolean = true,
        private val optionsFailure: Throwable? = null,
    ) : GatewayConnection {
        private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
        private val mutableEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 32)
        override val state: StateFlow<GatewayConnectionState> = mutableState
        override val events: SharedFlow<GatewayEvent> = mutableEvents
        val methods = mutableListOf<String>()

        override suspend fun connect() {
            mutableState.value = GatewayConnectionState.Connected
        }

        override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
            methods += method
            return when (method) {
                "session.resume" -> resumePayload()
                "model.options" -> {
                    optionsFailure?.let { throw it }
                    Json.parseToJsonElement(
                        """{"providers":[{"slug":"nous","name":"Nous","models":["gpt-5.6-sol","gpt-5.6-fast"],"capabilities":{"gpt-5.6-sol":{"reasoning":true},"gpt-5.6-fast":{"reasoning":true}}}]}""",
                    )
                }
                "config.set" -> buildJsonObject {
                    put("key", params["key"]?.jsonPrimitive?.content.orEmpty())
                    put("value", params["value"]?.jsonPrimitive?.content.orEmpty())
                    if (deferredModelApply && params["key"]?.jsonPrimitive?.content == "model") put("deferred", true)
                }
                "session.list" -> buildJsonObject {}
                else -> buildJsonObject {}
            }
        }

        override fun close() {
            mutableState.value = GatewayConnectionState.Closed
        }

        fun emit(type: String, payload: String = "{}", sessionId: String = "runtime-7") {
            mutableEvents.tryEmit(
                GatewayEvent(
                    type = type,
                    sessionId = sessionId,
                    payload = Json.parseToJsonElement(payload) as JsonObject,
                ),
            )
        }

        private fun resumePayload(): JsonObject = Json.parseToJsonElement(
            if (resumeIncludesEffectiveFields) {
                """{"session_id":"runtime-7","resumed":"stored-42","running":false,"status":"idle","info":{"profile_name":"work","model":"gpt-5.6-sol","provider":"nous","reasoning_effort":"high"},"messages":[]}"""
            } else {
                """{"session_id":"runtime-7","resumed":"stored-42","running":false,"status":"idle","info":{"profile_name":"work"},"messages":[]}"""
            },
        ) as JsonObject
    }
}
