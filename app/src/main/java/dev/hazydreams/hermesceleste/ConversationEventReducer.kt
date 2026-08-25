package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.ConversationStepKind
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.TaskProgress
import dev.hazydreams.hermesceleste.network.UserMessagePlacement
import dev.hazydreams.hermesceleste.network.appendCurrentTurnMessage
import dev.hazydreams.hermesceleste.network.appendReasoningToCurrentTurn
import dev.hazydreams.hermesceleste.network.bindClarificationRequest
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.clarificationFromRequest
import dev.hazydreams.hermesceleste.network.clearUnansweredClarifications
import dev.hazydreams.hermesceleste.network.completeClarificationInCurrentTurn
import dev.hazydreams.hermesceleste.network.completeFileEditInCurrentTurn
import dev.hazydreams.hermesceleste.network.completeToolInCurrentTurn
import dev.hazydreams.hermesceleste.network.currentTurnContentTailIndex
import dev.hazydreams.hermesceleste.network.fileEditDiff
import dev.hazydreams.hermesceleste.network.fileEditPaths
import dev.hazydreams.hermesceleste.network.isFileEditTool
import dev.hazydreams.hermesceleste.network.parseBackgroundProcessResult
import dev.hazydreams.hermesceleste.network.settleCurrentReasoning
import dev.hazydreams.hermesceleste.network.settleCurrentTurnSteps
import dev.hazydreams.hermesceleste.network.startClarificationInCurrentTurn
import dev.hazydreams.hermesceleste.network.startFileEditInCurrentTurn
import dev.hazydreams.hermesceleste.network.startToolInCurrentTurn
import dev.hazydreams.hermesceleste.network.string
import dev.hazydreams.hermesceleste.network.taskProgressSnapshotFromPayload
import dev.hazydreams.hermesceleste.network.toolArguments
import dev.hazydreams.hermesceleste.network.toolCompletionFailed
import dev.hazydreams.hermesceleste.network.upsertBackgroundProcessResult

internal data class ConversationProjection(
    val messages: List<ConversationMessage>,
    val streamingText: String,
    val turnState: TurnState,
    val isCompacting: Boolean,
    val taskProgress: TaskProgress?,
    val errorMessage: String?,
)

internal data class ConversationEventReduction(
    val projection: ConversationProjection,
    val localMessageCounter: Long,
)

private val COMPACTION_RESUME_EVENT_TYPES = setOf(
    "message.start",
    "message.delta",
    "message.interim",
    "thinking.delta",
    "reasoning.delta",
    "reasoning.available",
    "tool.start",
    "tool.progress",
    "tool.generating",
    "tool.complete",
    "clarify.request",
    "moa.reference",
    "moa.aggregating",
    "moa.progress",
    "moa.phase",
)

internal fun reduceConversationEvent(
    projection: ConversationProjection,
    event: GatewayEvent,
    localMessageCounter: Long,
): ConversationEventReduction {
    var counter = localMessageCounter
    fun nextLocalMessageId(prefix: String): String {
        counter += 1
        return "$prefix-$counter"
    }

    var next = projection
    if (event.type in COMPACTION_RESUME_EVENT_TYPES && next.isCompacting) {
        next = next.copy(isCompacting = false)
    }
    next = when (event.type) {
        "status.update" -> when (event.payload.string("kind")) {
            "compacting" -> next.copy(isCompacting = true)
            "compacted" -> next.copy(isCompacting = false)
            "process" -> event.payload.string("text")
                ?.let(::parseBackgroundProcessResult)
                ?.let { result ->
                    next.copy(messages = upsertBackgroundProcessResult(next.messages, result))
                }
                ?: next
            else -> next
        }

        "message.start" -> {
            if (next.streamingText.isNotBlank()) next = next.finalizeAssistant()
            next.copy(
                streamingText = "",
                turnState = TurnState.Running,
                errorMessage = null,
            )
        }

        "message.delta" -> {
            val delta = event.payload.string("text").orEmpty()
            if (delta.isEmpty()) {
                next
            } else {
                next.copy(
                    messages = settleCurrentTurnSteps(next.messages),
                    streamingText = next.streamingText + delta,
                    turnState = TurnState.Running,
                )
            }
        }

        "message.interim" -> {
            val text = event.payload.string("text").orEmpty()
            val alreadyStreamed = event.payload.boolean("already_streamed") == true
            next = next.copy(messages = settleCurrentTurnSteps(next.messages))
            when {
                alreadyStreamed && next.streamingText.isNotBlank() -> next.finalizeAssistant(
                    text.ifBlank { next.streamingText },
                    keepRunning = true,
                    interim = true,
                )
                text.isNotBlank() -> next.finalizeAssistant(text, keepRunning = true, interim = true)
                else -> next
            }
        }

        "message.complete" -> {
            val status = event.payload.string("status")
            val content = event.payload.string("text")
                ?: event.payload.string("content")
                ?: event.payload.string("rendered")
                ?: ""
            val echoedStreamingAnswer = next.streamingText.trimEnd().let { streamed ->
                streamed.isNotBlank() && streamed == content.trimEnd()
            } || next.hasInterimAssistantEcho(content)
            if (echoedStreamingAnswer) {
                next = next.removeTrailingReasoningEcho(content)
            }
            next = next.finalizeAssistant(content, keepRunning = false)
            next = next.removeReasoningEchoOfFinalAnswer(echoedStreamingAnswer)
            next.copy(
                messages = clearUnansweredClarifications(settleCurrentTurnSteps(next.messages)),
                turnState = TurnState.Idle,
                isCompacting = false,
                errorMessage = if (status == "error") {
                    event.payload.string("error") ?: "Hermes could not finish that response."
                } else {
                    next.errorMessage
                },
            ).clearActiveTaskProgress()
        }

        "error", "message.error" -> {
            next = next.finalizeAssistant(keepRunning = false)
            next.copy(
                messages = clearUnansweredClarifications(settleCurrentTurnSteps(next.messages)),
                turnState = TurnState.Idle,
                isCompacting = false,
                errorMessage = event.payload.string("message") ?: "Hermes reported an error.",
            ).clearActiveTaskProgress()
        }

        "message.interrupted", "session.interrupted" -> {
            next = next.finalizeAssistant(keepRunning = false)
            next.copy(
                messages = clearUnansweredClarifications(settleCurrentTurnSteps(next.messages)),
                turnState = TurnState.Idle,
                isCompacting = false,
            ).clearActiveTaskProgress()
        }

        "session.busy" -> {
            val running = event.payload.boolean("busy") == true
            next.copy(
                turnState = if (running) TurnState.Running else TurnState.Idle,
                isCompacting = next.isCompacting && running,
            )
        }

        "session.info" -> event.payload.boolean("running")?.let { running ->
            next.copy(
                turnState = if (running) TurnState.Running else TurnState.Idle,
                isCompacting = next.isCompacting && running,
            )
        } ?: next

        "clarify.request" -> clarificationFromRequest(event.payload)?.let { request ->
            next.copy(
                messages = bindClarificationRequest(
                    messages = settleCurrentTurnSteps(next.messages),
                    request = request,
                ),
                turnState = TurnState.Running,
            )
        } ?: next

        "reasoning.delta", "reasoning.available" -> {
            val text = event.payload.string("text").orEmpty()
            if (text.isEmpty()) {
                next
            } else {
                if (next.streamingText.isNotBlank()) {
                    next = next.finalizeAssistant(keepRunning = true, interim = true)
                }
                next.copy(
                    messages = appendReasoningToCurrentTurn(
                        messages = next.messages,
                        id = nextLocalMessageId("reasoning"),
                        text = text,
                        replaceTail = event.type == "reasoning.available",
                        stepsMessageId = nextLocalMessageId("steps"),
                    ),
                    turnState = TurnState.Running,
                )
            }
        }

        "tool.start" -> {
            if (next.streamingText.isNotBlank()) {
                next = next.finalizeAssistant(keepRunning = true)
            }
            val name = event.payload.string("name") ?: "tool"
            val context = event.payload.string("args_text")
                ?: event.payload.string("context")
                ?: event.payload["args"]?.toString().orEmpty()
            val toolId = event.payload.string("tool_id")
                ?: event.payload.string("tool_call_id")
                ?: nextLocalMessageId("tool")
            when {
                name == "clarify" -> next.copy(
                    messages = startClarificationInCurrentTurn(
                        messages = settleCurrentTurnSteps(next.messages),
                        toolId = toolId,
                        arguments = toolArguments(event.payload),
                    ),
                    turnState = TurnState.Running,
                )
                name == "todo" -> taskProgressSnapshotFromPayload(event.payload)?.let { snapshot ->
                    next.copy(taskProgress = snapshot.progress, turnState = TurnState.Running)
                } ?: next.copy(turnState = TurnState.Running)
                isFileEditTool(name) -> next.copy(
                    messages = startFileEditInCurrentTurn(
                        messages = settleCurrentReasoning(next.messages),
                        toolId = toolId,
                        paths = fileEditPaths(name, toolArguments(event.payload)),
                        changesMessageId = nextLocalMessageId("changes"),
                    ),
                    turnState = TurnState.Running,
                )
                else -> next.copy(
                    messages = startToolInCurrentTurn(
                        messages = next.messages,
                        id = toolId,
                        name = name,
                        context = context,
                        stepsMessageId = nextLocalMessageId("steps"),
                    ),
                    turnState = TurnState.Running,
                )
            }
        }

        "tool.progress" -> {
            val name = event.payload.string("name")
            val isTodo = name == "todo" || (name == null && "todos" in event.payload)
            if (isTodo) {
                taskProgressSnapshotFromPayload(event.payload)?.let { snapshot ->
                    next.copy(taskProgress = snapshot.progress, turnState = TurnState.Running)
                } ?: next
            } else {
                next
            }
        }

        "tool.complete" -> {
            val explicitName = event.payload.string("name")
            val name = explicitName ?: "tool"
            val explicitToolId = event.payload.string("tool_id")
                ?: event.payload.string("tool_call_id")
            val diff = fileEditDiff(event.payload)
            when {
                explicitName == "clarify" -> next.copy(
                    messages = completeClarificationInCurrentTurn(
                        messages = next.messages,
                        toolId = explicitToolId,
                        payload = event.payload,
                    ),
                )
                explicitName == "todo" || (explicitName == null && "todos" in event.payload) ->
                    taskProgressSnapshotFromPayload(event.payload)?.let { snapshot ->
                        next.copy(taskProgress = snapshot.progress)
                } ?: next
                isFileEditTool(name) || diff.isNotBlank() -> {
                    val toolId = explicitToolId ?: nextLocalMessageId("tool")
                    val args = toolArguments(event.payload)
                    next.copy(
                        messages = completeFileEditInCurrentTurn(
                            messages = settleCurrentReasoning(next.messages),
                            toolId = toolId,
                            paths = fileEditPaths(name, args, diff),
                            diff = diff,
                            summary = event.payload.string("summary").orEmpty(),
                            failed = toolCompletionFailed(event.payload),
                            changesMessageId = nextLocalMessageId("changes"),
                        ),
                    )
                }
                else -> {
                    val context = event.payload.string("args_text")
                        ?: event.payload.string("context")
                        ?: event.payload["args"]?.toString().orEmpty()
                    val summary = event.payload.string("summary").orEmpty()
                    val result = event.payload.string("result_text")
                        ?: event.payload.string("result")
                        ?: event.payload["result"]?.toString().orEmpty()
                    next.copy(
                        messages = completeToolInCurrentTurn(
                            messages = next.messages,
                            id = explicitToolId,
                            name = name,
                            context = context,
                            summary = summary,
                            result = result,
                            fallbackStepId = nextLocalMessageId("tool"),
                            stepsMessageId = nextLocalMessageId("steps"),
                        ),
                    )
                }
            }
        }

        else -> next
    }
    return ConversationEventReduction(next, counter)
}

private fun ConversationProjection.clearActiveTaskProgress(): ConversationProjection =
    if (taskProgress?.isActive == true) copy(taskProgress = null) else this

private fun ConversationProjection.hasInterimAssistantEcho(content: String): Boolean {
    val finalText = content.trimEnd()
    if (finalText.isBlank()) return false
    val turnStart = messages.indexOfLast { it.role == "user" }
    return messages.indices.any { index ->
        index > turnStart &&
            messages[index].role == "assistant" &&
            messages[index].interim &&
            messages[index].text.trimEnd() == finalText
    }
}

private fun ConversationProjection.finalizeAssistant(
    suppliedContent: String = "",
    keepRunning: Boolean = turnState == TurnState.Running,
    interim: Boolean = false,
): ConversationProjection {
    val latestUserIndex = messages.indexOfLast { it.role == "user" }
    val latestUserPlacement = messages.getOrNull(latestUserIndex)?.userPlacement
    val correctionIndex = when (latestUserPlacement) {
        UserMessagePlacement.MidTurnCorrection -> latestUserIndex
        UserMessagePlacement.NextTurn -> latestMidTurnCorrectionIndex(messages, latestUserIndex)
        else -> -1
    }
    val adjustedSupplied = if (correctionIndex >= 0) {
        redirectedAssistantSuffix(suppliedContent, messages, correctionIndex)
    } else {
        suppliedContent
    }
    val finalText = mergedFinalText(adjustedSupplied, streamingText)
    val nextMessages = if (latestUserPlacement == UserMessagePlacement.NextTurn) {
        finalizeAssistantBeforeNextTurn(messages, latestUserIndex, finalText)
    } else {
        val previousIndex = currentTurnLastAssistantIndex(messages)
        val previous = messages.getOrNull(previousIndex)
        val continuesInterim = !interim &&
            previous?.role == "assistant" &&
            previous.interim &&
            finalText.isNotBlank() &&
            (finalText.startsWith(previous.text) || previous.text.startsWith(finalText))
        when {
            continuesInterim -> messages.toMutableList().also { next ->
                next[previousIndex] = previous.copy(
                    text = if (finalText.length >= previous.text.length) finalText else previous.text,
                    interim = false,
                )
            }
            finalText.isNotBlank() && previous?.let { it.role == "assistant" && it.text == finalText } != true ->
                appendCurrentTurnMessage(
                    messages = messages,
                    message = ConversationMessage(
                        role = "assistant",
                        text = finalText,
                        interim = interim,
                    ),
                )
            else -> messages
        }
    }
    return copy(
        messages = nextMessages,
        streamingText = "",
        turnState = if (keepRunning) TurnState.Running else TurnState.Idle,
    )
}

private fun mergedFinalText(suppliedContent: String, streamingText: String): String = when {
    suppliedContent.isBlank() -> streamingText
    streamingText.isBlank() -> suppliedContent
    suppliedContent.startsWith(streamingText) -> suppliedContent
    streamingText.startsWith(suppliedContent) -> streamingText
    else -> suppliedContent
}.trimEnd()

private fun latestMidTurnCorrectionIndex(
    messages: List<ConversationMessage>,
    beforeUserIndex: Int,
): Int {
    if (beforeUserIndex <= 0) return -1
    val turnStart = messages.subList(0, beforeUserIndex).indexOfLast { message ->
        message.role == "user" && message.userPlacement == UserMessagePlacement.Prompt
    }
    return (beforeUserIndex - 1 downTo turnStart + 1).firstOrNull { index ->
        messages[index].role == "user" &&
            messages[index].userPlacement == UserMessagePlacement.MidTurnCorrection
    } ?: -1
}

private fun redirectedAssistantSuffix(
    suppliedContent: String,
    messages: List<ConversationMessage>,
    correctionIndex: Int,
): String {
    if (suppliedContent.isBlank() || correctionIndex < 0) return suppliedContent
    val turnStart = messages.subList(0, correctionIndex).indexOfLast { message ->
        message.role == "user" && message.userPlacement == UserMessagePlacement.Prompt
    }
    val sealedPrefix = messages.subList(turnStart + 1, correctionIndex)
        .filter { it.role == "assistant" }
        .joinToString(separator = "", transform = ConversationMessage::text)
    return if (sealedPrefix.isNotBlank() && suppliedContent.startsWith(sealedPrefix)) {
        suppliedContent.removePrefix(sealedPrefix).trimStart()
    } else {
        suppliedContent
    }
}

private fun finalizeAssistantBeforeNextTurn(
    messages: List<ConversationMessage>,
    nextTurnUserIndex: Int,
    finalText: String,
): List<ConversationMessage> {
    if (nextTurnUserIndex < 0) return messages
    val markerId = messages[nextTurnUserIndex].id
    val previousUserIndex = messages.subList(0, nextTurnUserIndex).indexOfLast { it.role == "user" }
    val previousAssistantIndex = (nextTurnUserIndex - 1 downTo previousUserIndex + 1)
        .firstOrNull { messages[it].role == "assistant" }
    val previous = previousAssistantIndex?.let(messages::get)
    val completionInsertionIndex = currentTurnContentTailIndex(messages.subList(0, nextTurnUserIndex)) + 1
    val finalContinuesInterim = previous?.takeIf { it.interim }?.text?.let { interimText ->
        finalText == interimText ||
            finalText.startsWith(interimText) ||
            interimText.startsWith(finalText)
    } == true
    val nextMessages = when {
        finalText.isBlank() -> messages
        finalContinuesInterim -> messages.toMutableList().also { next ->
            next[previousAssistantIndex] = previous.copy(
                text = if (finalText.length >= previous.text.length) finalText else previous.text,
                interim = false,
            )
        }
        previous?.text == finalText -> messages
        else -> messages.toMutableList().also { next ->
            next.add(
                completionInsertionIndex,
                ConversationMessage(role = "assistant", text = finalText),
            )
        }
    }
    val markerIndex = nextMessages.indexOfFirst { message ->
        markerId != null && message.id == markerId
    }.takeIf { it >= 0 } ?: nextMessages.indexOfLast { message ->
        message.role == "user" && message.userPlacement == UserMessagePlacement.NextTurn
    }
    if (markerIndex < 0) return nextMessages
    return nextMessages.toMutableList().also { next ->
        next[markerIndex] = next[markerIndex].copy(userPlacement = UserMessagePlacement.Prompt)
    }
}

private fun currentTurnLastAssistantIndex(messages: List<ConversationMessage>): Int {
    val userIndex = messages.indexOfLast { it.role == "user" }
    val contentTail = currentTurnContentTailIndex(messages)
    return contentTail.takeIf { index ->
        index > userIndex && messages[index].role == "assistant"
    } ?: -1
}

private fun ConversationProjection.removeReasoningEchoOfFinalAnswer(
    echoedStreamingAnswer: Boolean,
): ConversationProjection {
    if (!echoedStreamingAnswer) return this
    val finalIndex = currentTurnLastAssistantIndex(messages)
    val finalText = messages.getOrNull(finalIndex)
        ?.takeIf { it.role == "assistant" }
        ?.text
        ?.trimEnd()
        .orEmpty()
    if (finalText.isBlank()) return this
    val userIndex = messages.indexOfLast { it.role == "user" }
    val stepsIndex = (messages.lastIndex downTo userIndex + 1)
        .firstOrNull { messages[it].role == "steps" }
        ?: return this
    val stepsMessage = messages[stepsIndex]
    val hasEcho = stepsMessage.steps.lastOrNull()?.let { step ->
        step.kind == ConversationStepKind.Reasoning && step.detail.trimEnd() == finalText
    } == true
    if (!hasEcho) return this
    val nextSteps = stepsMessage.steps.dropLast(1)
    val nextMessages = messages.toMutableList().also { next ->
        if (nextSteps.isEmpty()) {
            next.removeAt(stepsIndex)
        } else {
            next[stepsIndex] = stepsMessage.copy(steps = nextSteps)
        }
    }
    return copy(messages = nextMessages)
}

private fun ConversationProjection.removeTrailingReasoningEcho(finalAnswer: String): ConversationProjection {
    val finalText = finalAnswer.trimEnd()
    if (finalText.isBlank()) return this
    val userIndex = messages.indexOfLast { it.role == "user" }
    val stepsIndex = currentTurnContentTailIndex(messages)
        .takeIf { index -> index > userIndex && messages[index].role == "steps" }
        ?: return this
    val stepsMessage = messages[stepsIndex]
    val hasEcho = stepsMessage.steps.lastOrNull()?.let { step ->
        step.kind == ConversationStepKind.Reasoning && step.detail.trimEnd() == finalText
    } == true
    if (!hasEcho) return this
    val nextSteps = stepsMessage.steps.dropLast(1)
    return copy(
        messages = messages.toMutableList().also { next ->
            if (nextSteps.isEmpty()) {
                next.removeAt(stepsIndex)
            } else {
                next[stepsIndex] = stepsMessage.copy(steps = nextSteps)
            }
        },
    )
}
