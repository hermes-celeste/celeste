package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteGoldText
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanelRaised
import dev.hazydreams.hermesceleste.ui.CelesteSoftBlue

internal const val STREAMING_TRANSCRIPT_KEY = "streaming:assistant"

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
    activityOwner: ConversationActivityOwner? = null,
) {
    when (message.role) {
        "user" -> UserMessage(message)
        "assistant" -> AssistantMessage(message, activityOwner)
        else -> ToolMessage(message, activityOwner)
    }
}

@Composable
private fun UserMessage(message: ConversationMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .background(CelesteSoftBlue, RoundedCornerShape(20.dp))
                .padding(horizontal = 17.dp, vertical = 15.dp),
        ) {
            MessageLabel("You", CelesteBlue, message.pending)
            if (message.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message.text,
                    color = CelesteInk,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ConversationMessage,
    activityOwner: ConversationActivityOwner?,
) {
    val accent = CelesteCoral
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = accent,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(start = 17.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
    ) {
        MessageLabel("Hermes", accent, message.pending, activityOwner)
        if (message.text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message.text,
                color = CelesteInk,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ToolMessage(
    message: ConversationMessage,
    activityOwner: ConversationActivityOwner?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CelestePanelRaised.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
            .border(1.dp, CelesteHairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        MessageLabel(
            message.toolName?.replace('_', ' ') ?: "Tool",
            CelesteGoldText,
            message.pending,
            activityOwner,
        )
        if (message.text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message.text,
                color = CelesteMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MessageLabel(
    label: String,
    color: Color,
    pending: Boolean,
    activityOwner: ConversationActivityOwner? = null,
) {
    Row(
        modifier = activityOwner?.let { Modifier.conversationActivitySemantics(it) } ?: Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        if (pending) {
            Spacer(Modifier.size(7.dp))
            Box(Modifier.size(6.dp).background(CelesteGoldText, CircleShape))
        }
    }
}
