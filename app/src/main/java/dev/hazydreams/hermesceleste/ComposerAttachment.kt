package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage

enum class ComposerAttachmentKind {
    Image,
    File,
}

data class PickedComposerAttachment(
    val kind: ComposerAttachmentKind,
    val name: String,
    val mimeType: String,
    val contentBytes: ByteArray,
    val byteSize: Long,
)

data class AttachmentImportBudget(
    val attachmentCount: Int,
    val byteSize: Long,
)

data class ComposerAttachment(
    val id: String,
    val kind: ComposerAttachmentKind,
    val name: String,
    val mimeType: String,
    val contentBytes: ByteArray,
    val byteSize: Long,
    val attachedRuntimeSessionId: String? = null,
    val remotePath: String? = null,
    val refText: String? = null,
) {
    val accessibilityLabel: String
        get() = "${if (kind == ComposerAttachmentKind.Image) "Image" else "File"}: $name"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ComposerAttachment &&
            id == other.id &&
            kind == other.kind &&
            name == other.name &&
            mimeType == other.mimeType &&
            contentBytes.contentEquals(other.contentBytes) &&
            byteSize == other.byteSize &&
            attachedRuntimeSessionId == other.attachedRuntimeSessionId &&
            remotePath == other.remotePath &&
            refText == other.refText

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + contentBytes.contentHashCode()
        result = 31 * result + byteSize.hashCode()
        result = 31 * result + (attachedRuntimeSessionId?.hashCode() ?: 0)
        result = 31 * result + (remotePath?.hashCode() ?: 0)
        result = 31 * result + (refText?.hashCode() ?: 0)
        return result
    }
}

data class ConversationAttachment(
    val kind: ComposerAttachmentKind,
    val name: String,
)

internal fun ComposerAttachment.conversationSummary(): ConversationAttachment =
    ConversationAttachment(kind = kind, name = name)

internal fun preserveLocalAttachmentPresentation(
    authoritative: List<ConversationMessage>,
    local: List<ConversationMessage>,
): List<ConversationMessage> {
    val authoritativeUsers = authoritative.filter { it.role == "user" }
    val localUsers = local.filter { it.role == "user" }
    if (authoritativeUsers.size != localUsers.size || localUsers.none { it.attachments.isNotEmpty() }) {
        return authoritative
    }

    var userIndex = 0
    return authoritative.map { message ->
        if (message.role != "user") return@map message
        val localMessage = localUsers[userIndex++]
        if (localMessage.attachments.isEmpty()) {
            message
        } else {
            message.copy(
                text = localMessage.text,
                attachments = localMessage.attachments,
            )
        }
    }
}
