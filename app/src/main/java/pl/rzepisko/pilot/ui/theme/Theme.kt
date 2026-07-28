package pl.rzepisko.pilot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Accent = Color(0xFF3D7BFF)
private val AccentDark = Color(0xFF9BB8FF)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondary = Color(0xFF4F5B6E),
    tertiary = Color(0xFF6C5CA8),
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    secondary = Color(0xFFB9C5DC),
    tertiary = Color(0xFFCFC0FF),
)

@Composable
fun PilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You: na Androidzie 12+ pilot dopasowuje się do tapety użytkownika.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
