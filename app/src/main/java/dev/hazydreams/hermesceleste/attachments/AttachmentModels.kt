package dev.hazydreams.hermesceleste.attachments

import java.util.UUID

const val MAX_PENDING_ATTACHMENTS = 4
const val MAX_ATTACHMENT_BYTES = 24L * 1024L * 1024L
const val MAX_ATTACHMENT_RETRIES = 3

enum class AttachmentSource {
    PhotoPicker,
}

enum class AttachmentPreviewState {
    Pending,
    Ready,
    Unavailable,
}

enum class AttachmentTransferState {
    Preparing,
    Ready,
    Uploading,
    Staged,
    Failed,
    Unknown,
}

enum class AttachmentCapabilityState {
    Unknown,
    Supported,
    Unsupported,
    TransientFailure,
    AuthRequired,
}

enum class ImageOnlyCapabilityState {
    Unknown,
    Supported,
    Unsupported,
}

enum class AttachmentErrorKind(
    val message: String,
    val retryable: Boolean,
) {
    ReadFailed("Couldn't read this image", true),
    Unsupported("Unsupported image", true),
    TooLarge("Image is too large (24 MiB maximum)", true),
    UploadFailed("Couldn't upload this image", true),
    UploadStatusUnknown("Upload status unknown — Retry", true),
    UnsupportedGateway("This gateway does not support image attachments", false),
    AuthenticationRequired("Reconnect to send this image", true),
    PreviewUnavailable("Image unavailable", true),
}

data class UserFacingAttachmentError(
    val kind: AttachmentErrorKind,
) {
    val message: String get() = kind.message
    val retryable: Boolean get() = kind.retryable
}

class AttachmentValidationException(
    val userError: UserFacingAttachmentError,
    cause: Throwable? = null,
) : java.io.IOException(userError.message, cause)

data class DraftOwner(
    val normalizedGatewayOrigin: String,
    val profileId: String,
    val storedSessionIdOrNewConversationId: String,
)

data class FileAttachment(
    val localFileId: String,
    val displayName: String?,
    val mimeType: String,
    val byteSize: Long,
    val owner: DraftOwner,
    val generation: Long,
)

data class AttachmentDraft(
    val id: UUID = UUID.randomUUID(),
    val displayName: String? = null,
    val mimeType: String = "image/*",
    val byteSize: Long = 0L,
    val source: AttachmentSource = AttachmentSource.PhotoPicker,
    val localFileId: String = "",
    val preview: AttachmentPreviewState = AttachmentPreviewState.Pending,
    val transfer: AttachmentTransferState = AttachmentTransferState.Preparing,
    val error: UserFacingAttachmentError? = null,
    val serverReference: String? = null,
    /** Small in-memory preview only; raw source bytes remain in the staging store. */
    val previewBytes: ByteArray? = null,
    val owner: DraftOwner,
    val generation: Long,
)

data class MessageAttachment(
    val id: String,
    val displayName: String?,
    val mimeType: String,
    val byteSize: Long,
    val serverReference: String?,
    val preview: AttachmentPreviewState,
    val previewBytes: ByteArray? = null,
)

data class ComposerDraft(
    val key: DraftOwner,
    val text: String,
    val attachments: List<AttachmentDraft>,
    val generation: Long,
)

data class AttachmentOperationOwner(
    val draftOwner: DraftOwner,
    val runtimeSessionIdAtStart: String?,
    val editorGeneration: Long,
    val attachmentId: UUID,
    val attachmentGeneration: Long,
)

data class PickerSelection(
    val accepted: List<AttachmentDraft>,
    val droppedCount: Int,
)

object AttachmentReducer {
    fun capPickerSelection(
        attachments: List<AttachmentDraft>,
        maximum: Int = MAX_PENDING_ATTACHMENTS,
    ): PickerSelection {
        val accepted = attachments.take(maximum.coerceAtLeast(0))
        return PickerSelection(accepted = accepted, droppedCount = attachments.size - accepted.size)
    }

    fun remove(
        draft: ComposerDraft,
        attachmentId: UUID,
        expectedGeneration: Long,
    ): ComposerDraft? {
        if (draft.generation != expectedGeneration || draft.attachments.none { it.id == attachmentId }) {
            return null
        }
        return draft.copy(
            attachments = draft.attachments.filterNot { it.id == attachmentId },
            generation = draft.generation + 1,
        )
    }

    fun accepts(
        operation: AttachmentOperationOwner,
        owner: DraftOwner,
        runtimeSessionId: String?,
        editorGeneration: Long,
        attachment: AttachmentDraft,
        allowRuntimeChangeAfterStoredOwnerCheck: Boolean = false,
    ): Boolean =
        operation.draftOwner == owner &&
            (allowRuntimeChangeAfterStoredOwnerCheck || operation.runtimeSessionIdAtStart == runtimeSessionId) &&
            operation.editorGeneration == editorGeneration &&
            operation.attachmentId == attachment.id &&
            operation.attachmentGeneration == attachment.generation &&
            attachment.owner == owner
}
