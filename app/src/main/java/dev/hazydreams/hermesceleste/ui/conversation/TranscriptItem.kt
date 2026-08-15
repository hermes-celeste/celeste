package dev.hazydreams.hermesceleste.ui.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.attachments.AttachmentPreviewState
import dev.hazydreams.hermesceleste.attachments.MessageAttachment
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteGoldText
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanelRaised
import dev.hazydreams.hermesceleste.ui.CelestePaper
import dev.hazydreams.hermesceleste.ui.CelesteSoftBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val STREAMING_TRANSCRIPT_KEY = "streaming:assistant"

private val transcriptPreviewCache = object : LruCache<String, Bitmap>(4 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int =
        (value.rowBytes * value.height / 1024).coerceAtLeast(1)

    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
        if (evicted && oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
    }
}

private fun decodeBoundedTranscriptThumbnail(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 96 || bounds.outHeight / sampleSize > 96) {
        sampleSize = (sampleSize * 2).coerceAtMost(1 shl 16)
        if (sampleSize == 1 shl 16) break
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        },
    )
}

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

internal fun messageAttachmentKeys(message: ConversationMessage): List<String> {
    val id = message.id?.takeIf(String::isNotBlank)
    val base = if (id == null) {
        "transcript:fallback"
    } else {
        "transcript:id:${id.length}:$id"
    }
    return message.attachments.indices.map { index -> "$base:attachment:$index" }
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
            if (message.attachments.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AttachmentList(message.attachments)
            }
        }
    }
}

@Composable
private fun AttachmentList(attachments: List<MessageAttachment>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        attachments.forEachIndexed { index, attachment ->
            val name = attachment.displayName?.takeIf(String::isNotBlank) ?: "Image"
            val state = when (attachment.preview) {
                AttachmentPreviewState.Ready -> "Image ready"
                AttachmentPreviewState.Pending -> "Loading image"
                AttachmentPreviewState.Unavailable -> "Image unavailable"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CelestePanelRaised.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics {
                        contentDescription = "Image ${index + 1}: $name, $state"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(CelestePaper, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    TranscriptAttachmentThumbnail(attachment)
                }
                Spacer(Modifier.size(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = CelesteInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state, color = CelesteMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TranscriptAttachmentThumbnail(attachment: MessageAttachment) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = attachment.id,
        key2 = attachment.previewBytes,
    ) {
        val bytes = attachment.previewBytes ?: return@produceState
        val cacheKey = "${attachment.id}:${attachment.serverReference.orEmpty()}"
        val cached = synchronized(transcriptPreviewCache) { transcriptPreviewCache.get(cacheKey) }
        value = cached ?: withContext(Dispatchers.Default) {
            decodeBoundedTranscriptThumbnail(bytes)
        }?.also { decoded ->
            synchronized(transcriptPreviewCache) { transcriptPreviewCache.put(cacheKey, decoded) }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = requireNotNull(bitmap).asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(42.dp),
        )
    } else {
        Text(
            text = if (attachment.preview == AttachmentPreviewState.Pending) "…" else "▧",
            color = CelesteBlue,
            fontSize = 21.sp,
        )
    }
}

@Composable
private fun AssistantMessage(message: ConversationMessage) {
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
        MessageLabel("Hermes", accent, message.pending)
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
private fun ToolMessage(message: ConversationMessage) {
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
