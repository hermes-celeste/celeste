package dev.hazydreams.hermesceleste.attachments

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class ValidatedImage(
    val mimeType: String,
    val extension: String,
    val byteSize: Long,
)

/** Validates the image formats accepted by Hermes' image.attach_bytes RPC. */
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

    private fun detect(bytes: ByteArray): DetectedImage? = when {
        bytes.startsWith(PNG_SIGNATURE) && isPng(bytes) -> DetectedImage("image/png", ".png")
        bytes.startsWith(JPEG_SIGNATURE) && isJpeg(bytes) -> DetectedImage("image/jpeg", ".jpg")
        bytes.startsWith(GIF_SIGNATURE_87) && isGif(bytes) -> DetectedImage("image/gif", ".gif")
        bytes.startsWith(GIF_SIGNATURE_89) && isGif(bytes) -> DetectedImage("image/gif", ".gif")
        bytes.startsWith(BMP_SIGNATURE) && isBmp(bytes) -> DetectedImage("image/bmp", ".bmp")
        isWebp(bytes) -> DetectedImage("image/webp", ".webp")
        else -> null
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 33 || !hasPngHeader(bytes)) return false
        var offset = 8
        var hasImageData = false
        while (offset + 12 <= bytes.size) {
            val length = readBigEndianInt(bytes, offset)
            if (length < 0 || offset + 12L + length > bytes.size) return false
            val type = bytes.copyOfRange(offset + 4, offset + 8).decodeToString()
            if (type == "IDAT" && length > 0) hasImageData = true
            if (type == "IEND" && length == 0) {
                return hasImageData && offset + 12 == bytes.size
            }
            offset += 12 + length
        }
        return false
    }

    private fun hasPngHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 24 || !bytes.startsWith(PNG_SIGNATURE)) return false
        if (readBigEndianInt(bytes, 8) != 13) return false
        if (!bytes.copyOfRange(12, 16).contentEquals(IHDR_SIGNATURE)) return false
        return readBigEndianInt(bytes, 16) > 0 && readBigEndianInt(bytes, 20) > 0
    }

    private fun isJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || !bytes.startsWith(JPEG_SIGNATURE)) return false
        if (bytes[bytes.size - 2] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) return false
        var index = 2
        var hasFrame = false
        while (index + 3 < bytes.size) {
            if (bytes[index] != 0xff.toByte()) {
                return false
            }
            while (index < bytes.size && bytes[index] == 0xff.toByte()) index++
            if (index >= bytes.size) return false
            val marker = bytes[index++].toInt() and 0xff
            if (marker == 0xd9) return hasFrame && index == bytes.size
            if (marker == 0xda) {
                if (!hasFrame || index + 1 >= bytes.size) return false
                val length = readBigEndianShort(bytes, index)
                val scanStart = index + length
                return length >= 2 && scanStart < bytes.size - 2
            }
            if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) continue
            if (index + 1 >= bytes.size) return false
            val length = readBigEndianShort(bytes, index)
            if (length < 2 || index + length > bytes.size) return false
            if (
                marker in 0xc0..0xc3 ||
                marker in 0xc5..0xc7 ||
                marker in 0xc9..0xcb ||
                marker in 0xcd..0xcf
            ) {
                if (length < 7) return false
                val height = readBigEndianShort(bytes, index + 3)
                val width = readBigEndianShort(bytes, index + 5)
                if (width <= 0 || height <= 0) return false
                hasFrame = true
            }
            index += length
        }
        return false
    }

    private fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 14 || (!bytes.startsWith(GIF_SIGNATURE_87) && !bytes.startsWith(GIF_SIGNATURE_89))) {
            return false
        }
        if (readLittleEndianShort(bytes, 6) <= 0 || readLittleEndianShort(bytes, 8) <= 0) return false
        if (bytes.last() != 0x3b.toByte()) return false
        var offset = 13
        val packed = bytes[10].toInt() and 0xff
        if (packed and 0x80 != 0) {
            val colorTableBytes = 3 * (1 shl ((packed and 0x07) + 1))
            offset += colorTableBytes
        }
        var hasFrame = false
        while (offset < bytes.size - 1) {
            when (bytes[offset].toInt() and 0xff) {
                0x2c -> {
                    if (offset + 10 >= bytes.size) return false
                    val width = readLittleEndianShort(bytes, offset + 5)
                    val height = readLittleEndianShort(bytes, offset + 7)
                    if (width <= 0 || height <= 0) return false
                    var imageOffset = offset + 10
                    val imagePacked = bytes[imageOffset].toInt() and 0xff
                    imageOffset++
                    if (imagePacked and 0x80 != 0) {
                        val colorTableBytes = 3 * (1 shl ((imagePacked and 0x07) + 1))
                        imageOffset += colorTableBytes
                    }
                    if (imageOffset >= bytes.size - 1) return false
                    imageOffset++ // LZW minimum code size
                    if (skipGifSubBlocks(bytes, imageOffset) < 0) return false
                    hasFrame = true
                    offset = skipGifSubBlocks(bytes, imageOffset)
                }

                0x21 -> {
                    if (offset + 1 >= bytes.size) return false
                    val extensionEnd = skipGifSubBlocks(bytes, offset + 2)
                    if (extensionEnd < 0) return false
                    offset = extensionEnd
                }

                else -> return false
            }
        }
        return hasFrame
    }

    private fun skipGifSubBlocks(bytes: ByteArray, start: Int): Int {
        var offset = start
        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xff
            offset++
            if (length == 0) return offset
            if (offset + length > bytes.size) return -1
            offset += length
        }
        return -1
    }

    private fun isBmp(bytes: ByteArray): Boolean {
        if (bytes.size < 26 || !bytes.startsWith(BMP_SIGNATURE)) return false
        val headerSize = readLittleEndianInt(bytes, 14)
        val pixelOffset = readLittleEndianInt(bytes, 10)
        val fileSize = readLittleEndianInt(bytes, 2)
        val width = readLittleEndianInt(bytes, 18)
        val height = readLittleEndianInt(bytes, 22)
        return headerSize >= 12 &&
            pixelOffset in (14 + headerSize) until bytes.size &&
            (fileSize == 0 || fileSize <= bytes.size) &&
            width > 0 && height != 0
    }

    private fun isWebp(bytes: ByteArray): Boolean =
        hasWebpHeader(bytes) && webpHasImageChunk(bytes)

    private fun hasWebpHeader(bytes: ByteArray): Boolean =
        bytes.size >= 20 &&
            bytes.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
            bytes.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) &&
            readLittleEndianInt(bytes, 4) in 12..(bytes.size - 8)

    private fun webpHasImageChunk(bytes: ByteArray): Boolean {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val type = bytes.copyOfRange(offset, offset + 4).decodeToString()
            val length = readLittleEndianInt(bytes, offset + 4)
            if (length <= 0 || offset + 8L + length > bytes.size) return false
            if (
                (type == "VP8 " && length >= 10) ||
                (type == "VP8L" && length >= 5) ||
                (type == "VP8X" && length >= 10)
            ) return true
            offset += 8 + length + (length and 1)
        }
        return false
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 4 > bytes.size) -1 else
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)

    private fun readBigEndianShort(bytes: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 2 > bytes.size) -1 else
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 4 > bytes.size) -1 else
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 2 > bytes.size) -1 else
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private data class DetectedImage(
        val mimeType: String,
        val extension: String,
    )

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val GIF_SIGNATURE_87 = "GIF87a".encodeToByteArray()
        val GIF_SIGNATURE_89 = "GIF89a".encodeToByteArray()
        val BMP_SIGNATURE = byteArrayOf(0x42, 0x4d)
        val RIFF_SIGNATURE = "RIFF".encodeToByteArray()
        val WEBP_SIGNATURE = "WEBP".encodeToByteArray()
        val IHDR_SIGNATURE = "IHDR".encodeToByteArray()
    }
}
