package dev.hazydreams.hermesceleste.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class TaskItemStatus {
    Pending,
    InProgress,
    Completed,
    Cancelled,
}

data class TaskProgressItem(
    val id: String,
    val content: String,
    val status: TaskItemStatus,
)

data class TaskProgress(
    val items: List<TaskProgressItem>,
) {
    val completedCount: Int get() = items.count { it.status == TaskItemStatus.Completed }
    val totalCount: Int get() = items.size
    val isActive: Boolean get() = items.any {
        it.status == TaskItemStatus.Pending || it.status == TaskItemStatus.InProgress
    }
    val isFinished: Boolean get() = items.isNotEmpty() && !isActive
}

data class TaskProgressSnapshot(
    val progress: TaskProgress?,
)

enum class FileEditState {
    Pending,
    Completed,
    Failed,
}

data class FileEditOperation(
    val toolId: String,
    val paths: List<String>,
    val diff: String = "",
    val summary: String = "",
    val state: FileEditState = FileEditState.Pending,
)

data class ChangedFile(
    val path: String,
    val diffs: List<String>,
    val summary: String,
    val state: FileEditState,
    val additions: Int,
    val removals: Int,
)

internal data class DecodedGatewayConversation(
    val messages: List<ConversationMessage>,
    val taskProgressSnapshot: TaskProgressSnapshot?,
) {
    val taskProgress: TaskProgress? get() = taskProgressSnapshot?.progress
}

private data class ChangedFileAccumulator(
    val path: String,
    val diffs: MutableList<String> = mutableListOf(),
    var summary: String = "",
    var state: FileEditState = FileEditState.Completed,
)

fun ConversationMessage.changedFiles(): List<ChangedFile> {
    val files = linkedMapOf<String, ChangedFileAccumulator>()
    fileEdits.forEach { edit ->
        val paths = edit.paths.ifEmpty { listOf("Changed file") }
        paths.forEach { path ->
            val accumulator = files.getOrPut(path) { ChangedFileAccumulator(path = path) }
            diffForPath(edit.diff, path, paths.size)?.takeIf(String::isNotBlank)?.let(accumulator.diffs::add)
            if (edit.summary.isNotBlank()) accumulator.summary = edit.summary
            accumulator.state = combinedFileEditState(accumulator.state, edit.state)
        }
    }
    return files.values.map { file ->
        val additions = file.diffs.sumOf { diff -> diffLineStats(diff).first }
        val removals = file.diffs.sumOf { diff -> diffLineStats(diff).second }
        ChangedFile(
            path = file.path,
            diffs = file.diffs,
            summary = file.summary,
            state = file.state,
            additions = additions,
            removals = removals,
        )
    }
}

internal fun isFileEditTool(name: String): Boolean = name in FILE_EDIT_TOOLS

internal fun startFileEditInCurrentTurn(
    messages: List<ConversationMessage>,
    toolId: String,
    paths: List<String>,
    changesMessageId: String,
): List<ConversationMessage> {
    val nextEdit = FileEditOperation(
        toolId = toolId,
        paths = paths.distinct(),
        state = FileEditState.Pending,
    )
    val index = currentTurnChangesIndex(messages)
    if (index < 0) {
        return messages + ConversationMessage(
            role = "changes",
            text = "",
            id = changesMessageId,
            pending = true,
            fileEdits = listOf(nextEdit),
        )
    }
    val message = messages[index]
    val existingIndex = message.fileEdits.indexOfFirst { it.toolId == toolId }
    val edits = if (existingIndex < 0) {
        message.fileEdits + nextEdit
    } else {
        message.fileEdits.toMutableList().also { edits ->
            val previous = edits[existingIndex]
            edits[existingIndex] = previous.copy(
                paths = paths.distinct().ifEmpty { previous.paths },
                state = FileEditState.Pending,
            )
        }
    }
    return messages.toMutableList().also { next ->
        next[index] = message.copy(fileEdits = edits, pending = true)
    }
}

internal fun completeFileEditInCurrentTurn(
    messages: List<ConversationMessage>,
    toolId: String,
    paths: List<String>,
    diff: String,
    summary: String,
    failed: Boolean,
    changesMessageId: String,
): List<ConversationMessage> {
    val boundedDiff = boundedNormalizedDiff(diff)
    val state = if (failed) FileEditState.Failed else FileEditState.Completed
    val index = currentTurnChangesIndex(messages)
    if (index < 0) {
        return messages + ConversationMessage(
            role = "changes",
            text = "",
            id = changesMessageId,
            pending = false,
            fileEdits = listOf(
                FileEditOperation(
                    toolId = toolId,
                    paths = paths.distinct(),
                    diff = boundedDiff,
                    summary = summary,
                    state = state,
                ),
            ),
        )
    }
    val message = messages[index]
    val existingIndex = message.fileEdits.indexOfFirst { it.toolId == toolId }
    val edits = if (existingIndex < 0) {
        message.fileEdits + FileEditOperation(
            toolId = toolId,
            paths = paths.distinct(),
            diff = boundedDiff,
            summary = summary,
            state = state,
        )
    } else {
        message.fileEdits.toMutableList().also { edits ->
            val previous = edits[existingIndex]
            edits[existingIndex] = previous.copy(
                paths = paths.distinct().ifEmpty { previous.paths },
                diff = boundedDiff.ifBlank { previous.diff },
                summary = summary.ifBlank { previous.summary },
                state = state,
            )
        }
    }
    return messages.toMutableList().also { next ->
        next[index] = message.copy(
            fileEdits = edits,
            pending = edits.any { it.state == FileEditState.Pending },
        )
    }
}

internal fun fileEditPaths(
    name: String,
    args: JsonObject?,
    diff: String = "",
): List<String> = buildList {
    args?.string("path")?.takeIf(String::isNotBlank)?.let(::add)
    if (name == "patch" && args?.string("mode") == "patch") {
        val patch = args.string("patch").orEmpty()
        V4A_FILE_PATTERN.findAll(patch).forEach { match ->
            match.groupValues[1].trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }
    if (name == "skill_manage") {
        val skillName = args?.string("name").orEmpty()
        val action = args?.string("action").orEmpty()
        val filePath = args?.string("file_path")
        if (skillName.isNotBlank()) {
            when (action) {
                "create", "edit", "patch" -> add("skills/$skillName/${filePath ?: "SKILL.md"}")
                "write_file", "remove_file" -> filePath?.let { add("skills/$skillName/$it") }
            }
        }
    }
    diffSections(boundedNormalizedDiff(diff)).mapNotNullTo(this) { it.path }
}.map(::cleanPath).filter(String::isNotBlank).distinct()

internal fun fileEditDiff(payload: JsonObject): String {
    payload.string("inline_diff")?.takeIf(String::isNotBlank)?.let { return it }
    val result = payload["result"]
    result.jsonObjectOrNull()?.string("diff")?.takeIf(String::isNotBlank)?.let { return it }
    result.jsonObjectFromString()?.string("diff")?.takeIf(String::isNotBlank)?.let { return it }
    payload.string("result_text")
        ?.let(::jsonObjectFromText)
        ?.string("diff")
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    return ""
}

internal fun toolArguments(payload: JsonObject): JsonObject? =
    payload["args"].jsonObjectOrNull()
        ?: payload["args"].jsonObjectFromString()
        ?: payload.string("args_text")?.let(::jsonObjectFromText)

internal fun toolCompletionFailed(payload: JsonObject): Boolean {
    if (toolErrorValue(payload["error"])) return true
    val resultObject = payload["result"].jsonObjectOrNull()
        ?: payload["result"].jsonObjectFromString()
        ?: payload.string("result_text")?.let(::jsonObjectFromText)
    return toolResultFailed(resultObject)
}

internal fun toolResultFailed(result: JsonObject?): Boolean = toolErrorValue(result?.get("error"))

private fun toolErrorValue(value: JsonElement?): Boolean = when (value) {
    null -> false
    is JsonPrimitive -> value.contentOrNull?.let { content ->
        content.isNotBlank() && !content.equals("false", ignoreCase = true)
    } == true
    else -> true
}

internal fun taskProgressSnapshotFromPayload(payload: JsonObject): TaskProgressSnapshot? {
    val todos = payload["todos"] as? JsonArray
        ?: payload["result"].jsonObjectOrNull()?.get("todos") as? JsonArray
        ?: payload["result"].jsonObjectFromString()?.get("todos") as? JsonArray
        ?: payload["args"].jsonObjectOrNull()?.get("todos") as? JsonArray
        ?: payload["args"].jsonObjectFromString()?.get("todos") as? JsonArray
        ?: payload.string("result_text")?.let(::jsonObjectFromText)?.get("todos") as? JsonArray
    return decodeTaskProgressSnapshot(todos)
}

internal fun decodeTaskProgressSnapshot(todos: JsonArray?): TaskProgressSnapshot? {
    if (todos == null) return null
    if (todos.isEmpty()) return TaskProgressSnapshot(progress = null)
    return decodeTaskProgress(todos)?.let(::TaskProgressSnapshot)
}

internal fun decodeTaskProgress(todos: JsonArray?): TaskProgress? {
    if (todos == null) return null
    val items = linkedMapOf<String, TaskProgressItem>()
    todos.forEach { element ->
        val item = element as? JsonObject ?: return@forEach
        val id = item.string("id")?.takeIf(String::isNotBlank) ?: return@forEach
        val content = item.string("content")?.takeIf(String::isNotBlank) ?: return@forEach
        val status = when (item.string("status")) {
            "pending" -> TaskItemStatus.Pending
            "in_progress" -> TaskItemStatus.InProgress
            "completed" -> TaskItemStatus.Completed
            "cancelled" -> TaskItemStatus.Cancelled
            else -> return@forEach
        }
        items[id] = TaskProgressItem(id = id, content = content, status = status)
    }
    return items.values.toList().takeIf { it.isNotEmpty() }?.let(::TaskProgress)
}

private fun currentTurnChangesIndex(messages: List<ConversationMessage>): Int {
    val turnStart = messages.indexOfLast { it.role == "user" }
    for (index in (turnStart + 1)..messages.lastIndex) {
        if (messages[index].role == "changes") return index
    }
    return -1
}

private fun combinedFileEditState(current: FileEditState, next: FileEditState): FileEditState = when {
    current == FileEditState.Pending || next == FileEditState.Pending -> FileEditState.Pending
    current == FileEditState.Failed || next == FileEditState.Failed -> FileEditState.Failed
    else -> FileEditState.Completed
}

private data class DiffSection(val path: String?, val text: String)

private fun diffForPath(diff: String, path: String, pathCount: Int): String? {
    val normalized = boundedNormalizedDiff(diff)
    if (normalized.isBlank()) return null
    if (pathCount <= 1) return normalized
    val target = cleanPath(path)
    return diffSections(normalized).firstOrNull { section ->
        section.path == target
    }?.text ?: normalized
}

private fun diffSections(diff: String): List<DiffSection> {
    if (diff.isBlank()) return emptyList()
    val sections = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    diff.lineSequence().forEach { line ->
        val beginsSection = line.startsWith("--- ") || line.contains(" → ")
        if (beginsSection && current.isNotEmpty()) {
            sections += current
            current = mutableListOf()
        }
        current += line
    }
    if (current.isNotEmpty()) sections += current
    return sections.map { lines ->
        val arrowPath = lines.firstNotNullOfOrNull { line ->
            line.takeIf { it.contains(" → ") }?.substringAfter(" → ")?.trim()
        }
        val destinationPath = lines.firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("+++ ") }
                ?.removePrefix("+++ ")
                ?.trim()
                ?.takeUnless { it == "/dev/null" }
        }
        val sourcePath = lines.firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("--- ") }
                ?.removePrefix("--- ")
                ?.trim()
                ?.takeUnless { it == "/dev/null" }
        }
        val path = arrowPath ?: destinationPath ?: sourcePath
        DiffSection(path = path?.let(::normalizedDiffPath), text = lines.joinToString("\n"))
    }
}

private fun diffLineStats(diff: String): Pair<Int, Int> {
    var additions = 0
    var removals = 0
    val lines = diff.lineSequence().toList()
    val headerLines = diffHeaderLineIndexes(lines)
    lines.forEachIndexed { index, line ->
        when {
            index in headerLines -> Unit
            line.startsWith("+") -> additions += 1
            line.startsWith("-") -> removals += 1
        }
    }
    return additions to removals
}

private fun boundedNormalizedDiff(diff: String): String {
    val normalized = ANSI_PATTERN.replace(diff, "")
        .lineSequence()
        .dropWhile { it.trim() == "┊ review diff" }
        .joinToString("\n")
        .trim()
    if (normalized.length <= MAX_RETAINED_DIFF_CHARS) return normalized
    return normalized.take(MAX_RETAINED_DIFF_CHARS).trimEnd() + "\n… Diff truncated"
}

private fun diffHeaderLineIndexes(lines: List<String>): Set<Int> = buildSet {
    for (index in 0 until lines.lastIndex) {
        if (!lines[index].startsWith("--- ") || !lines[index + 1].startsWith("+++ ")) continue
        val before = lines.getOrNull(index - 1)
        val after = lines.getOrNull(index + 2)
        val structuralPair = index == 0 ||
            before?.startsWith("diff --git ") == true ||
            after?.startsWith("@@") == true ||
            after?.startsWith("Binary files ") == true
        if (structuralPair) {
            add(index)
            add(index + 1)
        }
    }
}

private fun cleanPath(path: String): String = path.trim().removeSurrounding("\"")

private fun normalizedDiffPath(path: String): String {
    val clean = cleanPath(path)
    return when {
        clean.startsWith("a/") -> clean.removePrefix("a/")
        clean.startsWith("b/") -> clean.removePrefix("b/")
        else -> clean
    }
}

private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.jsonObjectFromString(): JsonObject? =
    (this as? JsonPrimitive)?.contentOrNull?.let(::jsonObjectFromText)

private fun jsonObjectFromText(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

private val FILE_EDIT_TOOLS = setOf("patch", "write_file", "skill_manage")
private val V4A_FILE_PATTERN = Regex("""(?m)^\*\*\* (?:Update|Add|Delete) File:\s*(.+)$""")
private val ANSI_PATTERN = Regex("""\u001B\[[0-9;]*m""")
private const val MAX_RETAINED_DIFF_CHARS = 60_000
