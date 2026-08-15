package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.AttachmentDraft
import dev.hazydreams.hermesceleste.network.AttachmentReadiness
import dev.hazydreams.hermesceleste.network.AttachmentReference
import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveTurnSteeringTest {
    private val text = ActiveTurnPayload(text = "guide Hermes")
    private val attachment = AttachmentDraft(
        reference = AttachmentReference(
            id = "attachment-1",
            uri = "content://synthetic/1",
            mimeType = "image/png",
            name = "synthetic.png",
        ),
        readiness = AttachmentReadiness.Ready,
    )

    @Test
    fun policyMatrixKeepsRunningComposerEditableAndChoosesAuthoritativeAction() {
        assertEquals(ComposerAction.Send, composerAction(TurnState.Idle, text, BusyInputPolicy.Steer, false))
        assertEquals(ComposerAction.Steer, composerAction(TurnState.Running, text, BusyInputPolicy.Steer, false))
        assertEquals(ComposerAction.Queue, composerAction(TurnState.Running, text, BusyInputPolicy.Queue, false))
        assertEquals(ComposerAction.Redirect, composerAction(TurnState.Running, text, BusyInputPolicy.Redirect, true))
        assertEquals(ComposerAction.Steer, composerAction(TurnState.Running, text, BusyInputPolicy.Redirect, false))
        assertEquals(ComposerAction.Queue, composerAction(TurnState.Running, ActiveTurnPayload("", listOf(attachment)), BusyInputPolicy.Steer, true))
        assertEquals(ComposerAction.Stop, composerAction(TurnState.Running, ActiveTurnPayload(""), BusyInputPolicy.Steer, false))
        assertEquals(ComposerAction.None, composerAction(TurnState.Synchronizing, text, BusyInputPolicy.Steer, false))
        assertEquals(ComposerAction.None, composerAction(TurnState.Reconnecting, text, BusyInputPolicy.Steer, false))
        assertEquals(ComposerAction.None, composerAction(TurnState.UnsupportedGateway, text, BusyInputPolicy.Steer, false))
    }

    @Test
    fun attachmentReadinessNeverFallsBackToTextOnlySubmission() {
        val uploading = attachment.copy(readiness = AttachmentReadiness.Uploading)
        val failed = attachment.copy(readiness = AttachmentReadiness.Failed)

        assertEquals(
            ComposerAction.None,
            composerAction(TurnState.Running, ActiveTurnPayload("keep text", listOf(uploading)), BusyInputPolicy.Steer, false),
        )
        assertEquals(
            ComposerAction.None,
            composerAction(TurnState.Running, ActiveTurnPayload("keep text", listOf(failed)), BusyInputPolicy.Steer, false),
        )
        assertEquals(
            ComposerAction.None,
            composerAction(TurnState.Idle, ActiveTurnPayload("keep text", listOf(uploading)), BusyInputPolicy.Steer, false),
        )
    }
}
