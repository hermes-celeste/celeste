package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.attachments.AttachmentPreviewState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.decodeGatewayMessages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAttachmentTest {
    @Test
    fun normalizesImageDirectivesWithoutLeakingRawPathsOrDroppingTheCaption() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[{"row_id":"user-1","role":"user","text":"Look at this\n@image:`/private/Hermes/images/cat photo.png`\n@image:/private/Hermes/images/dog.png"}]""",
            ).jsonArray,
        )

        val message = messages.single()
        assertEquals("Look at this", message.text)
        assertEquals(
            listOf("/private/Hermes/images/cat photo.png", "/private/Hermes/images/dog.png"),
            message.attachments.map { it.serverReference },
        )
        assertEquals(listOf("cat photo.png", "dog.png"), message.attachments.map { it.displayName })
        assertTrue(message.rawText.contains("@image:"))
        assertFalse(message.text.contains("@image:"))
        assertFalse(message.text.contains("/private/"))
        assertTrue(message.attachments.all { it.preview == AttachmentPreviewState.Unavailable })
    }

    @Test
    fun preservesDirectiveOrderAndProvidesDeterministicAttachmentKeys() {
        val message = ConversationMessage(
            role = "user",
            text = "Caption",
            id = "row-1",
            attachments = listOf(
                attachment("row-1:attachment:0", "/gateway/a.png"),
                attachment("row-1:attachment:1", "/gateway/b.png"),
            ),
        )

        assertEquals(
            listOf("transcript:id:5:row-1:attachment:0", "transcript:id:5:row-1:attachment:1"),
            messageAttachmentKeys(message),
        )
    }

    private fun attachment(id: String, ref: String) =
        dev.hazydreams.hermesceleste.attachments.MessageAttachment(
            id = id,
            displayName = ref.substringAfterLast('/'),
            mimeType = "image/png",
            byteSize = 0,
            serverReference = ref,
            preview = AttachmentPreviewState.Unavailable,
        )
}
