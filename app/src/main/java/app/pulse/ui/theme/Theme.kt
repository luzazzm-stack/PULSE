package app.pulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PulseColors = darkColorScheme(
    primary = Red,
    onPrimary = OnRed,
    secondary = Red,
    onSecondary = OnRed,
    background = Bg0,
    onBackground = Tx0,
    surface = Bg1,
    onSurface = Tx0,
    surfaceVariant = Bg2,
    onSurfaceVariant = Tx1,
    outline = Line12,
    error = Red,
    onError = OnRed,
)

@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PulseColors, typography = PulseType, content = content)
}
