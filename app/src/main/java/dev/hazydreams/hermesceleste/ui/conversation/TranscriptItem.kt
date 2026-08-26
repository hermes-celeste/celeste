package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceSelected
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.StatusMessage

internal const val STREAMING_TRANSCRIPT_KEY = "streaming:assistant"

internal fun streamingTranscriptKey(sessionId: String): String = "$STREAMING_TRANSCRIPT_KEY:$sessionId"

internal fun transcriptItemKeys(messages: List<ConversationMessage>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return messages.mapIndexed { index, message ->
        val id = message.id?.takeIf(String::isNotBlank)
            ?: return@mapIndexed "transcript:fallback:$index"
        val base = "transcript:id:${id.length}:$id"
        val occurrence = occurrences.getOrDefault(base, 0) + 1
        occurrences[base] = occurrence
        if (occurrence == 1) base else "$base:occurrence:$occurrence"
    }
}

@Composable
internal fun MessageBubble(
    message: ConversationMessage,
    streaming: Boolean = false,
    initiallyExpandedUserMessage: Boolean = false,
    onOpenInspection: () -> Unit = {},
    onClarificationRespond: (messageId: String, requestId: String, answer: String) -> Unit = { _, _, _ -> },
    gatewayImageLoader: (suspend (String) -> ByteArray?)? = null,
    gatewayImageScope: Any? = null,
) {
    when (message.role) {
        "user" -> UserMessage(message, streaming, initiallyExpandedUserMessage)
        "assistant" -> AssistantMessage(message, streaming, gatewayImageLoader, gatewayImageScope)
        "clarification" -> ClarificationTranscriptEntry(message, onClarificationRespond)
        "steps" -> StepsTranscriptEntry(message, onOpenInspection)
        "process" -> ProcessResultTranscriptEntry(message, onOpenInspection)
        "changes" -> ChangedFilesTranscriptEntry(message, onOpenInspection)
        else -> LabeledMessage(message)
    }
}

private const val USER_MESSAGE_COLLAPSE_CHARACTER_THRESHOLD = 120
private const val USER_MESSAGE_COLLAPSED_LINE_COUNT = 3

internal fun shouldCollapseUserMessage(text: String): Boolean {
    val content = text.trim()
    if (content.isEmpty()) return false
    return content.lineSequence().count() > USER_MESSAGE_COLLAPSED_LINE_COUNT ||
        content.length > USER_MESSAGE_COLLAPSE_CHARACTER_THRESHOLD
}

@Composable
private fun UserMessage(
    message: ConversationMessage,
    streaming: Boolean,
    initiallyExpanded: Boolean,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val richContent = containsRichMarkdown(message.text)
        val maximumPillWidth = maxWidth * 0.8f
        val collapsible = !streaming && shouldCollapseUserMessage(message.text)
        var expanded by rememberSaveable(message.id, message.text) {
            mutableStateOf(initiallyExpanded && collapsible)
        }
        val collapsedTextHeight = with(LocalDensity.current) {
            MaterialTheme.typography.bodyMedium.lineHeight.toDp() * USER_MESSAGE_COLLAPSED_LINE_COUNT
        }
        val interactionModifier = if (collapsible) {
            Modifier
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Collapse user message" else "Expand user message",
                ) { expanded = !expanded }
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
        } else {
            Modifier
        }
        Column(
            modifier = (if (richContent) {
                Modifier.width(maximumPillWidth)
            } else {
                Modifier
                    .widthIn(max = maximumPillWidth)
                    .wrapContentWidth()
            })
                .background(CelesteSurfaceSelected, RoundedCornerShape(18.dp))
                .then(interactionModifier)
                .padding(horizontal = 15.dp, vertical = 11.dp),
        ) {
            if (message.attachments.isNotEmpty()) {
                TranscriptAttachmentSummaries(
                    attachments = message.attachments,
                    pending = message.pending,
                )
                if (message.text.isNotBlank()) Spacer(Modifier.height(8.dp))
            }
            if (message.text.isNotBlank()) {
                Box {
                    RichMarkdown(
                        content = message.text,
                        streaming = streaming,
                        modifier = if (collapsible && !expanded) {
                            Modifier
                                .heightIn(max = collapsedTextHeight)
                                .clipToBounds()
                        } else {
                            Modifier
                        },
                        widthPolicy = if (richContent) {
                            MarkdownWidthPolicy.Fill
                        } else {
                            MarkdownWidthPolicy.WrapContent
                        },
                    )
                    if (collapsible && !expanded) {
                        Icon(
                            imageVector = InspectionChevronIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(CelesteSurfaceSelected, RoundedCornerShape(7.dp))
                                .padding(start = 6.dp, top = 2.dp)
                                .size(16.dp)
                                .rotate(90f),
                            tint = CelesteAccent,
                        )
                    }
                }
                if (collapsible && expanded) {
                    Icon(
                        imageVector = InspectionChevronIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp)
                            .size(16.dp)
                            .rotate(-90f),
                        tint = CelesteAccent,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ConversationMessage,
    streaming: Boolean,
    gatewayImageLoader: (suspend (String) -> ByteArray?)?,
    gatewayImageScope: Any?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        assistantContentBlocks(
            content = message.text,
            allowGatewayImages = !streaming,
        ).forEach { block ->
            when (block) {
                is AssistantContentBlock.Text -> RichMarkdown(
                    content = block.content,
                    streaming = streaming,
                )

                is AssistantContentBlock.GatewayImage -> ConversationImage(
                    path = block.path,
                    alt = block.alt,
                    gatewayImageLoader = gatewayImageLoader,
                    gatewayImageScope = gatewayImageScope,
                )
            }
        }
        message.errorMessage?.let { error ->
            StatusMessage(error, CelesteError)
        }
    }
}

@Composable
private fun LabeledMessage(message: ConversationMessage) {
    CelestePanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 15.dp,
            vertical = 13.dp,
        ),
    ) {
        Column {
            MessageLabel(
                message.role.replace('_', ' '),
                CelesteAccent,
                message.pending,
            )
            if (message.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message.text,
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MessageLabel(label: String, color: Color, pending: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        if (pending) {
            Spacer(Modifier.size(7.dp))
            Box(Modifier.size(6.dp).background(CelesteAccent, CircleShape))
        }
    }
}
