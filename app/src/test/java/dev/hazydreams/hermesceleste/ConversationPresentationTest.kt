package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.AssistantContentKind
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.ConversationStep
import dev.hazydreams.hermesceleste.network.ConversationStepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationPresentationTest {
    @Test
    fun transcriptPresentationPreservesCanonicalMessages() {
        val messages = listOf(
            ConversationMessage(role = "user", text = "Check it"),
            ConversationMessage(
                role = "assistant",
                text = "I’m checking.",
                assistantContentKind = AssistantContentKind.Commentary,
                interim = true,
            ),
        )

        assertSame(
            messages,
            presentConversationMessages(messages, CommentaryPresentation.Transcript),
        )
    }

    @Test
    fun thinkingPresentationMovesTaggedCommentaryIntoTheChronologicalTimeline() {
        val messages = listOf(
            ConversationMessage(role = "user", text = "Check it", id = "user-1"),
            ConversationMessage(
                role = "steps",
                text = "",
                id = "steps-1",
                steps = listOf(
                    ConversationStep(
                        id = "reasoning-1",
                        kind = ConversationStepKind.Reasoning,
                        detail = "Plan the check.",
                    ),
                ),
            ),
            ConversationMessage(
                role = "assistant",
                text = "I’m checking the current implementation.",
                id = "commentary-1",
                interim = true,
                assistantContentKind = AssistantContentKind.Commentary,
            ),
            ConversationMessage(
                role = "steps",
                text = "",
                id = "steps-2",
                steps = listOf(
                    ConversationStep(
                        id = "tool-1",
                        kind = ConversationStepKind.Tool,
                        toolName = "read_file",
                    ),
                ),
            ),
            ConversationMessage(
                role = "assistant",
                text = "Everything checks out.",
                id = "answer-1",
            ),
            ConversationMessage(role = "changes", text = "", id = "changes-1"),
        )

        val presented = CelesteUiState(
            messages = messages,
            commentaryPresentation = CommentaryPresentation.Thinking,
        ).presentedMessages

        assertEquals(listOf("user", "steps", "assistant", "changes"), presented.map { it.role })
        assertEquals(
            listOf(
                ConversationStepKind.Reasoning,
                ConversationStepKind.Commentary,
                ConversationStepKind.Tool,
            ),
            presented[1].steps.map { it.kind },
        )
        assertEquals(
            listOf("Plan the check.", "I’m checking the current implementation.", ""),
            presented[1].steps.map { it.detail },
        )
        assertEquals("Everything checks out.", presented[2].text)
        assertEquals(AssistantContentKind.Response, presented[2].assistantContentKind)
    }

    @Test
    fun thinkingPresentationLeavesUnclassifiedInterimAssistantTextVisible() {
        val interimResponse = ConversationMessage(
            role = "assistant",
            text = "A response still being streamed",
            interim = true,
        )

        assertEquals(
            listOf(interimResponse),
            presentConversationMessages(
                messages = listOf(interimResponse),
                commentaryPresentation = CommentaryPresentation.Thinking,
            ),
        )
    }
}
