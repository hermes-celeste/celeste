package dev.hazydreams.hermesceleste.network

enum class BackgroundProcessState {
    Completed,
    Failed,
}

data class BackgroundProcessResult(
    val processId: String,
    val state: BackgroundProcessState,
    val status: String,
    val command: String,
    val output: String,
    val exitCode: Int?,
    val outputTruncated: Boolean = false,
)

internal fun parseBackgroundProcessResult(value: String): BackgroundProcessResult? {
    val normalized = value.replace("\r\n", "\n").trim()
    if (!normalized.startsWith(PROCESS_NOTIFICATION_PREFIX) || !normalized.endsWith(']')) return null

    val headerEnd = normalized.indexOf('\n')
    if (headerEnd < 0) return null
    val header = normalized.substring(0, headerEnd)
    val headerMatch = PROCESS_NOTIFICATION_HEADER.matchEntire(header) ?: return null
    val commandMarker = "\nCommand: "
    val commandStart = normalized.indexOf(commandMarker, startIndex = headerEnd)
    if (commandStart < 0) return null
    val outputMarker = "\nOutput:\n"
    val outputStart = normalized.indexOf(outputMarker, startIndex = commandStart + commandMarker.length)
    if (outputStart < 0) return null

    val processId = headerMatch.groupValues[1]
    val status = headerMatch.groupValues[2]
    val exitCode = headerMatch.groupValues[3].toIntOrNull()
    val command = normalized.substring(commandStart + commandMarker.length, outputStart)
        .trim()
        .take(MAX_PROCESS_COMMAND_CHARS)
        .ifBlank { "Background process" }
    val rawOutput = normalized.substring(outputStart + outputMarker.length, normalized.length - 1)
        .trimEnd()
    val outputTruncated = rawOutput.length > MAX_PROCESS_OUTPUT_CHARS
    val output = if (outputTruncated) {
        rawOutput.takeLast(MAX_PROCESS_OUTPUT_CHARS).trimStart()
    } else {
        rawOutput
    }
    val completed = status == "completed normally" && exitCode == 0

    return BackgroundProcessResult(
        processId = processId,
        state = if (completed) BackgroundProcessState.Completed else BackgroundProcessState.Failed,
        status = status.replaceFirstChar(Char::uppercase),
        command = command,
        output = output,
        exitCode = exitCode,
        outputTruncated = outputTruncated,
    )
}

internal fun upsertBackgroundProcessResult(
    messages: List<ConversationMessage>,
    result: BackgroundProcessResult,
    messageId: String = "process:${result.processId}",
): List<ConversationMessage> {
    val next = ConversationMessage(
        role = "process",
        text = "",
        id = messageId,
        processResult = result,
    )
    val existingIndex = messages.indexOfFirst { message ->
        message.role == "process" && message.processResult?.processId == result.processId
    }
    if (existingIndex < 0) return appendCurrentTurnMessage(messages, next)
    return messages.toMutableList().also { it[existingIndex] = next }
}

private const val PROCESS_NOTIFICATION_PREFIX = "[IMPORTANT: Background process "
private const val MAX_PROCESS_COMMAND_CHARS = 4_000
internal const val MAX_PROCESS_OUTPUT_CHARS = 12_000
private val PROCESS_NOTIFICATION_HEADER = Regex(
    """^\[IMPORTANT: Background process (\S+) (.+) \(exit code ([^,)]+)(?:, SIGTERM)?\)\.$""",
)
