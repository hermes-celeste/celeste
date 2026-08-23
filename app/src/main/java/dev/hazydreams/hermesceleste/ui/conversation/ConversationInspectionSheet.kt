package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.TaskProgress
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary

@Composable
internal fun ConversationInspectionSheet(
    message: ConversationMessage,
    onDismiss: () -> Unit,
) {
    val supported = (message.role == "steps" && message.steps.isNotEmpty()) ||
        (message.role == "process" && message.processResult != null) ||
        (message.role == "changes" && message.fileEdits.isNotEmpty())
    if (!supported) return

    InspectionModalSheet(onDismiss = onDismiss) {
        when (message.role) {
            "steps" -> StepsSheetSurface(message)
            "process" -> ProcessResultSheetSurface(message.processResult!!)
            "changes" -> ChangesSheetSurface(message)
        }
    }
}

@Composable
internal fun TaskProgressInspectionSheet(
    progress: TaskProgress,
    onDismiss: () -> Unit,
) {
    InspectionModalSheet(onDismiss = onDismiss) {
        TaskProgressSheetSurface(progress)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InspectionModalSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CelesteSurfaceRaised,
        contentColor = CelesteTextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
    ) {
        content()
    }
}

@Composable
internal fun InspectionSheetSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp, bottom = 8.dp)
                .size(width = 48.dp, height = 5.dp)
                .background(CelesteHairline, RoundedCornerShape(3.dp)),
        )
        content()
    }
}

internal val InspectionChevronIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Open details",
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
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineTo(9f, 19f)
        }
    }.build()
}
