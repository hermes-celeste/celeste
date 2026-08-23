package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.appendReasoningToCurrentTurn
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.completeToolInCurrentTurn
import dev.hazydreams.hermesceleste.network.parseBackgroundProcessResult
import dev.hazydreams.hermesceleste.network.settleCurrentReasoning
import dev.hazydreams.hermesceleste.network.settleCurrentTurnSteps
import dev.hazydreams.hermesceleste.network.startToolInCurrentTurn
import dev.hazydreams.hermesceleste.network.string
import dev.hazydreams.hermesceleste.network.upsertBackgroundProcessResult

internal data class ConversationProjection(
    val messages: List<ConversationMessage>,
    val streamingText: String,
    val turnState: TurnState,
    val isCompacting: Boolean,
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
                    messages = settleCurrentReasoning(next.messages),
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
            next = next.finalizeAssistant(content, keepRunning = false)
            next.copy(
                messages = settleCurrentTurnSteps(next.messages),
                turnState = TurnState.Idle,
                isCompacting = false,
                errorMessage = if (status == "error") {
                    event.payload.string("error") ?: "Hermes could not finish that response."
                } else {
                    next.errorMessage
                },
            )
        }

        "error", "message.error" -> {
            next = next.finalizeAssistant(keepRunning = false)
            next.copy(
                messages = settleCurrentTurnSteps(next.messages),
                turnState = TurnState.Idle,
                isCompacting = false,
                errorMessage = event.payload.string("message") ?: "Hermes reported an error.",
            )
        }

        "message.interrupted", "session.interrupted" -> {
            next = next.finalizeAssistant(keepRunning = false)
            next.copy(
                messages = settleCurrentTurnSteps(next.messages),
                turnState = TurnState.Idle,
                isCompacting = false,
            )
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
            next.copy(
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

        "tool.complete" -> {
            val name = event.payload.string("name") ?: "tool"
            val toolId = event.payload.string("tool_id")
                ?: event.payload.string("tool_call_id")
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
                    id = toolId,
                    name = name,
                    context = context,
                    summary = summary,
                    result = result,
                    fallbackStepId = nextLocalMessageId("tool"),
                    stepsMessageId = nextLocalMessageId("steps"),
                ),
            )
        }

        else -> next
    }
    return ConversationEventReduction(next, counter)
}

private fun ConversationProjection.finalizeAssistant(
    suppliedContent: String = "",
    keepRunning: Boolean = turnState == TurnState.Running,
    interim: Boolean = false,
): ConversationProjection {
    val finalText = when {
        suppliedContent.isBlank() -> streamingText
        streamingText.isBlank() -> suppliedContent
        suppliedContent.startsWith(streamingText) -> suppliedContent
        streamingText.startsWith(suppliedContent) -> streamingText
        else -> suppliedContent
    }.trimEnd()
    val previous = messages.lastOrNull()
    val continuesInterim = !interim &&
        previous?.role == "assistant" &&
        previous.interim &&
        finalText.isNotBlank() &&
        (finalText.startsWith(previous.text) || previous.text.startsWith(finalText))
    val nextMessages = when {
        continuesInterim -> messages.dropLast(1) + previous.copy(
            text = if (finalText.length >= previous.text.length) finalText else previous.text,
            interim = false,
        )
        finalText.isNotBlank() && previous?.let { it.role == "assistant" && it.text == finalText } != true ->
            messages + ConversationMessage(
                role = "assistant",
                text = finalText,
                interim = interim,
            )
        else -> messages
    }
    return copy(
        messages = nextMessages,
        streamingText = "",
        turnState = if (keepRunning) TurnState.Running else TurnState.Idle,
    )
}
