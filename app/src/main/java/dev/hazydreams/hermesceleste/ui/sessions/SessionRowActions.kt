package dev.hazydreams.hermesceleste.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted

internal data class SessionRowActionLabels(
    val pin: String,
    val rename: String = "Rename",
)

internal fun sessionRowActionLabels(pinned: Boolean): SessionRowActionLabels =
    SessionRowActionLabels(pin = if (pinned) "Unpin" else "Pin")

internal fun sessionRowAccessibilityActions(
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    onRename: () -> Unit,
): List<CustomAccessibilityAction> {
    val labels = sessionRowActionLabels(pinned)
    return listOf(
        CustomAccessibilityAction(labels.pin) {
            onPinnedChange(!pinned)
            true
        },
        CustomAccessibilityAction(labels.rename) {
            onRename()
            true
        },
    )
}

@Composable
internal fun SessionRowActionMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onRename: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        SessionRowActionItems(
            pinned = pinned,
            onPinnedChange = {
                onDismiss()
                onPinnedChange(it)
            },
            onRename = {
                onDismiss()
                onRename()
            },
        )
    }
}

@Composable
internal fun SessionRowActionItems(
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    onRename: () -> Unit,
) {
    val labels = sessionRowActionLabels(pinned)
    DropdownMenuItem(
        text = { Text(labels.pin) },
        onClick = { onPinnedChange(!pinned) },
        leadingIcon = {
            Icon(
                imageVector = SessionPinIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = CelesteTextMuted,
            )
        },
    )
    DropdownMenuItem(
        text = { Text(labels.rename) },
        onClick = onRename,
        leadingIcon = {
            Icon(
                imageVector = SessionRenameIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = CelesteTextMuted,
            )
        },
    )
}

private val SessionPinIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Pin conversation",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9f, 4f)
            horizontalLineTo(15f)
            moveTo(10f, 4f)
            lineTo(9f, 9f)
            lineTo(6.5f, 12f)
            verticalLineTo(14f)
            horizontalLineTo(17.5f)
            verticalLineTo(12f)
            lineTo(15f, 9f)
            lineTo(14f, 4f)
            moveTo(12f, 14f)
            verticalLineTo(20f)
        }
    }.build()
}

private val SessionRenameIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Rename conversation",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5f, 19f)
            lineTo(6f, 15f)
            lineTo(15.8f, 5.2f)
            lineTo(18.8f, 8.2f)
            lineTo(9f, 18f)
            close()
            moveTo(14.4f, 6.6f)
            lineTo(17.4f, 9.6f)
        }
    }.build()
}

@Composable
internal fun RenameConversationDialog(
    sessionId: String,
    currentTitle: String,
    onDismiss: () -> Unit,
    onRename: (String, (String?) -> Unit) -> Unit,
) {
    var value by remember(sessionId, currentTitle) { mutableStateOf(currentTitle) }
    var submitting by remember(sessionId) { mutableStateOf(false) }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (!submitting) onDismiss()
        },
        title = {
            Text(
                text = "Rename conversation",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    enabled = !submitting,
                    singleLine = true,
                    label = { Text("Name") },
                    isError = error != null,
                )
                error?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        color = CelesteError,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && value.trim().isNotEmpty(),
                onClick = {
                    submitting = true
                    error = null
                    onRename(value.trim()) { renameError ->
                        submitting = false
                        if (renameError == null) onDismiss() else error = renameError
                    }
                },
            ) {
                Text(if (submitting) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    )
}
