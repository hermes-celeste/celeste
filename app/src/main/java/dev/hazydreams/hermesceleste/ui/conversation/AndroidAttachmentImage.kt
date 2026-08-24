package dev.hazydreams.hermesceleste.ui.conversation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import dev.hazydreams.hermesceleste.ComposerAttachment
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AndroidAttachmentImage(
    attachment: ComposerAttachment,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = attachment.id,
    ) {
        value = withContext(Dispatchers.Default) {
            decodePreviewBitmap(attachment.contentBytes)
        }
    }
    val resolvedBitmap = bitmap
    if (resolvedBitmap == null) {
        Box(modifier = modifier.background(CelesteSurfacePrimary), contentAlignment = Alignment.Center) {
            Text("IMG", color = CelesteAccent, fontWeight = FontWeight.Bold)
        }
    } else {
        Image(
            bitmap = resolvedBitmap.asImageBitmap(),
            contentDescription = attachment.name,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

private fun decodePreviewBitmap(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1_024 || bounds.outHeight / sample > 1_024) sample *= 2
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()
