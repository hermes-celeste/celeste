package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.ui.conversation.STREAMING_TRANSCRIPT_KEY
import dev.hazydreams.hermesceleste.ui.conversation.transcriptItemKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptItemKeysTest {
    @Test
    fun namespacesMessagesAwayFromStreamingAndFallbackRows() {
        val keys = transcriptItemKeys(
            listOf(
                message("streaming-assistant"),
                message("transcript-2"),
                message(null),
            ),
        )

        assertEquals(
            listOf(
                "transcript:id:19:streaming-assistant",
                "transcript:id:12:transcript-2",
                "transcript:fallback:2",
            ),
            keys,
        )
        assertEquals(false, STREAMING_TRANSCRIPT_KEY in keys)
    }

    @Test
    fun duplicateMessageIdentitiesReceiveDeterministicOccurrenceKeys() {
        val messages = listOf(message("shared"), message("shared"), message("shared"))

        assertEquals(
            listOf(
                "transcript:id:6:shared",
                "transcript:id:6:shared:occurrence:2",
                "transcript:id:6:shared:occurrence:3",
            ),
            transcriptItemKeys(messages),
        )
        assertEquals(transcriptItemKeys(messages), transcriptItemKeys(messages))
    }

    private fun message(id: String?): ConversationMessage =
        ConversationMessage(role = "assistant", text = "Fixture", id = id)
}
