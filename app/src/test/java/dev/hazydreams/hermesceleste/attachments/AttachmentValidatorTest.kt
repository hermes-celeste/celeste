package dev.hazydreams.hermesceleste.attachments

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentValidatorTest {
    @Test
    fun usesTheImageSignatureWhenPickerMetadataIsMissingOrGeneric() {
        val validated = AttachmentValidator.validate(
            bytes = pngBytes,
            declaredMimeType = "application/octet-stream",
            displayName = "camera export",
        )

        assertEquals("image/png", validated.mimeType)
        assertEquals(".png", validated.extension)
        assertEquals(pngBytes.size.toLong(), validated.byteSize)
    }

    @Test
    fun rejectsEmptyMalformedAndUnsupportedImagesWithRedactedMessages() {
        val empty = assertThrows(AttachmentValidationException::class.java) {
            AttachmentValidator.validate(ByteArray(0), "image/png", "content://private/empty.png")
        }
        val malformed = assertThrows(AttachmentValidationException::class.java) {
            AttachmentValidator.validate("not an image".encodeToByteArray(), "image/png", "/private/secret.png")
        }

        assertEquals("Couldn't read this image", empty.userError.message)
        assertEquals("Unsupported image", malformed.userError.message)
        assertFalse(empty.message.orEmpty().contains("content://"))
        assertFalse(empty.message.orEmpty().contains("private"))
        assertFalse(malformed.message.orEmpty().contains("secret"))
    }

    @Test
    fun enforcesTheTwentyFourMebibyteClientLimitBeforeUpload() {
        val oversized = ByteArray((MAX_ATTACHMENT_BYTES + 1).toInt())
        oversized[0] = 0x89.toByte()
        oversized[1] = 0x50
        oversized[2] = 0x4e
        oversized[3] = 0x47
        oversized[4] = 0x0d
        oversized[5] = 0x0a
        oversized[6] = 0x1a
        oversized[7] = 0x0a

        val failure = assertThrows(AttachmentValidationException::class.java) {
            AttachmentValidator.validate(oversized, "image/png", "large.png")
        }

        assertEquals("Image is too large (24 MiB maximum)", failure.userError.message)
    }

    @Test
    fun boundedStagingUsesRandomPrivateIdsAndCleansUp() = runBlocking {
        val root = Files.createTempDirectory("celeste-attachments").toFile()
        val store = FileAttachmentStagingStore(root)
        val owner = DraftOwner("https://gateway.example", "default", "session-1")

        val first = store.stage(
            input = ByteArrayInputStream(pngBytes),
            displayName = "first.png",
            declaredMimeType = "image/png",
            owner = owner,
            generation = 4,
        )
        val second = store.stage(
            input = ByteArrayInputStream(pngBytes),
            displayName = "second.png",
            declaredMimeType = "image/png",
            owner = owner,
            generation = 4,
        )

        assertNotEquals(first.attachment.localFileId, second.attachment.localFileId)
        assertTrue(first.file.isFile)
        assertTrue(first.file.parentFile == root)
        assertEquals(pngBytes.toList(), store.readBytes(first.attachment.localFileId).toList())

        store.delete(first.attachment.localFileId)
        assertFalse(first.file.exists())
        assertTrue(second.file.exists())
    }

    private companion object {
        val pngBytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
