package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceSelected
import dev.hazydreams.hermesceleste.ui.CelestePanel

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
internal fun MessageBubble(message: ConversationMessage, streaming: Boolean = false) {
    when (message.role) {
        "user" -> UserMessage(message, streaming)
        "assistant" -> AssistantMessage(message, streaming)
        else -> ToolMessage(message)
    }
}

@Composable
private fun UserMessage(message: ConversationMessage, streaming: Boolean) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val richContent = containsRichMarkdown(message.text)
        val maximumPillWidth = maxWidth * 0.8f
        Column(
            modifier = (if (richContent) {
                Modifier.width(maximumPillWidth)
            } else {
                Modifier
                    .widthIn(max = maximumPillWidth)
                    .wrapContentWidth()
            })
                .background(CelesteSurfaceSelected, RoundedCornerShape(18.dp))
                .padding(horizontal = 15.dp, vertical = 11.dp),
        ) {
            if (message.text.isNotBlank()) {
                RichMarkdown(
                    content = message.text,
                    streaming = streaming,
                    widthPolicy = if (richContent) {
                        MarkdownWidthPolicy.Fill
                    } else {
                        MarkdownWidthPolicy.WrapContent
                    },
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: ConversationMessage, streaming: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        if (message.text.isNotBlank()) {
            RichMarkdown(
                content = message.text,
                streaming = streaming,
            )
        }
    }
}

@Composable
private fun ToolMessage(message: ConversationMessage) {
    CelestePanel(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 15.dp,
            vertical = 13.dp,
        ),
    ) {
        Column {
            MessageLabel(
                message.toolName?.replace('_', ' ') ?: "Tool",
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
