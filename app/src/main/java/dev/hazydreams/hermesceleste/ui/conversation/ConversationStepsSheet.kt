package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.ConversationStep
import dev.hazydreams.hermesceleste.network.ConversationStepKind
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary

@Composable
internal fun StepsTranscriptEntry(
    message: ConversationMessage,
    onOpen: () -> Unit,
) {
    val count = message.steps.size
    val countLabel = "$count ${if (count == 1) "step" else "steps"}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "Open Thinking, $countLabel"
                role = Role.Button
                stateDescription = if (message.pending) "In progress" else "Complete"
            }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepStatusDot(pending = message.pending)
        Spacer(Modifier.width(11.dp))
        Text(
            text = "Thinking",
            modifier = Modifier.weight(1f),
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = StepsChevronIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = CelesteTextMuted,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationStepsSheet(
    message: ConversationMessage,
    onDismiss: () -> Unit,
) {
    if (message.steps.isEmpty()) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CelesteSurfaceRaised,
        contentColor = CelesteTextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
    ) {
        StepsSheetSurface(message)
    }
}

@Composable
internal fun StepsSheetSurface(message: ConversationMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp, bottom = 8.dp)
                .size(width = 48.dp, height = 5.dp)
                .background(CelesteHairline, RoundedCornerShape(3.dp)),
        )
        StepsSheetContent(message)
    }
}

@Composable
internal fun StepsSheetContent(message: ConversationMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Text(
            text = "Steps",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 640.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            itemsIndexed(
                items = message.steps,
                key = { index, step -> "${step.id}:$index" },
            ) { index, step ->
                StepTimelineItem(
                    step = step,
                    drawLine = index < message.steps.lastIndex || !message.pending,
                )
            }
            if (!message.pending) {
                item(key = "steps-done") { CompletedTimelineItem() }
            }
        }
    }
}

@Composable
private fun StepTimelineItem(
    step: ConversationStep,
    drawLine: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics {
                contentDescription = buildString {
                    append(stepTitle(step))
                    val detail = stepDetail(step)
                    if (detail.isNotBlank()) append(". $detail")
                }
                stateDescription = if (step.pending) "In progress" else "Complete"
            },
    ) {
        TimelineRail(pending = step.pending, drawLine = drawLine)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, bottom = 22.dp),
        ) {
            Text(
                text = stepTitle(step),
                color = CelesteTextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val detail = stepDetail(step)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TimelineRail(
    pending: Boolean,
    drawLine: Boolean,
) {
    Box(
        modifier = Modifier
            .width(18.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (drawLine) {
            Box(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(CelesteHairline),
            )
        }
        StepStatusDot(pending = pending, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun StepStatusDot(
    pending: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(if (pending) 10.dp else 8.dp)
            .background(if (pending) CelesteAccent else CelesteTextMuted, CircleShape),
    )
}

@Composable
private fun CompletedTimelineItem() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Done"
                stateDescription = "Complete"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(2.dp, CelesteTextMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✓",
                color = CelesteTextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "Done",
            modifier = Modifier.padding(start = 12.dp),
            color = CelesteTextMuted,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun stepTitle(step: ConversationStep): String = when (step.kind) {
    ConversationStepKind.Reasoning -> "Thought"
    ConversationStepKind.Tool -> when (step.toolName.orEmpty().lowercase()) {
        "terminal" -> "Ran command"
        "execute_code" -> "Ran code"
        "skill_view" -> "Viewed skill"
        "read_file" -> "Read file"
        "search_files" -> "Searched files"
        "web_search" -> "Searched the web"
        "web_extract" -> "Read web pages"
        "browser_exec" -> "Used browser"
        "patch" -> "Edited file"
        "write_file" -> "Wrote file"
        "delegate_task" -> "Delegated work"
        "todo" -> "Updated tasks"
        "image_generate" -> "Generated image"
        else -> step.toolName.orEmpty()
            .replace('_', ' ')
            .trim()
            .replaceFirstChar { character -> character.uppercase() }
            .ifBlank { "Used tool" }
    }
}

internal fun stepDetail(step: ConversationStep): String = when (step.kind) {
    ConversationStepKind.Reasoning -> step.detail.trim()
    ConversationStepKind.Tool -> listOf(step.context, step.summary)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("\n")
        .ifBlank { step.result.trim() }
        .let(::boundedStepDetail)
}

private fun boundedStepDetail(value: String, maximum: Int = 420): String {
    if (value.length <= maximum) return value
    return value.take(maximum).trimEnd() + "…"
}

private val StepsChevronIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Open steps",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineTo(9f, 19f)
        }
    }.build()
}
