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
    val profile: String,
)

suspend fun GatewayConnection.createSession(
    profile: String,
    clientSource: String,
): CreatedSession {
    val selectedProfile = profile.trim().ifEmpty { "default" }
    require(clientSource.isNotBlank()) { "A client source is required." }
    val result = request(
        method = "session.create",
        params = buildJsonObject {
            put("cols", 96)
            put("source", clientSource)
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
            ?: throw IOException("Hermes created a conversation without a stored identity."),
        profile = info?.string("profile_name")
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Hermes created a conversation without a profile identity."),
    )
}

suspend fun GatewayConnection.resumeStoredSession(
    storedSessionId: String,
    clientSource: String,
): ResumedSession {
    require(storedSessionId.isNotBlank()) { "Choose a Hermes session to open." }
    require(clientSource.isNotBlank()) { "A client source is required." }
    val result = request(
        method = "session.resume",
        params = buildJsonObject {
            put("session_id", storedSessionId)
            put("cols", 96)
            put("source", clientSource)
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
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Hermes returned no resumed session identity."),
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
    var messages = emptyList<ConversationMessage>()

    fun uniqueMessageId(preferred: String?, fallback: String): String {
        val explicit = preferred?.takeIf(String::isNotBlank)
        if (explicit != null && usedIds.add(explicit)) return explicit
        return generateSequence(fallback) { current -> "$current-duplicate" }
            .first(usedIds::add)
    }

    elements.forEachIndexed { index, element ->
        val row = element as? JsonObject ?: return@forEachIndexed
        val role = row.string("role")?.takeIf(String::isNotBlank) ?: return@forEachIndexed
        val sourceIdentity = row["row_id"].scalarIdentity()?.let { "row-$it" }
            ?: row["id"].scalarIdentity()
            ?: row["message_id"].scalarIdentity()
        val text = row.string("text")
            ?: row.string("content")
            ?: row.string("context")
            ?: ""

        if (role == "assistant") {
            val reasoning = sequenceOf("reasoning", "reasoning_content", "reasoning_details")
                .mapNotNull(row::string)
                .firstOrNull(String::isNotBlank)
            if (reasoning != null) {
                messages = appendReasoningToCurrentTurn(
                    messages = messages,
                    id = "${sourceIdentity ?: "resume-$index"}:reasoning",
                    text = reasoning,
                    replaceTail = false,
                    stepsMessageId = "steps:${sourceIdentity ?: "resume-$index"}",
                )
            }
            if (text.isBlank()) return@forEachIndexed
        }

        if (role == "tool") {
            val name = row.string("name") ?: row.string("tool_name") ?: "tool"
            val context = text.ifBlank { row["args"]?.toString().orEmpty() }.ifBlank { name }
            val toolId = row.string("tool_id")
                ?: row.string("tool_call_id")
                ?: "${sourceIdentity ?: "resume-$index"}:tool"
            messages = completeToolInCurrentTurn(
                messages = messages,
                id = toolId,
                name = name,
                context = context,
                summary = row.string("summary").orEmpty(),
                result = row.string("result").orEmpty(),
                fallbackStepId = toolId,
                stepsMessageId = "steps:${sourceIdentity ?: "resume-$index"}",
            )
            return@forEachIndexed
        }

        if (text.isBlank()) return@forEachIndexed
        messages = messages + ConversationMessage(
            role = role,
            text = text,
            id = uniqueMessageId(sourceIdentity, "resume-$index"),
        )
    }

    return messages.map(ConversationMessage::settledSteps)
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
    (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.boolean(key: String): Boolean? =
    get(key)?.jsonPrimitive?.booleanOrNull
