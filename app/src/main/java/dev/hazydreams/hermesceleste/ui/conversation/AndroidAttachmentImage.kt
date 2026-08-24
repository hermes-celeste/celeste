package dev.hazydreams.hermesceleste.ui.conversation

import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AndroidAttachmentImageDecoder : AttachmentImageDecoder {
    override suspend fun decode(bytes: ByteArray) = withContext(Dispatchers.Default) {
        decodePreviewBitmap(bytes)?.asImageBitmap()
    }
}

private fun decodePreviewBitmap(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val longestEdge = max(width, height)
        if (longestEdge > MAX_PREVIEW_EDGE_PX) {
            val scale = MAX_PREVIEW_EDGE_PX.toFloat() / longestEdge
            decoder.setTargetSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
            )
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

private const val MAX_PREVIEW_EDGE_PX = 1_024
