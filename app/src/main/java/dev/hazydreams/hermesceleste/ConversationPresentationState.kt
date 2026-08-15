package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage

/**
 * The active-turn action contract consumed by DF-05. DF-06 can replace the
 * interrupt-only default with steering or queueing without changing the
 * conversation surface or inventing protocol behavior here.
 */
internal enum class ActiveTurnAction {
    StopResponse,
    SteerWithMessage,
    QueueMessage,
    Unavailable,
}

internal data class ConversationActionModel(
    /** Action selected by the authoritative capability/ordering layer. */
    val activeTurn: ActiveTurnAction = ActiveTurnAction.StopResponse,
)

/**
 * Incrementally maintained candidates for the activity selector. The selector
 * must not walk the transcript during streaming recompositions; reconciliation
 * may rebuild these once from the authoritative snapshot.
 */
internal data class ConversationActivityCandidate(
    val index: Int,
    val identity: String? = null,
    val displayName: String? = null,
)

internal data class ConversationActivityCandidates(
    val pendingTool: ConversationActivityCandidate? = null,
    val interimAssistant: ConversationActivityCandidate? = null,
)

internal fun activityCandidatesFor(
    messages: List<ConversationMessage>,
): ConversationActivityCandidates {
    val pendingToolIndex = messages.indexOfLast { message ->
        message.role == "tool" && message.pending
    }
    val interimAssistantIndex = messages.indexOfLast { message ->
        message.role == "assistant" && message.interim && message.text.isNotBlank()
    }
    return ConversationActivityCandidates(
        pendingTool = messages.getOrNull(pendingToolIndex)?.let { message ->
            ConversationActivityCandidate(
                index = pendingToolIndex,
                identity = message.id,
                displayName = message.toolName,
            )
        },
        interimAssistant = messages.getOrNull(interimAssistantIndex)?.let { message ->
            ConversationActivityCandidate(
                index = interimAssistantIndex,
                identity = message.id,
            )
        },
    )
}
