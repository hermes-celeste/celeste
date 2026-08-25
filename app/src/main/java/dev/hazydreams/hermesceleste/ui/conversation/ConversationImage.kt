package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary

internal sealed interface ConversationImageSource {
    data class Https(val url: String) : ConversationImageSource
    data class Gateway(val path: String) : ConversationImageSource
}

private sealed interface GatewayImageState {
    data object Loading : GatewayImageState
    data class Ready(val bytes: ByteArray) : GatewayImageState
    data object Failed : GatewayImageState
}

@Composable
internal fun ConversationImage(
    source: ConversationImageSource,
    alt: String,
    gatewayImageLoader: (suspend (String) -> ByteArray?)? = null,
    gatewayImageScope: Any? = null,
    modifier: Modifier = Modifier,
) {
    when (source) {
        is ConversationImageSource.Https -> LoadedConversationImage(
            model = source.url,
            alt = alt,
            fallbackUri = source.url,
            modifier = modifier,
        )

        is ConversationImageSource.Gateway -> GatewayConversationImage(
            source = source,
            alt = alt,
            gatewayImageLoader = gatewayImageLoader,
            gatewayImageScope = gatewayImageScope,
            modifier = modifier,
        )
    }
}

@Composable
private fun GatewayConversationImage(
    source: ConversationImageSource.Gateway,
    alt: String,
    gatewayImageLoader: (suspend (String) -> ByteArray?)?,
    gatewayImageScope: Any?,
    modifier: Modifier,
) {
    val currentLoader by rememberUpdatedState(gatewayImageLoader)
    if (LocalInspectionMode.current) {
        LoadedConversationImage(
            model = source.path,
            alt = alt,
            fallbackUri = null,
            modifier = modifier,
        )
        return
    }

    val loadState by produceState<GatewayImageState>(
        initialValue = GatewayImageState.Loading,
        source.path,
        gatewayImageScope,
        gatewayImageLoader != null,
    ) {
        value = runCatching { currentLoader?.invoke(source.path) }
            .getOrNull()
            ?.takeIf(ByteArray::isNotEmpty)
            ?.let(GatewayImageState::Ready)
            ?: GatewayImageState.Failed
    }

    when (val state = loadState) {
        GatewayImageState.Loading -> ConversationImageLoading(alt, modifier)
        GatewayImageState.Failed -> ConversationImageFailure(alt, fallbackUri = null, modifier)
        is GatewayImageState.Ready -> LoadedConversationImage(
            model = state.bytes,
            alt = alt,
            fallbackUri = null,
            modifier = modifier,
        )
    }
}

@Composable
private fun LoadedConversationImage(
    model: Any,
    alt: String,
    fallbackUri: String?,
    modifier: Modifier,
) {
    var loadState by remember(model) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var previewOpen by remember(model) { mutableStateOf(false) }
    val inspectionMode = LocalInspectionMode.current
    val description = alt.ifBlank { "Conversation image" }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .heightIn(min = 120.dp, max = 360.dp)
            .clip(shape)
            .background(
                if (inspectionMode) CelesteAccent.copy(alpha = 0.32f) else CelesteSurfaceRaised,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = description,
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
                    fallbackUri = fallbackUri,
                    modifier = Modifier.fillMaxSize(),
                )

                is AsyncImagePainter.State.Success -> Unit
            }
        }
    }

    if (previewOpen) {
        ConversationImagePreview(
            model = model,
            description = description,
            onDismiss = { previewOpen = false },
        )
    }
}

@Composable
private fun ConversationImageLoading(alt: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
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
            text = alt.ifBlank { "Loading image…" },
            color = CelesteTextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConversationImageFailure(
    alt: String,
    fallbackUri: String?,
    modifier: Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .background(CelesteSurfaceRaised)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "${alt.ifBlank { "Image" }} unavailable",
            color = CelesteTextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (fallbackUri != null) {
            TextButton(onClick = { runCatching { uriHandler.openUri(fallbackUri) } }) {
                Text("Open image")
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
