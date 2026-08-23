package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.network.BackgroundProcessResult
import dev.hazydreams.hermesceleste.network.BackgroundProcessState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteSuccess
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary

@Composable
internal fun ProcessResultTranscriptEntry(
    message: ConversationMessage,
    onOpen: () -> Unit,
) {
    val result = message.processResult ?: return
    val statusLabel = processResultStatusLabel(result)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "Open background process details. $statusLabel"
                role = Role.Button
                stateDescription = statusLabel
            }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProcessTerminalGlyph()
        Spacer(Modifier.width(9.dp))
        Text(
            text = "Background process",
            modifier = Modifier.weight(1f),
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = InspectionChevronIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = CelesteTextMuted,
        )
    }
}

@Composable
internal fun ProcessResultSheetSurface(result: BackgroundProcessResult) {
    InspectionSheetSurface {
        ProcessResultSheetContent(result)
    }
}

@Composable
internal fun ProcessResultSheetContent(result: BackgroundProcessResult) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Background process",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 640.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(key = "process-status") {
                ProcessStatusSummary(result)
            }
            item(key = "process-command") {
                ProcessResultSection(
                    label = "Command",
                    value = result.command,
                )
            }
            item(key = "process-output") {
                ProcessResultSection(
                    label = "Output",
                    value = processResultOutputText(result),
                )
            }
        }
    }
}

@Composable
private fun ProcessTerminalGlyph() {
    Icon(
        imageVector = ProcessTerminalIcon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = CelesteTextMuted,
    )
}

@Composable
private fun ProcessStatusSummary(result: BackgroundProcessResult) {
    val statusLabel = processResultStatusLabel(result)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append(statusLabel)
                    result.exitCode?.let { append(". Exit code $it") }
                    append(". Process ${result.processId}")
                }
                stateDescription = statusLabel
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProcessStatusGlyph(result.state)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = statusLabel,
                color = CelesteTextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = result.exitCode?.let { "Exit code $it · ${result.processId}" } ?: result.processId,
                color = CelesteTextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProcessStatusGlyph(state: BackgroundProcessState) {
    val successful = state == BackgroundProcessState.Completed
    val color = if (successful) CelesteSuccess else CelesteError
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(color.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (successful) "✓" else "!",
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ProcessResultSection(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            color = CelesteTextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CelesteSurfacePrimary)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = value,
                    color = CelesteTextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        }
    }
}

internal fun processResultStatusLabel(result: BackgroundProcessResult): String =
    if (result.state == BackgroundProcessState.Completed) "Completed" else "Failed"

internal fun processResultOutputText(result: BackgroundProcessResult): String {
    val output = result.output.ifBlank { "No output" }
    return if (result.outputTruncated) "… Earlier output omitted\n$output" else output
}

private val ProcessTerminalIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Terminal",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 4f)
            lineTo(18f, 4f)
            curveTo(19.1f, 4f, 20f, 4.9f, 20f, 6f)
            lineTo(20f, 18f)
            curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f)
            lineTo(6f, 20f)
            curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f)
            lineTo(4f, 6f)
            curveTo(4f, 4.9f, 4.9f, 4f, 6f, 4f)
            close()
            moveTo(7.5f, 8f)
            lineTo(11.5f, 12f)
            lineTo(7.5f, 16f)
            moveTo(13f, 16f)
            lineTo(17f, 16f)
        }
    }.build()
}
