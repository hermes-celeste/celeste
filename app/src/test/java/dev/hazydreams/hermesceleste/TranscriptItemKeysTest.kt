package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.ui.conversation.ScrollFollowObservation
import dev.hazydreams.hermesceleste.ui.conversation.STREAMING_TRANSCRIPT_KEY
import dev.hazydreams.hermesceleste.ui.conversation.latestTranscriptIndex
import dev.hazydreams.hermesceleste.ui.conversation.remainingScrollToLatest
import dev.hazydreams.hermesceleste.ui.conversation.shouldShowJumpToLatest
import dev.hazydreams.hermesceleste.ui.conversation.streamingTranscriptKey
import dev.hazydreams.hermesceleste.ui.conversation.transcriptItemKeys
import dev.hazydreams.hermesceleste.ui.conversation.updatedFollowLatest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun streamingParserIdentityIsScopedToTheActiveSession() {
        assertEquals("streaming:assistant:session-a", streamingTranscriptKey("session-a"))
        assertNotEquals(streamingTranscriptKey("session-a"), streamingTranscriptKey("session-b"))
    }

    @Test
    fun jumpToLatestTargetsTheLastVisibleTranscriptItem() {
        assertEquals(null, latestTranscriptIndex(0))
        assertEquals(0, latestTranscriptIndex(1))
        assertEquals(4, latestTranscriptIndex(5))
    }

    @Test
    fun jumpToLatestAppearsOnlyWhenContentContinuesBelowTheViewport() {
        assertEquals(false, shouldShowJumpToLatest(canScrollForward = false, visibleMessageCount = 4))
        assertEquals(false, shouldShowJumpToLatest(canScrollForward = true, visibleMessageCount = 0))
        assertEquals(true, shouldShowJumpToLatest(canScrollForward = true, visibleMessageCount = 4))
    }

    @Test
    fun jumpToLatestIncludesTheTallFinalItemsRemainingHeightAndBottomPadding() {
        assertEquals(
            140,
            remainingScrollToLatest(
                itemOffset = 0,
                itemSize = 600,
                viewportEndOffset = 480,
                afterContentPadding = 20,
            ),
        )
        assertEquals(
            0,
            remainingScrollToLatest(
                itemOffset = 300,
                itemSize = 120,
                viewportEndOffset = 480,
                afterContentPadding = 20,
            ),
        )
    }

    @Test
    fun scrollingBackwardPausesFollowingUntilTheReaderReturnsToTheBottom() {
        val scrolledUp = ScrollFollowObservation(
            readerDragging = true,
            scrolledBackward = true,
            canScrollForward = true,
        )
        val streamingContinues = ScrollFollowObservation(
            readerDragging = false,
            scrolledBackward = false,
            canScrollForward = true,
        )
        val nonReaderScroll = ScrollFollowObservation(
            readerDragging = false,
            scrolledBackward = true,
            canScrollForward = true,
        )
        val returnedToBottom = ScrollFollowObservation(
            readerDragging = false,
            scrolledBackward = false,
            canScrollForward = false,
        )

        assertEquals(false, updatedFollowLatest(current = true, observation = scrolledUp))
        assertEquals(false, updatedFollowLatest(current = false, observation = streamingContinues))
        assertEquals(true, updatedFollowLatest(current = true, observation = nonReaderScroll))
        assertEquals(true, updatedFollowLatest(current = false, observation = returnedToBottom))
    }

    private fun message(id: String?): ConversationMessage =
        ConversationMessage(role = "assistant", text = "Fixture", id = id)
}
