package dev.hazydreams.hermesceleste

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

internal class AndroidAttachmentReader(
    private val contentResolver: ContentResolver,
) {
    fun read(
        uris: List<Uri>,
        kind: ComposerAttachmentKind,
        existingAttachmentCount: Int = 0,
        existingAttachmentBytes: Long = 0L,
    ): AttachmentReadResult {
        val attachments = mutableListOf<PickedComposerAttachment>()
        val errors = mutableListOf<String>()
        val remainingAttachmentCount = (AttachmentLimits.MAX_COUNT - existingAttachmentCount).coerceAtLeast(0)
        var totalBytes = existingAttachmentBytes.coerceIn(0L, AttachmentLimits.MAX_TOTAL_BYTES)
        uris.take(remainingAttachmentCount).forEach { uri ->
            runCatching {
                val remainingBytes = AttachmentLimits.MAX_TOTAL_BYTES - totalBytes
                if (remainingBytes <= 0L) throw IOException(TOTAL_SIZE_ERROR)
                readOne(uri, kind, remainingBytes)
            }.onSuccess { attachment ->
                totalBytes += attachment.byteSize
                attachments += attachment
            }.onFailure { error ->
                errors += error.message ?: "That attachment could not be read."
            }
        }
        if (uris.size > remainingAttachmentCount) {
            errors += "The composer holds up to ${AttachmentLimits.MAX_COUNT} attachments."
        }
        return AttachmentReadResult(attachments = attachments, errors = errors)
    }

    private fun readOne(
        uri: Uri,
        kind: ComposerAttachmentKind,
        remainingBytes: Long,
    ): PickedComposerAttachment {
        val metadata = queryMetadata(uri)
        if (metadata.size != null && metadata.size > AttachmentLimits.MAX_ITEM_BYTES) {
            throw IOException(
                "${metadata.name} is larger than ${AttachmentLimits.MAX_ITEM_MEGABYTES} MB.",
            )
        }
        val readLimit = minOf(AttachmentLimits.MAX_ITEM_BYTES, remainingBytes)
        if (metadata.size != null && metadata.size > readLimit) throw IOException(TOTAL_SIZE_ERROR)
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            readBounded(input, readLimit, metadata.size)
        }
            ?: throw IOException("${metadata.name} could not be opened.")
        val mimeType = contentResolver.getType(uri)
            ?.takeIf(String::isNotBlank)
            ?: if (kind == ComposerAttachmentKind.Image) "image/*" else "application/octet-stream"
        val resolvedKind = if (mimeType.startsWith("image/", ignoreCase = true)) {
            ComposerAttachmentKind.Image
        } else {
            kind
        }
        return PickedComposerAttachment(
            kind = resolvedKind,
            name = metadata.name,
            mimeType = mimeType,
            contentBytes = bytes,
            byteSize = bytes.size.toLong(),
        )
    }

    private fun queryMetadata(uri: Uri): AttachmentMetadata {
        var name: String? = null
        var size: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val safeName = name
            ?.takeIf(String::isNotBlank)
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf(String::isNotBlank)
            ?: "attachment"
        return AttachmentMetadata(
            name = safeName,
            size = size,
        )
    }

    private fun readBounded(
        input: java.io.InputStream,
        maxBytes: Long,
        expectedBytes: Long?,
    ): ByteArray {
        val initialCapacity = minOf(
            expectedBytes?.coerceAtLeast(0L) ?: DEFAULT_BUFFER_SIZE.toLong(),
            maxBytes,
            Int.MAX_VALUE.toLong(),
        ).toInt()
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IOException(
                    if (maxBytes < AttachmentLimits.MAX_ITEM_BYTES) {
                        TOTAL_SIZE_ERROR
                    } else {
                        "This attachment is larger than ${AttachmentLimits.MAX_ITEM_MEGABYTES} MB."
                    },
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private data class AttachmentMetadata(
        val name: String,
        val size: Long?,
    )

    companion object {
        private const val TOTAL_SIZE_ERROR = "The selected attachments are larger than 50 MB together."
    }
}

internal data class AttachmentReadResult(
    val attachments: List<PickedComposerAttachment>,
    val errors: List<String>,
)
