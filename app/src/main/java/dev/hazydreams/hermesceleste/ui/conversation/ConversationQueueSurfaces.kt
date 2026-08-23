package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.QueuedPrompt
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteAccentContent
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import dev.hazydreams.hermesceleste.ui.CelesteWarning

@Composable
internal fun QueuedPromptsPill(
    prompts: List<QueuedPrompt>,
    paused: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompts.isEmpty()) return
    val count = prompts.size
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CelesteSurfacePrimary, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "Open queued messages"
                stateDescription = "$count queued${if (paused) ", paused" else ""}"
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = QueueLayersIcon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (paused) CelesteWarning else CelesteTextMuted,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Queued",
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            color = if (paused) CelesteWarning else CelesteTextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun QueuedPromptsInspectionSheet(
    prompts: List<QueuedPrompt>,
    paused: Boolean,
    onResume: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (prompts.isEmpty()) return
    InspectionModalSheet(onDismiss = onDismiss) {
        QueuedPromptsSheetSurface(
            prompts = prompts,
            paused = paused,
            onResume = onResume,
            onRemove = onRemove,
        )
    }
}

@Composable
internal fun QueuedPromptsSheetSurface(
    prompts: List<QueuedPrompt>,
    paused: Boolean,
    onResume: () -> Unit,
    onRemove: (String) -> Unit,
) {
    InspectionSheetSurface {
        Text(
            text = "Queued messages",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (paused) {
            Button(
                onClick = onResume,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp)
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CelesteAccent,
                    contentColor = CelesteAccentContent,
                ),
            ) {
                Text("Resume queue", fontWeight = FontWeight.SemiBold)
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 560.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(prompts, key = { _, prompt -> prompt.id }) { index, prompt ->
                QueuedPromptRow(
                    prompt = prompt,
                    position = index,
                    onRemove = { onRemove(prompt.id) },
                )
            }
        }
    }
}

@Composable
private fun QueuedPromptRow(
    prompt: QueuedPrompt,
    position: Int,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CelesteSurfacePrimary, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, top = 11.dp, bottom = 11.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    prompt.deliveryUncertain -> "Delivery uncertain"
                    position == 0 -> "Next"
                    else -> "Queued ${position + 1}"
                },
                color = if (prompt.deliveryUncertain) CelesteWarning else CelesteTextMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                text = prompt.text,
                color = CelesteTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = RemoveQueuedPromptIcon,
                contentDescription = "Remove queued message",
                modifier = Modifier.size(18.dp),
                tint = CelesteTextMuted,
            )
        }
    }
}

private val QueueLayersIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Queued messages",
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
            moveTo(5f, 8f)
            lineTo(12f, 4f)
            lineTo(19f, 8f)
            lineTo(12f, 12f)
            close()
            moveTo(5f, 12f)
            lineTo(12f, 16f)
            lineTo(19f, 12f)
            moveTo(5f, 16f)
            lineTo(12f, 20f)
            lineTo(19f, 16f)
        }
    }.build()
}

private val RemoveQueuedPromptIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Remove",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(7f, 7f)
            lineTo(17f, 17f)
            moveTo(17f, 7f)
            lineTo(7f, 17f)
        }
    }.build()
}
