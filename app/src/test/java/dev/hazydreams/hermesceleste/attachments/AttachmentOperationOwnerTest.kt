package dev.hazydreams.hermesceleste.attachments

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentOperationOwnerTest {
    private val owner = DraftOwner("https://gateway.example", "default", "stored-1")

    @Test
    fun lateCompletionsRequireEveryOwnerAndGenerationFieldToMatch() {
        val attachment = attachment("photo.png", generation = 8)
        val operation = AttachmentOperationOwner(
            draftOwner = owner,
            runtimeSessionIdAtStart = "runtime-1",
            editorGeneration = 8,
            attachmentId = attachment.id,
            attachmentGeneration = attachment.generation,
        )

        assertTrue(operation.accepts(owner, "runtime-1", 8, attachment))
        assertFalse(operation.accepts(owner.copy(profileId = "work"), "runtime-1", 8, attachment))
        assertFalse(operation.accepts(owner, "runtime-2", 8, attachment))
        assertTrue(
            operation.accepts(
                owner,
                "runtime-2",
                8,
                attachment,
                allowRuntimeChangeAfterStoredOwnerCheck = true,
            ),
        )
        assertFalse(
            operation.accepts(
                owner.copy(profileId = "work"),
                "runtime-2",
                8,
                attachment,
                allowRuntimeChangeAfterStoredOwnerCheck = true,
            ),
        )
        assertFalse(operation.accepts(owner, "runtime-1", 9, attachment))
        assertFalse(operation.accepts(owner, "runtime-1", 8, attachment.copy(generation = 9)))
    }

    private fun attachment(name: String, generation: Long): AttachmentDraft = AttachmentDraft(
        id = UUID.randomUUID(),
        displayName = name,
        mimeType = "image/png",
        byteSize = 16,
        localFileId = "local-${name.removeSuffix(".png")}",
        owner = owner,
        generation = generation,
        preview = AttachmentPreviewState.Ready,
        transfer = AttachmentTransferState.Ready,
    )
}
