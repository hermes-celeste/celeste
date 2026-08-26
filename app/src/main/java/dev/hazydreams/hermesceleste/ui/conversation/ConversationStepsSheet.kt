package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "Open Thinking, $countLabel"
                role = Role.Button
                stateDescription = if (message.pending) "In progress" else "Complete"
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThinkingPulse(pending = message.pending)
        Spacer(Modifier.width(9.dp))
        Text(
            text = "Thinking",
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = InspectionChevronIcon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = CelesteTextMuted,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
internal fun StepsSheetSurface(message: ConversationMessage) {
    InspectionSheetSurface {
        StepsSheetContent(message)
    }
}

@Composable
private fun ThinkingPulse(
    pending: Boolean,
    modifier: Modifier = Modifier,
) {
    val offsets = listOf(1.dp, (-2).dp, 1.dp)
    if (!pending) {
        Row(
            modifier = modifier.width(23.dp).height(12.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            offsets.forEachIndexed { index, offset ->
                Box(
                    Modifier
                        .offset(y = offset)
                        .size(if (index == 1) 5.dp else 4.dp)
                        .background(CelesteTextMuted.copy(alpha = if (index == 1) 0.9f else 0.55f), CircleShape),
                )
            }
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "Thinking pulse")
    Row(
        modifier = modifier.width(23.dp).height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        offsets.forEachIndexed { index, offset ->
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 150, StartOffsetType.FastForward),
                ),
                label = "Thinking pulse ${index + 1}",
            )
            Box(
                Modifier
                    .offset(y = offset)
                    .size(if (index == 1) 5.dp else 4.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = 0.75f + alpha * 0.25f
                        scaleY = scaleX
                    }
                    .background(CelesteAccent, CircleShape),
            )
        }
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
    ConversationStepKind.Reasoning -> plainReasoningDetail(step.detail)
    ConversationStepKind.Tool -> listOf(step.context, step.summary)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("\n")
        .ifBlank { step.result.trim() }
        .let(::boundedStepDetail)
}

private fun plainReasoningDetail(value: String): String {
    val lines = mutableListOf<String>()
    var activeFence: String? = null

    value.trim().lineSequence().forEach { line ->
        val fence = activeFence
        when {
            fence != null && closesReasoningFence(line, fence) -> activeFence = null
            fence != null -> lines += line.trimEnd()
            else -> {
                val openingFence = reasoningFenceMarker(line)
                if (openingFence != null) {
                    activeFence = openingFence
                } else {
                    lines += plainReasoningLine(line)
                }
            }
        }
    }

    return lines.joinToString("\n")
}

private fun plainReasoningLine(value: String): String {
    val line = value
        .replace(ReasoningHeadingPrefix, "")
        .replace(ReasoningListPrefix, "")
        .replace(ReasoningQuotePrefix, "")
        .let(::unwrapReasoningDecoration)
    return line.replace(ReasoningInlineCode, "$1").trimEnd()
}

private fun unwrapReasoningDecoration(value: String): String {
    val trimmed = value.trim()
    val marker = when {
        trimmed.startsWith("**") && trimmed.endsWith("**") -> "**"
        trimmed.startsWith("~~") && trimmed.endsWith("~~") -> "~~"
        else -> return value
    }
    if (trimmed.length < marker.length * 2) return value
    val content = trimmed.substring(marker.length, trimmed.length - marker.length)

    // Whole-line prose emphasis is presentation; compact technical tokens stay literal.
    return content.takeIf { inner -> inner.any(Char::isWhitespace) } ?: value
}

private fun reasoningFenceMarker(value: String): String? {
    val content = value.dropWhile { character -> character == ' ' }
    if (value.length - content.length > 3) return null
    val markerCharacter = content.firstOrNull()?.takeIf { character ->
        character == '`' || character == '~'
    } ?: return null
    return content.takeWhile { character -> character == markerCharacter }
        .takeIf { marker -> marker.length >= 3 }
}

private fun closesReasoningFence(value: String, openingFence: String): Boolean {
    val content = value.dropWhile { character -> character == ' ' }
    if (value.length - content.length > 3) return false
    val marker = content.takeWhile { character -> character == openingFence.first() }
    return marker.length >= openingFence.length && content.drop(marker.length).isBlank()
}

private val ReasoningHeadingPrefix = Regex("""^\s{0,3}#{1,6}\s+""")
private val ReasoningListPrefix = Regex("""^\s{0,3}[-+*]\s+""")
private val ReasoningQuotePrefix = Regex("""^\s*>\s?""")
private val ReasoningInlineCode = Regex("""(?<!`)`([^`\n]+)`(?!`)""")

private fun boundedStepDetail(value: String, maximum: Int = 420): String {
    if (value.length <= maximum) return value
    return value.take(maximum).trimEnd() + "…"
}
