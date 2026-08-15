package dev.hazydreams.hermesceleste.network

import java.io.IOException
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

suspend fun GatewayConnection.resumeStoredSession(
    storedSessionId: String,
    profile: String? = null,
    originKey: NormalizedDashboardOrigin? = null,
): ResumedSession {
    require(storedSessionId.isNotBlank()) { "Choose a Hermes session to open." }
    val result = request(
        method = "session.resume",
        params = buildJsonObject {
            put("session_id", storedSessionId)
            put("cols", 96)
            put("source", "android")
            profile?.trim()?.takeIf(String::isNotBlank)?.let { put("profile", it) }
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
    val messages = result["messages"]?.jsonArray.orEmpty()
    val decoded = withContext(Dispatchers.Default) {
        decodeGatewayMessages(messages) to decodeGatewayActivity(messages)
    }
    val resolvedStoredSessionId = sequenceOf(
        result.string("resumed"),
        result.string("stored_session_id"),
        result.string("session_key"),
        storedSessionId,
    ).map(String?::orEmpty)
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        ?: storedSessionId.trim()

    return ResumedSession(
        runtimeSessionId = runtimeId.trim(),
        storedSessionId = resolvedStoredSessionId,
        messages = decoded.first,
        running = running,
        status = status,
        inflightAssistantText = inflightAssistantText(inflight),
        hasLiveProjection = inflight.isTruthy() || queued.isTruthy(),
        // The current Hermes gateway exposes durable activity through its
        // server-authored `messages` projection: role=tool rows plus explicit
        // `reasoning` fields on assistant rows. Provider-facing
        // `reasoning_content` is intentionally ignored by decodeGatewayActivity.
        activityItems = decoded.second,
        originKey = firstResumeString(result, info, "origin_key", "origin", "dashboard_origin", "base_url")
            ?: originKey,
        profile = firstResumeString(result, info, "profile", "profile_id", "profile_name")
            ?: profile?.trim()?.takeIf(String::isNotBlank),
        serverReasoningAllowed = null,
    )
}

private fun firstResumeString(
    result: JsonObject,
    info: JsonObject?,
    vararg keys: String,
): String? = keys.asSequence()
    .mapNotNull { key -> result.string(key) ?: info?.string(key) }
    .map(String::trim)
    .firstOrNull(String::isNotBlank)

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
    val safeToolName = if (role == "tool") {
        toolName?.let { safeToolName(it, row.toolNameIsUnsafe()) }
    } else {
        toolName
    }
    val safeText = if (role == "tool") {
        sanitizeActivityText(text, TOOL_ACTIVITY_DETAIL_LIMIT)
    } else {
        text
    }
    return ConversationMessage(
        role = role,
        text = safeText,
        toolName = safeToolName,
        id = row["row_id"].scalarIdentity()?.let { "row-$it" }
            ?: row["id"].scalarIdentity()
            ?: row["message_id"].scalarIdentity(),
    )
}

private fun JsonElement?.scalarIdentity(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

internal fun inflightAssistantText(element: JsonElement?): String {
    val row = element as? JsonObject ?: return ""
    return sequenceOf("assistant", "text", "content")
        .mapNotNull(row::string)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
}

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
