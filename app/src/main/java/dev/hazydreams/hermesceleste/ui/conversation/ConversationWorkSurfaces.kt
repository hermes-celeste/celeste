package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.network.ChangedFile
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DelegateAgentActivity
import dev.hazydreams.hermesceleste.network.DelegateAgentLineKind
import dev.hazydreams.hermesceleste.network.DelegateAgentStatus
import dev.hazydreams.hermesceleste.network.FileEditState
import dev.hazydreams.hermesceleste.network.TaskItemStatus
import dev.hazydreams.hermesceleste.network.TaskProgress
import dev.hazydreams.hermesceleste.network.TaskProgressItem
import dev.hazydreams.hermesceleste.network.changedFiles
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteSuccess
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import dev.hazydreams.hermesceleste.ui.CelesteWarning

@Composable
internal fun ChangedFilesTranscriptEntry(
    message: ConversationMessage,
    onOpen: () -> Unit,
) {
    val files = message.changedFiles()
    if (files.isEmpty()) return
    val additions = files.sumOf(ChangedFile::additions)
    val removals = files.sumOf(ChangedFile::removals)
    val label = changedFilesLabel(files.size)
    val state = changedFilesState(files)

    Row(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CelesteSurfacePrimary)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = buildString {
                    append("Open code changes. $label")
                    if (additions > 0 || removals > 0) append(". $additions additions, $removals removals")
                }
                role = Role.Button
                stateDescription = state
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ChangedFilesIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = CelesteTextMuted,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (additions > 0 || removals > 0) {
            Spacer(Modifier.width(9.dp))
            Text(
                text = "+$additions",
                color = CelesteSuccess,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "−$removals",
                color = CelesteError,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun TaskProgressPill(
    progress: TaskProgress,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = "Tasks ${progress.completedCount}/${progress.totalCount}"
    val running = progress.items.any { it.status == TaskItemStatus.InProgress }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CelesteSurfacePrimary)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "Open tasks. ${progress.completedCount} of ${progress.totalCount} completed"
                stateDescription = if (running) "$label. One task in progress" else label
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskProgressRing(progress)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Tasks",
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${progress.completedCount}/${progress.totalCount}",
            color = CelesteTextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun DelegateAgentsPill(
    agents: List<DelegateAgentActivity>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (agents.isEmpty()) return
    val activeCount = agents.count(DelegateAgentActivity::isActive)
    val stateLabel = if (activeCount > 0) "$activeCount working" else "${agents.size} finished"
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CelesteSurfacePrimary)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "Open delegate agents. $stateLabel"
                stateDescription = stateLabel
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(delegateAgentsPillColor(agents), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Agents",
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (activeCount > 0) activeCount.toString() else agents.size.toString(),
            color = CelesteTextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TaskProgressRing(progress: TaskProgress) {
    val fraction = if (progress.totalCount == 0) 0f else progress.completedCount.toFloat() / progress.totalCount
    Canvas(modifier = Modifier.size(17.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(
            color = CelesteHairline,
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        if (fraction > 0f) {
            drawArc(
                color = CelesteAccent,
                startAngle = -90f,
                sweepAngle = fraction * 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        } else if (progress.items.any { it.status == TaskItemStatus.InProgress }) {
            drawArc(
                color = CelesteAccent,
                startAngle = -90f,
                sweepAngle = 72f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
internal fun ChangesSheetSurface(message: ConversationMessage) {
    val files = message.changedFiles()
    if (files.isEmpty()) return
    var selectedPath by remember(message.id, files) { mutableStateOf(files.first().path) }
    val additions = files.sumOf(ChangedFile::additions)
    val removals = files.sumOf(ChangedFile::removals)

    InspectionSheetSurface {
        SheetTitle("Changes")
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 640.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "changes-summary") {
                Text(
                    text = changesSummary(files.size, additions, removals),
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(files, key = ChangedFile::path) { file ->
                ChangedFileRow(
                    file = file,
                    selected = selectedPath == file.path,
                    onClick = { selectedPath = file.path },
                )
                if (selectedPath == file.path) {
                    Spacer(Modifier.size(2.dp))
                    ChangedFileDetail(file)
                }
            }
        }
    }
}

@Composable
internal fun TaskProgressSheetSurface(progress: TaskProgress) {
    InspectionSheetSurface {
        SheetTitle("Tasks")
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 620.dp),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "task-progress-summary") {
                Text(
                    text = "${progress.completedCount} of ${progress.totalCount} completed",
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = "${progress.completedCount} of ${progress.totalCount} tasks completed"
                    },
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(progress.items, key = TaskProgressItem::id) { item ->
                TaskProgressRow(item)
            }
        }
    }
}

@Composable
internal fun DelegateAgentsSheetSurface(agents: List<DelegateAgentActivity>) {
    val activeCount = agents.count(DelegateAgentActivity::isActive)
    InspectionSheetSurface {
        SheetTitle("Agents")
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 680.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "delegate-agents-summary") {
                Text(
                    text = if (activeCount > 0) "$activeCount working now" else "Latest delegation activity",
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(agents, key = DelegateAgentActivity::id) { agent ->
                DelegateAgentCard(agent)
            }
        }
    }
}

@Composable
private fun DelegateAgentCard(agent: DelegateAgentActivity) {
    val statusLabel = delegateAgentStatusLabel(agent.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CelesteSurfacePrimary)
            .semantics {
                contentDescription = "${agent.goal}. $statusLabel"
                stateDescription = statusLabel
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(9.dp)
                    .background(delegateAgentStatusColor(agent.status), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.goal,
                    color = CelesteTextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Text(
                    text = listOfNotNull(agent.model, agent.currentTool, statusLabel)
                        .joinToString(separator = " · "),
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        val lines = agent.stream.takeLast(10)
        if (lines.isEmpty()) {
            Text(
                text = agent.summary ?: if (agent.isActive) "Waiting for activity…" else "No activity details returned.",
                color = CelesteTextMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = if (line.kind == DelegateAgentLineKind.Tool) "›" else "•",
                        color = if (line.isError) CelesteError else CelesteAccent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = line.text,
                        color = if (line.isError) CelesteError else CelesteTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        color = CelesteTextPrimary,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ChangedFileRow(
    file: ChangedFile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val stateLabel = fileEditStateLabel(file.state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) CelesteSurfacePrimary else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = buildString {
                    append("${file.path}. $stateLabel")
                    if (file.additions > 0 || file.removals > 0) {
                        append(". ${file.additions} additions, ${file.removals} removals")
                    }
                }
                role = Role.Button
                stateDescription = if (selected) "Selected. $stateLabel" else stateLabel
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileStateGlyph(file.state)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.path.substringAfterLast('/'),
                color = CelesteTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            file.path.substringBeforeLast('/', missingDelimiterValue = "")
                .takeIf(String::isNotBlank)
                ?.let { directory ->
                    Text(
                        text = directory,
                        color = CelesteTextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
        }
        if (file.additions > 0 || file.removals > 0) {
            Text(
                text = "+${file.additions}  −${file.removals}",
                color = CelesteTextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ChangedFileDetail(file: ChangedFile) {
    val diff = file.diffs.joinToString(separator = "\n\n")
    if (diff.isBlank()) {
        Text(
            text = file.summary.ifBlank {
                when (file.state) {
                    FileEditState.Pending -> "Change in progress"
                    FileEditState.Completed -> "Change completed"
                    FileEditState.Failed -> "Change failed"
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = CelesteTextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val lines = diff.lineSequence().take(MAX_RENDERED_DIFF_LINES).toList()
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CelesteSurfacePrimary)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            lines.forEach { line ->
                Text(
                    text = line.ifEmpty { " " },
                    color = diffLineColor(line),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
            if (diff.lineSequence().count() > MAX_RENDERED_DIFF_LINES) {
                Text(
                    text = "… More diff lines omitted",
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun TaskProgressRow(item: TaskProgressItem) {
    val statusLabel = taskStatusLabel(item.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${item.content}. $statusLabel"
                stateDescription = statusLabel
            },
        verticalAlignment = Alignment.Top,
    ) {
        TaskStatusGlyph(item.status)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.content,
                color = if (item.status == TaskItemStatus.Cancelled) CelesteTextMuted else CelesteTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.status == TaskItemStatus.InProgress) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = statusLabel,
                color = taskStatusColor(item.status),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun FileStateGlyph(state: FileEditState) {
    val color = when (state) {
        FileEditState.Pending -> CelesteAccent
        FileEditState.Completed -> CelesteSuccess
        FileEditState.Failed -> CelesteError
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(color.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (state) {
                FileEditState.Pending -> "•"
                FileEditState.Completed -> "✓"
                FileEditState.Failed -> "!"
            },
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TaskStatusGlyph(status: TaskItemStatus) {
    val color = taskStatusColor(status)
    val base = Modifier.size(20.dp)
    Box(
        modifier = when (status) {
            TaskItemStatus.Pending -> base.border(1.dp, CelesteHairline, CircleShape)
            TaskItemStatus.InProgress -> base
                .border(2.dp, CelesteAccent, CircleShape)
                .padding(5.dp)
                .background(CelesteAccent, CircleShape)
            TaskItemStatus.Completed -> base.background(CelesteSuccess.copy(alpha = 0.15f), CircleShape)
            TaskItemStatus.Cancelled -> base.border(1.dp, CelesteTextMuted, CircleShape)
        },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = when (status) {
            TaskItemStatus.Completed -> "✓"
            TaskItemStatus.Cancelled -> "×"
            else -> ""
        }
        if (glyph.isNotEmpty()) {
            Text(
                text = glyph,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal fun changedFilesLabel(count: Int): String = if (count == 1) "Changed 1 file" else "Changed $count files"

internal fun changesSummary(count: Int, additions: Int, removals: Int): String =
    "${if (count == 1) "1 file" else "$count files"} · $additions additions · $removals removals"

internal fun taskStatusLabel(status: TaskItemStatus): String = when (status) {
    TaskItemStatus.Pending -> "Pending"
    TaskItemStatus.InProgress -> "In progress"
    TaskItemStatus.Completed -> "Completed"
    TaskItemStatus.Cancelled -> "Cancelled"
}

private fun fileEditStateLabel(state: FileEditState): String = when (state) {
    FileEditState.Pending -> "Changing"
    FileEditState.Completed -> "Changed"
    FileEditState.Failed -> "Failed"
}

private fun changedFilesState(files: List<ChangedFile>): String = when {
    files.any { it.state == FileEditState.Pending } -> "Changes in progress"
    files.any { it.state == FileEditState.Failed } -> "Some changes failed"
    else -> "Changes completed"
}

@Composable
private fun delegateAgentsPillColor(agents: List<DelegateAgentActivity>): Color = when {
    agents.any(DelegateAgentActivity::isActive) -> CelesteAccent
    agents.any { it.status == DelegateAgentStatus.Failed } -> CelesteError
    else -> CelesteSuccess
}

private fun delegateAgentStatusLabel(status: DelegateAgentStatus): String = when (status) {
    DelegateAgentStatus.Queued -> "Queued"
    DelegateAgentStatus.Running -> "Running"
    DelegateAgentStatus.Completed -> "Completed"
    DelegateAgentStatus.Failed -> "Failed"
    DelegateAgentStatus.Interrupted -> "Interrupted"
}

@Composable
private fun delegateAgentStatusColor(status: DelegateAgentStatus): Color = when (status) {
    DelegateAgentStatus.Queued -> CelesteWarning
    DelegateAgentStatus.Running -> CelesteAccent
    DelegateAgentStatus.Completed -> CelesteSuccess
    DelegateAgentStatus.Failed -> CelesteError
    DelegateAgentStatus.Interrupted -> CelesteTextMuted
}

@Composable
private fun taskStatusColor(status: TaskItemStatus): Color = when (status) {
    TaskItemStatus.Pending -> CelesteTextMuted
    TaskItemStatus.InProgress -> CelesteAccent
    TaskItemStatus.Completed -> CelesteSuccess
    TaskItemStatus.Cancelled -> CelesteTextMuted
}

@Composable
private fun diffLineColor(line: String): Color = when {
    line.startsWith("+") && !line.startsWith("+++") -> CelesteSuccess
    line.startsWith("-") && !line.startsWith("---") -> CelesteError
    line.startsWith("@@") || line.contains(" → ") -> CelesteAccent
    else -> CelesteTextPrimary
}

private val ChangedFilesIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Changed files",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(6f, 6f)
            horizontalLineTo(18f)
            moveTo(6f, 10.5f)
            horizontalLineTo(18f)
            moveTo(6f, 15f)
            horizontalLineTo(12f)
            moveTo(14f, 17f)
            lineTo(16f, 19f)
            lineTo(20f, 14f)
        }
    }.build()
}

private const val MAX_RENDERED_DIFF_LINES = 400
