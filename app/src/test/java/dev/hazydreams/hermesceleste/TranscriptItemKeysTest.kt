package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ActivityCapabilityState
import dev.hazydreams.hermesceleste.network.ActivityItem
import dev.hazydreams.hermesceleste.network.ActivityPresentationState
import dev.hazydreams.hermesceleste.network.ActivitySource
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.CorrelationQuality
import dev.hazydreams.hermesceleste.network.ReasoningPhase
import dev.hazydreams.hermesceleste.network.ReasoningSource
import dev.hazydreams.hermesceleste.network.ServerReasoningActivity
import dev.hazydreams.hermesceleste.network.ToolActivity
import dev.hazydreams.hermesceleste.network.ToolPhase
import dev.hazydreams.hermesceleste.network.initialActivityProjection
import dev.hazydreams.hermesceleste.ui.conversation.STREAMING_TRANSCRIPT_KEY
import dev.hazydreams.hermesceleste.ui.conversation.activityItemKeys
import dev.hazydreams.hermesceleste.ui.conversation.transcriptItemKeys
import dev.hazydreams.hermesceleste.ui.conversation.transcriptMessagesForActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun activityRowsUseStableNamespacedKeysForDuplicateOrBlankSourceKeys() {
        val items: List<ActivityItem> = listOf(
            ToolActivity(
                uiKey = "activity:stored-42:live:1",
                callId = "call-1",
                name = "terminal",
                phase = ToolPhase.Running,
                input = null,
                output = null,
                startedAt = null,
                finishedAt = null,
                correlation = CorrelationQuality.ExactId,
            ),
            ToolActivity(
                uiKey = "activity:stored-42:live:1",
                callId = "call-2",
                name = "terminal",
                phase = ToolPhase.Completed,
                input = null,
                output = null,
                startedAt = null,
                finishedAt = null,
                correlation = CorrelationQuality.ExactId,
            ),
            ServerReasoningActivity(
                uiKey = "",
                source = ReasoningSource.ServerSummary,
                phase = ReasoningPhase.Complete,
                text = dev.hazydreams.hermesceleste.network.DisplayedDetail(
                    text = "<server-summary>",
                    originalLength = "<server-summary>".length,
                    wasTruncated = false,
                    wasRedacted = false,
                    canRestore = false,
                ),
                serverLabel = "Server-provided summary",
            ),
        )

        val keys = activityItemKeys(items)
        assertEquals(3, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("activity-ui:") })
        assertTrue(keys[1].contains(":occurrence:2"))
        assertEquals(keys, activityItemKeys(items))
    }

    @Test
    fun legacyToolTranscriptRowsRemainVisibleWhenActivityIsUnavailable() {
        val messages = listOf(
            message("user-1").copy(role = "user", text = "Run this"),
            message("tool-1").copy(role = "tool", text = "Legacy output", toolName = "terminal"),
        )
        val unavailable = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
        ).copy(
            source = ActivitySource.Unavailable,
            capability = ActivityCapabilityState.Unsupported,
            presentation = ActivityPresentationState.Unavailable,
        )

        assertEquals(messages, transcriptMessagesForActivity(messages, null))
        assertEquals(messages, transcriptMessagesForActivity(messages, unavailable))
    }

    @Test
    fun transcriptToolRowsAreRemovedOnlyWhenEveryRowHasAnActivityCard() {
        val tool = ToolActivity(
            uiKey = "activity:stored-42:tool-legacy:terminal:occurrence:1",
            callId = null,
            name = "terminal",
            phase = ToolPhase.Completed,
            input = null,
            output = null,
            startedAt = null,
            finishedAt = null,
            correlation = CorrelationQuality.LegacyName,
        )
        val projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
        ).copy(
            items = listOf(tool),
            source = ActivitySource.Legacy,
            capability = ActivityCapabilityState.LegacyToolOnly,
            presentation = ActivityPresentationState.Available,
        )
        val messages = listOf(
            message("tool-1").copy(role = "tool", text = "First", toolName = "terminal"),
            message("tool-2").copy(role = "tool", text = "Second", toolName = "terminal"),
        )

        assertEquals(messages, transcriptMessagesForActivity(messages, projection))
        assertTrue(
            transcriptMessagesForActivity(messages.dropLast(1), projection).isEmpty(),
        )
    }

    private fun message(id: String?): ConversationMessage =
        ConversationMessage(role = "assistant", text = "Fixture", id = id)
}
