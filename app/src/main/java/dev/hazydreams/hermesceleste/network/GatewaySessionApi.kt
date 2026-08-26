package dev.hazydreams.hermesceleste.network

import dev.hazydreams.hermesceleste.ComposerAttachment
import dev.hazydreams.hermesceleste.ComposerAttachmentKind
import dev.hazydreams.hermesceleste.ConversationAttachment
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.json.intOrNull
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
    profile: String,
    clientSource: String,
): ResumedSession {
    require(storedSessionId.isNotBlank()) { "Choose a Hermes session to open." }
    require(clientSource.isNotBlank()) { "A client source is required." }
    val selectedProfile = profile.trim().ifEmpty { "default" }
    val result = request(
        method = "session.resume",
        params = buildJsonObject {
            put("session_id", storedSessionId)
            put("cols", 96)
            put("source", clientSource)
            put("profile", selectedProfile)
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

    val decoded = decodeGatewayConversation(result["messages"]?.jsonArray.orEmpty())
    val pendingClarification = (result["pending_clarify"] as? JsonObject)
        ?.let(::clarificationFromRequest)
    val resumedMessages = pendingClarification
        ?.let { bindClarificationRequest(decoded.messages, it) }
        ?: decoded.messages
    val inflightObject = inflight as? JsonObject
    val inflightAssistant = inflightAssistantText(inflight)
    val correctionOffsets = (inflightObject?.get("correction_offsets") as? JsonArray)
        ?.map { it.jsonPrimitive.intOrNull }
        .orEmpty()
    val inflightCorrections = (inflightObject?.get("corrections") as? JsonArray)
        ?.mapIndexedNotNull { index, correction ->
            correction.jsonPrimitive.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { text ->
                    InflightCorrection(
                        text = text,
                        assistantOffset = correctionOffsets.getOrNull(index)
                            ?.let { offset -> codePointOffsetToUtf16Index(inflightAssistant, offset) },
                    )
                }
        }
        .orEmpty()
    return ResumedSession(
        runtimeSessionId = runtimeId,
        storedSessionId = result.string("resumed")
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Hermes returned no resumed session identity."),
        messages = resumedMessages,
        pendingClarification = pendingClarification,
        taskProgress = decoded.taskProgress,
        running = running,
        status = status,
        inflightUserText = (inflight as? JsonObject)?.string("user").orEmpty(),
        queuedUserText = (queued as? JsonObject)?.string("user").orEmpty(),
        inflightAssistantText = inflightAssistant,
        inflightCorrections = inflightCorrections,
        hasLiveProjection = inflight.isTruthy() || queued.isTruthy() || pendingClarification != null,
    )
}

suspend fun GatewayConnection.submitPrompt(
    runtimeSessionId: String,
    text: String,
    queued: Boolean = false,
): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    return request(
        method = "prompt.submit",
        params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("text", text)
            if (queued) put("queued", true)
        },
        timeoutMillis = 180_000,
    ).asObject("Hermes returned no prompt status.")
}

suspend fun GatewayConnection.redirectSession(runtimeSessionId: String, text: String): SessionRedirectStatus {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    require(text.isNotBlank()) { "A correction is required." }
    val result = request(
        method = "session.redirect",
        params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("text", text)
        },
        timeoutMillis = 30_000,
    ).asObject("Hermes returned no redirect status.")
    return when (result.string("status")) {
        "redirected" -> SessionRedirectStatus.Redirected
        "queued" -> SessionRedirectStatus.Queued
        else -> SessionRedirectStatus.Rejected
    }
}

suspend fun GatewayConnection.stageAttachment(
    runtimeSessionId: String,
    attachment: ComposerAttachment,
    encodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ComposerAttachment {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    if (attachment.attachedRuntimeSessionId == runtimeSessionId) return attachment
    val contentBase64 = withContext(encodingDispatcher) {
        Base64.encode(attachment.contentBytes)
    }
    return when (attachment.kind) {
        ComposerAttachmentKind.Image -> {
            val result = request(
                method = "image.attach_bytes",
                params = buildJsonObject {
                    put("session_id", runtimeSessionId)
                    put("content_base64", contentBase64)
                    put("filename", attachment.name)
                },
                timeoutMillis = 60_000,
            ).asObject("Hermes returned no image attachment status.")
            if (result.boolean("attached") != true) {
                throw IOException(result.string("message") ?: "Hermes could not attach ${attachment.name}.")
            }
            attachment.copy(
                attachedRuntimeSessionId = runtimeSessionId,
                remotePath = result.string("path"),
            )
        }

        ComposerAttachmentKind.File -> {
            val result = request(
                method = "file.attach",
                params = buildJsonObject {
                    put("session_id", runtimeSessionId)
                    put("name", attachment.name)
                    put("data_url", "data:${attachment.mimeType};base64,$contentBase64")
                },
                timeoutMillis = 60_000,
            ).asObject("Hermes returned no file attachment status.")
            val refText = result.string("ref_text")?.takeIf(String::isNotBlank)
            if (result.boolean("attached") != true || refText == null) {
                throw IOException(result.string("message") ?: "Hermes could not attach ${attachment.name}.")
            }
            attachment.copy(
                attachedRuntimeSessionId = runtimeSessionId,
                remotePath = result.string("path"),
                refText = refText,
            )
        }
    }
}

suspend fun GatewayConnection.detachImage(runtimeSessionId: String, remotePath: String): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    require(remotePath.isNotBlank()) { "No staged image is available." }
    val result = request(
        method = "image.detach",
        params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("path", remotePath)
        },
    ).asObject("Hermes returned no image detach status.")
    if (result.boolean("detached") != true) {
        throw IOException(result.string("message") ?: "Hermes could not detach the staged image.")
    }
    return result
}

suspend fun GatewayConnection.respondToClarification(requestId: String, answer: String): JsonObject {
    require(requestId.isNotBlank()) { "No Hermes clarification request is open." }
    return request(
        method = "clarify.respond",
        params = buildJsonObject {
            put("request_id", requestId)
            put("answer", answer)
        },
    ).asObject("Hermes returned no clarification status.")
}

suspend fun GatewayConnection.interruptSession(runtimeSessionId: String): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    return request(
        method = "session.interrupt",
        params = buildJsonObject { put("session_id", runtimeSessionId) },
    ).asObject("Hermes returned no interrupt status.")
}

suspend fun GatewayConnection.closeRuntimeSession(runtimeSessionId: String): Boolean {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    return request(
        method = "session.close",
        params = buildJsonObject { put("session_id", runtimeSessionId) },
    ).asObject("Hermes returned no session close status.")
        .boolean("closed") == true
}

private data class PersistedToolCall(
    val name: String,
    val arguments: JsonObject?,
    val context: String,
)

private data class PersistedUserPresentation(
    val text: String,
    val attachments: List<ConversationAttachment>,
)

private val persistedAttachmentLine = Regex(
    """^@(image|file):(`[^`\n]+`|"[^"\n]+"|'[^'\n]+'|\S+)$""",
    RegexOption.IGNORE_CASE,
)

private fun persistedUserPresentation(text: String): PersistedUserPresentation {
    val attachments = mutableListOf<ConversationAttachment>()
    val visibleLines = text.lines().filterNot { line ->
        val match = persistedAttachmentLine.matchEntire(line.trim()) ?: return@filterNot false
        val kind = if (match.groupValues[1].equals("image", ignoreCase = true)) {
            ComposerAttachmentKind.Image
        } else {
            ComposerAttachmentKind.File
        }
        val path = match.groupValues[2].removeSurrounding("`")
            .removeSurrounding("\"")
            .removeSurrounding("'")
        val fallback = if (kind == ComposerAttachmentKind.Image) "image" else "file"
        val name = path.replace('\\', '/').substringAfterLast('/').ifBlank { fallback }
        attachments += ConversationAttachment(kind = kind, name = name)
        true
    }.toMutableList()

    if (attachments.isEmpty()) {
        return PersistedUserPresentation(text = text, attachments = emptyList())
    }
    if (attachments.any { it.kind == ComposerAttachmentKind.Image }) {
        visibleLines.removeAll { line -> line.trim() == "[screenshot]" || line.trim() == "[image]" }
    }
    val visibleText = visibleLines.joinToString("\n").trim()
    return PersistedUserPresentation(
        text = visibleText,
        attachments = attachments,
    )
}

internal fun decodeGatewayMessages(elements: List<JsonElement>): List<ConversationMessage> =
    decodeGatewayConversation(elements).messages

internal fun decodeGatewayConversation(elements: List<JsonElement>): DecodedGatewayConversation {
    val usedIds = mutableSetOf<String>()
    val persistedToolCalls = mutableMapOf<String, PersistedToolCall>()
    var messages = emptyList<ConversationMessage>()
    var taskProgressSnapshot: TaskProgressSnapshot? = null

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
        val persistedText = persistedContentText(row["text"])
            ?: persistedContentText(row["content"])
            ?: persistedContentText(row["context"])
            ?: ""
        val text = visiblePersistedText(row, role, persistedText) ?: return@forEachIndexed

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
                val arguments = runCatching {
                    Json.parseToJsonElement(context) as? JsonObject
                }.getOrNull()
                persistedToolCalls[toolId] = PersistedToolCall(
                    name = name,
                    arguments = arguments,
                    context = context,
                )
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
                    messages = appendCurrentTurnMessage(
                        messages = messages,
                        message = ConversationMessage(
                            role = "assistant",
                            text = segment.text,
                            id = uniqueMessageId(
                                preferred = "$baseIdentity:commentary-$commentaryIndex",
                                fallback = "resume-$index-commentary-$commentaryIndex",
                            ),
                            interim = true,
                        ),
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
            val name = row.string("name") ?: row.string("tool_name") ?: persistedCall?.name ?: "tool"
            val context = row.string("context")
                ?: persistedCall?.context
                ?: row["args"]?.toString().orEmpty()
            val arguments = (row["args"] as? JsonObject) ?: persistedCall?.arguments
            val result = row.string("result")
                ?: row.string("content")
                ?: ""
            val resultObject = runCatching { Json.parseToJsonElement(result) as? JsonObject }.getOrNull()
            if (name == "clarify") {
                messages = completeClarificationInCurrentTurn(
                    messages = messages,
                    toolId = toolId,
                    payload = buildJsonObject {
                        put("result", row["result"] ?: JsonPrimitive(result))
                    },
                )
                return@forEachIndexed
            }
            if (name == "todo") {
                decodeTaskProgressSnapshot(resultObject?.get("todos") as? JsonArray)?.let {
                    taskProgressSnapshot = it
                }
                return@forEachIndexed
            }
            val diff = row.string("inline_diff")
                ?: resultObject?.string("diff")
                ?: ""
            if (isFileEditTool(name) || diff.isNotBlank()) {
                messages = settleCurrentReasoning(messages)
                messages = startFileEditInCurrentTurn(
                    messages = messages,
                    toolId = toolId,
                    paths = fileEditPaths(name, arguments, diff),
                    changesMessageId = "changes:${sourceIdentity ?: "resume-$index"}",
                )
                messages = completeFileEditInCurrentTurn(
                    messages = messages,
                    toolId = toolId,
                    paths = fileEditPaths(name, arguments, diff),
                    diff = diff,
                    summary = row.string("summary").orEmpty(),
                    failed = toolResultFailed(resultObject),
                    changesMessageId = "changes:${sourceIdentity ?: "resume-$index"}",
                )
                return@forEachIndexed
            }
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

        val userPresentation = if (role == "user") persistedUserPresentation(text) else null
        if (text.isBlank() && userPresentation?.attachments.isNullOrEmpty()) return@forEachIndexed
        val message = ConversationMessage(
            role = role,
            text = userPresentation?.text ?: text,
            id = uniqueMessageId(sourceIdentity, "resume-$index"),
            attachments = userPresentation?.attachments.orEmpty(),
        )
        messages = if (role == "user") {
            messages + message
        } else {
            appendCurrentTurnMessage(messages, message)
        }
    }

    return DecodedGatewayConversation(
        messages = messages.map(ConversationMessage::settledSteps),
        taskProgressSnapshot = taskProgressSnapshot,
    )
}

private fun persistedContentText(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull
    is JsonArray -> element.joinToString(separator = "", transform = ::persistedContentPartText)
    else -> null
}

private fun persistedContentPartText(element: JsonElement): String = when (element) {
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonObject -> {
        val type = element.string("type")
        if (type == null || type == "text") element.string("text").orEmpty() else ""
    }
    else -> ""
}

private fun visiblePersistedText(row: JsonObject, role: String, text: String): String? {
    val displayKind = row.string("display_kind")
    if (displayKind == "hidden" || displayKind == "async_delegation_complete") return null
    if (role == "tool") return text

    val metadata = row["metadata"] as? JsonObject
    val flaggedAsSummary = row.boolean("_compressed_summary") == true ||
        metadata?.boolean("_compressed_summary") == true
    val wrappedCarrier = startsWithAfterLeadingWhitespace(text, PRIOR_CONTEXT_WRAPPER_HEADER)
    val delimiterIndex = text.indexOf(COMPACTION_SUMMARY_DELIMITER)
    if (delimiterIndex >= 0 && (flaggedAsSummary || wrappedCarrier)) {
        var visible = text.substring(0, delimiterIndex).trim()
        if (visible.startsWith(PRIOR_CONTEXT_WRAPPER_HEADER)) {
            visible = visible.removePrefix(PRIOR_CONTEXT_WRAPPER_HEADER).trim()
        }
        return visible.takeIf { it.isNotBlank() && !hasCompactionSummaryPrefix(it) }
    }

    if (flaggedAsSummary || isKnownStandaloneCompactionCarrier(text)) return null
    if (role != "user") return text
    val visibleUserText = text.withoutInjectedTodoSnapshot() ?: return null
    return visibleUserText.takeUnless(::isKnownAsyncDelegationCarrier)
}

private fun String.withoutInjectedTodoSnapshot(): String? {
    val markerIndex = lastIndexOf(TODO_INJECTION_HEADER)
    if (markerIndex < 0) return this

    val prefix = substring(0, markerIndex)
    val startsInjectedBlock = markerIndex == 0 ||
        prefix.endsWith("\n\n") ||
        prefix.endsWith("\r\n\r\n")
    if (!startsInjectedBlock) return this

    var itemStart = markerIndex + TODO_INJECTION_HEADER.length
    if (itemStart >= length || (this[itemStart] != '\n' && this[itemStart] != '\r')) return this
    while (itemStart < length && (this[itemStart] == '\n' || this[itemStart] == '\r')) itemStart += 1

    val startsTodoItem = regionMatches(itemStart, "- [>] ", 0, 6) ||
        regionMatches(itemStart, "- [ ] ", 0, 6)
    if (!startsTodoItem) return this

    return prefix.trimEnd().takeIf(String::isNotBlank)
}

private fun isKnownStandaloneCompactionCarrier(text: String): Boolean {
    val headerEnd = leadingHeaderEnd(text, CONTEXT_COMPACTION_REFERENCE_HEADER) ?: return false
    return startsWithAfterWhitespaceAt(
        text = text,
        start = headerEnd,
        prefix = "Earlier turns were compacted into the summary below.",
    )
}

private fun isKnownAsyncDelegationCarrier(text: String): Boolean {
    val start = leadingContentStart(text)
    if (!text.regionMatches(start, ASYNC_DELEGATION_BATCH_HEADER, 0, ASYNC_DELEGATION_BATCH_HEADER.length)) {
        return false
    }
    val headerEnd = text.indexOf(']', startIndex = start).takeIf { it >= 0 } ?: return false
    return startsWithAfterWhitespaceAt(
        text = text,
        start = headerEnd + 1,
        prefix = "A background fan-out of",
    )
}

private fun hasCompactionSummaryPrefix(text: String): Boolean =
    startsWithAfterLeadingWhitespace(text, "[context compaction", ignoreCase = true) ||
        startsWithAfterLeadingWhitespace(text, "[context summary]:", ignoreCase = true)

private fun startsWithAfterLeadingWhitespace(
    text: String,
    prefix: String,
    ignoreCase: Boolean = false,
): Boolean {
    val start = leadingContentStart(text)
    return text.regionMatches(start, prefix, 0, prefix.length, ignoreCase = ignoreCase)
}

private fun leadingHeaderEnd(text: String, header: String): Int? {
    val start = leadingContentStart(text)
    return if (text.regionMatches(start, header, 0, header.length)) start + header.length else null
}

private fun startsWithAfterWhitespaceAt(text: String, start: Int, prefix: String): Boolean {
    var contentStart = start
    while (contentStart < text.length && text[contentStart].isWhitespace()) contentStart += 1
    return text.regionMatches(contentStart, prefix, 0, prefix.length)
}

private fun leadingContentStart(text: String): Int {
    var start = 0
    while (start < text.length && text[start].isWhitespace()) start += 1
    return start
}

private const val COMPACTION_SUMMARY_DELIMITER =
    "[END OF PRIOR CONTEXT — COMPACTION SUMMARY BELOW]"
private const val PRIOR_CONTEXT_WRAPPER_HEADER =
    "[PRIOR CONTEXT — for reference only; not a new message]"
private const val CONTEXT_COMPACTION_REFERENCE_HEADER =
    "[CONTEXT COMPACTION — REFERENCE ONLY]"
private const val ASYNC_DELEGATION_BATCH_HEADER =
    "[ASYNC DELEGATION BATCH COMPLETE —"
private const val TODO_INJECTION_HEADER =
    "[Your active task list was preserved across context compression]"

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

internal fun codePointOffsetToUtf16Index(text: String, codePointOffset: Int): Int? {
    if (codePointOffset < 0) return null
    var codePoints = 0
    var utf16Index = 0
    while (codePoints < codePointOffset) {
        if (utf16Index >= text.length) return null
        val current = text[utf16Index].code
        val hasLowSurrogate = current in 0xD800..0xDBFF &&
            utf16Index + 1 < text.length &&
            text[utf16Index + 1].code in 0xDC00..0xDFFF
        utf16Index += if (hasLowSurrogate) 2 else 1
        codePoints += 1
    }
    return utf16Index
}

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
