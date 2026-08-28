package com.moronigranja.localttsreader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Ayvu brand palette, light: dark-amber primary / dark-teal secondary+tertiary on warm
 * cream background/surface. Roles not listed keep the M3 `lightColorScheme()` defaults;
 * no component in B1/B2 reads them. Verified contrast: ink/paper 13.6,
 * white-on-#7A5200 6.9, #7A5200-on-paper 6.0, #0B5F72-on-paper 6.3.
 */
val AyvuLightColors = lightColorScheme(
    primary = Color(0xFF7A5200),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBE0B8),
    onPrimaryContainer = Color(0xFF2A1A00),
    secondary = Color(0xFF0B5F72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEFF6),
    onSecondaryContainer = Color(0xFF042E39),
    tertiary = Color(0xFF0B5F72),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDEFF6),
    onTertiaryContainer = Color(0xFF042E39),
    background = Color(0xFFF5EFE0),
    onBackground = Color(0xFF1B2430),
    surface = Color(0xFFF5EFE0),
    onSurface = Color(0xFF1B2430),
    surfaceVariant = Color(0xFFE7DEC9),
    onSurfaceVariant = Color(0xFF56503F),
    outline = Color(0xFF5F5745),
    outlineVariant = Color(0xFFCFC6AE),
)

/** Ayvu brand palette, dark: bright amber / light teal on ink. Unlisted roles keep M3 defaults. */
val AyvuDarkColors = darkColorScheme(
    primary = Color(0xFFE8A33D),
    onPrimary = Color(0xFF1B2430),
    primaryContainer = Color(0xFF5A3C00),
    onPrimaryContainer = Color(0xFFFBE0B8),
    secondary = Color(0xFF66C8E1),
    onSecondary = Color(0xFF042E39),
    secondaryContainer = Color(0xFF104D5C),
    onSecondaryContainer = Color(0xFFCDEFF6),
    tertiary = Color(0xFF66C8E1),
    onTertiary = Color(0xFF042E39),
    tertiaryContainer = Color(0xFF104D5C),
    onTertiaryContainer = Color(0xFFCDEFF6),
    background = Color(0xFF1B2430),
    onBackground = Color(0xFFF5EFE0),
    surface = Color(0xFF1B2430),
    onSurface = Color(0xFFF5EFE0),
    surfaceVariant = Color(0xFF2B3542),
    onSurfaceVariant = Color(0xFFCBC2AE),
    outline = Color(0xFF8A94A1),
    outlineVariant = Color(0xFF424B57),
)

/** M3 default type scale; the single future override point for a branded face (B4 decision). */
val AyvuTypography: Typography = Typography()

val AyvuShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object AyvuSpacing {
    val XS = 4.dp
    val SM = 8.dp
    val MD = 12.dp
    val LG = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
}

object AyvuMotion {
    const val STANDARD_MS = 300
}

/** Brand theme wrapper. Dumb: hosts resolve [darkTheme] from their own ThemeMode setting. */
@Composable
fun AyvuTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) AyvuDarkColors else AyvuLightColors,
        typography = AyvuTypography,
        shapes = AyvuShapes,
        content = content,
    )
}