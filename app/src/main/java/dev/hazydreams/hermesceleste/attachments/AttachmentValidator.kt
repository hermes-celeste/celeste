package dev.hazydreams.hermesceleste.attachments

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class ValidatedImage(
    val mimeType: String,
    val extension: String,
    val byteSize: Long,
)

object AttachmentValidator {
    private const val COPY_BUFFER_SIZE = 16 * 1024

    fun validate(
        bytes: ByteArray,
        declaredMimeType: String? = null,
        displayName: String? = null,
    ): ValidatedImage {
        if (bytes.isEmpty()) throw AttachmentValidationException(
            UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
        )
        if (bytes.size.toLong() > MAX_ATTACHMENT_BYTES) throw AttachmentValidationException(
            UserFacingAttachmentError(AttachmentErrorKind.TooLarge),
        )
        val detected = detect(bytes)
            ?: throw AttachmentValidationException(
                UserFacingAttachmentError(AttachmentErrorKind.Unsupported),
            )
        return ValidatedImage(
            mimeType = detected.mimeType,
            extension = detected.extension,
            byteSize = bytes.size.toLong(),
        )
    }

    fun validate(
        input: InputStream,
        declaredMimeType: String? = null,
        displayName: String? = null,
    ): ValidatedImage {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_ATTACHMENT_BYTES) throw AttachmentValidationException(
                UserFacingAttachmentError(AttachmentErrorKind.TooLarge),
            )
            bytes.write(buffer, 0, count)
        }
        return validate(bytes.toByteArray(), declaredMimeType, displayName)
    }

    internal fun validateMetadata(
        byteSize: Long,
        header: ByteArray,
        declaredMimeType: String?,
        displayName: String?,
    ): ValidatedImage {
        if (byteSize <= 0L) throw AttachmentValidationException(
            UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
        )
        if (byteSize > MAX_ATTACHMENT_BYTES) throw AttachmentValidationException(
            UserFacingAttachmentError(AttachmentErrorKind.TooLarge),
        )
        val detected = detect(header)
            ?: throw AttachmentValidationException(
                UserFacingAttachmentError(AttachmentErrorKind.Unsupported),
            )
        return ValidatedImage(detected.mimeType, detected.extension, byteSize)
    }

    private fun detect(bytes: ByteArray): DetectedImage? {
        if (bytes.startsWith(PNG_SIGNATURE)) return DetectedImage("image/png", ".png")
        if (bytes.startsWith(JPEG_SIGNATURE)) return DetectedImage("image/jpeg", ".jpg")
        if (bytes.startsWith(GIF_SIGNATURE)) return DetectedImage("image/gif", ".gif")
        if (bytes.startsWith(BMP_SIGNATURE)) return DetectedImage("image/bmp", ".bmp")
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
            bytes.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)
        ) {
            return DetectedImage("image/webp", ".webp")
        }
        if (bytes.size >= 12 && bytes.copyOfRange(4, 8).contentEquals(FTYP_SIGNATURE)) {
            val brand = bytes.copyOfRange(8, 12).decodeToString()
            return when {
                brand == "heic" || brand == "heix" || brand == "hevc" || brand == "hevx" ->
                    DetectedImage("image/heic", ".heic")
                brand == "avif" || brand == "avis" -> DetectedImage("image/avif", ".avif")
                else -> null
            }
        }
        return null
    }

    private data class DetectedImage(
        val mimeType: String,
        val extension: String,
    )

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val GIF_SIGNATURE = "GIF8".encodeToByteArray()
        val BMP_SIGNATURE = byteArrayOf(0x42, 0x4d)
        val RIFF_SIGNATURE = "RIFF".encodeToByteArray()
        val WEBP_SIGNATURE = "WEBP".encodeToByteArray()
        val FTYP_SIGNATURE = "ftyp".encodeToByteArray()
    }
}
