package dev.hazydreams.hermesceleste.ui.conversation

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

internal sealed interface ConversationImageSource {
    data class Https(val url: String) : ConversationImageSource
    data class Gateway(val path: String) : ConversationImageSource
}

private sealed interface GatewayImageState {
    data object Loading : GatewayImageState
    data class Ready(val bytes: ByteArray) : GatewayImageState
    data object Failed : GatewayImageState
}

private object ConversationImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    private val httpClient = OkHttpClient.Builder()
        .addNetworkInterceptor { chain ->
            if (!chain.request().url.isHttps) {
                throw IOException("Conversation image redirects must stay on HTTPS.")
            }
            chain.proceed(chain.request())
        }
        .build()

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .diskCachePolicy(CachePolicy.DISABLED)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { httpClient }))
            }
            .build()
            .also { instance = it }
    }
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
        val bytes = try {
            currentLoader?.invoke(source.path)
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
            ConversationImageFailure(alt, fallbackUri = null, Modifier.fillMaxSize())
        }

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
    val context = LocalContext.current
    val imageLoader = remember(context) { ConversationImageLoader.get(context) }
    val inspectionMode = LocalInspectionMode.current
    val description = alt.ifBlank { "Conversation image" }

    ConversationImageFrame(modifier = modifier) {
        AsyncImage(
            model = model,
            imageLoader = imageLoader,
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
            imageLoader = imageLoader,
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .heightIn(min = 120.dp, max = 360.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (inspectionMode) CelesteAccent.copy(alpha = 0.32f) else CelesteSurfaceRaised,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ConversationImageLoading(alt: String, modifier: Modifier) {
    val description = alt.ifBlank { "Image" }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
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
    fallbackUri: String?,
    modifier: Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val description = alt.ifBlank { "Image" }
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
            text = "$description unavailable",
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "$description unavailable"
            },
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
    imageLoader: ImageLoader,
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
                imageLoader = imageLoader,
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
