package dev.hazydreams.hermesceleste.attachments

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AttachmentStagingStore {
    suspend fun stageUri(
        resolver: ContentResolver?,
        uri: Uri,
        owner: DraftOwner,
        generation: Long,
    ): StagedAttachment

    suspend fun stage(
        input: InputStream,
        displayName: String?,
        owner: DraftOwner,
        generation: Long,
    ): StagedAttachment

    suspend fun readBytes(localFileId: String): ByteArray

    suspend fun delete(localFileId: String): Boolean
}

data class StagedAttachment(
    val attachment: FileAttachment,
    val file: File,
    val previewBytes: ByteArray? = null,
)

/**
 * App-private attachment bytes. The caller supplies the no-backup root owned by
 * CF-03; this class deliberately stores only random local IDs, never content URIs.
 */
class FileAttachmentStagingStore(
    private val root: File,
) : AttachmentStagingStore {
    init {
        require(root.mkdirs() || root.isDirectory) { "Attachment storage is unavailable." }
    }

    override suspend fun stageUri(
        resolver: ContentResolver?,
        uri: Uri,
        owner: DraftOwner,
        generation: Long,
    ): StagedAttachment = withContext(Dispatchers.IO) {
        val contentResolver = resolver ?: throw AttachmentValidationException(
            UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
        )
        val metadata = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to
                    (if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null)
            }
        }.getOrNull()
        val displayName = sanitizeDisplayName(metadata?.first) ?: "Image"
        val input = try {
            contentResolver.openInputStream(uri)
                ?: throw AttachmentValidationException(
                    UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
                )
        } catch (error: SecurityException) {
            throw AttachmentValidationException(
                UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
                error,
            )
        } catch (error: IOException) {
            throw AttachmentValidationException(
                UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
                error,
            )
        }
        input.use {
            stage(it, displayName, owner, generation)
        }
    }

    override suspend fun stage(
        input: InputStream,
        displayName: String?,
        owner: DraftOwner,
        generation: Long,
    ): StagedAttachment = withContext(Dispatchers.IO) {
        val localFileId = java.util.UUID.randomUUID().toString()
        val temporary = File(root, ".$localFileId.tmp")
        val destination = File(root, localFileId)
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > MAX_ATTACHMENT_BYTES) {
                        throw AttachmentValidationException(
                            UserFacingAttachmentError(AttachmentErrorKind.TooLarge),
                        )
                    }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            val validated = FileInputStream(temporary).use {
                AttachmentValidator.validate(it)
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            StagedAttachment(
                attachment = FileAttachment(
                    localFileId = localFileId,
                    displayName = sanitizeDisplayName(displayName) ?: "Image",
                    mimeType = validated.mimeType,
                    byteSize = validated.byteSize,
                    owner = owner,
                    generation = generation,
                ),
                file = destination,
                previewBytes = createPreview(destination),
            )
        } catch (error: AttachmentValidationException) {
            throw error
        } catch (error: IOException) {
            throw AttachmentValidationException(
                UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
                error,
            )
        } finally {
            temporary.delete()
        }
    }

    override suspend fun readBytes(localFileId: String): ByteArray = withContext(Dispatchers.IO) {
        val file = resolve(localFileId)
        if (!file.isFile) throw AttachmentValidationException(
            UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
        )
        if (file.length() <= 0L) throw AttachmentValidationException(
            UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
        )
        if (file.length() > MAX_ATTACHMENT_BYTES) throw AttachmentValidationException(
            UserFacingAttachmentError(AttachmentErrorKind.TooLarge),
        )
        file.readBytes()
    }

    override suspend fun delete(localFileId: String): Boolean = withContext(Dispatchers.IO) {
        val file = resolve(localFileId)
        !file.exists() || file.delete()
    }

    private fun resolve(localFileId: String): File {
        require(LOCAL_ID_REGEX.matches(localFileId)) { "Invalid attachment identifier." }
        return File(root, localFileId)
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 16 * 1024
        val LOCAL_ID_REGEX = Regex("[0-9a-fA-F-]{36}")

        fun createPreview(file: File): ByteArray? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > 192 || bounds.outHeight / sampleSize > 192) {
                    sampleSize = (sampleSize * 2).coerceAtMost(1 shl 16)
                    if (sampleSize == 1 shl 16) break
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
                try {
                    ByteArrayOutputStream().use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            null
                        } else {
                            output.toByteArray().takeIf {
                                it.isNotEmpty() && it.size.toLong() <= MAX_TRANSCRIPT_PREVIEW_BYTES
                            }
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            } catch (_: Throwable) {
                // Preview decoding is optional; a valid staged image remains sendable.
                null
            }
        }

        fun sanitizeDisplayName(value: String?): String? = value
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(160)
    }
}

/** Used by unit-only ViewModel instances until the application injects CF-03 storage. */
class UnavailableAttachmentStagingStore : AttachmentStagingStore {
    override suspend fun stageUri(
        resolver: ContentResolver?,
        uri: Uri,
        owner: DraftOwner,
        generation: Long,
    ): StagedAttachment = throw AttachmentValidationException(
        UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
    )

    override suspend fun stage(
        input: InputStream,
        displayName: String?,
        owner: DraftOwner,
        generation: Long,
    ): StagedAttachment = throw AttachmentValidationException(
        UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
    )

    override suspend fun readBytes(localFileId: String): ByteArray = throw AttachmentValidationException(
        UserFacingAttachmentError(AttachmentErrorKind.ReadFailed),
    )

    override suspend fun delete(localFileId: String): Boolean = true
}
