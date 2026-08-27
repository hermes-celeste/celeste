package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.AssistantContentKind
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.ConversationStep
import dev.hazydreams.hermesceleste.network.ConversationStepKind

internal enum class CommentaryPresentation {
    Transcript,
    Thinking,
}

/**
 * Projects canonical conversation content into its display placement without discarding provenance.
 * Streaming remains outside this settled-message projection until Hermes seals the segment.
 */
internal fun presentConversationMessages(
    messages: List<ConversationMessage>,
    commentaryPresentation: CommentaryPresentation,
): List<ConversationMessage> {
    if (commentaryPresentation == CommentaryPresentation.Transcript) return messages

    return buildList {
        messages.forEachIndexed { index, message ->
            if (
                message.role == "assistant" &&
                message.assistantContentKind == AssistantContentKind.Commentary &&
                message.text.isNotBlank()
            ) {
                appendPresentedMessage(message.asCommentarySteps(index))
                message.asStatusOnlyMessage()?.let(::appendPresentedMessage)
            } else {
                appendPresentedMessage(message)
            }
        }
    }
}

private fun ConversationMessage.asCommentarySteps(index: Int): ConversationMessage {
    val identity = id?.takeIf(String::isNotBlank) ?: "index-$index"
    return ConversationMessage(
        role = "steps",
        text = "",
        id = "steps:commentary:$identity",
        steps = listOf(
            ConversationStep(
                id = "commentary:$identity",
                kind = ConversationStepKind.Commentary,
                detail = text,
            ),
        ),
    )
}

private fun ConversationMessage.asStatusOnlyMessage(): ConversationMessage? {
    val failure = errorMessage ?: return null
    return ConversationMessage(
        role = "assistant",
        text = "",
        id = id,
        assistantContentKind = AssistantContentKind.Unclassified,
        errorMessage = failure,
    )
}

private fun MutableList<ConversationMessage>.appendPresentedMessage(message: ConversationMessage) {
    val previous = lastOrNull()
    if (previous?.role == "steps" && message.role == "steps") {
        this[lastIndex] = previous.copy(
            pending = previous.pending || message.pending,
            steps = previous.steps + message.steps,
        )
    } else {
        add(message)
    }
}
