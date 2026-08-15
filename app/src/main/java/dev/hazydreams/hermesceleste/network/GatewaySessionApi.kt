package dev.hazydreams.hermesceleste.network

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class CreatedSession(
    val runtimeSessionId: String,
    val storedSessionId: String,
    val profile: String?,
)

data class AttachmentReference(
    val id: String,
    val uri: String,
    val mimeType: String? = null,
    val name: String? = null,
)

enum class AttachmentReadiness {
    Ready,
    Uploading,
    Failed,
}

data class AttachmentDraft(
    val reference: AttachmentReference,
    val readiness: AttachmentReadiness,
)

data class SubmitOptions(
    val queued: Boolean = false,
    val attachments: List<AttachmentReference> = emptyList(),
)

enum class SteerOutcome {
    Steered,
    Queued,
    Rejected,
    Unsupported,
}

enum class RedirectOutcome {
    Redirected,
    Queued,
    Rejected,
    Unsupported,
}

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
    return decodeResumedSession(result, storedSessionId)
}

suspend fun GatewayConnection.submitPrompt(runtimeSessionId: String, text: String): JsonObject {
    return submitPrompt(runtimeSessionId, text, SubmitOptions())
}

suspend fun GatewayConnection.submitPrompt(
    runtimeSessionId: String,
    text: String,
    options: SubmitOptions,
): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    require(text.isNotBlank() || options.attachments.isNotEmpty()) { "Write a message first." }
    return request(
        method = "prompt.submit",
        params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("text", text)
            if (options.queued) put("queued", true)
            if (options.attachments.isNotEmpty()) {
                put(
                    "attachments",
                    buildJsonArray {
                        options.attachments.forEach { attachment ->
                            add(
                                buildJsonObject {
                                    put("id", attachment.id)
                                    put("uri", attachment.uri)
                                    attachment.mimeType?.let { put("mime_type", it) }
                                    attachment.name?.let { put("name", it) }
                                },
                            )
                        }
                    },
                )
            }
        },
        timeoutMillis = 180_000,
    ).asObject("Hermes returned no prompt status.")
}

suspend fun GatewayConnection.submitQueuedPrompt(
    runtimeSessionId: String,
    text: String,
    options: SubmitOptions = SubmitOptions(queued = true),
): JsonObject = submitPrompt(
    runtimeSessionId = runtimeSessionId,
    text = text,
    options = options.copy(queued = true),
)

suspend fun GatewayConnection.submitQueuedPrompt(
    runtimeSessionId: String,
    text: String,
    attachments: List<AttachmentReference>,
): JsonObject = submitQueuedPrompt(
    runtimeSessionId,
    text,
    SubmitOptions(queued = true, attachments = attachments),
)

suspend fun GatewayConnection.steerSession(
    runtimeSessionId: String,
    text: String,
): SteerOutcome {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    require(text.isNotBlank()) { "Write a message first." }
    val result = try {
        request(
            method = "session.steer",
            params = buildJsonObject {
                put("session_id", runtimeSessionId)
                put("text", text)
            },
        ).asObject("Hermes returned no steer status.")
    } catch (error: GatewayRpcException) {
        if (error.isUnsupportedActiveTurnMethod()) return SteerOutcome.Unsupported
        throw error
    }
    return when (result.string("status")?.lowercase()) {
        "steered" -> SteerOutcome.Steered
        "queued" -> SteerOutcome.Queued
        "rejected" -> SteerOutcome.Rejected
        else -> throw IOException("Hermes returned an unknown steer status.")
    }
}

suspend fun GatewayConnection.redirectSession(
    runtimeSessionId: String,
    text: String,
): RedirectOutcome {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    require(text.isNotBlank()) { "Write a message first." }
    val result = try {
        request(
            method = "session.redirect",
            params = buildJsonObject {
                put("session_id", runtimeSessionId)
                put("text", text)
            },
        ).asObject("Hermes returned no redirect status.")
    } catch (error: GatewayRpcException) {
        if (error.isUnsupportedActiveTurnMethod()) return RedirectOutcome.Unsupported
        throw error
    }
    return when (result.string("status")?.lowercase()) {
        "redirected" -> RedirectOutcome.Redirected
        "queued" -> RedirectOutcome.Queued
        "rejected" -> RedirectOutcome.Rejected
        else -> throw IOException("Hermes returned an unknown redirect status.")
    }
}

suspend fun GatewayConnection.interruptSession(runtimeSessionId: String): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    val result = request(
        method = "session.interrupt",
        params = buildJsonObject { put("session_id", runtimeSessionId) },
    ).asObject("Hermes returned no interrupt status.")
    if (result.string("status")?.lowercase() != "interrupted") {
        throw IOException("Hermes did not confirm that the active turn was interrupted.")
    }
    return result
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
        message.copy(id = id)
    }
}

private fun decodeGatewayMessage(element: JsonElement): ConversationMessage? {
    val row = element as? JsonObject ?: return null
    val role = row.string("role")?.takeIf(String::isNotBlank) ?: return null
    val toolName = row.string("name") ?: row.string("tool_name")
    val text = row.string("text")
        ?: row.string("content")
        ?: row.string("context")
        ?: if (role == "tool") toolName.orEmpty() else ""
    return ConversationMessage(
        role = role,
        text = text,
        toolName = toolName,
        id = row["row_id"].scalarIdentity()?.let { "row-$it" }
            ?: row["id"].scalarIdentity()
            ?: row["message_id"].scalarIdentity(),
    )
}

private fun JsonElement?.scalarIdentity(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private data class DecodedInflightSnapshot(
    val user: String,
    val assistant: String,
    val streaming: Boolean,
    val corrections: List<InflightCorrection>,
    val error: String?,
)

internal fun decodeResumedSession(
    result: JsonObject,
    requestedStoredSessionId: String,
): ResumedSession {
    val runtimeId = result.string("session_id")
        ?.takeIf(String::isNotBlank)
        ?: throw IOException("Hermes returned no runtime session identity.")
    val info = result["info"] as? JsonObject
    val running = result.boolean("running") ?: info?.boolean("running")
    val status = result.string("status") ?: info?.string("status")
    val inflightSnapshot = decodeInflightSnapshot(result["inflight"])
    val inflightCorrections = inflightSnapshot?.corrections.orEmpty()
    val correctionOffsets = inflightCorrections
        .map { it.assistantOffset }
        .takeIf { offsets -> offsets.isNotEmpty() && offsets.all { it != null } }
        ?.map { requireNotNull(it) }
        .orEmpty()
    val queuedUserTexts = decodeQueuedUserTexts(
        result["queued"] ?: result["queued_prompts"],
    )

    return ResumedSession(
        runtimeSessionId = runtimeId,
        storedSessionId = result.string("resumed")
            ?: result.string("stored_session_id")
            ?: result.string("session_key")
            ?: requestedStoredSessionId,
        messages = decodeGatewayMessages(result["messages"]?.jsonArray.orEmpty()),
        running = running,
        status = status,
        inflightAssistantText = inflightSnapshot?.assistant.orEmpty(),
        inflightUserText = inflightSnapshot?.user.orEmpty(),
        inflightCorrections = inflightCorrections,
        correctionOffsets = correctionOffsets,
        inflightStreaming = inflightSnapshot?.streaming == true,
        inflightError = inflightSnapshot?.error,
        queuedUserTexts = queuedUserTexts,
        queuedUserText = queuedUserTexts.firstOrNull().orEmpty(),
        hasLiveProjection = inflightSnapshot != null || queuedUserTexts.isNotEmpty(),
        supportsActiveTurnRedirect = result.explicitRedirectCapability() == true,
    )
}

internal fun decodeInflightCorrections(element: JsonElement?): List<InflightCorrection> {
    val row = element as? JsonObject ?: return emptyList()
    val rawCorrections = row["corrections"] as? JsonArray ?: return emptyList()
    val rawOffsets = row["correction_offsets"] as? JsonArray
    return rawCorrections.mapIndexedNotNull { index, correction ->
        val text = (correction as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) {
            null
        } else {
            val offset = rawOffsets
                ?.getOrNull(index)
                ?.let { it as? JsonPrimitive }
                ?.intOrNull
                ?.takeIf { it >= 0 }
            InflightCorrection(text = text, assistantOffset = offset)
        }
    }
}

private fun decodeInflightSnapshot(element: JsonElement?): DecodedInflightSnapshot? {
    val row = element as? JsonObject ?: return null
    val user = row.string("user").orEmpty().trim()
    val assistant = row.string("assistant")
        ?: row.string("text")
        ?: row.string("content")
        ?: ""
    val streaming = row.boolean("streaming") == true
    val corrections = decodeInflightCorrections(row)
    val error = row.string("error")?.takeIf(String::isNotBlank)
    if (user.isBlank() && assistant.isBlank() && !streaming && corrections.isEmpty() && error == null) {
        return null
    }
    return DecodedInflightSnapshot(
        user = user,
        assistant = assistant,
        streaming = streaming,
        corrections = corrections,
        error = error,
    )
}

private fun decodeQueuedUserTexts(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.flatMap(::decodeQueuedUserTexts)
    is JsonObject -> {
        val nested = listOf("prompts", "queued_prompts", "items", "queue")
            .asSequence()
            .mapNotNull { key -> element[key] }
            .firstOrNull()
        if (nested != null) {
            decodeQueuedUserTexts(nested)
        } else {
            listOfNotNull(
                queuedScalarText(element["user"]),
                queuedScalarText(element["text"]),
                queuedScalarText(element["content"]),
            ).firstOrNull()?.let(::listOf).orEmpty()
        }
    }
    is JsonPrimitive -> queuedScalarText(element)?.let(::listOf).orEmpty()
    else -> emptyList()
}.map(String::trim).filter(String::isNotBlank)

private fun queuedScalarText(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    val encoded = primitive.toString()
    if (encoded.length < 2 || encoded.first() != '"' || encoded.last() != '"') return null
    return primitive.contentOrNull?.takeIf(String::isNotBlank)
}

internal fun inflightAssistantText(element: JsonElement?): String =
    decodeInflightSnapshot(element)?.assistant.orEmpty()

internal fun JsonElement?.isTruthy(): Boolean = when (this) {
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

internal fun JsonObject.explicitRedirectCapability(): Boolean? {
    val info = this["info"] as? JsonObject
    val containers = listOfNotNull(
        this,
        this["capabilities"] as? JsonObject,
        info?.get("capabilities") as? JsonObject,
        info,
    )
    val keys = listOf(
        "supports_active_turn_redirect",
        "supportsActiveTurnRedirect",
    )
    return containers.asSequence()
        .flatMap { container -> keys.asSequence().mapNotNull { key -> container.boolean(key) } }
        .firstOrNull()
}

private fun GatewayRpcException.isUnsupportedActiveTurnMethod(): Boolean =
    code == 4010 || code == -32601
