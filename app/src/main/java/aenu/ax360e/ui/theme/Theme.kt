package aenu.ax360e.ui.theme

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

// Material You 3 Expressive palette
private val LightPrimary = Color(0xFF006A6A)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFF6FF7F6)
private val LightOnPrimaryContainer = Color(0xFF002020)

private val LightSecondary = Color(0xFF4A6357)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFCDE8D9)
private val LightOnSecondaryContainer = Color(0xFF072016)

private val LightTertiary = Color(0xFF426278)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFC7E8FF)
private val LightOnTertiaryContainer = Color(0xFF001E2F)

private val DarkPrimary = Color(0xFF4CDADA)
private val DarkOnPrimary = Color(0xFF003737)
private val DarkPrimaryContainer = Color(0xFF004F50)
private val DarkOnPrimaryContainer = Color(0xFF6FF7F6)

private val DarkSecondary = Color(0xFFB1CCBE)
private val DarkOnSecondary = Color(0xFF1C352A)
private val DarkSecondaryContainer = Color(0xFF334B40)
private val DarkOnSecondaryContainer = Color(0xFFCDE8D9)

private val DarkTertiary = Color(0xFFAACBE3)
private val DarkOnTertiary = Color(0xFF113447)
private val DarkTertiaryContainer = Color(0xFF294A5F)
private val DarkOnTertiaryContainer = Color(0xFFC7E8FF)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFDF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFAFDF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2D3130),
    inverseOnSurface = Color(0xFFEEF1EF),
    inversePrimary = Color(0xFF4CDADA),
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1B),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFC4C7C4),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE1E3E0),
    inverseOnSurface = Color(0xFF2D3130),
    inversePrimary = Color(0xFF006A6A),
)

@Composable
fun Ax360eTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Ax360eTypography,
        shapes = Ax360eShapes,
        content = content
    )
}
