package dev.hazydreams.hermesceleste.network

import dev.hazydreams.hermesceleste.attachments.AttachmentCapabilityState
import dev.hazydreams.hermesceleste.attachments.ImageOnlyCapabilityState
import dev.hazydreams.hermesceleste.attachments.MAX_ATTACHMENT_BYTES
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class CreatedSession(
    val runtimeSessionId: String,
    val storedSessionId: String,
    val profile: String?,
)

suspend fun GatewayConnection.createSession(profile: String): CreatedSession {
    val selectedProfile = profile.trim().ifEmpty { "default" }
    val result = request(
        method = "session.create",
        params = buildJsonObject {
            put("cols", 96)
            put("source", "android")
            put("profile", selectedProfile)
        },
        timeoutMillis = 30_000,
    ).asObject("Hermes returned no created session.")
    val runtimeId = result.string("session_id")
        ?.takeIf(String::isNotBlank)
        ?: throw IOException("Hermes created a conversation without a runtime identity.")
    val info = result["info"] as? JsonObject
    return CreatedSession(
        runtimeSessionId = runtimeId,
        storedSessionId = result.string("stored_session_id")
            ?.takeIf(String::isNotBlank)
            ?: runtimeId,
        profile = result.string("profile")
            ?: result.string("profile_id")
            ?: info?.string("profile_name"),
    )
}

suspend fun GatewayConnection.resumeStoredSession(storedSessionId: String): ResumedSession {
    require(storedSessionId.isNotBlank()) { "Choose a Hermes session to open." }
    val result = request(
        method = "session.resume",
        params = buildJsonObject {
            put("session_id", storedSessionId)
            put("cols", 96)
            put("source", "android")
        },
        timeoutMillis = 30_000,
    ).asObject("Hermes returned no resumed session.")

    val runtimeId = result.string("session_id")
        ?.takeIf(String::isNotBlank)
        ?: throw IOException("Hermes returned no runtime session identity.")
    val info = result["info"] as? JsonObject
    val running = result.boolean("running") ?: info?.boolean("running")
    val status = result.string("status") ?: info?.string("status")
    val inflight = result["inflight"]
    val queued = result["queued"]

    return ResumedSession(
        runtimeSessionId = runtimeId,
        storedSessionId = result.string("resumed")
            ?: result.string("stored_session_id")
            ?: result.string("session_key")
            ?: storedSessionId,
        messages = decodeGatewayMessages(result["messages"]?.jsonArray.orEmpty()),
        running = running,
        status = status,
        inflightAssistantText = inflightAssistantText(inflight),
        hasLiveProjection = inflight.isTruthy() || queued.isTruthy(),
    )
}

suspend fun GatewayConnection.submitPrompt(
    runtimeSessionId: String,
    text: String,
    allowEmptyCaption: Boolean = false,
): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    require(allowEmptyCaption || text.isNotBlank()) { "Write a message first." }
    return request(
        method = "prompt.submit",
        params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("text", text)
        },
        timeoutMillis = 180_000,
    ).asObject("Hermes returned no prompt status.")
}

data class AttachmentSessionOwner(
    val storedSessionId: String,
    val runtimeSessionId: String?,
) {
    fun requestSessionId(): String =
        runtimeSessionId?.takeIf(String::isNotBlank)
            ?: storedSessionId.takeIf(String::isNotBlank)
            ?: throw IOException("No Hermes conversation is open.")
}

data class AttachedImage(
    val serverReference: String,
    val byteSize: Long,
)

data class DetachedImage(
    val detached: Boolean,
    val serverFileDeleted: Boolean,
)

enum class AttachmentFailureClass {
    Unsupported,
    Definitive,
    Unknown,
    AuthRequired,
}

data class AttachmentCapabilityAdvertisement(
    val upload: AttachmentCapabilityState = AttachmentCapabilityState.Unknown,
    val imageOnly: ImageOnlyCapabilityState = ImageOnlyCapabilityState.Unknown,
)

class AttachmentMediaUnavailable(message: String = "Image unavailable") : IOException(message)

suspend fun GatewayConnection.attachImageBytes(
    owner: AttachmentSessionOwner,
    bytes: ByteArray,
    filename: String?,
    mimeType: String,
    clientAttachmentId: String? = null,
): AttachedImage {
    require(bytes.isNotEmpty()) { "Image is empty." }
    require(bytes.size.toLong() <= MAX_ATTACHMENT_BYTES) { "Image is too large." }
    val safeName = safeAttachmentFilename(filename, mimeType)
    val params = withContext(Dispatchers.IO) {
        buildJsonObject {
            put("session_id", owner.requestSessionId())
            put("content_base64", Base64.getEncoder().encodeToString(bytes))
            if (safeName != null) put("filename", safeName)
            // The current Hermes method has no idempotency/client-id field.
        }
    }
    val result = request(
        method = "image.attach_bytes",
        params = params,
        timeoutMillis = 180_000,
    ).asObject("Hermes returned no image attachment status.")
    val reference = result.string("path")?.takeIf(String::isNotBlank)
        ?: result.string("ref_path")?.takeIf(String::isNotBlank)
        ?: result.string("reference")?.takeIf(String::isNotBlank)
        ?: throw IOException("Hermes returned no image attachment reference.")
    return AttachedImage(
        serverReference = reference,
        byteSize = result["bytes"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong(),
    )
}

suspend fun GatewayConnection.detachImage(
    owner: AttachmentSessionOwner,
    serverReference: String,
): DetachedImage {
    require(serverReference.isNotBlank()) { "An image reference is required." }
    val result = request(
        method = "image.detach",
        params = buildJsonObject {
            put("session_id", owner.requestSessionId())
            put("path", serverReference)
        },
    ).asObject("Hermes returned no image detach status.")
    return DetachedImage(
        detached = result.boolean("detached") == true,
        // image.detach removes session metadata only in the current protocol.
        serverFileDeleted = false,
    )
}

fun classifyAttachmentFailure(error: Throwable): AttachmentFailureClass = when {
    error is AuthenticationRejected -> AttachmentFailureClass.AuthRequired
    error is GatewayRpcException && error.code == -32601 -> AttachmentFailureClass.Unsupported
    error is GatewayRpcException && error.code in setOf(4016, 4017, 4018) -> AttachmentFailureClass.Definitive
    error is GatewayRpcException && error.code in setOf(401, 403) -> AttachmentFailureClass.AuthRequired
    error is GatewayRpcException && error.message.orEmpty().contains("method not found", ignoreCase = true) ->
        AttachmentFailureClass.Unsupported
    error is GatewayRpcException && error.message.orEmpty().let {
        it.contains("origin", ignoreCase = true) ||
            (it.contains("profile", ignoreCase = true) && it.contains("mismatch", ignoreCase = true))
    } -> AttachmentFailureClass.AuthRequired
    error is GatewayRpcException -> AttachmentFailureClass.Unknown
    error is TimeoutCancellationException || error is IOException -> AttachmentFailureClass.Unknown
    else -> AttachmentFailureClass.Definitive
}

fun decodeAttachmentCapability(payload: JsonObject): AttachmentCapabilityAdvertisement {
    val capabilities = payload["capabilities"] as? JsonObject ?: return AttachmentCapabilityAdvertisement()
    val attachment = capabilities["attachments"] as? JsonObject ?: return AttachmentCapabilityAdvertisement()
    val upload = booleanCapability(attachment["supported"])
    val imageOnly = booleanCapability(attachment["image_only"])
    return AttachmentCapabilityAdvertisement(
        upload = when (upload) {
            true -> AttachmentCapabilityState.Supported
            false -> AttachmentCapabilityState.Unsupported
            null -> AttachmentCapabilityState.Unknown
        },
        imageOnly = when (imageOnly) {
            true -> ImageOnlyCapabilityState.Supported
            false -> ImageOnlyCapabilityState.Unsupported
            null -> ImageOnlyCapabilityState.Unknown
        },
    )
}

private fun booleanCapability(element: JsonElement?): Boolean? =
    runCatching { element?.jsonPrimitive?.booleanOrNull }.getOrNull()

private fun safeAttachmentFilename(filename: String?, mimeType: String): String? {
    val extension = when (mimeType.lowercase()) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "image/bmp" -> ".bmp"
        else -> ".img"
    }
    val base = filename
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.let { it.substringBeforeLast('.', missingDelimiterValue = it) }
        ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }
        ?.trim()
        ?.take(80)
        ?.takeIf(String::isNotBlank)
        ?: "image"
    return "$base$extension"
}

suspend fun GatewayConnection.interruptSession(runtimeSessionId: String): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    return request(
        method = "session.interrupt",
        params = buildJsonObject { put("session_id", runtimeSessionId) },
    ).asObject("Hermes returned no interrupt status.")
}

internal fun decodeGatewayMessages(elements: List<JsonElement>): List<ConversationMessage> {
    val usedIds = mutableSetOf<String>()
    return elements.mapIndexedNotNull { index, element ->
        val message = decodeGatewayMessage(element) ?: return@mapIndexedNotNull null
        val preferredId = message.id?.takeIf(String::isNotBlank)
        val id = if (preferredId != null && usedIds.add(preferredId)) {
            preferredId
        } else {
            generateSequence("resume-$index") { current -> "$current-duplicate" }
                .first(usedIds::add)
        }
        message.copy(
            id = id,
            attachments = message.attachments.mapIndexed { attachmentIndex, attachment ->
                attachment.copy(id = "$id:attachment:$attachmentIndex")
            },
        )
    }
}

private fun decodeGatewayMessage(element: JsonElement): ConversationMessage? {
    val row = element as? JsonObject ?: return null
    val role = row.string("role")?.takeIf(String::isNotBlank) ?: return null
    val toolName = row.string("name") ?: row.string("tool_name")
    val rawText = row.string("text")
        ?: row.string("content")
        ?: row.string("context")
        ?: if (role == "tool") toolName.orEmpty() else ""
    val messageId = row["row_id"].scalarIdentity()?.let { "row-$it" }
        ?: row["id"].scalarIdentity()
        ?: row["message_id"].scalarIdentity()
    val normalized = if (role == "user") normalizeImageReferences(rawText) else null
    return ConversationMessage(
        role = role,
        text = normalized?.visibleText ?: rawText,
        toolName = toolName,
        id = messageId,
        rawText = rawText,
        attachments = normalized?.references.orEmpty().mapIndexed { index, reference ->
            messageAttachmentFromReference(reference, index, messageId)
        },
    )
}

private fun JsonElement?.scalarIdentity(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun inflightAssistantText(element: JsonElement?): String {
    val row = element as? JsonObject ?: return ""
    return sequenceOf("assistant", "text", "content")
        .mapNotNull(row::string)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
}

private fun JsonElement?.isTruthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonObject, is JsonArray -> true
    is JsonPrimitive -> booleanOrNull
        ?: contentOrNull?.let { value ->
            value.isNotEmpty() && value != "0" && !value.equals("false", ignoreCase = true)
        }
        ?: false
}

private fun JsonElement.asObject(errorMessage: String): JsonObject =
    this as? JsonObject ?: throw IOException(errorMessage)

internal fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull

internal fun JsonObject.boolean(key: String): Boolean? =
    get(key)?.jsonPrimitive?.booleanOrNull
