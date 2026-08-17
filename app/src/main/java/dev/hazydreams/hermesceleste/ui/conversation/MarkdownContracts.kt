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
