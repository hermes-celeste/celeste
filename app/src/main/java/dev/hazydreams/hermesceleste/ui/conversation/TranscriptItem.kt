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
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteGold
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePanelRaised

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
    val isUser = message.role == "user"
    val isAssistant = message.role == "assistant"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.88f else 0.94f)
                .background(
                    when {
                        isUser -> CelesteBlue.copy(alpha = 0.16f)
                        isAssistant -> CelestePanel
                        else -> CelestePanelRaised.copy(alpha = 0.72f)
                    },
                    RoundedCornerShape(18.dp),
                )
                .border(
                    1.dp,
                    if (message.pending && isAssistant) {
                        CelesteGold.copy(alpha = 0.34f)
                    } else {
                        Color.White.copy(alpha = 0.06f)
                    },
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (message.role) {
                        "user" -> "You"
                        "assistant" -> "Hermes"
                        "tool" -> message.toolName ?: "Tool"
                        else -> message.role.replaceFirstChar(Char::uppercase)
                    },
                    color = if (isUser) CelesteBlue else CelesteCoral,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (message.pending) {
                    Spacer(Modifier.size(7.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(if (isAssistant) CelesteGold else CelesteBlue, CircleShape),
                    )
                }
            }
            if (message.text.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(message.text, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}
