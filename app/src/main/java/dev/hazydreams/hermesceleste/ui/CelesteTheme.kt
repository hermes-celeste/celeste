package dev.hazydreams.hermesceleste.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.R

val CelesteCanvas = Color(0xFF171718)
val CelesteSurfacePrimary = Color(0xFF232325)
val CelesteSurfaceRaised = Color(0xFF2B2B2E)
val CelesteSurfaceSelected = Color(0xFF343438)
val CelesteTextPrimary = Color(0xFFF4F4F5)
val CelesteTextMuted = Color(0xFFA5A5AB)
val CelesteHairline = Color(0xFF404044)
val CelesteAccent = Color(0xFF4C9EFF)
val CelesteAccentContent = Color(0xFF07131F)
val CelesteSuccess = Color(0xFF62C99A)
val CelesteWarning = Color(0xFFE1B36B)
val CelesteError = Color(0xFFFF8585)

private val CelesteColors = darkColorScheme(
    primary = CelesteAccent,
    onPrimary = CelesteAccentContent,
    secondary = CelesteTextMuted,
    onSecondary = CelesteCanvas,
    tertiary = CelesteWarning,
    onTertiary = CelesteCanvas,
    background = CelesteCanvas,
    onBackground = CelesteTextPrimary,
    surface = CelesteSurfacePrimary,
    onSurface = CelesteTextPrimary,
    surfaceVariant = CelesteSurfaceRaised,
    onSurfaceVariant = CelesteTextMuted,
    outline = CelesteHairline,
    error = CelesteError,
    onError = CelesteCanvas,
)

private val CelesteFontFamily = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal),
    Font(R.font.inter_variable, weight = FontWeight.Medium),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold),
    Font(R.font.inter_variable, weight = FontWeight.Bold),
)

private val MaterialTypography = Typography()

private val CelesteTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = CelesteFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 48.sp,
        lineHeight = 51.sp,
        letterSpacing = (-1.7).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = CelesteFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 43.sp,
        letterSpacing = (-1.2).sp,
    ),
    displaySmall = MaterialTypography.displaySmall.copy(fontFamily = CelesteFontFamily),
    headlineLarge = TextStyle(
        fontFamily = CelesteFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = CelesteFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = MaterialTypography.headlineSmall.copy(fontFamily = CelesteFontFamily),
    titleLarge = TextStyle(
        fontFamily = CelesteFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = CelesteFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleSmall = MaterialTypography.titleSmall.copy(fontFamily = CelesteFontFamily),
    bodyLarge = TextStyle(
        fontFamily = CelesteFontFamily,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = CelesteFontFamily,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = MaterialTypography.bodySmall.copy(fontFamily = CelesteFontFamily),
    labelLarge = TextStyle(
        fontFamily = CelesteFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = CelesteFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
    ),
    labelSmall = MaterialTypography.labelSmall.copy(fontFamily = CelesteFontFamily),
)

@Composable
fun HermesCelesteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CelesteColors,
        typography = CelesteTypography,
        content = content,
    )
}
