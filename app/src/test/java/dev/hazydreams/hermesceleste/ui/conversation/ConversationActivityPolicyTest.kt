package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.ActiveTurnAction
import dev.hazydreams.hermesceleste.ConversationActionModel
import dev.hazydreams.hermesceleste.ConversationActivityCandidate
import dev.hazydreams.hermesceleste.ConversationActivityCandidates
import dev.hazydreams.hermesceleste.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("Send message", projection.composerAction.label)
        assertTrue(projection.draftEnabled)
    }

    @Test
    fun synchronizingOwnsOnePoliteNoticeAndAnUnavailableDisabledAction() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Synchronizing,
        )

        assertEquals(ActivityOwnerKind.Synchronizing, projection.owner?.kind)
        assertEquals("Synchronizing…", projection.owner?.label)
        assertEquals(ActivityAnnouncementMode.Polite, projection.owner?.announcementMode)
        assertEquals(ConversationComposerAction.Unavailable, projection.composerAction)
        assertEquals("Unavailable", projection.composerAction.label)
        assertFalse(projection.draftEnabled)
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
        assertEquals("Working…", projection.owner?.semanticsSpec()?.contentDescription)
        assertEquals(ActivityAnnouncementMode.Polite, projection.owner?.semanticsSpec()?.liveRegion)
        assertEquals(ConversationComposerAction.StopResponse, projection.composerAction)
        assertEquals("Stop response", projection.composerAction.label)
        assertFalse(projection.draftEnabled)
    }

    @Test
    fun unavailableActiveTurnCapabilityDoesNotFallBackToStop() {
        val projection = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Running,
            actionModel = ConversationActionModel(ActiveTurnAction.Unavailable),
        )

        assertEquals(ConversationComposerAction.Unavailable, projection.composerAction)
        assertEquals("Unavailable", projection.composerAction.label)
        assertFalse(projection.draftEnabled)
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
        val candidates = ConversationActivityCandidates(
            pendingTool = ConversationActivityCandidate(
                index = 2,
                identity = "tool-1",
                displayName = "terminal",
            ),
        )
        val first = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Running,
            streamingText = "Before tool",
            activityCandidates = candidates,
        )
        val updated = selectConversationActivity(
            scope = scope(),
            turnState = TurnState.Running,
            activityCandidates = candidates,
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
        assertEquals("Retry connection", projection.composerAction.label)
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
        assertEquals("Hermes could not finish that response.", projection.owner?.semanticsSpec()?.contentDescription)
        assertEquals(ActivityAnnouncementMode.Assertive, projection.owner?.semanticsSpec()?.liveRegion)
        assertEquals(ConversationComposerAction.Retry, projection.composerAction)
        assertEquals("Retry", projection.composerAction.label)
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
