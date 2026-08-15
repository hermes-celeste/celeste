package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

internal data class ConversationActivitySemanticsSpec(
    val contentDescription: String,
    val liveRegion: ActivityAnnouncementMode,
)

internal fun ConversationActivityOwner.semanticsSpec(): ConversationActivitySemanticsSpec =
    ConversationActivitySemanticsSpec(
        contentDescription = announcement,
        liveRegion = announcementMode,
    )

internal fun Modifier.conversationActivitySemantics(
    owner: ConversationActivityOwner,
): Modifier {
    val spec = owner.semanticsSpec()
    return semantics(mergeDescendants = true) {
        liveRegion = when (spec.liveRegion) {
        ActivityAnnouncementMode.Polite -> LiveRegionMode.Polite
        ActivityAnnouncementMode.Assertive -> LiveRegionMode.Assertive
        }
        contentDescription = spec.contentDescription
    }
}
