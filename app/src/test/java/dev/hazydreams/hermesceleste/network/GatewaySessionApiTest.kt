package dev.hazydreams.hermesceleste.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySessionApiTest {
    @Test
    fun activeTurnMethodsUseRuntimeIdentityAndExactAdmissionParameters() = runBlocking {
        val gateway = RecordingGateway()

        assertEquals(SteerOutcome.Queued, gateway.steerSession("runtime-7", "guide the next step"))
        assertEquals(RedirectOutcome.Redirected, gateway.redirectSession("runtime-7", "change direction"))
        gateway.submitQueuedPrompt("runtime-7", "run this next")
        gateway.interruptSession("runtime-7")

        assertEquals(
            listOf("session.steer", "session.redirect", "prompt.submit", "session.interrupt"),
            gateway.requests.map { it.first },
        )
        assertEquals("runtime-7", gateway.requests[0].second["session_id"]?.jsonPrimitive?.content)
        assertEquals("guide the next step", gateway.requests[0].second["text"]?.jsonPrimitive?.content)
        assertEquals("runtime-7", gateway.requests[1].second["session_id"]?.jsonPrimitive?.content)
        assertEquals("change direction", gateway.requests[1].second["text"]?.jsonPrimitive?.content)
        assertEquals("runtime-7", gateway.requests[2].second["session_id"]?.jsonPrimitive?.content)
        assertEquals("run this next", gateway.requests[2].second["text"]?.jsonPrimitive?.content)
        assertEquals(true, gateway.requests[2].second["queued"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("runtime-7", gateway.requests[3].second["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun resumedSessionPropagatesOnlyAnExplicitRedirectCapability() = runBlocking {
        val gateway = RecordingGateway(
            resumePayload = buildJsonObject {
                put("session_id", "runtime-7")
                put("resumed", "stored-7")
                put("running", false)
                put(
                    "capabilities",
                    buildJsonObject { put("supports_active_turn_redirect", true) },
                )
            },
        )

        val resumed = gateway.resumeStoredSession("stored-7")

        assertTrue(resumed.supportsActiveTurnRedirect)
        assertNull(
            buildJsonObject { put("version", "new-enough") }
                .explicitRedirectCapability(),
        )
    }

    @Test
    fun methodNotFoundIsAConcreteUnsupportedActionNotAnUncertainDelivery() = runBlocking {
        val gateway = RecordingGateway(redirectError = GatewayRpcException(-32601, "missing"))

        assertEquals(RedirectOutcome.Unsupported, gateway.redirectSession("runtime-7", "change direction"))
    }
    @Test
    fun queuedSubmissionKeepsTheWholeAttachmentEnvelope() = runBlocking {
        val gateway = RecordingGateway()
        val attachment = AttachmentReference(
            id = "attachment-1",
            uri = "content://synthetic/1",
            mimeType = "image/png",
            name = "synthetic.png",
        )

        gateway.submitQueuedPrompt(
            runtimeSessionId = "runtime-7",
            text = "use this image next",
            options = SubmitOptions(attachments = listOf(attachment)),
        )

        val params = gateway.requests.single().second
        assertEquals(true, params["queued"]?.jsonPrimitive?.content?.toBoolean())
        val encodedAttachment = params["attachments"].toString()
        assertTrue(encodedAttachment.contains("attachment-1"))
        assertTrue(encodedAttachment.contains("content://synthetic/1"))
        assertTrue(encodedAttachment.contains("image/png"))
    }

    @Test
    fun redirectUnsupportedIsTypedWithoutTurningItIntoASuccess() = runBlocking {
        val gateway = RecordingGateway(redirectError = GatewayRpcException(4010, "unsupported"))

        assertEquals(RedirectOutcome.Unsupported, gateway.redirectSession("runtime-7", "change direction"))
    }

    @Test
    fun steerUnsupportedIsTypedWithoutTurningItIntoAnAcceptedCorrection() = runBlocking {
        val gateway = RecordingGateway(steerError = GatewayRpcException(4010, "unsupported"))

        assertEquals(SteerOutcome.Unsupported, gateway.steerSession("runtime-7", "guide the next step"))
    }

    private class RecordingGateway(
        private val redirectError: Throwable? = null,
        private val steerError: Throwable? = null,
        private val resumePayload: JsonObject? = null,
    ) : GatewayConnection {
        private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Connected)
        override val state = mutableState
        override val events = MutableSharedFlow<GatewayEvent>()
        val requests = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun connect() = Unit

        override suspend fun request(
            method: String,
            params: JsonObject,
            timeoutMillis: Long,
        ): JsonElement {
            requests += method to params
            if (method == "session.redirect" && redirectError != null) throw redirectError
            if (method == "session.steer" && steerError != null) throw steerError
            return when (method) {
                "session.resume" -> resumePayload ?: buildJsonObject {
                    put("session_id", "runtime-7")
                    put("resumed", "stored-7")
                    put("running", false)
                }
                "session.steer" -> buildJsonObject { put("status", "queued") }
                "session.redirect" -> buildJsonObject { put("status", "redirected") }
                "prompt.submit" -> buildJsonObject { put("status", "queued") }
                "session.interrupt" -> buildJsonObject { put("status", "interrupted") }
                else -> buildJsonObject {}
            }
        }

        override fun close() = Unit
    }
}
