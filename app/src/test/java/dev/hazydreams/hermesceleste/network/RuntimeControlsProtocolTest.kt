package dev.hazydreams.hermesceleste.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeControlsProtocolTest {
    @Test
    fun modelOptionsDecodeCapabilitiesAndIgnoreUnknownFields() {
        val capabilities = decodeRuntimeControlsCapabilities(
            Json.parseToJsonElement(
                """
                {
                  "providers": [
                    {
                      "slug": "nous",
                      "name": "Nous Portal",
                      "models": ["gpt-5.6-sol", "gpt-5.6-fast"],
                      "capabilities": {
                        "gpt-5.6-sol": {"reasoning": true, "fast": false},
                        "gpt-5.6-fast": {"reasoning": false, "fast": true}
                      },
                      "future_field": {"ignore": true}
                    }
                  ],
                  "can_apply_while_running": true,
                  "future_root_field": "ignore"
                }
                """.trimIndent(),
            ),
        )

        assertTrue(capabilities.available)
        assertTrue(capabilities.canApplyWhileRunning)
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.6-fast"),
            capabilities.modelOptions.map(RuntimeModelOption::model),
        )
        assertTrue(capabilities.modelOptions.first().supportsReasoning)
        assertFalse(capabilities.modelOptions.last().supportsReasoning)
        assertTrue(capabilities.modelOptions.last().supportsFast)
    }

    @Test
    fun malformedOptionsBecomeUnavailableInsteadOfInventingChoices() {
        val capabilities = decodeRuntimeControlsCapabilities(
            Json.parseToJsonElement("{\"providers\": [{\"slug\": 7, \"models\": [true]}]}") ,
        )

        assertFalse(capabilities.available)
        assertTrue(capabilities.modelOptions.isEmpty())
    }

    @Test
    fun resumeAndSessionInfoDecodeEffectiveFieldsWithoutUsingCatalogDefaults() {
        val resumed = decodeRuntimeControlsInfo(
            Json.parseToJsonElement(
                """
                {
                  "session_id": "runtime-7",
                  "resumed": "stored-42",
                  "info": {
                    "profile_name": "work",
                    "model": "gpt-5.6-sol",
                    "provider": "nous",
                    "reasoning_effort": "xhigh"
                  },
                  "running": false,
                  "profile_default": {"model": "profile-default"}
                }
                """.trimIndent(),
            ) as JsonObject,
            authoritative = true,
        )
        val event = decodeRuntimeControlsInfo(
            Json.parseToJsonElement(
                """{"profile_name":"work","model":"gpt-5.6-fast","provider":"nous","reasoning_effort":"none"}""",
            ) as JsonObject,
            authoritative = true,
        )

        assertEquals("work", resumed.profile)
        assertEquals("stored-42", resumed.storedSessionId)
        assertEquals("gpt-5.6-sol", resumed.model)
        assertEquals("nous", resumed.provider)
        assertEquals("xhigh", resumed.reasoningEffort)
        assertEquals("gpt-5.6-fast", event.model)
        assertEquals("none", event.reasoningEffort)
    }

    @Test
    fun applyUsesSessionScopedOfficialConfigSetAndReportsDeferred() = runBlocking {
        val gateway = RecordingGateway(
            responses = ArrayDeque(
                listOf(
                    Json.parseToJsonElement(
                        """{"key":"model","value":"gpt-5.6-fast","deferred":true}""",
                    ),
                    Json.parseToJsonElement("""{"key":"reasoning","value":"high"}"""),
                ),
            ),
        )

        val result = gateway.applyRuntimeControls(
            runtimeSessionId = "runtime-7",
            provider = "nous",
            model = "gpt-5.6-fast",
            reasoningEffort = "high",
            applyModel = true,
            applyReasoning = true,
        )

        assertTrue(result.deferred)
        assertEquals(listOf("config.set", "config.set"), gateway.methods)
        assertEquals("model", gateway.requests[0]["key"]?.jsonPrimitive?.content)
        assertEquals(
            "gpt-5.6-fast --provider nous --session",
            gateway.requests[0]["value"]?.jsonPrimitive?.content,
        )
        assertEquals("runtime-7", gateway.requests[0]["session_id"]?.jsonPrimitive?.content)
        assertEquals("reasoning", gateway.requests[1]["key"]?.jsonPrimitive?.content)
        assertEquals("high", gateway.requests[1]["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun secondOfficialWriteFailureIsMarkedPartial() = runBlocking {
        val gateway = RecordingGateway(
            responses = ArrayDeque(
                listOf(
                    Json.parseToJsonElement("""{"key":"model","value":"new-model"}"""),
                ),
            ),
            failure = GatewayRpcException(4002, "reasoning rejected"),
        )

        val failure = runCatching {
            gateway.applyRuntimeControls(
                runtimeSessionId = "runtime-7",
                provider = "nous",
                model = "new-model",
                reasoningEffort = "high",
                applyModel = true,
                applyReasoning = true,
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is RuntimeControlsPartialApplyException)
        assertEquals(2, gateway.methods.size)
    }

    private class RecordingGateway(
        private val responses: ArrayDeque<JsonElement>,
        private val failure: Throwable? = null,
    ) : GatewayConnection {
        private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Connected)
        private val mutableEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 4)
        override val state = mutableState
        override val events = mutableEvents
        val methods = mutableListOf<String>()
        val requests = mutableListOf<JsonObject>()

        override suspend fun connect() = Unit

        override suspend fun request(
            method: String,
            params: JsonObject,
            timeoutMillis: Long,
        ): JsonElement {
            methods += method
            requests += params
            failure?.let { throw it }
            return responses.removeFirstOrNull() ?: buildJsonObject {}
        }

        override fun close() = Unit
    }
}
