package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationActivityPolicyTest {
    @Test
    fun idleHasNoActivityOwnerAndUsesTheSendAction() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Idle,
        )

        assertNull(projection.owner)
        assertEquals(ConversationComposerAction.SendMessage, projection.composerAction)
    }

    @Test
    fun synchronizingOwnsOnePoliteNoticeWithoutAComposerStatusCopy() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Synchronizing,
        )

        assertEquals(ActivityOwnerKind.Synchronizing, projection.owner?.kind)
        assertEquals("Synchronizing…", projection.owner?.label)
        assertEquals(ActivityAnnouncementMode.Polite, projection.owner?.announcementMode)
        assertEquals(ConversationComposerAction.Unavailable, projection.composerAction)
    }

    @Test
    fun runningWithoutOutputUsesWorkingAsTheOnlyGenericOwner() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Running,
        )

        assertEquals(ActivityOwnerKind.Working, projection.owner?.kind)
        assertEquals("Working…", projection.owner?.label)
        assertEquals(ActivityAnnouncementMode.Polite, projection.owner?.announcementMode)
        assertEquals(ConversationComposerAction.StopResponse, projection.composerAction)
    }

    @Test
    fun visibleStreamingOutputReplacesWorkingAndKeepsTheAnnouncementKeyStable() {
        val first = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Running,
            streamingText = "Hel",
        )
        val later = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Running,
            streamingText = "Hello, still streaming",
        )

        assertEquals(ActivityOwnerKind.Streaming, first.owner?.kind)
        assertEquals(ActivityOwnerKind.Streaming, later.owner?.kind)
        assertEquals(first.owner?.key, later.owner?.key)
        assertEquals("Hermes response in progress", first.owner?.announcement)
    }

    @Test
    fun pendingToolReplacesStreamingAndKeepsOneStableToolOwner() {
        val first = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Running,
            streamingText = "Before tool",
            messages = listOf(
                ConversationMessage(
                    role = "tool",
                    text = "",
                    toolName = "terminal",
                    id = "tool-1",
                    pending = true,
                ),
            ),
        )
        val updated = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Running,
            messages = listOf(
                ConversationMessage(
                    role = "tool",
                    text = "still running",
                    toolName = "terminal",
                    id = "tool-1",
                    pending = true,
                ),
            ),
        )

        assertEquals(ActivityOwnerKind.Tool, first.owner?.kind)
        assertEquals("Running terminal…", first.owner?.label)
        assertEquals(first.owner?.key, updated.owner?.key)
        assertEquals(ConversationComposerAction.StopResponse, first.composerAction)
    }

    @Test
    fun reconnectingReplacesConnectionErrorsWithOneRetryConnectionOwner() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Reconnecting,
            errorMessage = "socket closed",
        )

        assertEquals(ActivityOwnerKind.Reconnecting, projection.owner?.kind)
        assertEquals("Reconnecting to Hermes…", projection.owner?.label)
        assertEquals(ActivityAnnouncementMode.Polite, projection.owner?.announcementMode)
        assertEquals(ConversationComposerAction.RetryConnection, projection.composerAction)
    }

    @Test
    fun actionableErrorsOwnOneAssertiveSurfaceAndPreserveTheRetryAction() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Idle,
            errorMessage = "Hermes could not finish that response.",
        )

        assertEquals(ActivityOwnerKind.Error, projection.owner?.kind)
        assertEquals("Hermes could not finish that response.", projection.owner?.label)
        assertEquals(ActivityAnnouncementMode.Assertive, projection.owner?.announcementMode)
        assertEquals(ConversationComposerAction.Retry, projection.composerAction)
    }

    @Test
    fun expectedCancellationDoesNotBecomeRawProductCopy() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Idle,
            errorMessage = "StandaloneCoroutine was cancelled",
        )

        assertNull(projection.owner)
        assertEquals(ConversationComposerAction.SendMessage, projection.composerAction)
    }

    @Test
    fun activityKeysIncludeTheGatewayProfileAndSessionScope() {
        val first = selectConversationActivity(
            scope = scope(sessionId = "session-a"),
            turnState = TurnState.Running,
        )
        val second = selectConversationActivity(
            scope = scope(sessionId = "session-b"),
            turnState = TurnState.Running,
        )

        assertNotEquals(first.owner?.key, second.owner?.key)
        assertTrue(first.owner?.key?.contains("session-a") == true)
        assertTrue(first.owner?.key?.contains("default") == true)
    }

    @Test
    fun completedOrInterruptedIdleStateHasNoActivityOwner() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Idle,
            messages = listOf(
                ConversationMessage(role = "assistant", text = "Done", pending = false),
            ),
        )

        assertNull(projection.owner)
        assertEquals(ConversationComposerAction.SendMessage, projection.composerAction)
    }

    private fun scope(sessionId: String = "session-a"): ConversationActivityScope =
        ConversationActivityScope(
            gatewayOrigin = "https://hermes.example/chat",
            profile = "default",
            sessionId = sessionId,
        )
}
