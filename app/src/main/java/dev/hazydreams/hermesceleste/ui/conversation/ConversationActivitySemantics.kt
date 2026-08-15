package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

internal fun Modifier.conversationActivitySemantics(
    owner: ConversationActivityOwner,
): Modifier = semantics(mergeDescendants = true) {
    liveRegion = when (owner.announcementMode) {
        ActivityAnnouncementMode.Polite -> LiveRegionMode.Polite
        ActivityAnnouncementMode.Assertive -> LiveRegionMode.Assertive
    }
    contentDescription = owner.announcement
}
