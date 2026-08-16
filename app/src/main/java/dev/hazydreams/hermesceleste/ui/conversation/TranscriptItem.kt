package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteGoldText
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteLightTone
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelesteSoftBlue
import dev.hazydreams.hermesceleste.ui.CelesteSurface

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
internal fun MessageBubble(message: ConversationMessage) {
    when (message.role) {
        "user" -> UserMessage(message)
        "assistant" -> AssistantMessage(message)
        else -> ToolMessage(message)
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
                .fillMaxWidth(0.78f)
                .background(CelesteSoftBlue, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            if (message.text.isNotBlank()) {
                Text(
                    text = message.text,
                    color = CelesteInk,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: ConversationMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        if (message.text.isNotBlank()) {
            Text(
                text = message.text,
                color = CelesteInk,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ToolMessage(message: ConversationMessage) {
    CelesteSurface(
        modifier = Modifier
            .fillMaxWidth(),
        tone = CelesteLightTone.Cool,
        emphasized = message.pending,
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 15.dp,
            vertical = 13.dp,
        ),
    ) {
        Column {
            MessageLabel(
                message.toolName?.replace('_', ' ') ?: "Tool",
                CelesteBlue,
                message.pending,
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
            Box(Modifier.size(6.dp).background(CelesteGoldText, CircleShape))
        }
    }
}
