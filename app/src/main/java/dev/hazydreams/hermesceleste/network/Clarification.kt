package dev.hazydreams.hermesceleste.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val RECOMMENDED_SUFFIX = "(Recommended)"
private const val MAX_CLARIFICATION_CHOICES = 4
private const val MAX_CLARIFICATION_CHOICE_LENGTH = 200

data class ClarificationExchange(
    val requestId: String? = null,
    val question: String,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    val answer: String? = null,
    val submitting: Boolean = false,
)

internal fun clarificationFromToolArguments(arguments: JsonObject?): ClarificationExchange? {
    val question = arguments?.string("question")?.trim().orEmpty()
    if (question.isBlank()) return null
    val choices = normalizedClarificationChoices(arguments?.get("choices"))
    return ClarificationExchange(
        question = question,
        choices = choices,
        multiSelect = arguments?.boolean("multi_select") == true && choices.isNotEmpty(),
    )
}

internal fun clarificationFromRequest(payload: JsonObject): ClarificationExchange? {
    val requestId = payload.string("request_id")?.takeIf(String::isNotBlank) ?: return null
    val question = payload.string("question")?.trim().orEmpty()
    if (question.isBlank()) return null
    val choices = normalizedClarificationChoices(payload["choices"])
    return ClarificationExchange(
        requestId = requestId,
        question = question,
        choices = choices,
        multiSelect = payload.boolean("multi_select") == true && choices.isNotEmpty(),
    )
}

internal fun startClarificationInCurrentTurn(
    messages: List<ConversationMessage>,
    toolId: String,
    arguments: JsonObject?,
): List<ConversationMessage> {
    val clarification = clarificationFromToolArguments(arguments) ?: return messages
    val messageId = "clarify:$toolId"
    val existingIndex = messages.indexOfFirst { it.id == messageId }
    if (existingIndex >= 0 && !messages[existingIndex].pending) return messages
    val message = ConversationMessage(
        role = "clarification",
        text = "",
        id = messageId,
        pending = true,
        clarification = clarification,
    )
    return if (existingIndex < 0) {
        appendCurrentTurnMessage(messages, message)
    } else {
        messages.toMutableList().also { it[existingIndex] = message }
    }
}

internal fun bindClarificationRequest(
    messages: List<ConversationMessage>,
    request: ClarificationExchange,
): List<ConversationMessage> {
    val settledMatch = messages.any { message ->
        message.role == "clarification" &&
            !message.pending &&
            message.clarification?.requestId == request.requestId
    }
    if (settledMatch) return messages

    val existingIndex = messages.indexOfLast { message ->
        message.role == "clarification" && message.pending && when {
            message.clarification?.requestId == request.requestId -> true
            message.clarification?.requestId == null ->
                message.clarification?.question == request.question
            else -> false
        }
    }
    val nextMessage = if (existingIndex >= 0) {
        messages[existingIndex].copy(
            pending = true,
            clarification = request,
        )
    } else {
        ConversationMessage(
            role = "clarification",
            text = "",
            id = "clarify-request:${request.requestId}",
            pending = true,
            clarification = request,
        )
    }
    return if (existingIndex < 0) {
        appendCurrentTurnMessage(messages, nextMessage)
    } else {
        messages.toMutableList().also { it[existingIndex] = nextMessage }
    }
}

internal fun completeClarificationInCurrentTurn(
    messages: List<ConversationMessage>,
    toolId: String?,
    payload: JsonObject,
): List<ConversationMessage> {
    val preferredId = toolId?.takeIf(String::isNotBlank)?.let { "clarify:$it" }
    val existingIndex = messages.indexOfFirst { it.id == preferredId }
        .takeIf { it >= 0 }
        ?: messages.indexOfLast { it.role == "clarification" && it.pending }
            .takeIf { it >= 0 }
        ?: messages.indexOfLast { message ->
            message.role == "clarification" && message.clarification?.requestId != null
        }
    val previous = existingIndex.takeIf { it >= 0 }?.let(messages::get)
    val result = clarificationResult(payload, previous?.clarification) ?: return messages
    val nextMessage = (previous ?: ConversationMessage(
        role = "clarification",
        text = "",
        id = preferredId ?: "clarify-result:${result.question.hashCode()}",
    )).copy(
        pending = false,
        clarification = result.copy(submitting = false),
    )
    return if (existingIndex < 0) {
        appendCurrentTurnMessage(messages, nextMessage)
    } else {
        messages.toMutableList().also { it[existingIndex] = nextMessage }
    }
}

internal fun markClarificationSubmitting(
    messages: List<ConversationMessage>,
    messageId: String,
    requestId: String,
): List<ConversationMessage> = messages.updateClarification(messageId, requestId) { clarification ->
    clarification.copy(submitting = true)
}

internal fun settleClarificationLocally(
    messages: List<ConversationMessage>,
    messageId: String,
    requestId: String,
    answer: String,
): List<ConversationMessage> = messages.updateClarification(messageId, requestId) { clarification ->
    clarification.copy(
        answer = displayClarificationAnswer(answer),
        submitting = false,
    )
}.map { message ->
    if (message.id == messageId && message.clarification?.requestId == requestId) {
        message.copy(pending = false)
    } else {
        message
    }
}

internal fun resetClarificationSubmission(
    messages: List<ConversationMessage>,
    messageId: String,
    requestId: String,
): List<ConversationMessage> = messages.updateClarification(messageId, requestId) { clarification ->
    clarification.copy(submitting = false)
}

internal fun clearUnansweredClarifications(messages: List<ConversationMessage>): List<ConversationMessage> =
    messages.filterNot { message ->
        message.role == "clarification" && message.pending && message.clarification?.answer == null
    }

internal fun clarificationChoiceLabel(choice: String): String =
    if (choice.trim().endsWith(RECOMMENDED_SUFFIX, ignoreCase = true)) {
        choice.trim().dropLast(RECOMMENDED_SUFFIX.length).trim()
    } else {
        choice
    }

internal fun clarificationChoiceIsRecommended(choice: String): Boolean =
    choice.trim().endsWith(RECOMMENDED_SUFFIX, ignoreCase = true)

internal fun encodeClarificationChoices(choices: List<String>): String = Json.encodeToString(choices)

private fun List<ConversationMessage>.updateClarification(
    messageId: String,
    requestId: String,
    transform: (ClarificationExchange) -> ClarificationExchange,
): List<ConversationMessage> = map { message ->
    val clarification = message.clarification
    if (message.id == messageId && clarification?.requestId == requestId) {
        message.copy(clarification = transform(clarification))
    } else {
        message
    }
}

private fun clarificationResult(
    payload: JsonObject,
    fallback: ClarificationExchange?,
): ClarificationExchange? {
    val result = payload["result"].jsonObjectOrNull()
        ?: payload["result"].jsonObjectFromString()
        ?: payload.string("result_text")?.let(::jsonObjectFromText)
        ?: return fallback?.copy(answer = payload.string("result_text").orEmpty())
    val question = result.string("question")?.trim().orEmpty()
        .ifBlank { fallback?.question.orEmpty() }
    if (question.isBlank()) return null
    val choices = normalizedClarificationChoices(result["choices_offered"])
        .ifEmpty { fallback?.choices.orEmpty() }
    val rawAnswer = result["user_response"] ?: result["answer"]
    return ClarificationExchange(
        requestId = fallback?.requestId,
        question = question,
        choices = choices,
        multiSelect = fallback?.multiSelect == true || rawAnswer is JsonArray,
        answer = displayClarificationAnswer(rawAnswer),
    )
}

private fun normalizedClarificationChoices(element: JsonElement?): List<String> =
    (element as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        .filter { choice ->
            choice.isNotBlank() &&
                '\n' !in choice &&
                clarificationChoiceLabel(choice).length <= MAX_CLARIFICATION_CHOICE_LENGTH
        }
        .take(MAX_CLARIFICATION_CHOICES)

private fun displayClarificationAnswer(element: JsonElement?): String = when (element) {
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .map(::clarificationChoiceLabel)
        .joinToString(separator = ", ")
    is JsonPrimitive -> clarificationChoiceLabel(element.contentOrNull.orEmpty())
    null -> ""
    else -> element.toString()
}

private fun displayClarificationAnswer(answer: String): String {
    val parsed = runCatching { Json.parseToJsonElement(answer) }.getOrNull()
    return if (parsed is JsonArray) {
        displayClarificationAnswer(parsed)
    } else {
        clarificationChoiceLabel(answer)
    }
}

private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.jsonObjectFromString(): JsonObject? =
    (this as? JsonPrimitive)?.contentOrNull?.let(::jsonObjectFromText)

private fun jsonObjectFromText(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
