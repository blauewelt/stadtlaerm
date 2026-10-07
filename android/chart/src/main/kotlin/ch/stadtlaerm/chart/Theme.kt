package ch.stadtlaerm.chart

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The app theme lives here (not in :app) so the chart can be rendered with it on the JVM.

val LightColors = lightColorScheme(
    primary = Color(0xFF1F3A4D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F0),
    onPrimaryContainer = Color(0xFF0B1E2A),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFE3A8),
    onSecondaryContainer = Color(0xFF2B1B00),
    tertiary = Color(0xFF3E6B48),
    background = Color(0xFFF7F7F4),
    surface = Color(0xFFF7F7F4),
    surfaceVariant = Color(0xFFE4E4DE),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC6E4),
    onPrimary = Color(0xFF0B1E2A),
    primaryContainer = Color(0xFF2A4B61),
    onPrimaryContainer = Color(0xFFD3E4F0),
    secondary = Color(0xFFF2C14E),
    secondaryContainer = Color(0xFF5A3F00),
    onSecondaryContainer = Color(0xFFFFE3A8),
    tertiary = Color(0xFF9FD3A8),
    background = Color(0xFF111416),
    surface = Color(0xFF111416),
    surfaceVariant = Color(0xFF2A2E31),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun StadtlaermTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/**
 * Chart data colours (validated for colour-vision deficiency and contrast), chosen per mode, never
 * derived by inversion. Text never uses these; it uses the theme's text colours.
 */
data class ChartPalette(val series1: Color, val series2: Color, val bandAlpha: Float) {
    companion object {
        // Band alpha: ~14 % on light; on the dark card 14 % was hard to see in renders, so 22 %.
        val Light = ChartPalette(series1 = Color(0xFF2A78D6), series2 = Color(0xFFEB6834), bandAlpha = 0.14f)
        val Dark = ChartPalette(series1 = Color(0xFF3987E5), series2 = Color(0xFFD95926), bandAlpha = 0.22f)

        @Composable
        @ReadOnlyComposable
        fun current(): ChartPalette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Dark else Light
    }
}
