package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import dev.hazydreams.hermesceleste.ComposerAttachment
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted

internal fun interface AttachmentImageDecoder {
    suspend fun decode(bytes: ByteArray): ImageBitmap?
}

internal val LocalAttachmentImageDecoder = staticCompositionLocalOf<AttachmentImageDecoder> {
    AttachmentImageDecoder { null }
}

@Composable
internal fun AttachmentImagePreview(
    attachment: ComposerAttachment,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val decoder = LocalAttachmentImageDecoder.current
    val resolvedBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = attachment.id,
        key2 = attachment.contentBytes,
        key3 = decoder,
    ) {
        value = decoder.decode(attachment.contentBytes)
    }
    if (resolvedBitmap == null) {
        Box(
            modifier = modifier.background(CelesteSurfacePrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Preview unavailable",
                color = CelesteTextMuted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    } else {
        Image(
            bitmap = resolvedBitmap!!,
            contentDescription = attachment.name,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}
