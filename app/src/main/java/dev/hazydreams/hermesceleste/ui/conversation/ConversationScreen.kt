package dev.hazydreams.hermesceleste.ui.conversation

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.attachments.AttachmentCapabilityState
import dev.hazydreams.hermesceleste.attachments.AttachmentDraft
import dev.hazydreams.hermesceleste.attachments.AttachmentPreviewState
import dev.hazydreams.hermesceleste.attachments.AttachmentTransferState
import dev.hazydreams.hermesceleste.attachments.ImageOnlyCapabilityState
import dev.hazydreams.hermesceleste.attachments.MAX_PENDING_ATTACHMENTS
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteGoldText
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePanelRaised
import dev.hazydreams.hermesceleste.ui.CelestePaper

import dev.hazydreams.hermesceleste.ui.StatusMessage
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ConversationScreen(
    summary: StoredSession,
    messages: List<ConversationMessage>,
    streamingText: String,
    draft: String,
    attachments: List<AttachmentDraft> = emptyList(),
    attachmentCapability: AttachmentCapabilityState = AttachmentCapabilityState.Unknown,
    imageOnlyCapability: ImageOnlyCapabilityState = ImageOnlyCapabilityState.Unknown,
    attachmentNotice: String? = null,
    turnState: TurnState,
    loadingMessage: String?,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
    onBeginAttachmentPicker: () -> Boolean = { true },
    onAttachmentPickerResult: (ContentResolver, List<Uri>) -> Unit = { _, _ -> },
    onRemoveAttachment: (UUID) -> Unit = {},
    onRetryAttachment: (UUID) -> Unit = {},
) {
    val context = LocalContext.current
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = MAX_PENDING_ATTACHMENTS,
        ),
    ) { uris ->
        onAttachmentPickerResult(context.contentResolver, uris)
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val canCompose = turnState == TurnState.Idle || turnState == TurnState.Reconnecting
    val canSend = turnState == TurnState.Idle &&
        (draft.isNotBlank() || (attachments.isNotEmpty() && imageOnlyCapability == ImageOnlyCapabilityState.Supported)) &&
        attachments.all { it.transfer == AttachmentTransferState.Ready || it.transfer == AttachmentTransferState.Staged }
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val visibleMessageCount = messages.size + if (streamingText.isNotBlank()) 1 else 0
    val safeDrawingInsets = WindowInsets.safeDrawing
    val headerTopPadding = maxOf(
        34.dp,
        safeDrawingInsets.asPaddingValues().calculateTopPadding() + 10.dp,
    )
    LaunchedEffect(visibleMessageCount, streamingText.length) {
        if (visibleMessageCount > 0) listState.animateScrollToItem(visibleMessageCount - 1)
    }

    CelesteBackdrop(showOrnament = false) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(safeDrawingInsets.only(WindowInsetsSides.Horizontal))
                .padding(top = headerTopPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Text("←  Back", color = CelesteBlue, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                if (turnState == TurnState.Running || turnState == TurnState.Synchronizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 1.8.dp,
                        color = turnStateColor(turnState),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(
                    summary.title.ifBlank { "Conversation" },
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(6.dp).background(turnStateColor(turnState), androidx.compose.foundation.shape.CircleShape),
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        text = when (turnState) {
                            TurnState.Idle -> "Connected"
                            TurnState.Running -> "Responding"
                            TurnState.Synchronizing -> "Synchronizing"
                            TurnState.Reconnecting -> "Reconnecting"
                        },
                        color = CelesteMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            if (loadingMessage != null || errorMessage != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    loadingMessage?.let { StatusMessage(it, CelesteBlue, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(25.dp),
            ) {
                itemsIndexed(
                    items = messages,
                    key = { index, _ -> transcriptKeys[index] },
                ) { _, message ->
                    MessageBubble(message)
                }
                if (streamingText.isNotBlank()) {
                    item(key = STREAMING_TRANSCRIPT_KEY) {
                        MessageBubble(
                            ConversationMessage(role = "assistant", text = streamingText, pending = true),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CelestePanel)
                    .border(1.dp, CelesteHairline)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (attachments.isNotEmpty()) {
                    ComposerAttachmentRail(
                        attachments = attachments,
                        enabled = canCompose,
                        onRemove = onRemoveAttachment,
                        onRetry = onRetryAttachment,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (attachmentNotice != null) {
                    Text(
                        text = attachmentNotice,
                        color = CelesteError,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (attachmentCapability == AttachmentCapabilityState.Unsupported) {
                    Text(
                        text = "Image attachments are unavailable for this gateway.",
                        color = CelesteMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = canCompose,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (turnState) {
                                TurnState.Idle -> "Message Hermes"
                                TurnState.Running -> "Hermes is responding…"
                                TurnState.Synchronizing -> "Synchronizing…"
                                TurnState.Reconnecting -> "Keep drafting while Celeste reconnects…"
                            },
                            color = CelesteMuted,
                        )
                    },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend) {
                                onSend()
                                focusManager.clearFocus()
                            }
                        },
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CelesteBlue,
                        unfocusedBorderColor = CelesteHairline,
                        focusedContainerColor = CelestePaper,
                        unfocusedContainerColor = CelestePaper,
                        disabledContainerColor = CelestePaper,
                        cursorColor = CelesteBlue,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (turnState) {
                            TurnState.Idle -> ""
                            TurnState.Running -> "Responding"
                            TurnState.Synchronizing -> "Synchronizing"
                            TurnState.Reconnecting -> "Draft saved here"
                        },
                        color = CelesteMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (canCompose) {
                        OutlinedButton(
                            onClick = {
                                if (onBeginAttachmentPicker()) {
                                    pickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                }
                            },
                            enabled = attachmentCapability != AttachmentCapabilityState.Unsupported &&
                                attachments.size < MAX_PENDING_ATTACHMENTS,
                            modifier = Modifier
                                .height(46.dp)
                                .semantics { contentDescription = "Add image attachment" },
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteBlue),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteBlue),
                        ) { Text("+ Image", fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.size(8.dp))
                    }
                    when (turnState) {
                        TurnState.Running -> OutlinedButton(
                            onClick = onInterrupt,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteCoral),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteCoral),
                        ) { Text("Stop", fontWeight = FontWeight.SemiBold) }

                        TurnState.Reconnecting -> OutlinedButton(
                            onClick = onReconnect,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteBlue),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteBlue),
                        ) { Text("Retry", fontWeight = FontWeight.SemiBold) }

                        else -> Button(
                            onClick = {
                                onSend()
                                focusManager.clearFocus()
                            },
                            enabled = canSend,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CelesteInk,
                                contentColor = CelestePaper,
                                disabledContainerColor = CelesteHairline,
                                disabledContentColor = CelesteMuted,
                            ),
                        ) { Text("Send  →", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerAttachmentRail(
    attachments: List<AttachmentDraft>,
    enabled: Boolean,
    onRemove: (UUID) -> Unit,
    onRetry: (UUID) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEachIndexed { index, attachment ->
            ComposerAttachmentCard(
                index = index,
                attachment = attachment,
                enabled = enabled,
                onRemove = { onRemove(attachment.id) },
                onRetry = { onRetry(attachment.id) },
            )
        }
    }
}

@Composable
private fun ComposerAttachmentCard(
    index: Int,
    attachment: AttachmentDraft,
    enabled: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val name = attachment.displayName?.takeIf(String::isNotBlank) ?: "Image"
    val state = attachmentStateLabel(attachment)
    Column(
        modifier = Modifier
            .width(176.dp)
            .background(CelestePanelRaised, RoundedCornerShape(14.dp))
            .border(1.dp, CelesteHairline, RoundedCornerShape(14.dp))
            .padding(9.dp)
            .semantics {
                contentDescription = "Image ${index + 1}: $name, $state"
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttachmentThumbnail(attachment)
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = CelesteInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                attachment.byteSize.takeIf { it > 0L }?.let { size ->
                    Text(formatAttachmentSize(size), color = CelesteMuted, fontSize = 10.sp)
                }
            }
        }
        Text(state, color = if (attachment.error != null) CelesteError else CelesteMuted, fontSize = 11.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (attachment.localFileId.isNotBlank() &&
                attachment.error?.retryable == true &&
                attachment.transfer in setOf(AttachmentTransferState.Failed, AttachmentTransferState.Unknown)
            ) {
                TextButton(
                    onClick = onRetry,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
                    modifier = Modifier.semantics { contentDescription = "Retry image ${index + 1}" },
                ) { Text("Retry", color = CelesteBlue, fontSize = 11.sp) }
            }
            TextButton(
                onClick = onRemove,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
                modifier = Modifier.semantics { contentDescription = "Remove image ${index + 1}" },
            ) { Text("Remove", color = CelesteCoral, fontSize = 11.sp) }
        }
    }
}

private val composerPreviewCache = object : LruCache<String, Bitmap>(4 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int =
        (value.rowBytes * value.height / 1024).coerceAtLeast(1)

    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
        if (evicted && oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
    }
}

@Composable
private fun AttachmentThumbnail(attachment: AttachmentDraft) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = attachment.id,
        key2 = attachment.previewBytes,
    ) {
        val bytes = attachment.previewBytes ?: return@produceState
        val cached = synchronized(composerPreviewCache) { composerPreviewCache.get(attachment.id.toString()) }
        value = cached ?: withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }?.also { decoded ->
            synchronized(composerPreviewCache) {
                composerPreviewCache.put(attachment.id.toString(), decoded)
            }
        }
    }
    Box(
        modifier = Modifier
            .size(54.dp)
            .background(CelestePaper, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(54.dp),
            )
        } else {
            Text(
                text = if (attachment.preview == AttachmentPreviewState.Pending) "…" else "▧",
                color = CelesteBlue,
                fontSize = 22.sp,
            )
        }
        if (attachment.transfer == AttachmentTransferState.Uploading) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(CelesteGoldText, CircleShape),
            )
        }
    }
}

private fun attachmentStateLabel(attachment: AttachmentDraft): String = when {
    attachment.error != null -> attachment.error.message
    attachment.transfer == AttachmentTransferState.Preparing -> "Preparing image"
    attachment.transfer == AttachmentTransferState.Uploading -> "Uploading"
    attachment.transfer == AttachmentTransferState.Staged -> "Ready to send"
    attachment.transfer == AttachmentTransferState.Unknown -> "Upload status unknown — Retry"
    attachment.preview == AttachmentPreviewState.Unavailable -> "Ready · preview unavailable"
    else -> "Ready"
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KiB"
    else -> "${bytes / (1024L * 1024L)} MiB"
}

private fun turnStateColor(turnState: TurnState): Color = when (turnState) {
    TurnState.Idle -> CelesteCoral
    TurnState.Running -> CelesteGoldText
    TurnState.Synchronizing, TurnState.Reconnecting -> CelesteBlue
}
