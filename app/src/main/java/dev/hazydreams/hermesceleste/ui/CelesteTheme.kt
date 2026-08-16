package dev.hazydreams.hermesceleste.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.R

val CelestePaper = Color(0xFFFCFCFD)
val CelesteInk = Color(0xFF111113)
val CelestePanel = Color(0xFFFFFFFF)
val CelestePanelRaised = Color(0xFFF6F6F9)
val CelesteGlowBlue = Color(0xFF7AA6FF)
val CelesteBlue = Color(0xFF3756C8)
val CelesteAmber = Color(0xFFFFC57A)
val CelesteAmberText = Color(0xFF8A651F)
val CelesteSuccess = Color(0xFF39B987)
val CelesteMuted = Color(0xFF6E6E73)
val CelesteHairline = Color(0xFFE3E3E8)
val CelesteSoftBlue = Color(0xFFF2F5FC)
val CelesteError = Color(0xFFB74747)

private val CelesteColors = lightColorScheme(
    primary = CelesteInk,
    onPrimary = CelestePaper,
    secondary = CelesteAmberText,
    tertiary = CelesteAmber,
    background = CelestePaper,
    onBackground = CelesteInk,
    surface = CelestePanel,
    onSurface = CelesteInk,
    surfaceVariant = CelestePanelRaised,
    onSurfaceVariant = CelesteMuted,
    outline = CelesteHairline,
    error = CelesteError,
    onError = CelestePaper,
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
