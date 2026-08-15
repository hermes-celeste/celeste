package dev.hazydreams.hermesceleste.attachments

/** Builds the current Hermes-compatible raw prompt without exposing refs in UI text. */
fun buildImagePrompt(
    caption: String,
    serverReferences: List<String>,
): String {
    val visibleCaption = caption.trim()
    val directives = serverReferences
        .filter(String::isNotBlank)
        .map(::imageDirective)
    return (listOf(visibleCaption).filter(String::isNotBlank) + directives).joinToString("\n")
}

fun imageDirective(serverReference: String): String {
    val reference = serverReference.trim()
    return if (reference.any(Char::isWhitespace) || reference.contains('`')) {
        "@image:`${reference.replace("`", "")}`"
    } else {
        "@image:$reference"
    }
}

data class NormalizedImageReferences(
    val visibleText: String,
    val references: List<String>,
)

/** Moves only standalone @image directive lines out of visible transcript text. */
fun normalizeImageReferences(rawText: String): NormalizedImageReferences {
    if (rawText.isBlank()) return NormalizedImageReferences("", emptyList())
    val retained = mutableListOf<String>()
    val references = mutableListOf<String>()
    rawText.split('\n').forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("@image:")) {
            val rawReference = trimmed.removePrefix("@image:").trim()
            val reference = if (rawReference.length >= 2 &&
                rawReference.first() == '`' && rawReference.last() == '`'
            ) {
                rawReference.substring(1, rawReference.length - 1)
            } else {
                rawReference
            }
            if (reference.isNotBlank()) references += reference
        } else {
            retained += line
        }
    }
    if (references.isNotEmpty()) {
        retained.removeAll { it.trim() == "[screenshot]" }
    }
    return NormalizedImageReferences(
        visibleText = retained.joinToString("\n").trim(),
        references = references,
    )
}

fun messageAttachmentFromReference(
    reference: String,
    index: Int,
    messageId: String?,
): MessageAttachment = MessageAttachment(
    id = "${messageId ?: "message"}:attachment:$index",
    displayName = reference
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .takeIf(String::isNotBlank)
        ?.take(160),
    mimeType = when (reference.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        else -> "image/png"
    },
    byteSize = 0L,
    serverReference = reference,
    preview = AttachmentPreviewState.Unavailable,
)
