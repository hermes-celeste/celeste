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
    var activeFence: Pair<Char, Int>? = null
    var gatewayImageCount = 0

    fun flushText() {
        val text = textLines.joinToString("\n").trim('\n')
        if (text.isNotBlank()) blocks += AssistantContentBlock.Text(text)
        textLines.clear()
    }

    content.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        val currentFence = activeFence
        if (currentFence != null) {
            textLines += line
            if (line.closesFence(currentFence)) {
                activeFence = null
            }
            return@forEach
        }
        val fence = fenceMarker(trimmed)
        if (!line.isIndentedCodeLine() && fence != null) {
            activeFence = fence
            textLines += line
            return@forEach
        }

        val path = if (!line.isIndentedCodeLine() && gatewayImageCount < MAX_GATEWAY_IMAGES_PER_MESSAGE) {
            gatewayMediaPath(line)
        } else {
            null
        }
        if (path == null) {
            textLines += line
        } else {
            flushText()
            gatewayImageCount += 1
            blocks += AssistantContentBlock.GatewayImage(
                path = path,
                alt = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "Image" },
            )
        }
    }
    flushText()
    return blocks.ifEmpty { listOf(AssistantContentBlock.Text(content)) }
}

private fun fenceMarker(line: String): Pair<Char, Int>? {
    val marker = line.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = line.takeWhile { it == marker }.length
    return (marker to length).takeIf { length >= 3 }
}

private fun String.closesFence(openFence: Pair<Char, Int>): Boolean {
    val indentation = takeWhile { it == ' ' }.length
    if (indentation > 3 || startsWith('\t')) return false
    val trimmed = drop(indentation)
    val marker = trimmed.firstOrNull()?.takeIf { it == openFence.first } ?: return false
    val length = trimmed.takeWhile { it == marker }.length
    return length >= openFence.second && trimmed.drop(length).isBlank()
}

private fun String.isIndentedCodeLine(): Boolean = startsWith('\t') || takeWhile { it == ' ' }.length >= 4

private fun gatewayMediaPath(line: String): String? {
    val unwrapped = line.trim().removeMatchingQuotes()
    if (!unwrapped.startsWith("MEDIA:")) return null
    val path = unwrapped.removePrefix("MEDIA:").trim().removeMatchingQuotes()
    if (path.isBlank()) return null
    val windowsAbsolute = path.length >= 3 && path[0].isLetter() && path[1] == ':' &&
        (path[2] == '\\' || path[2] == '/')
    val imageExtension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return path.takeIf {
        (it.startsWith('/') || windowsAbsolute) && imageExtension in gatewayImageExtensions
    }
}

private fun String.removeMatchingQuotes(): String {
    if (length < 2 || first() != last() || first() !in charArrayOf('"', '\'')) return this
    return substring(1, lastIndex).trim()
}

private const val MAX_GATEWAY_IMAGES_PER_MESSAGE = 4
private val gatewayImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

/** Returns the append-only suffix, or null when a recovered stream replaced prior text. */
internal fun markdownStreamDelta(rendered: String, incoming: String): String? =
    if (incoming.startsWith(rendered)) incoming.removePrefix(rendered) else null

/** Keeps ordinary Markdown image syntax readable without resolving its destination. */
internal fun containsMarkdownImageSyntax(content: String): Boolean = content.contains("![")

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
