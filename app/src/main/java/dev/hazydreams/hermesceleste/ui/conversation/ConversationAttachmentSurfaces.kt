package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.ComposerAttachment
import dev.hazydreams.hermesceleste.ComposerAttachmentKind
import dev.hazydreams.hermesceleste.ConversationAttachment
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary

@Composable
internal fun ComposerAttachmentStrip(
    attachments: List<ComposerAttachment>,
    onOpen: (ComposerAttachment) -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = attachments,
            key = { attachment -> attachment.id },
        ) { attachment ->
            AttachmentChip(
                attachment = attachment,
                leadingLabel = if (attachment.kind == ComposerAttachmentKind.Image) {
                    "IMG"
                } else {
                    attachmentExtension(attachment.name)
                },
                openAction = if (attachment.kind == ComposerAttachmentKind.Image) "Preview" else "Inspect",
                onOpen = onOpen,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: ComposerAttachment,
    leadingLabel: String,
    openAction: String,
    onOpen: (ComposerAttachment) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(56.dp)
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(CelesteSurfacePrimary)
            .semantics {
                contentDescription = attachment.accessibilityLabel
                stateDescription = "Ready to send"
            }
            .clickable(
                role = Role.Button,
                onClickLabel = "$openAction ${attachment.name}",
            ) { onOpen(attachment) }
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leadingLabel,
            color = CelesteAccent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                color = CelesteTextPrimary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatAttachmentBytes(attachment.byteSize),
                color = CelesteTextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        RemoveAttachmentButton(
            name = attachment.name,
            onClick = { onRemove(attachment.id) },
        )
    }
}

@Composable
private fun RemoveAttachmentButton(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(CelesteSurfaceRaised.copy(alpha = 0.94f))
            .clickable(
                role = Role.Button,
                onClickLabel = "Remove $name",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "×",
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun ComposerAttachmentPreviewSheet(
    attachment: ComposerAttachment,
    onDismiss: () -> Unit,
) {
    InspectionModalSheet(onDismiss = onDismiss) {
        InspectionSheetSurface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (attachment.kind == ComposerAttachmentKind.Image) {
                    AndroidAttachmentImage(
                        attachment = attachment,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    text = attachment.name,
                    color = CelesteTextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${attachment.mimeType} · ${formatAttachmentBytes(attachment.byteSize)}",
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
internal fun TranscriptAttachmentSummaries(
    attachments: List<ConversationAttachment>,
    pending: Boolean,
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CelesteSurfacePrimary.copy(alpha = 0.74f))
                    .semantics {
                        contentDescription = "${if (attachment.kind == ComposerAttachmentKind.Image) "Image" else "File"}: ${attachment.name}"
                        stateDescription = if (pending) "Sending" else "Sent"
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (attachment.kind == ComposerAttachmentKind.Image) "IMG" else attachmentExtension(attachment.name),
                    color = CelesteAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = attachment.name,
                    color = CelesteTextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun attachmentExtension(name: String): String =
    name.substringAfterLast('.', "FILE").take(5).uppercase()

private fun formatAttachmentBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
