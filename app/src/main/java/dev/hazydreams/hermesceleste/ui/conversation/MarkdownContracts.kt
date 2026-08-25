package dev.hazydreams.hermesceleste.ui.conversation

/** Only hand explicit web links to the host platform. */
internal fun allowedMarkdownUri(uri: String): Boolean {
    if (uri.isBlank() || uri.any(Char::isWhitespace)) return false
    val schemeEnd = uri.indexOf("://")
    if (schemeEnd <= 0) return false
    val scheme = uri.substring(0, schemeEnd)
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
        return false
    }
    val authorityStart = schemeEnd + 3
    val authorityEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
        .let { if (it == -1) uri.length else it }
    return authorityEnd > authorityStart
}

/** Conversation images load only from explicit encrypted web destinations. */
internal fun allowedConversationImageUri(uri: String): Boolean =
    allowedMarkdownUri(uri) && uri.substringBefore("://").equals("https", ignoreCase = true)

internal data class MarkdownImageTarget(
    val url: String,
    val alt: String,
)

/** Reads the direct image form handled by the conversation renderer. */
internal fun markdownImageTarget(source: String): MarkdownImageTarget? {
    if (!source.startsWith("![") || !source.endsWith(')')) return null
    val destinationStart = source.indexOf("](", startIndex = 2)
    if (destinationStart < 0) return null

    val alt = source.substring(2, destinationStart).replace("\\]", "]")
    val destinationAndTitle = source.substring(destinationStart + 2, source.lastIndex).trim()
    if (destinationAndTitle.isEmpty()) return null

    val destination = if (destinationAndTitle.startsWith('<')) {
        val angleEnd = destinationAndTitle.indexOf('>', startIndex = 1)
        if (angleEnd <= 1) return null
        destinationAndTitle.substring(1, angleEnd)
    } else {
        destinationAndTitle.takeWhile { !it.isWhitespace() }
    }
    return destination.takeIf(String::isNotBlank)?.let { MarkdownImageTarget(url = it, alt = alt) }
}

internal sealed interface AssistantContentBlock {
    data class Text(val content: String) : AssistantContentBlock

    data class GatewayImage(
        val path: String,
        val alt: String,
    ) : AssistantContentBlock
}

/** Separates settled Hermes MEDIA output while leaving ordinary prose untouched. */
internal fun assistantContentBlocks(
    content: String,
    allowGatewayImages: Boolean = true,
): List<AssistantContentBlock> {
    if (!allowGatewayImages) return listOf(AssistantContentBlock.Text(content))
    val blocks = mutableListOf<AssistantContentBlock>()
    val textLines = mutableListOf<String>()

    fun flushText() {
        val text = textLines.joinToString("\n").trim('\n')
        if (text.isNotBlank()) blocks += AssistantContentBlock.Text(text)
        textLines.clear()
    }

    content.lineSequence().forEach { line ->
        val path = gatewayMediaPath(line)
        if (path == null) {
            textLines += line
        } else {
            flushText()
            blocks += AssistantContentBlock.GatewayImage(
                path = path,
                alt = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "Image" },
            )
        }
    }
    flushText()
    return blocks.ifEmpty { listOf(AssistantContentBlock.Text(content)) }
}

private fun gatewayMediaPath(line: String): String? {
    val unwrapped = line.trim().removeMatchingQuotes()
    if (!unwrapped.startsWith("MEDIA:")) return null
    val path = unwrapped.removePrefix("MEDIA:").trim().removeMatchingQuotes()
    if (path.isBlank()) return null
    val windowsAbsolute = path.length >= 3 && path[0].isLetter() && path[1] == ':' &&
        (path[2] == '\\' || path[2] == '/')
    return path.takeIf { it.startsWith('/') || windowsAbsolute }
}

private fun String.removeMatchingQuotes(): String {
    if (length < 2 || first() != last() || first() !in charArrayOf('`', '"', '\'')) return this
    return substring(1, lastIndex).trim()
}

/** Returns the append-only suffix, or null when a recovered stream replaced prior text. */
internal fun markdownStreamDelta(rendered: String, incoming: String): String? =
    if (incoming.startsWith(rendered)) incoming.removePrefix(rendered) else null

private val orderedListMarker = Regex("^\\d+[.)]\\s+")
private val tableSeparator = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")

/** Keeps ordinary prose on Compose Text and starts the parser only for actual rich syntax. */
internal fun containsRichMarkdown(content: String): Boolean {
    if (content.contains("```") || content.contains("~~~")) return true
    if (content.contains("~~") || content.contains("](") || content.contains("][")) return true
    if (hasPairedDelimiter(content, '*') || hasPairedDelimiter(content, '_') || hasPairedDelimiter(content, '`')) {
        return true
    }

    return content.lineSequence().any { line ->
        val trimmed = line.trimStart()
        val headingDepth = trimmed.takeWhile { it == '#' }.length
        (headingDepth in 1..6 && trimmed.getOrNull(headingDepth) == ' ') ||
            trimmed.startsWith(">") ||
            trimmed.startsWith("- ") ||
            trimmed.startsWith("+ ") ||
            trimmed.startsWith("* ") ||
            orderedListMarker.containsMatchIn(trimmed) ||
            tableSeparator.matches(trimmed) ||
            trimmed == "---" || trimmed == "***" || trimmed == "___"
    }
}

private fun hasPairedDelimiter(content: String, delimiter: Char): Boolean {
    val first = content.indexOf(delimiter)
    return first >= 0 && content.indexOf(delimiter, startIndex = first + 1) > first
}
