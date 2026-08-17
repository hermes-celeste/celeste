package dev.hazydreams.hermesceleste.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class CelesteLightTone {
    None,
    Cool,
    Warm,
}

@Composable
internal fun CelesteBackdrop(
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

@Composable
internal fun CelesteSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = CelesteMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

@Composable
internal fun StatusMessage(message: String, color: Color, showSpinner: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.7.dp,
                color = color,
            )
        } else {
            Box(Modifier.size(7.dp).background(color, CircleShape))
        }
        Text(
            text = message,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun CelesteSurface(
    modifier: Modifier = Modifier,
    tone: CelesteLightTone = CelesteLightTone.Cool,
    emphasized: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    containerColor: Color = CelestePanel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val glow = when (tone) {
        CelesteLightTone.None -> Color.Transparent
        CelesteLightTone.Cool -> CelesteGlowBlue
        CelesteLightTone.Warm -> CelesteAmber
    }
    val elevation = when {
        tone == CelesteLightTone.None -> 1.dp
        emphasized -> 18.dp
        else -> 12.dp
    }
    val border = when (tone) {
        CelesteLightTone.None -> CelesteHairline
        CelesteLightTone.Cool -> CelesteGlowBlue.copy(alpha = if (emphasized) 0.72f else 0.48f)
        CelesteLightTone.Warm -> CelesteAmber.copy(alpha = if (emphasized) 0.74f else 0.50f)
    }

    Box(modifier = modifier) {
        if (tone != CelesteLightTone.None) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(
                        radius = if (emphasized) 20.dp else 14.dp,
                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
                    )
                    .background(
                        color = glow.copy(alpha = if (emphasized) 0.30f else 0.22f),
                        shape = shape,
                    ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = glow.copy(alpha = if (emphasized) 0.38f else 0.24f),
                    spotColor = glow.copy(alpha = if (emphasized) 0.48f else 0.32f),
                )
                .shadow(
                    elevation = if (tone == CelesteLightTone.None) 0.dp else 3.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = glow.copy(alpha = if (emphasized) 0.46f else 0.34f),
                    spotColor = glow.copy(alpha = if (emphasized) 0.54f else 0.40f),
                )
                .clip(shape)
                .background(containerColor)
                .border(1.dp, border, shape)
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
internal fun CelesteActivityFrame(
    visible: Boolean,
    moving: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val inspectionMode = LocalInspectionMode.current
    val animate = visible && moving && !inspectionMode && ValueAnimator.areAnimatorsEnabled()
    val progress = if (animate) {
        val transition = rememberInfiniteTransition(label = "active turn frame")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "active turn light position",
        )
        value
    } else {
        0.58f
    }

    Box(modifier = modifier) {
        content()
        if (visible) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp),
            ) {
                val radius = 30.dp.toPx()
                drawRoundRect(
                    color = CelesteGlowBlue.copy(alpha = 0.12f),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 9.dp.toPx()),
                )
                drawRoundRect(
                    color = CelesteGlowBlue.copy(alpha = 0.34f),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 1.2.dp.toPx()),
                )

                val travel = size.width * (progress * 1.8f - 0.4f)
                val brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        CelesteAmber.copy(alpha = 0.92f),
                        CelesteGlowBlue.copy(alpha = 0.98f),
                        Color.Transparent,
                    ),
                    start = Offset(travel - size.width * 0.52f, 0f),
                    end = Offset(travel + size.width * 0.52f, size.height),
                )
                drawRoundRect(
                    brush = brush,
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 1.7.dp.toPx()),
                )
            }
        }
    }
}

@Composable
internal fun CelesteOrb(
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val coreRadius = size.minDimension * 0.16f
        drawCircle(
            color = CelesteGlowBlue.copy(alpha = 0.08f),
            radius = size.minDimension * 0.42f,
            center = center,
        )
        drawCircle(
            color = CelesteGlowBlue.copy(alpha = 0.24f),
            radius = size.minDimension * 0.31f,
            center = center,
            style = Stroke(width = 0.8.dp.toPx()),
        )
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(CelesteGlowBlue, CelesteAmber),
                start = Offset(center.x - coreRadius, center.y - coreRadius),
                end = Offset(center.x + coreRadius, center.y + coreRadius),
            ),
            radius = coreRadius,
            center = center,
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.38f),
            radius = coreRadius * 0.26f,
            center = Offset(center.x - coreRadius * 0.28f, center.y - coreRadius * 0.28f),
        )
    }
}
