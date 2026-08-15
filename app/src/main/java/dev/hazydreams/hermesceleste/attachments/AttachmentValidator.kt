package dev.hazydreams.hermesceleste.attachments

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

internal data class ValidatedImage(
    val mimeType: String,
    val extension: String,
    val byteSize: Long,
)

/** Validates the image formats accepted by Hermes' image.attach_bytes RPC. */
object AttachmentValidator {
    private const val COPY_BUFFER_SIZE = 16 * 1024
    private const val MAX_DECODED_IMAGE_BYTES = 64L * 1024L * 1024L

    fun validate(bytes: ByteArray): ValidatedImage {
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

    fun validate(input: InputStream): ValidatedImage {
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
        return validate(bytes.toByteArray())
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
        if (bytes.size < 33 || !bytes.startsWith(PNG_SIGNATURE)) return false
        var offset = 8
        var hasHeader = false
        var hasImageData = false
        var width = 0L
        var height = 0L
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val imageData = ByteArrayOutputStream()
        while (offset + 12 <= bytes.size) {
            val length = readBigEndianUInt(bytes, offset)
            if (length > Int.MAX_VALUE) return false
            val chunkEnd = offset.toLong() + 12L + length
            if (chunkEnd > bytes.size) return false
            val dataOffset = offset + 8
            val dataLength = length.toInt()
            if (!chunkCrcMatches(bytes, offset, dataLength, dataOffset + dataLength)) return false
            val type = bytes.copyOfRange(offset + 4, offset + 8).decodeToString()
            when (type) {
                "IHDR" -> {
                    if (hasHeader || offset != 8 || dataLength != 13) return false
                    width = readBigEndianUInt(bytes, dataOffset)
                    height = readBigEndianUInt(bytes, dataOffset + 4)
                    bitDepth = bytes[dataOffset + 8].toInt() and 0xff
                    colorType = bytes[dataOffset + 9].toInt() and 0xff
                    if (
                        width <= 0L || height <= 0L ||
                        !validPngColorDepth(colorType, bitDepth) ||
                        bytes[dataOffset + 10].toInt() != 0 ||
                        bytes[dataOffset + 11].toInt() != 0 ||
                        bytes[dataOffset + 12].toInt() !in 0..1
                    ) return false
                    interlace = bytes[dataOffset + 12].toInt()
                    hasHeader = true
                }

                "IDAT" -> {
                    if (!hasHeader || dataLength == 0) return false
                    hasImageData = true
                    imageData.write(bytes, dataOffset, dataLength)
                }

                "IEND" -> {
                    if (!hasHeader || !hasImageData || dataLength != 0 || chunkEnd != bytes.size.toLong()) {
                        return false
                    }
                    return validPngPayload(
                        compressed = imageData.toByteArray(),
                        width = width,
                        height = height,
                        bitDepth = bitDepth,
                        colorType = colorType,
                        interlace = interlace,
                    )
                }
            }
            offset = chunkEnd.toInt()
        }
        return false
    }

    private fun validPngColorDepth(colorType: Int, bitDepth: Int): Boolean = when (colorType) {
        0 -> bitDepth in setOf(1, 2, 4, 8, 16)
        2 -> bitDepth in setOf(8, 16)
        3 -> bitDepth in setOf(1, 2, 4, 8)
        4, 6 -> bitDepth in setOf(8, 16)
        else -> false
    }

    private fun validPngPayload(
        compressed: ByteArray,
        width: Long,
        height: Long,
        bitDepth: Int,
        colorType: Int,
        interlace: Int,
    ): Boolean {
        val bitsPerPixel = when (colorType) {
            0 -> bitDepth
            2 -> bitDepth * 3
            3 -> bitDepth
            4 -> bitDepth * 2
            6 -> bitDepth * 4
            else -> return false
        }
        val expected = if (interlace == 0) {
            val rowBytes = (width * bitsPerPixel + 7L) / 8L
            (rowBytes + 1L).takeIf { it > 0L }?.let { row -> row * height }
        } else {
            null
        }
        if (expected != null && (expected <= 0L || expected > MAX_DECODED_IMAGE_BYTES)) return false

        val inflater = Inflater()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var produced = 0L
        try {
            inflater.setInput(compressed)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    produced += count
                    if (produced > MAX_DECODED_IMAGE_BYTES || (expected != null && produced > expected)) {
                        return false
                    }
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    return false
                }
            }
            return produced > 0L && (expected == null || produced == expected)
        } catch (_: Throwable) {
            return false
        } finally {
            inflater.end()
        }
    }

    private fun chunkCrcMatches(bytes: ByteArray, chunkOffset: Int, dataLength: Int, crcOffset: Int): Boolean {
        val crc = CRC32()
        crc.update(bytes, chunkOffset + 4, dataLength + 4)
        return crc.value == readBigEndianUInt(bytes, crcOffset)
    }

    private fun isJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 6 || !bytes.startsWith(JPEG_SIGNATURE)) return false
        var index = 2
        var hasFrame = false
        while (index < bytes.size) {
            if (bytes[index] != 0xff.toByte()) return false
            while (index < bytes.size && bytes[index] == 0xff.toByte()) index++
            if (index >= bytes.size) return false
            val marker = bytes[index++].toInt() and 0xff
            if (marker == 0xd9 || marker == 0xda && !hasFrame) return false
            if (marker == 0xda) {
                if (index + 2 > bytes.size) return false
                val length = readBigEndianShort(bytes, index)
                if (length < 2 || index + length > bytes.size) return false
                return jpegHasScanData(bytes, index + length)
            }
            if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) continue
            if (index + 2 > bytes.size) return false
            val length = readBigEndianShort(bytes, index)
            if (length < 2 || index + length > bytes.size) return false
            if (isJpegFrameMarker(marker)) {
                if (length < 8) return false
                val precision = bytes[index + 2].toInt() and 0xff
                val height = readBigEndianShort(bytes, index + 3)
                val width = readBigEndianShort(bytes, index + 5)
                val components = bytes[index + 7].toInt() and 0xff
                if (precision != 8 || width <= 0 || height <= 0 || components <= 0 || length < 8 + 3 * components) {
                    return false
                }
                hasFrame = true
            }
            index += length
        }
        return false
    }

    private fun jpegHasScanData(bytes: ByteArray, start: Int): Boolean {
        var index = start
        var dataBytes = 0
        while (index < bytes.size) {
            if (bytes[index] != 0xff.toByte()) {
                dataBytes++
                index++
                continue
            }
            while (index < bytes.size && bytes[index] == 0xff.toByte()) index++
            if (index >= bytes.size) return false
            val marker = bytes[index++].toInt() and 0xff
            when {
                marker == 0x00 -> dataBytes++
                marker in 0xd0..0xd7 -> Unit
                marker == 0xd9 -> return dataBytes > 0 && index == bytes.size
                else -> return false
            }
        }
        return false
    }

    private fun isJpegFrameMarker(marker: Int): Boolean =
        marker in 0xc0..0xc3 || marker in 0xc5..0xc7 || marker in 0xc9..0xcb || marker in 0xcd..0xcf

    private fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 14 || (!bytes.startsWith(GIF_SIGNATURE_87) && !bytes.startsWith(GIF_SIGNATURE_89))) {
            return false
        }
        if (readLittleEndianShort(bytes, 6) <= 0 || readLittleEndianShort(bytes, 8) <= 0) return false
        if (bytes.last() != 0x3b.toByte()) return false
        var offset = 13
        val packed = bytes[10].toInt() and 0xff
        if (packed and 0x80 != 0) {
            offset += 3 * (1 shl ((packed and 0x07) + 1))
            if (offset >= bytes.lastIndex) return false
        }
        var hasFrame = false
        while (offset < bytes.lastIndex) {
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
                        imageOffset += 3 * (1 shl ((imagePacked and 0x07) + 1))
                    }
                    if (imageOffset >= bytes.lastIndex) return false
                    val minimumCodeSize = bytes[imageOffset].toInt() and 0xff
                    if (minimumCodeSize !in 2..8) return false
                    imageOffset++
                    if (imageOffset >= bytes.lastIndex || bytes[imageOffset].toInt() == 0) return false
                    val end = skipGifSubBlocks(bytes, imageOffset)
                    if (end < 0) return false
                    hasFrame = true
                    offset = end
                }

                0x21 -> {
                    if (offset + 2 >= bytes.size) return false
                    val extensionEnd = skipGifSubBlocks(bytes, offset + 2)
                    if (extensionEnd < 0) return false
                    offset = extensionEnd
                }

                else -> return false
            }
        }
        return hasFrame && offset == bytes.lastIndex
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
        val pixelOffset = readLittleEndianUInt(bytes, 10)
        val fileSize = readLittleEndianUInt(bytes, 2)
        val width: Long
        val height: Long
        val bitsPerPixel: Int
        val headerEnd: Long
        if (headerSize == 12) {
            width = readLittleEndianShort(bytes, 18).toLong()
            height = readLittleEndianShort(bytes, 20).toLong()
            bitsPerPixel = readLittleEndianShort(bytes, 24)
            if (readLittleEndianShort(bytes, 22) != 1) return false
            headerEnd = 26L
        } else {
            if (headerSize < 40 || 14L + headerSize > bytes.size) return false
            width = readLittleEndianInt(bytes, 18).toLong()
            height = readLittleEndianInt(bytes, 22).toLong()
            bitsPerPixel = readLittleEndianShort(bytes, 28)
            if (readLittleEndianShort(bytes, 26) != 1 || readLittleEndianInt(bytes, 30) != 0) return false
            headerEnd = 14L + headerSize
        }
        if (width <= 0L || height == 0L || bitsPerPixel !in 1..32 || pixelOffset < headerEnd) return false
        val absoluteHeight = if (height < 0L) -height else height
        val rowBytes = ((width * bitsPerPixel + 31L) / 32L) * 4L
        val pixelBytes = rowBytes * absoluteHeight
        val pixelEnd = pixelOffset + pixelBytes
        if (rowBytes <= 0L || pixelBytes <= 0L || pixelEnd < pixelOffset || pixelEnd > bytes.size) return false
        return fileSize == 0L || (fileSize >= pixelEnd && fileSize <= bytes.size)
    }

    private fun isWebp(bytes: ByteArray): Boolean =
        hasWebpHeader(bytes) && webpHasImageChunk(bytes)

    private fun hasWebpHeader(bytes: ByteArray): Boolean =
        bytes.size >= 20 &&
            bytes.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
            bytes.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) &&
            readLittleEndianUInt(bytes, 4) + 8L == bytes.size.toLong()

    private fun webpHasImageChunk(bytes: ByteArray): Boolean {
        val end = bytes.size
        var offset = 12
        var hasImageData = false
        while (offset + 8 <= end) {
            val type = bytes.copyOfRange(offset, offset + 4).decodeToString()
            val length = readLittleEndianUInt(bytes, offset + 4)
            val contentEnd = offset.toLong() + 8L + length
            val paddedEnd = contentEnd + (length and 1L)
            if (contentEnd > end || paddedEnd > end) return false
            val payload = offset + 8
            when (type) {
                "VP8 " -> {
                    if (length < 10L || bytes[payload] != 0x9d.toByte() ||
                        bytes[payload + 1] != 0x01.toByte() || bytes[payload + 2] != 0x2a.toByte()
                    ) return false
                    if ((readLittleEndianShort(bytes, payload + 6) and 0x3fff) <= 0 ||
                        (readLittleEndianShort(bytes, payload + 8) and 0x3fff) <= 0
                    ) return false
                    hasImageData = true
                }

                "VP8L" -> {
                    if (length < 5L || bytes[payload].toInt() and 0xff != 0x2f) return false
                    val width = 1 + ((bytes[payload + 1].toInt() and 0xff) or
                        ((bytes[payload + 2].toInt() and 0x3f) shl 8))
                    val height = 1 + (((bytes[payload + 2].toInt() and 0xc0) shr 6) or
                        ((bytes[payload + 3].toInt() and 0xff) shl 2) or
                        ((bytes[payload + 4].toInt() and 0x0f) shl 10))
                    if (width <= 0 || height <= 0) return false
                    hasImageData = true
                }

                "VP8X" -> {
                    if (length < 10L) return false
                    val width = 1 + (bytes[payload + 4].toInt() and 0xff) +
                        ((bytes[payload + 5].toInt() and 0xff) shl 8) +
                        ((bytes[payload + 6].toInt() and 0xff) shl 16)
                    val height = 1 + (bytes[payload + 7].toInt() and 0xff) +
                        ((bytes[payload + 8].toInt() and 0xff) shl 8) +
                        ((bytes[payload + 9].toInt() and 0xff) shl 16)
                    if (width <= 0 || height <= 0) return false
                }
            }
            offset = paddedEnd.toInt()
        }
        return offset == end && hasImageData
    }

    private fun readBigEndianUInt(bytes: ByteArray, offset: Int): Long =
        if (offset < 0 || offset + 4 > bytes.size) -1L else
            ((bytes[offset].toLong() and 0xff) shl 24) or
                ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or
                (bytes[offset + 3].toLong() and 0xff)

    private fun readBigEndianShort(bytes: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 2 > bytes.size) -1 else
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readLittleEndianUInt(bytes: ByteArray, offset: Int): Long =
        if (offset < 0 || offset + 4 > bytes.size) -1L else
            (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        readLittleEndianUInt(bytes, offset).toInt()

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
    }
}
