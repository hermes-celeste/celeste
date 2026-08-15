package dev.hazydreams.hermesceleste.attachments

import java.util.UUID

const val MAX_PENDING_ATTACHMENTS = 4
const val MAX_ATTACHMENT_BYTES = 24L * 1024L * 1024L
const val MAX_TRANSCRIPT_PREVIEW_BYTES = 256L * 1024L
const val MAX_ATTACHMENT_RETRIES = 3
const val MAX_SUBMIT_RETRIES = 3

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

data class AttachmentOperationOwner(
    val draftOwner: DraftOwner,
    val runtimeSessionIdAtStart: String?,
    val editorGeneration: Long,
    val attachmentId: UUID,
    val attachmentGeneration: Long,
)

fun AttachmentOperationOwner.accepts(
    owner: DraftOwner,
    runtimeSessionId: String?,
    editorGeneration: Long,
    attachment: AttachmentDraft,
    allowRuntimeChangeAfterStoredOwnerCheck: Boolean = false,
): Boolean =
    draftOwner == owner &&
        (allowRuntimeChangeAfterStoredOwnerCheck || runtimeSessionIdAtStart == runtimeSessionId) &&
        this.editorGeneration == editorGeneration &&
        attachmentId == attachment.id &&
        attachmentGeneration == attachment.generation &&
        attachment.owner == owner
