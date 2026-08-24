package dev.hazydreams.hermesceleste.ui.conversation

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AndroidAttachmentImageDecoder : AttachmentImageDecoder {
    override suspend fun decode(bytes: ByteArray) = withContext(Dispatchers.Default) {
        decodePreviewBitmap(bytes)?.asImageBitmap()
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
