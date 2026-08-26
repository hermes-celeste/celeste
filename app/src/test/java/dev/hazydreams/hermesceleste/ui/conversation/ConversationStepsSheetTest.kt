package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.network.ConversationStep
import dev.hazydreams.hermesceleste.network.ConversationStepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStepsSheetTest {
    @Test
    fun toolDetailUsesConciseMetadataAndBoundsRawFallback() {
        val concise = ConversationStep(
            id = "read-1",
            kind = ConversationStepKind.Tool,
            toolName = "read_file",
            context = "GatewaySessionApi.kt",
            summary = "Read the resume projection",
            result = "raw output",
        )
        val rawOnly = concise.copy(
            id = "terminal-1",
            toolName = "terminal",
            context = "",
            summary = "",
            result = "x".repeat(600),
        )

        assertEquals("Read file", stepTitle(concise))
        assertEquals("GatewaySessionApi.kt\nRead the resume projection", stepDetail(concise))
        assertEquals(421, stepDetail(rawOnly).length)
        assertTrue(stepDetail(rawOnly).endsWith("…"))
    }

    @Test
    fun reasoningDetailRemovesPresentationMarkdownWhilePreservingReadableStructure() {
        val reasoning = ConversationStep(
            id = "reasoning-1",
            kind = ConversationStepKind.Reasoning,
            detail = """
                ## Closing issues
                **Planning programmatic checkbox updates**
                - Keep `TalkBack`, `__init__`, and `**kwargs` visible
                - Preserve __dirname outside code too

                **Updating checkbox statuses and closing issue**
            """.trimIndent(),
        )

        assertEquals(
            """
                Closing issues
                Planning programmatic checkbox updates
                Keep TalkBack, __init__, and **kwargs visible
                Preserve __dirname outside code too

                Updating checkbox statuses and closing issue
            """.trimIndent(),
            stepDetail(reasoning),
        )
    }

    @Test
    fun reasoningDetailPreservesLiteralSyntaxAndFencedCode() {
        val reasoning = ConversationStep(
            id = "reasoning-technical",
            kind = ConversationStepKind.Reasoning,
            detail = """
                - Call f(**kwargs) for src/**/*.kt
                **kwargs**
                ```python
                def f(**kwargs):
                    return __dirname
                ```
            """.trimIndent(),
        )

        assertEquals(
            """
                Call f(**kwargs) for src/**/*.kt
                **kwargs**
                def f(**kwargs):
                    return __dirname
            """.trimIndent(),
            stepDetail(reasoning),
        )
    }
}
