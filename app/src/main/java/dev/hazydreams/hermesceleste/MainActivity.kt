package dev.hazydreams.hermesceleste

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hazydreams.hermesceleste.connection.AndroidConnectionStore
import dev.hazydreams.hermesceleste.network.AndroidActivityDisclosurePreferenceStore
import dev.hazydreams.hermesceleste.ui.CelesteRoutes
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme

class MainActivity : ComponentActivity() {
    private val celesteViewModel by viewModels<CelesteViewModel> {
        CelesteViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            HermesCelesteTheme {
                HermesCelesteApp(celesteViewModel)
            }
        }
    }
}

private class CelesteViewModelFactory(
    private val context: android.content.Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CelesteViewModel::class.java))
        return CelesteViewModel(
            connectionStore = AndroidConnectionStore(context),
            activityDisclosurePreferences = AndroidActivityDisclosurePreferenceStore(context),
        ) as T
    }
}

@Composable
private fun HermesCelesteApp(viewModel: CelesteViewModel) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel, ui.activeSummary?.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.onForeground()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CelesteRoutes(ui = ui, viewModel = viewModel)
}
