package dev.hazydreams.hermesceleste.attachments

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentReducerTest {
    private val owner = DraftOwner("https://gateway.example", "default", "stored-1")

    @Test
    fun capsPickerResultsWithoutReorderingTheFirstFourItems() {
        val items = (1..5).map { attachment("image-$it.png", generation = 9) }

        val result = AttachmentReducer.capPickerSelection(items)

        assertEquals(items.take(4), result.accepted)
        assertEquals(1, result.droppedCount)
        assertEquals(listOf("image-1.png", "image-2.png", "image-3.png", "image-4.png"), result.accepted.map { it.displayName })
    }

    @Test
    fun removalInvalidatesTheItemAndLeavesOtherItemsInOrder() {
        val first = attachment("first.png", generation = 3)
        val second = attachment("second.png", generation = 3)
        val draft = ComposerDraft(owner, "caption", listOf(first, second), generation = 3)

        val reduced = requireNotNull(
            AttachmentReducer.remove(draft, first.id, expectedGeneration = 3),
        )

        assertEquals(listOf(second.id), reduced.attachments.map { it.id })
        assertEquals(4, reduced.generation)
        assertNull(AttachmentReducer.remove(draft, first.id, expectedGeneration = 2))
    }

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

        assertTrue(AttachmentReducer.accepts(operation, owner, "runtime-1", 8, attachment))
        assertFalse(AttachmentReducer.accepts(operation, owner.copy(profileId = "work"), "runtime-1", 8, attachment))
        assertFalse(AttachmentReducer.accepts(operation, owner, "runtime-2", 8, attachment))
        assertTrue(
            AttachmentReducer.accepts(
                operation,
                owner,
                "runtime-2",
                8,
                attachment,
                allowRuntimeChangeAfterStoredOwnerCheck = true,
            ),
        )
        assertFalse(
            AttachmentReducer.accepts(
                operation,
                owner.copy(profileId = "work"),
                "runtime-2",
                8,
                attachment,
                allowRuntimeChangeAfterStoredOwnerCheck = true,
            ),
        )
        assertFalse(AttachmentReducer.accepts(operation, owner, "runtime-1", 9, attachment))
        assertFalse(AttachmentReducer.accepts(operation, owner, "runtime-1", 8, attachment.copy(generation = 9)))
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
