package dev.hazydreams.hermesceleste.network

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentGatewayTest {
    @Test
    fun attachesBytesInPickerTransactionOrderWithoutSendingClientOnlyFields() = runBlocking {
        val gateway = RecordingGateway()
        val owner = AttachmentSessionOwner(storedSessionId = "stored-1", runtimeSessionId = "runtime-1")
        val bytes = byteArrayOf(1, 2, 3, 4)

        val result = gateway.attachImageBytes(
            owner = owner,
            bytes = bytes,
            filename = "photo.png",
            mimeType = "image/png",
            clientAttachmentId = "client-only-id",
        )

        assertEquals("/hermes/images/upload.png", result.serverReference)
        assertEquals("image.attach_bytes", gateway.calls.single().first)
        val params = gateway.calls.single().second
        assertEquals("runtime-1", params["session_id"]?.jsonPrimitive?.content)
        assertEquals(
            bytes.toList(),
            Base64.getDecoder().decode(params["content_base64"]!!.jsonPrimitive.content).toList(),
        )
        assertEquals("photo.png", params["filename"]?.jsonPrimitive?.content)
        assertEquals(null, params["client_attachment_id"])
        assertFalse(params.toString().contains("content://"))
        assertFalse(params.toString().contains("local-file"))
    }

    @Test
    fun classifiesUnsupportedAuthAndLostUploadResponsesWithoutPretendingTheyFailedDefinitively() {
        assertEquals(
            AttachmentFailureClass.Unsupported,
            classifyAttachmentFailure(GatewayRpcException(-32601, "Method not found")),
        )
        assertEquals(
            AttachmentFailureClass.AuthRequired,
            classifyAttachmentFailure(AuthenticationRejected("sign in")),
        )
        assertEquals(
            AttachmentFailureClass.Unknown,
            classifyAttachmentFailure(IOException("timeout")),
        )
        assertEquals(
            AttachmentFailureClass.Definitive,
            classifyAttachmentFailure(GatewayRpcException(4018, "image too large")),
        )
    }

    @Test
    fun detachUsesOnlyTheExactCurrentSessionReferenceAndReportsNonDeletingServerCleanup() = runBlocking {
        val gateway = RecordingGateway()
        val result = gateway.detachImage(
            owner = AttachmentSessionOwner("stored-1", "runtime-1"),
            serverReference = "/hermes/images/upload.png",
        )

        val params = gateway.calls.single().second
        assertEquals("image.detach", gateway.calls.single().first)
        assertEquals("runtime-1", params["session_id"]?.jsonPrimitive?.content)
        assertEquals("/hermes/images/upload.png", params["path"]?.jsonPrimitive?.content)
        assertTrue(result.detached)
        assertFalse(result.serverFileDeleted)
    }

    private class RecordingGateway : GatewayConnection {
        private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Connected)
        override val state = mutableState
        override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1)
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun connect() = Unit

        override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
            calls += method to params
            return when (method) {
                "image.attach_bytes" -> buildJsonObject {
                    put("attached", true)
                    put("path", "/hermes/images/upload.png")
                    put("bytes", 4)
                }
                "image.detach" -> buildJsonObject {
                    put("detached", true)
                    put("count", 0)
                }
                else -> buildJsonObject {}
            }
        }

        override fun close() = Unit
    }
}
