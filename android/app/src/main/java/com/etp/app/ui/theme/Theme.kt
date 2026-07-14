package com.etp.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Violet = Color(0xFF8B5CF6)
val VioletDeep = Color(0xFF6D4DF2)
val Cyan = Color(0xFF22D3EE)
val Pink = Color(0xFFF472B6)
val SuccessGreen = Color(0xFF34D399)

val BrandGradient = Brush.linearGradient(listOf(VioletDeep, Violet, Color(0xFFA78BFA)))

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B2D73),
    onPrimaryContainer = Color(0xFFE5DBFF),
    secondary = Cyan,
    onSecondary = Color(0xFF00363F),
    tertiary = Pink,
    background = Color(0xFF0D0D12),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF15151C),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF1E1E28),
    onSurfaceVariant = Color(0xFFA6A6B5),
    outline = Color(0xFF2E2E3C),
    error = Color(0xFFFB7185),
)

private val LightColors = lightColorScheme(
    primary = VioletDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E1FF),
    onPrimaryContainer = Color(0xFF2A1D5E),
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFFDB2777),
    background = Color(0xFFFAF9FE),
    onBackground = Color(0xFF17171F),
    surface = Color.White,
    onSurface = Color(0xFF17171F),
    surfaceVariant = Color(0xFFF0EDF8),
    onSurfaceVariant = Color(0xFF5D5D6E),
    outline = Color(0xFFDCD8EA),
    error = Color(0xFFDC2626),
)

private val EtpTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

private val EtpShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun EtpTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = EtpTypography,
        shapes = EtpShapes,
        content = content,
    )
}
