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
        val reference = parseImageDirective(line)
        if (reference != null) {
            references += reference
        } else {
            retained += line
        }
    }
    return NormalizedImageReferences(
        visibleText = retained.joinToString("\n").trim(),
        references = references,
    )
}

private fun parseImageDirective(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("@image:")) return null
    val rawReference = trimmed.removePrefix("@image:").trim()
    if (rawReference.isBlank()) return null
    if (rawReference.first() == '`') {
        if (rawReference.length < 2 || rawReference.last() != '`') return null
        return rawReference.substring(1, rawReference.length - 1).takeIf(String::isNotBlank)
    }
    // Unquoted references cannot contain whitespace; imageDirective quotes those.
    if (rawReference.any(Char::isWhitespace) || rawReference.contains('`')) return null
    return rawReference
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
        else -> "image/png"
    },
    byteSize = 0L,
    serverReference = reference,
    preview = AttachmentPreviewState.Pending,
)
