package dev.hazydreams.hermesceleste.network

import java.io.IOException
import kotlinx.serialization.json.Json
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
    val persistedToolCalls = mutableMapOf<String, Pair<String, String>>()
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

        if (role == "user") {
            parseBackgroundProcessResult(text)?.let { result ->
                val existingMessageId = messages.firstOrNull { message ->
                    message.role == "process" && message.processResult?.processId == result.processId
                }?.id
                messages = upsertBackgroundProcessResult(
                    messages = messages,
                    result = result,
                    messageId = existingMessageId
                        ?: uniqueMessageId("process:${result.processId}", "resume-$index"),
                )
                return@forEachIndexed
            }
        }

        if (role == "assistant") {
            (row["tool_calls"] as? JsonArray).orEmpty().forEach { callElement ->
                val call = callElement as? JsonObject ?: return@forEach
                val function = call["function"] as? JsonObject ?: return@forEach
                val toolId = call.string("id")?.takeIf(String::isNotBlank) ?: return@forEach
                val name = function.string("name")?.takeIf(String::isNotBlank) ?: "tool"
                val context = function.string("arguments")?.takeIf(String::isNotBlank) ?: name
                persistedToolCalls[toolId] = name to context
            }
            val commentary = row.codexCommentaryMessages()
            val reasoning = sequenceOf("reasoning", "reasoning_content", "reasoning_details")
                .mapNotNull(row::string)
                .firstOrNull(String::isNotBlank)
            val baseIdentity = sourceIdentity ?: "resume-$index"
            var reasoningIndex = 0
            var commentaryIndex = 0
            restoredAssistantSegments(reasoning, commentary).forEach { segment ->
                if (segment.commentary) {
                    messages = settleCurrentTurnSteps(messages)
                    messages = messages + ConversationMessage(
                        role = "assistant",
                        text = segment.text,
                        id = uniqueMessageId(
                            preferred = "$baseIdentity:commentary-$commentaryIndex",
                            fallback = "resume-$index-commentary-$commentaryIndex",
                        ),
                        interim = true,
                    )
                    commentaryIndex += 1
                } else {
                    val segmentIndex = reasoningIndex++
                    val identitySuffix = if (segmentIndex == 0) "" else "-$segmentIndex"
                    messages = appendReasoningToCurrentTurn(
                        messages = messages,
                        id = "$baseIdentity:reasoning$identitySuffix",
                        text = segment.text,
                        replaceTail = false,
                        stepsMessageId = "steps:$baseIdentity$identitySuffix",
                    )
                }
            }
            if (text.isBlank()) return@forEachIndexed
        }

        if (role == "tool") {
            val toolId = row.string("tool_id")
                ?: row.string("tool_call_id")
                ?: "${sourceIdentity ?: "resume-$index"}:tool"
            val persistedCall = persistedToolCalls[toolId]
            val name = row.string("name") ?: row.string("tool_name") ?: persistedCall?.first ?: "tool"
            val context = row.string("context")
                ?: persistedCall?.second
                ?: row["args"]?.toString().orEmpty()
            val result = row.string("result")
                ?: row.string("content")
                ?: ""
            messages = completeToolInCurrentTurn(
                messages = messages,
                id = toolId,
                name = name,
                context = context.ifBlank { name },
                summary = row.string("summary").orEmpty(),
                result = result,
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

private const val MIN_COMMENTARY_STRIP_LENGTH = 12

private data class RestoredAssistantSegment(
    val text: String,
    val commentary: Boolean,
)

private fun JsonObject.codexCommentaryMessages(): List<String> {
    val items = when (val rawItems = get("codex_message_items")) {
        is JsonArray -> rawItems
        is JsonPrimitive -> rawItems.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?.let { encoded ->
                runCatching { Json.parseToJsonElement(encoded) as? JsonArray }.getOrNull()
            }
        else -> null
    } ?: return emptyList()

    return items.mapNotNull { itemElement ->
        val item = itemElement as? JsonObject ?: return@mapNotNull null
        if (item.string("type") != "message") return@mapNotNull null
        if (!item.string("role").isNullOrBlank() && item.string("role") != "assistant") {
            return@mapNotNull null
        }
        if (!item.string("phase").orEmpty().trim().equals("commentary", ignoreCase = true)) {
            return@mapNotNull null
        }
        val content = item["content"] as? JsonArray ?: return@mapNotNull null
        content.mapNotNull { partElement ->
            val part = partElement as? JsonObject ?: return@mapNotNull null
            if (part.string("type") != "output_text") return@mapNotNull null
            part.string("text")?.takeIf(String::isNotBlank)
        }.joinToString(separator = "").trim().takeIf(String::isNotBlank)
    }
}

private fun restoredAssistantSegments(
    reasoning: String?,
    commentary: List<String>,
): List<RestoredAssistantSegment> {
    val visibleCommentary = commentary.map(String::trim).filter(String::isNotBlank)
    val visibleReasoning = reasoning?.takeIf(String::isNotBlank)
    if (visibleCommentary.isEmpty()) {
        return visibleReasoning
            ?.let { listOf(RestoredAssistantSegment(text = it, commentary = false)) }
            .orEmpty()
    }

    fun fallback(): List<RestoredAssistantSegment> = buildList {
        visibleReasoning?.let { add(RestoredAssistantSegment(text = it, commentary = false)) }
        visibleCommentary.forEach { add(RestoredAssistantSegment(text = it, commentary = true)) }
    }

    if (visibleReasoning == null || visibleCommentary.any { it.length < MIN_COMMENTARY_STRIP_LENGTH }) {
        return fallback()
    }

    var searchStart = 0
    val positions = visibleCommentary.map { text ->
        val index = visibleReasoning.indexOfCommentaryBlock(text, searchStart)
        if (index < 0) return fallback()
        searchStart = index + text.length
        index
    }

    return buildList {
        var reasoningStart = 0
        positions.forEachIndexed { index, commentaryStart ->
            cleanedRestoredReasoning(visibleReasoning.substring(reasoningStart, commentaryStart))
                .takeIf(String::isNotBlank)
                ?.let { add(RestoredAssistantSegment(text = it, commentary = false)) }
            val commentaryText = visibleCommentary[index]
            add(RestoredAssistantSegment(text = commentaryText, commentary = true))
            reasoningStart = commentaryStart + commentaryText.length
        }
        cleanedRestoredReasoning(visibleReasoning.substring(reasoningStart))
            .takeIf(String::isNotBlank)
            ?.let { add(RestoredAssistantSegment(text = it, commentary = false)) }
    }
}

private fun String.indexOfCommentaryBlock(candidate: String, startIndex: Int): Int {
    var index = indexOf(candidate, startIndex)
    while (index >= 0) {
        val end = index + candidate.length
        val beginsAtBoundary = index == 0 || (index >= 2 && substring(index - 2, index) == "\n\n")
        val endsAtBoundary = end == length || (end + 2 <= length && substring(end, end + 2) == "\n\n")
        if (beginsAtBoundary && endsAtBoundary) return index
        index = indexOf(candidate, startIndex = index + 1)
    }
    return -1
}

private fun cleanedRestoredReasoning(reasoning: String): String = reasoning
        .replace(Regex("""(\n\s*<!--\s*-->\s*)+(\n|$)"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

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
