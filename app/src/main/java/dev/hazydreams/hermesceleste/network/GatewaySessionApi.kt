package dev.hazydreams.hermesceleste.network

import java.io.IOException
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

suspend fun GatewayConnection.submitPrompt(runtimeSessionId: String, text: String): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    require(text.isNotBlank()) { "Write a message first." }
    return request(
        method = "prompt.submit",
        params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("text", text)
        },
        timeoutMillis = 180_000,
    ).asObject("Hermes returned no prompt status.")
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
        pending = row.boolean("pending") == true,
        interim = row.boolean("interim") == true,
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
