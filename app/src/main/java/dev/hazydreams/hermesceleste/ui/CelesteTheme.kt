package dev.hazydreams.hermesceleste.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CelesteInk = Color(0xFF08111F)
val CelestePanel = Color(0xFF101B2E)
val CelestePanelRaised = Color(0xFF17253D)
val CelesteCoral = Color(0xFFF18A70)
val CelesteBlue = Color(0xFF8CA8FF)
val CelesteGold = Color(0xFFFFD184)
val CelesteText = Color(0xFFF1ECDD)
val CelesteMuted = Color(0xFF99A7BC)
val CelesteError = Color(0xFFFFA2B1)

private val CelesteColors = darkColorScheme(
    primary = CelesteCoral,
    onPrimary = CelesteInk,
    secondary = CelesteBlue,
    tertiary = CelesteGold,
    background = CelesteInk,
    onBackground = CelesteText,
    surface = CelestePanel,
    onSurface = CelesteText,
    surfaceVariant = CelestePanelRaised,
    onSurfaceVariant = CelesteMuted,
    error = CelesteError,
)

@Composable
fun HermesCelesteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CelesteColors, content = content)
}
