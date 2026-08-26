package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import kotlinx.coroutines.CancellationException

private sealed interface GatewayImageState {
    data object Loading : GatewayImageState
    data class Ready(val bytes: ByteArray) : GatewayImageState
    data object Failed : GatewayImageState
}

@Composable
internal fun ConversationImage(
    path: String,
    alt: String,
    gatewayImageLoader: (suspend (String) -> ByteArray?)? = null,
    gatewayImageScope: Any? = null,
    modifier: Modifier = Modifier,
) {
    var retryRequest by remember(path, gatewayImageScope) { mutableIntStateOf(0) }
    val currentLoader by rememberUpdatedState(gatewayImageLoader)

    if (LocalInspectionMode.current) {
        LoadedConversationImage(
            model = path,
            alt = alt,
            onRetry = null,
            modifier = modifier,
        )
        return
    }

    val loadState by produceState<GatewayImageState>(
        initialValue = GatewayImageState.Loading,
        path,
        gatewayImageScope,
        gatewayImageLoader != null,
        retryRequest,
    ) {
        val bytes = try {
            currentLoader?.invoke(path)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            null
        }
        value = bytes
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let(GatewayImageState::Ready)
            ?: GatewayImageState.Failed
    }

    when (val state = loadState) {
        GatewayImageState.Loading -> ConversationImageFrame(modifier) {
            ConversationImageLoading(alt, Modifier.fillMaxSize())
        }

        GatewayImageState.Failed -> ConversationImageFrame(modifier) {
            ConversationImageFailure(
                alt = alt,
                onRetry = { retryRequest += 1 },
                modifier = Modifier.fillMaxSize(),
            )
        }

        is GatewayImageState.Ready -> LoadedConversationImage(
            model = state.bytes,
            alt = alt,
            onRetry = { retryRequest += 1 },
            modifier = modifier,
        )
    }
}

@Composable
private fun LoadedConversationImage(
    model: Any,
    alt: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier,
) {
    var loadState by remember(model) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var previewOpen by remember(model) { mutableStateOf(false) }
    val platformContext = LocalPlatformContext.current
    val request = remember(model, platformContext) {
        ImageRequest.Builder(platformContext)
            .data(model)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    val inspectionMode = LocalInspectionMode.current
    val description = alt.ifBlank { "Conversation image" }

    ConversationImageFrame(modifier = modifier) {
        AsyncImage(
            model = request,
            contentDescription = description.takeIf { loadState is AsyncImagePainter.State.Success },
            contentScale = ContentScale.Fit,
            onState = { loadState = it },
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (loadState is AsyncImagePainter.State.Success) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "Open image preview",
                        ) { previewOpen = true }
                    } else {
                        Modifier
                    },
                ),
        )
        if (!inspectionMode) {
            when (loadState) {
                AsyncImagePainter.State.Empty,
                is AsyncImagePainter.State.Loading,
                -> ConversationImageLoading(description, Modifier.fillMaxSize())

                is AsyncImagePainter.State.Error -> ConversationImageFailure(
                    alt = description,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )

                is AsyncImagePainter.State.Success -> Unit
            }
        }
    }

    if (previewOpen) {
        ConversationImagePreview(
            model = request,
            description = description,
            onDismiss = { previewOpen = false },
        )
    }
}

@Composable
private fun ConversationImageFrame(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val inspectionMode = LocalInspectionMode.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val frameWidth = minOf(maxWidth, 640.dp)
        val frameHeight = (frameWidth * 9f / 16f).coerceIn(120.dp, 360.dp)
        Box(
            modifier = Modifier
                .width(frameWidth)
                .height(frameHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (inspectionMode) CelesteAccent.copy(alpha = 0.32f) else CelesteSurfaceRaised,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun ConversationImageLoading(alt: String, modifier: Modifier) {
    val description = alt.ifBlank { "Image" }
    Column(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "Loading $description" }
            .background(CelesteSurfaceRaised)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(bottom = 10.dp),
            color = CelesteAccent,
            strokeWidth = 2.dp,
        )
        Text(
            text = "Loading $description…",
            color = CelesteTextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConversationImageFailure(
    alt: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier,
) {
    val description = alt.ifBlank { "Image" }
    Column(
        modifier = modifier
            .background(CelesteSurfaceRaised)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$description unavailable",
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "$description unavailable"
            },
            color = CelesteTextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun ConversationImagePreview(
    model: Any,
    description: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CelesteSurfacePrimary)
                .padding(18.dp),
        ) {
            AsyncImage(
                model = model,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 44.dp, bottom = 24.dp),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = "Close",
                    color = CelesteTextPrimary,
                )
            }
        }
    }
}
