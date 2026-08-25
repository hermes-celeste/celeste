package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.ui.conversation.AssistantContentBlock
import dev.hazydreams.hermesceleste.ui.conversation.allowedConversationImageUri
import dev.hazydreams.hermesceleste.ui.conversation.allowedMarkdownUri
import dev.hazydreams.hermesceleste.ui.conversation.assistantContentBlocks
import dev.hazydreams.hermesceleste.ui.conversation.containsRichMarkdown
import dev.hazydreams.hermesceleste.ui.conversation.markdownImageTarget
import dev.hazydreams.hermesceleste.ui.conversation.markdownStreamDelta
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.EmptyStreamingMarkdownFile
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichMarkdownContractTest {
    @Test
    fun parsesTheSupportedGitHubFlavoredMarkdownBlocks() {
        val markdown: CharSequence = """
            # Heading

            Paragraph with **strong**, *emphasis*, ~~strikethrough~~, [link](https://example.com), and `inline code`.

            > Quoted context

            1. Ordered
            2. List

            - Unordered
            - [x] Complete task
            - [ ] Open task

            ---

            ```kotlin
            val answer = 42
            ```

            | State | Meaning |
            | --- | --- |
            | Ready | Tested |
        """.trimIndent()

        val nodeTypes = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(markdown)
            .walk()
            .map(ASTNode::type)
            .toSet()

        assertTrue(MarkdownElementTypes.ATX_1 in nodeTypes)
        assertTrue(MarkdownElementTypes.BLOCK_QUOTE in nodeTypes)
        assertTrue(MarkdownElementTypes.ORDERED_LIST in nodeTypes)
        assertTrue(MarkdownElementTypes.UNORDERED_LIST in nodeTypes)
        assertTrue(MarkdownTokenTypes.HORIZONTAL_RULE in nodeTypes)
        assertTrue(MarkdownElementTypes.CODE_FENCE in nodeTypes)
        assertTrue(MarkdownElementTypes.STRONG in nodeTypes)
        assertTrue(MarkdownElementTypes.EMPH in nodeTypes)
        assertTrue(GFMElementTypes.STRIKETHROUGH in nodeTypes)
        assertTrue(GFMElementTypes.TABLE in nodeTypes)
    }

    @Test
    fun acceptsOnlyExplicitWebLinks() {
        assertTrue(allowedMarkdownUri("https://example.com/path"))
        assertTrue(allowedMarkdownUri("HTTP://example.com"))
        assertFalse(allowedMarkdownUri("javascript:alert(1)"))
        assertFalse(allowedMarkdownUri("file:///data/private"))
        assertFalse(allowedMarkdownUri("mailto:person@example.com"))
        assertFalse(allowedMarkdownUri("/relative/path"))
    }

    @Test
    fun loadsOnlyExplicitHttpsConversationImages() {
        assertTrue(allowedConversationImageUri("https://images.example.com/render.png"))
        assertTrue(allowedConversationImageUri("HTTPS://images.example.com/render.webp?size=large"))
        assertFalse(allowedConversationImageUri("http://images.example.com/render.png"))
        assertFalse(allowedConversationImageUri("file:///data/private.png"))
        assertFalse(allowedConversationImageUri("data:image/png;base64,AAAA"))
        assertFalse(allowedConversationImageUri("javascript:alert(1)"))
        assertFalse(allowedConversationImageUri("/relative/render.png"))
    }

    @Test
    fun readsDirectMarkdownImageDestinations() {
        assertEquals(
            "https://images.example.com/architecture.png",
            markdownImageTarget("![Architecture](https://images.example.com/architecture.png)")?.url,
        )
        assertEquals(
            "https://images.example.com/render wide.png",
            markdownImageTarget(
                "![Rendered comparison](<https://images.example.com/render wide.png> \"Preview\")",
            )?.url,
        )
        assertEquals(
            "Rendered comparison",
            markdownImageTarget(
                "![Rendered comparison](https://images.example.com/render.png \"Preview\")",
            )?.alt,
        )
        assertNull(markdownImageTarget("[Ordinary link](https://images.example.com/render.png)"))
    }

    @Test
    fun separatesStandaloneHermesMediaFromAssistantProse() {
        assertEquals(
            listOf(
                AssistantContentBlock.Text("Here is the rendered comparison."),
                AssistantContentBlock.GatewayImage(
                    path = "/home/juno/output/rendered comparison.png",
                    alt = "rendered comparison.png",
                ),
                AssistantContentBlock.Text("The narrow version follows."),
                AssistantContentBlock.GatewayImage(
                    path = "/home/juno/output/narrow.png",
                    alt = "narrow.png",
                ),
            ),
            assistantContentBlocks(
                """
                    Here is the rendered comparison.
                    MEDIA:"/home/juno/output/rendered comparison.png"
                    The narrow version follows.
                    `MEDIA:/home/juno/output/narrow.png`
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun ordinaryMediaMentionsRemainReadableAssistantText() {
        val content = "The protocol label MEDIA: is documented here."

        assertEquals(listOf(AssistantContentBlock.Text(content)), assistantContentBlocks(content))
    }

    @Test
    fun streamingMediaOutputStaysTextUntilThePathSettles() {
        val content = "MEDIA:/home/juno/output/rendered"

        assertEquals(
            listOf(AssistantContentBlock.Text(content)),
            assistantContentBlocks(content, allowGatewayImages = false),
        )
    }

    @Test
    fun parsesOnlyMessagesThatContainRichMarkdownSyntax() {
        assertFalse(containsRichMarkdown("Ordinary prose stays on the established text path."))
        assertFalse(containsRichMarkdown("Line one.\nLine two."))
        assertTrue(containsRichMarkdown("Use **strong emphasis** here."))
        assertTrue(containsRichMarkdown("## Heading"))
        assertTrue(containsRichMarkdown("> Quoted context"))
        assertTrue(containsRichMarkdown("1. Ordered item"))
        assertTrue(containsRichMarkdown("[Safe link](https://example.com)"))
        assertTrue(containsRichMarkdown("| State | Result |\n| --- | --- |"))
        assertTrue(containsRichMarkdown("```kotlin\nval stable = true\n```"))
    }

    @Test
    fun extractsOnlyAppendOnlyStreamingDeltas() {
        assertEquals(" world", markdownStreamDelta("Hello", "Hello world"))
        assertEquals("", markdownStreamDelta("Hello", "Hello"))
        assertNull(markdownStreamDelta("Previous projection", "Recovered projection"))
        assertEquals("Recovered projection", markdownStreamDelta("", "Recovered projection"))
    }

    @Test
    fun malformedMarkdownStillProducesAReadableSourceRange() {
        val source: CharSequence = "Before **unfinished emphasis\n\n[broken link](https://example.com\n\n```kotlin\nval answer = 42"
        val root = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(source)

        assertTrue(root.children.isNotEmpty())
        assertEquals(0, root.startOffset)
        assertEquals(source.length, root.endOffset)
    }

    @Test
    fun streamingParserKeepsCompletedBlocksStableWhileTheTailIsIncomplete() {
        val stream = EmptyStreamingMarkdownFile(GFMFlavourDescriptor())
        var source = ""

        fun append(chunk: String) {
            source += chunk
            stream.append(chunk)
            assertEquals(source.length, stream.endOffset)
        }

        append("Paragraph with **complete emphasis**.\n\n")
        assertTrue(stream.stableChildren.isNotEmpty())
        val completedParagraph = stream.stableChildren.first()

        append("| State | Result |\n| --- |")
        assertTrue(stream.unstableTail.isNotEmpty())
        assertTrue(stream.stableChildren.first() === completedParagraph)

        append(" --- |\n| Ready | Tested |\n\n```kotlin\nval stable =")
        assertTrue(stream.unstableTail.isNotEmpty())
        assertTrue(stream.stableChildren.first() === completedParagraph)

        append(" true\n```\n")
        assertTrue(stream.children.isNotEmpty())
    }

    private fun ASTNode.walk(): Sequence<ASTNode> = sequence {
        yield(this@walk)
        children.forEach { yieldAll(it.walk()) }
    }
}
