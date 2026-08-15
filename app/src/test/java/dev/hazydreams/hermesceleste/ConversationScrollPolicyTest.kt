package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.ui.conversation.ConversationScrollPolicy
import dev.hazydreams.hermesceleste.ui.conversation.ScrollCommand
import dev.hazydreams.hermesceleste.ui.conversation.activeBottomOcclusionPx
import dev.hazydreams.hermesceleste.ui.conversation.hasUsableViewportGeometry
import dev.hazydreams.hermesceleste.ui.conversation.ScrollPolicyInput
import dev.hazydreams.hermesceleste.ui.conversation.terminalBottomClearancePx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationScrollPolicyTest {
    private val policy = ConversationScrollPolicy()

    @Test
    fun initialProjectionClaimsLatestOnlyWhenTheListHasUsableContent() {
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = false,
                hasItems = true,
                initialProjectionReady = true,
            ),
        )

        assertTrue(decision.followsLatest)
        assertEquals(ScrollCommand.FollowLatest, decision.command)
    }

    @Test
    fun bottomOcclusionUsesTheLargerInsetOnly() {
        assertEquals(320, activeBottomOcclusionPx(320, 48, 24))
        assertEquals(48, activeBottomOcclusionPx(0, 48, 24))
        assertEquals(56, activeBottomOcclusionPx(0, 24, 56))
    }

    @Test
    fun terminalClearanceIsMeasuredAgainstTheUsableViewportEnd() {
        assertEquals(12, terminalBottomClearancePx(100, 88, 200))
        assertEquals(-8, terminalBottomClearancePx(100, 108, 200))
    }

    @Test
    fun viewportGeometryIsPendingUntilTheDockAndListHaveUsableBounds() {
        assertFalse(hasUsableViewportGeometry(0, 0, 400))
        assertFalse(hasUsableViewportGeometry(120, 400, 400))
        assertTrue(hasUsableViewportGeometry(120, 0, 400))
    }

    @Test
    fun exactNearBottomBoundaryIsInclusive() {
        assertTrue(policy.isNearBottom(40f))
        assertFalse(policy.isNearBottom(40.01f))
    }

    @Test
    fun deliberateDragCancelsPendingFollowBeforeContentChanges() {
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = true,
                deliberateDragAway = true,
                contentChanged = true,
                pendingGeneration = 7L,
                transitionGeneration = 7L,
            ),
        )

        assertFalse(decision.followsLatest)
        assertEquals(ScrollCommand.CancelPendingFollow, decision.command)
    }

    @Test
    fun releaseNearBottomRelatchesOnlyAfterTheTransitionSettles() {
        val waiting = policy.decide(
            ScrollPolicyInput(
                followsLatest = false,
                nearBottomDistanceDp = 40f,
                allowRelatch = true,
                imeOrInsetTransition = true,
                transitionSettled = false,
            ),
        )
        val settled = policy.decide(
            ScrollPolicyInput(
                followsLatest = false,
                nearBottomDistanceDp = 40f,
                allowRelatch = true,
                imeOrInsetTransition = true,
                transitionSettled = true,
            ),
        )

        assertEquals(ScrollCommand.Hold, waiting.command)
        assertFalse(waiting.followsLatest)
        assertEquals(ScrollCommand.RelatchLatest, settled.command)
        assertTrue(settled.followsLatest)
    }

    @Test
    fun settledInsetTransitionKeepsAFollowingReaderAtLatest() {
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = true,
                imeOrInsetTransition = true,
                transitionSettled = true,
            ),
        )

        assertEquals(ScrollCommand.FollowLatest, decision.command)
        assertTrue(decision.followsLatest)
    }

    @Test
    fun contentChangesFollowLatestOnlyForAReaderWhoWasAlreadyFollowing() {
        val following = policy.decide(
            ScrollPolicyInput(followsLatest = true, contentChanged = true),
        )
        val readingHistory = policy.decide(
            ScrollPolicyInput(followsLatest = false, contentChanged = true),
        )

        assertEquals(ScrollCommand.FollowLatest, following.command)
        assertEquals(ScrollCommand.Hold, readingHistory.command)
        assertTrue(following.followsLatest)
        assertFalse(readingHistory.followsLatest)
    }

    @Test
    fun newerTransitionGenerationCancelsStaleScrollWork() {
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = true,
                pendingGeneration = 3L,
                transitionGeneration = 4L,
            ),
        )

        assertEquals(ScrollCommand.CancelPendingFollow, decision.command)
        assertEquals(4L, decision.transitionGeneration)
    }

    @Test
    fun missingAnchorUsesSafeLatestFallbackWithoutRelatchingFollow() {
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = false,
                hasItems = true,
                restorationPending = true,
                anchorAvailable = false,
                transitionSettled = true,
            ),
        )

        assertEquals(ScrollCommand.RestoreAnchor, decision.command)
        assertTrue(decision.fallbackToLatest)
        assertFalse(decision.followsLatest)
    }

    @Test
    fun emptyTranscriptDoesNotIssueScrollCommands() {
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = true,
                hasItems = false,
                initialProjectionReady = true,
                contentChanged = true,
                explicitJumpToLatest = true,
            ),
        )

        assertEquals(ScrollCommand.Hold, decision.command)
        assertTrue(decision.followsLatest)
    }
}
