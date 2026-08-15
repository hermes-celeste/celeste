package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.ComposerAction
import dev.hazydreams.hermesceleste.DeliveryStatus
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.AttachmentDraft
import dev.hazydreams.hermesceleste.network.AttachmentReadiness
import dev.hazydreams.hermesceleste.network.AttachmentReference
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationAccessibilityTest {
    private val ready = AttachmentDraft(
        reference = AttachmentReference(
            id = "attachment-1",
            uri = "content://synthetic/1",
            mimeType = "image/png",
            name = "synthetic.png",
        ),
        readiness = AttachmentReadiness.Ready,
    )

    @Test
    fun attachmentCopyNamesIdleSendInsteadOfBusyQueue() {
        val copy = attachmentAccessibilityCopy(
            turnState = TurnState.Idle,
            attachments = listOf(ready),
            deliveryStatus = DeliveryStatus.None,
            lastAction = null,
        )

        assertTrue(copy.contains("sent with this message"))
        assertTrue(!copy.contains("queued"))
    }

    @Test
    fun attachmentCopyNamesRunningQueueAndUncertainDelivery() {
        val queued = attachmentAccessibilityCopy(
            turnState = TurnState.Running,
            attachments = listOf(ready),
            deliveryStatus = DeliveryStatus.Pending,
            lastAction = ComposerAction.Queue,
        )
        val uncertain = attachmentAccessibilityCopy(
            turnState = TurnState.Running,
            attachments = listOf(ready),
            deliveryStatus = DeliveryStatus.Uncertain,
            lastAction = ComposerAction.Queue,
        )

        assertTrue(queued.contains("queued for the next turn"))
        assertTrue(uncertain.contains("uncertain"))
        assertTrue(uncertain.contains("not be resent automatically"))
    }

    @Test
    fun attachmentCopyBlocksTextOnlyWhenUploadIsNotReady() {
        val uploading = ready.copy(readiness = AttachmentReadiness.Uploading)
        val copy = attachmentAccessibilityCopy(
            turnState = TurnState.Idle,
            attachments = listOf(uploading),
            deliveryStatus = DeliveryStatus.None,
            lastAction = null,
        )

        assertTrue(copy.contains("not ready"))
        assertTrue(copy.contains("not be sent by itself"))
    }
}
