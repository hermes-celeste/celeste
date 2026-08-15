package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.network.ReasoningPhase
import dev.hazydreams.hermesceleste.network.ToolPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityCardsTest {
    @Test
    fun toolAnnouncementsUseStableAccessiblePhaseNames() {
        assertEquals("running", activityAnnouncementPhase(ToolPhase.Started))
        assertEquals("running", activityAnnouncementPhase(ToolPhase.Running))
        assertEquals("completed", activityAnnouncementPhase(ToolPhase.Completed))
        assertEquals("error", activityAnnouncementPhase(ToolPhase.Failed))
        assertEquals("interrupted", activityAnnouncementPhase(ToolPhase.Interrupted))
    }

    @Test
    fun reasoningAnnouncementsDescribeTheDisplayedLifecycle() {
        assertEquals("streaming", reasoningAnnouncementPhase(ReasoningPhase.Streaming))
        assertEquals("complete", reasoningAnnouncementPhase(ReasoningPhase.Complete))
        assertEquals("unavailable", reasoningAnnouncementPhase(ReasoningPhase.Unavailable))
    }

    @Test
    fun selectableDetailSemanticsExposeOnlyTheSafeLabel() {
        val description = activityDetailContentDescription("Displayed output")

        assertEquals("Displayed output, selectable displayed detail", description)
        assertFalse(description.contains("<private-detail>"))
    }

    @Test
    fun activityPhaseAnnouncementsAreEmittedOncePerCardAndDisplayedPhase() {
        val tracker = ActivityPhaseAnnouncementTracker()

        assertTrue(tracker.consume("activity-ui:tool-1", "tool:running"))
        assertFalse(tracker.consume("activity-ui:tool-1", "tool:running"))
        assertTrue(tracker.consume("activity-ui:tool-1", "tool:completed"))
        assertFalse(tracker.consume("activity-ui:tool-1", "tool:completed"))
        assertTrue(tracker.consume("activity-ui:tool-2", "tool:running"))
        assertFalse(tracker.consume("", "tool:running"))
    }
}
