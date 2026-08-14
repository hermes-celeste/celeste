package dev.hazydreams.hermesceleste.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val CelestePaper = Color(0xFFFFFEFA)
val CelestePaperRaised = Color(0xFFFFFFFF)
val CelesteInk = Color(0xFF171717)
val CelestePanel = CelestePaperRaised
val CelestePanelRaised = Color(0xFFF6F5F1)
val CelesteGold = Color(0xFFC49A4A)
val CelesteCoral = CelesteGold
val CelesteBlue = CelesteInk
val CelesteText = CelesteInk
val CelesteMuted = Color(0xFF6E6E73)
val CelesteHairline = Color(0xFFD8D8D3)
val CelesteSoftBlue = Color(0xFFF2F2F0)
val CelesteSoftCoral = Color(0xFFF7F2E8)
val CelesteError = Color(0xFFB74747)

private val CelesteColors = lightColorScheme(
    primary = CelesteInk,
    onPrimary = CelestePaper,
    secondary = CelesteGold,
    tertiary = CelesteGold,
    background = CelestePaper,
    onBackground = CelesteInk,
    surface = CelestePaperRaised,
    onSurface = CelesteInk,
    surfaceVariant = CelestePanelRaised,
    onSurfaceVariant = CelesteMuted,
    outline = CelesteHairline,
    error = CelesteError,
    onError = CelestePaper,
)

private val CelesteTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 48.sp,
        lineHeight = 51.sp,
        letterSpacing = (-1.7).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 43.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun HermesCelesteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CelesteColors,
        typography = CelesteTypography,
        content = content,
    )
}
