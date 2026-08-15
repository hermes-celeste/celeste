package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.InflightCorrection
import dev.hazydreams.hermesceleste.network.ResumedSession

/**
 * The gateway's in-flight snapshot is authoritative for a live turn, but it is
 * not yet durable transcript. Keep its user corrections and queued inputs as
 * pending projection rows and leave the current assistant tail in the
 * streaming slot.
 */
internal data class ResumedTurnProjection(
    val messages: List<ConversationMessage>,
    val streamingText: String,
)

internal fun projectResumedTurn(resumed: ResumedSession): ResumedTurnProjection {
    val messages = resumed.messages.toMutableList()
    val sessionId = resumed.storedSessionId.ifBlank { resumed.runtimeSessionId }
    val existingUserTexts = latestUserRun(messages)
        .map(::normalizedText)
        .toMutableSet()
    val projected = mutableListOf<ConversationMessage>()

    val inflightUser = normalizedText(resumed.inflightUserText)
    val corrections = resumed.inflightCorrections.mapNotNull { correction ->
        val text = normalizedText(correction.text)
        if (text.isBlank()) null else correction.copy(text = text)
    }
    val queued = (
        if (resumed.queuedUserTexts.isNotEmpty()) {
            resumed.queuedUserTexts
        } else if (resumed.queuedUserText.isNotBlank()) {
            listOf(resumed.queuedUserText)
        } else {
            emptyList()
        }
    ).map(::normalizedText).filter(String::isNotBlank)

    fun isPersistedInLatestUserRun(text: String): Boolean =
        normalizedText(text) in existingUserTexts

    if (inflightUser.isNotBlank() && !isPersistedInLatestUserRun(inflightUser)) {
        projected += ConversationMessage(
            role = "user",
            text = inflightUser,
            id = "inflight-user-$sessionId",
            pending = true,
        )
        existingUserTexts += inflightUser
    }

    val assistant = resumed.inflightAssistantText
    val prefixLength = persistedAssistantPrefixLength(assistant, messages)
    val correctionOffsetsUsable = corrections.isNotEmpty() &&
        corrections.size == resumed.correctionOffsets.size &&
        corrections.indices.all { index ->
            val offset = resumed.correctionOffsets[index]
            offset >= 0 && offset <= assistant.length
        }
    val hasProjectedAssistant = messages.any { message ->
        message.role == "assistant" && isProjectedLiveAssistant(message)
    }
    val wantsAssistantRow = assistant.isNotBlank() ||
        resumed.inflightStreaming ||
        resumed.inflightError?.isNotBlank() == true ||
        (inflightUser.isNotBlank() && queued.isNotEmpty())

    var streamingText = ""
    if (!hasProjectedAssistant) {
        if (corrections.isEmpty()) {
            streamingText = unpersistedInflightText(assistant, messages)
        } else if (correctionOffsetsUsable && assistant.isNotBlank()) {
            var cursor = 0
            corrections.forEachIndexed { index, correction ->
                val boundary = resumed.correctionOffsets[index]
                    .coerceIn(cursor, assistant.length)
                val segmentStart = maxOf(cursor, prefixLength)
                if (boundary > segmentStart) {
                    projected += ConversationMessage(
                        role = "assistant",
                        text = assistant.substring(segmentStart, boundary),
                        id = "inflight-assistant-segment-$index-$sessionId",
                        interim = true,
                    )
                }
                appendCorrection(
                    projected = projected,
                    existingUserTexts = existingUserTexts,
                    correction = correction,
                    sessionId = sessionId,
                    index = index,
                    isPersistedInLatestUserRun = ::isPersistedInLatestUserRun,
                )
                cursor = boundary
            }
            val tailStart = maxOf(cursor, prefixLength).coerceAtMost(assistant.length)
            streamingText = assistant.substring(tailStart)
        } else {
            val assistantTail = unpersistedInflightText(assistant, messages)
            if (wantsAssistantRow && (assistantTail.isNotBlank() || resumed.inflightStreaming)) {
                projected += ConversationMessage(
                    role = "assistant",
                    text = assistantTail,
                    id = "inflight-assistant-$sessionId",
                    pending = resumed.inflightStreaming,
                )
            }
            corrections.forEachIndexed { index, correction ->
                appendCorrection(
                    projected = projected,
                    existingUserTexts = existingUserTexts,
                    correction = correction,
                    sessionId = sessionId,
                    index = index,
                    isPersistedInLatestUserRun = ::isPersistedInLatestUserRun,
                )
            }
        }
    } else {
        // A projection can be requested more than once by a reconnecting
        // caller. Generated live rows are already present, so only use the
        // authoritative rows as the source of truth and do not append copies.
        corrections.forEachIndexed { index, correction ->
            appendCorrection(
                projected = projected,
                existingUserTexts = existingUserTexts,
                correction = correction,
                sessionId = sessionId,
                index = index,
                isPersistedInLatestUserRun = ::isPersistedInLatestUserRun,
            )
        }
    }

    // The gateway exposes the accepted next-turn queue separately from the
    // active correction stream. Append it only after the active turn's
    // original input, assistant segments, and corrections, preserving FIFO.
    queued.forEachIndexed { index, text ->
        val id = if (index == 0) {
            "user-queued-$sessionId"
        } else {
            "user-queued-$index-$sessionId"
        }
        if (messages.none { it.id == id } && projected.none { it.id == id }) {
            projected += ConversationMessage(
                role = "user",
                text = text,
                id = id,
                pending = true,
            )
        }
    }

    return ResumedTurnProjection(
        messages = messages + projected,
        streamingText = streamingText,
    )
}

private fun appendCorrection(
    projected: MutableList<ConversationMessage>,
    existingUserTexts: MutableSet<String>,
    correction: InflightCorrection,
    sessionId: String,
    index: Int,
    isPersistedInLatestUserRun: (String) -> Boolean,
) {
    val normalized = normalizedText(correction.text)
    if (normalized.isBlank() ||
        isPersistedInLatestUserRun(normalized) ||
        !existingUserTexts.add(normalized) ||
        projected.any { it.id == "inflight-correction-$index-$sessionId" }
    ) {
        return
    }
    projected += ConversationMessage(
        role = "user",
        text = normalized,
        id = "inflight-correction-$index-$sessionId",
        pending = true,
    )
}

private fun latestUserRun(messages: List<ConversationMessage>): Set<String> {
    val latestUserIndex = messages.indexOfLast { it.role == "user" }
    if (latestUserIndex < 0) return emptySet()

    val texts = mutableSetOf<String>()
    for (index in latestUserIndex downTo 0) {
        when {
            messages[index].role == "user" -> texts += normalizedText(messages[index].text)
            isProjectedLiveAssistant(messages[index]) -> Unit
            else -> break
        }
    }
    return texts.filter(String::isNotBlank).toSet()
}

private fun isProjectedLiveAssistant(message: ConversationMessage): Boolean {
    val id = message.id.orEmpty()
    return id.startsWith("inflight-assistant-") || id.startsWith("assistant-stream-")
}

private fun persistedAssistantPrefixLength(
    inflight: String,
    messages: List<ConversationMessage>,
): Int {
    if (inflight.isBlank()) return 0
    val persisted = messages.asReversed()
        .firstOrNull { it.role == "assistant" && !isProjectedLiveAssistant(it) }
        ?.text
        ?.trim()
        .orEmpty()
    return if (persisted.isNotBlank() && inflight.trimStart().startsWith(persisted)) {
        inflight.indexOf(persisted) + persisted.length
    } else {
        0
    }
}

private fun unpersistedInflightText(
    inflight: String,
    messages: List<ConversationMessage>,
): String {
    val recovered = inflight.trim()
    if (recovered.isEmpty()) return ""
    val persisted = messages.asReversed()
        .firstOrNull { it.role == "assistant" && !isProjectedLiveAssistant(it) }
        ?.text
        ?.trim()
        .orEmpty()
    return if (persisted.isNotEmpty() && recovered.startsWith(persisted)) {
        recovered.removePrefix(persisted).trimStart()
    } else {
        recovered
    }
}

private fun normalizedText(value: String): String = value.trim()
