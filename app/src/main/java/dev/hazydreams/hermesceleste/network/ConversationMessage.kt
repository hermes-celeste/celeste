package dev.hazydreams.hermesceleste.network

enum class ConversationStepKind {
    Reasoning,
    Tool,
}

data class ConversationStep(
    val id: String,
    val kind: ConversationStepKind,
    val detail: String,
    val toolName: String? = null,
    val context: String = "",
    val summary: String = "",
    val result: String = "",
    val pending: Boolean = false,
)

data class ConversationMessage(
    val role: String,
    val text: String,
    val id: String? = null,
    val pending: Boolean = false,
    val interim: Boolean = false,
    val steps: List<ConversationStep> = emptyList(),
)

internal fun appendReasoningToCurrentTurn(
    messages: List<ConversationMessage>,
    id: String,
    text: String,
    replaceTail: Boolean,
    stepsMessageId: String,
): List<ConversationMessage> {
    if (text.isBlank()) return messages
    return updateCurrentTurnSteps(messages, stepsMessageId) { steps ->
        val tail = steps.lastOrNull()
        if (tail?.kind == ConversationStepKind.Reasoning) {
            steps.dropLast(1) + tail.copy(
                detail = if (replaceTail) text else tail.detail + text,
                pending = true,
            )
        } else {
            steps + ConversationStep(
                id = id,
                kind = ConversationStepKind.Reasoning,
                detail = text,
                pending = true,
            )
        }
    }
}

internal fun startToolInCurrentTurn(
    messages: List<ConversationMessage>,
    id: String,
    name: String,
    context: String,
    stepsMessageId: String,
): List<ConversationMessage> = updateCurrentTurnSteps(messages, stepsMessageId) { current ->
    val settled = current.mapIndexed { index, step ->
        if (index == current.lastIndex && step.kind == ConversationStepKind.Reasoning) {
            step.copy(pending = false)
        } else {
            step
        }
    }
    val existingIndex = settled.indexOfFirst { it.kind == ConversationStepKind.Tool && it.id == id }
    val next = ConversationStep(
        id = id,
        kind = ConversationStepKind.Tool,
        detail = context,
        toolName = name,
        context = context,
        pending = true,
    )
    if (existingIndex < 0) {
        settled + next
    } else {
        settled.toMutableList().also { steps ->
            steps[existingIndex] = steps[existingIndex].copy(
                toolName = name,
                detail = context.ifBlank { steps[existingIndex].detail },
                context = context.ifBlank { steps[existingIndex].context },
                pending = true,
            )
        }
    }
}

internal fun completeToolInCurrentTurn(
    messages: List<ConversationMessage>,
    id: String?,
    name: String,
    context: String,
    summary: String,
    result: String,
    fallbackStepId: String,
    stepsMessageId: String,
): List<ConversationMessage> = updateCurrentTurnSteps(messages, stepsMessageId) { current ->
    val existingIndex = current.indexOfFirst { step ->
        step.kind == ConversationStepKind.Tool && when {
            !id.isNullOrBlank() -> step.id == id
            else -> step.toolName == name && step.pending
        }
    }
    if (existingIndex < 0) {
        current + ConversationStep(
            id = id?.takeIf(String::isNotBlank) ?: fallbackStepId,
            kind = ConversationStepKind.Tool,
            detail = summary.ifBlank { context.ifBlank { result } },
            toolName = name,
            context = context,
            summary = summary,
            result = result,
            pending = false,
        )
    } else {
        current.toMutableList().also { steps ->
            val previous = steps[existingIndex]
            steps[existingIndex] = previous.copy(
                detail = summary.ifBlank {
                    previous.context.ifBlank { context.ifBlank { result.ifBlank { previous.detail } } }
                },
                toolName = name,
                context = previous.context.ifBlank { context },
                summary = summary,
                result = result,
                pending = false,
            )
        }
    }
}

internal fun settleCurrentReasoning(messages: List<ConversationMessage>): List<ConversationMessage> {
    val index = currentTurnStepsIndex(messages)
    if (index < 0) return messages
    val message = messages[index]
    val stepIndex = message.steps.indexOfLast { it.kind == ConversationStepKind.Reasoning && it.pending }
    if (stepIndex < 0) return messages
    val steps = message.steps.toMutableList().also { next ->
        next[stepIndex] = next[stepIndex].copy(pending = false)
    }
    return messages.toMutableList().also { next ->
        next[index] = message.copy(steps = steps, pending = steps.any(ConversationStep::pending))
    }
}

internal fun settleCurrentTurnSteps(messages: List<ConversationMessage>): List<ConversationMessage> {
    val index = currentTurnStepsIndex(messages)
    if (index < 0) return messages
    val message = messages[index]
    if (!message.pending && message.steps.none(ConversationStep::pending)) return messages
    return messages.toMutableList().also { next ->
        next[index] = message.copy(
            pending = false,
            steps = message.steps.map { it.copy(pending = false) },
        )
    }
}

private fun updateCurrentTurnSteps(
    messages: List<ConversationMessage>,
    stepsMessageId: String,
    transform: (List<ConversationStep>) -> List<ConversationStep>,
): List<ConversationMessage> {
    val index = currentTurnStepsIndex(messages)
    if (index >= 0) {
        val previous = messages[index]
        val steps = transform(previous.steps)
        return messages.toMutableList().also { next ->
            next[index] = previous.copy(steps = steps, pending = steps.any(ConversationStep::pending))
        }
    }

    val steps = transform(emptyList())
    if (steps.isEmpty()) return messages
    return messages + ConversationMessage(
        role = "steps",
        text = "",
        id = stepsMessageId,
        pending = steps.any(ConversationStep::pending),
        steps = steps,
    )
}

private fun currentTurnStepsIndex(messages: List<ConversationMessage>): Int {
    val turnStart = messages.indexOfLast { it.role == "user" }
    for (index in messages.lastIndex downTo (turnStart + 1)) {
        if (messages[index].role == "steps") return index
    }
    return -1
}
