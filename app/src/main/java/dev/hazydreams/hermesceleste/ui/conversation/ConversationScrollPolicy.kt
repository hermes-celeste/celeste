package dev.hazydreams.hermesceleste.ui.conversation

/**
 * The only policy that decides whether transcript mutations may move the viewport.
 *
 * This type deliberately has no Compose or ViewModel dependency. The UI supplies
 * measured geometry and transition state, while the ViewModel remains authoritative
 * for the transcript itself.
 */
internal fun activeBottomOcclusionPx(imeBottomPx: Int, navigationBottomPx: Int): Int =
    maxOf(imeBottomPx, navigationBottomPx)

internal class ConversationScrollPolicy(
    private val nearBottomThresholdDp: Float = 40f,
) {
    fun isNearBottom(distanceDp: Float?): Boolean =
        distanceDp != null && distanceDp.isFinite() && distanceDp <= nearBottomThresholdDp

    fun decide(input: ScrollPolicyInput): ScrollPolicyDecision {
        if (!input.hasItems) {
            return input.hold()
        }

        if (input.deliberateDragAway) {
            return ScrollPolicyDecision(
                followsLatest = false,
                command = ScrollCommand.CancelPendingFollow,
                transitionGeneration = input.transitionGeneration,
            )
        }

        if (input.pendingGeneration != null &&
            input.pendingGeneration != input.transitionGeneration
        ) {
            return ScrollPolicyDecision(
                followsLatest = input.followsLatest,
                command = ScrollCommand.CancelPendingFollow,
                transitionGeneration = input.transitionGeneration,
            )
        }

        if (input.explicitJumpToLatest) {
            return ScrollPolicyDecision(
                followsLatest = true,
                command = ScrollCommand.JumpToLatest,
                transitionGeneration = input.transitionGeneration,
            )
        }

        if (input.initialProjectionReady) {
            return ScrollPolicyDecision(
                followsLatest = true,
                command = ScrollCommand.FollowLatest,
                transitionGeneration = input.transitionGeneration,
            )
        }

        if (input.restorationPending) {
            if (!input.transitionSettled) return input.hold()
            return ScrollPolicyDecision(
                followsLatest = input.followsLatest,
                command = ScrollCommand.RestoreAnchor,
                fallbackToLatest = !input.anchorAvailable,
                transitionGeneration = input.transitionGeneration,
            )
        }

        if (input.imeOrInsetTransition && !input.transitionSettled) {
            return input.hold()
        }

        if (input.imeOrInsetTransition && input.transitionSettled && input.followsLatest) {
            return ScrollPolicyDecision(
                followsLatest = true,
                command = ScrollCommand.FollowLatest,
                transitionGeneration = input.transitionGeneration,
            )
        }

        if (!input.followsLatest &&
            input.allowRelatch &&
            isNearBottom(input.nearBottomDistanceDp)
        ) {
            return ScrollPolicyDecision(
                followsLatest = true,
                command = ScrollCommand.RelatchLatest,
                transitionGeneration = input.transitionGeneration,
            )
        }

        if (input.contentChanged && input.followsLatest) {
            return ScrollPolicyDecision(
                followsLatest = true,
                command = ScrollCommand.FollowLatest,
                transitionGeneration = input.transitionGeneration,
            )
        }

        return input.hold()
    }

    private fun ScrollPolicyInput.hold(): ScrollPolicyDecision =
        ScrollPolicyDecision(
            followsLatest = followsLatest,
            command = ScrollCommand.Hold,
            transitionGeneration = transitionGeneration,
        )
}

internal data class ScrollPolicyInput(
    val followsLatest: Boolean,
    val nearBottomDistanceDp: Float? = null,
    val deliberateDragAway: Boolean = false,
    val contentChanged: Boolean = false,
    val imeOrInsetTransition: Boolean = false,
    val restorationPending: Boolean = false,
    val transitionGeneration: Long = 0L,
    val pendingGeneration: Long? = null,
    val transitionSettled: Boolean = true,
    val initialProjectionReady: Boolean = false,
    val explicitJumpToLatest: Boolean = false,
    val hasItems: Boolean = true,
    val anchorAvailable: Boolean = true,
    val allowRelatch: Boolean = false,
)

internal data class ScrollPolicyDecision(
    val followsLatest: Boolean,
    val command: ScrollCommand,
    val fallbackToLatest: Boolean = false,
    val transitionGeneration: Long,
)

internal enum class ScrollCommand {
    Hold,
    FollowLatest,
    RestoreAnchor,
    RelatchLatest,
    JumpToLatest,
    CancelPendingFollow,
}
