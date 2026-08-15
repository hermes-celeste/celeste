package dev.hazydreams.hermesceleste.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.UiNotice
import dev.hazydreams.hermesceleste.UiRecoveryAction

/** Render a product notice without exposing exception or server-provided text. */
@Composable
internal fun UiNoticeMessage(
    notice: UiNotice,
    onRetry: () -> Unit = {},
    onSignIn: () -> Unit = {},
    showRecoveryAction: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatusMessage(notice.message, CelesteError)
        if (showRecoveryAction) {
            when (notice.recovery) {
                UiRecoveryAction.None -> Unit
                UiRecoveryAction.Retry -> RecoveryActionButton(
                    label = notice.recoveryLabel ?: "Retry",
                    onClick = onRetry,
                )
                UiRecoveryAction.SignIn -> RecoveryActionButton(
                    label = notice.recoveryLabel ?: "Sign in",
                    onClick = onSignIn,
                )
            }
        }
    }
}

@Composable
private fun RecoveryActionButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(label, color = CelesteBlue)
    }
}
