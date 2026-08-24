package dev.hazydreams.hermesceleste

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.lifecycleScope
import dev.hazydreams.hermesceleste.connection.AndroidConnectionStore
import dev.hazydreams.hermesceleste.ui.CelesteRoutes
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val celesteViewModel by viewModels<CelesteViewModel> {
        CelesteViewModelFactory(applicationContext)
    }
    private val attachmentReader by lazy { AndroidAttachmentReader(contentResolver) }
    private val attachmentImportMutex = Mutex()
    private var imagePickerGeneration: Long? = null
    private var filePickerGeneration: Long? = null
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { uris ->
        val expectedGeneration = imagePickerGeneration
        imagePickerGeneration = null
        if (expectedGeneration != null) {
            importAttachments(uris, ComposerAttachmentKind.Image, expectedGeneration)
        }
    }
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val expectedGeneration = filePickerGeneration
        filePickerGeneration = null
        if (expectedGeneration != null) {
            importAttachments(uris, ComposerAttachmentKind.File, expectedGeneration)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imagePickerGeneration = savedInstanceState
            ?.takeIf { it.containsKey(IMAGE_PICKER_GENERATION_KEY) }
            ?.getLong(IMAGE_PICKER_GENERATION_KEY)
        filePickerGeneration = savedInstanceState
            ?.takeIf { it.containsKey(FILE_PICKER_GENERATION_KEY) }
            ?.getLong(FILE_PICKER_GENERATION_KEY)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            HermesCelesteTheme {
                HermesCelesteApp(
                    viewModel = celesteViewModel,
                    onPickImages = {
                        imagePickerGeneration = celesteViewModel.state.value.composerAttachmentGeneration
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickFiles = {
                        filePickerGeneration = celesteViewModel.state.value.composerAttachmentGeneration
                        filePicker.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        imagePickerGeneration?.let { outState.putLong(IMAGE_PICKER_GENERATION_KEY, it) }
        filePickerGeneration?.let { outState.putLong(FILE_PICKER_GENERATION_KEY, it) }
        super.onSaveInstanceState(outState)
    }

    private fun importAttachments(
        uris: List<android.net.Uri>,
        kind: ComposerAttachmentKind,
        expectedGeneration: Long,
    ) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            attachmentImportMutex.withLock {
                if (celesteViewModel.state.value.composerAttachmentGeneration != expectedGeneration) {
                    celesteViewModel.reportAttachmentError(
                        "Attachments weren't added because the conversation changed.",
                    )
                    return@withLock
                }
                val budget = celesteViewModel.attachmentImportBudget()
                val result = withContext(Dispatchers.IO) {
                    attachmentReader.read(
                        uris = uris,
                        kind = kind,
                        existingAttachmentCount = budget.attachmentCount,
                        existingAttachmentBytes = budget.byteSize,
                    )
                }
                if (celesteViewModel.state.value.composerAttachmentGeneration != expectedGeneration) {
                    celesteViewModel.reportAttachmentError(
                        "Attachments weren't added because the conversation changed.",
                    )
                    return@withLock
                }
                if (result.attachments.isNotEmpty()) {
                    celesteViewModel.addPickedAttachments(result.attachments, expectedGeneration)
                }
                result.errors.firstOrNull()?.let(celesteViewModel::reportAttachmentError)
            }
        }
    }

    private companion object {
        const val IMAGE_PICKER_GENERATION_KEY = "attachment.image_picker_generation"
        const val FILE_PICKER_GENERATION_KEY = "attachment.file_picker_generation"
    }
}

private class CelesteViewModelFactory(
    private val context: android.content.Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CelesteViewModel::class.java))
        return CelesteViewModel(connectionStore = AndroidConnectionStore(context)) as T
    }
}

@Composable
private fun HermesCelesteApp(
    viewModel: CelesteViewModel,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
) {
    val controller = viewModel.controller
    val ui by controller.state.collectAsStateWithLifecycle()
    val composerFocusRequest by viewModel.composerFocusRequest.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, controller, ui.activeSummary?.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onForeground()
                Lifecycle.Event.ON_STOP -> controller.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            controller.onForeground()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CelesteRoutes(
        ui = ui,
        controller = controller,
        composerFocusRequest = composerFocusRequest,
        onComposerFocusRequestHandled = viewModel::completeComposerFocusRequest,
        onNewConversation = viewModel::createNewConversation,
        onPickImages = onPickImages,
        onPickFiles = onPickFiles,
    )
}
