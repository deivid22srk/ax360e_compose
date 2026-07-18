package aenu.ax360e.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Material You (M3) theme for Xenon360.
 *
 * Design goals
 * ------------
 *  • Honour the system wallpaper on Android 12+ (dynamicColor = true) so the
 *    app feels native and personalised — a core Material You principle.
 *  • Fall back to a refined violet palette on pre-Android 12 devices that
 *    matches the v2 redesign reference mockups.
 *  • Keep tonal surface containers following the M3 2024 spec so elevation
 *    tints render correctly inside cards, drawers and bars.
 *  • Make the system bars transparent and let Compose own the contrast via
 *    WindowInsetsControllerCompat (light/dark icons follow the theme).
 */
object Ax360eColors {
    // ---- Light scheme (violet family, refined) ----
    val primaryLight = Color(0xFF6B4EA2)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFFEBDCFF)
    val onPrimaryContainerLight = Color(0xFF22005C)
    val secondaryLight = Color(0xFF984061)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFFFD9E2)
    val onSecondaryContainerLight = Color(0xFF3E001E)
    val tertiaryLight = Color(0xFF6B4EA2)
    val onTertiaryLight = Color(0xFFFFFFFF)
    val tertiaryContainerLight = Color(0xFFEBDCFF)
    val onTertiaryContainerLight = Color(0xFF22005C)
    val errorLight = Color(0xFFBA1A1A)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFFDAD6)
    val onErrorContainerLight = Color(0xFF410002)
    val backgroundLight = Color(0xFFFFF7FB)
    val onBackgroundLight = Color(0xFF1E1B22)
    val surfaceLight = Color(0xFFFFF7FB)
    val onSurfaceLight = Color(0xFF1E1B22)
    val surfaceVariantLight = Color(0xFFE7E0EB)
    val onSurfaceVariantLight = Color(0xFF49454F)
    val outlineLight = Color(0xFF7A757F)
    val outlineVariantLight = Color(0xFFCAC4CF)
    val scrimLight = Color(0xFF000000)
    val inverseSurfaceLight = Color(0xFF332F38)
    val inverseOnSurfaceLight = Color(0xFFF6EFF7)
    val inversePrimaryLight = Color(0xFFD3BBFF)
    val surfaceDimLight = Color(0xFFE0D7E6)
    val surfaceBrightLight = Color(0xFFFFF7FB)
    val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    val surfaceContainerLowLight = Color(0xFFFAF1FA)
    val surfaceContainerLight = Color(0xFFF4EBF4)
    val surfaceContainerHighLight = Color(0xFFEEE5EE)
    val surfaceContainerHighestLight = Color(0xFFE9E0E9)

    // ---- Dark scheme (matches the redesign reference) ----
    val primaryDark = Color(0xFFE9DDFF)
    val onPrimaryDark = Color(0xFF37265E)
    val primaryContainerDark = Color(0xFF594983)
    val onPrimaryContainerDark = Color(0xFFEBDCFF)
    val secondaryDark = Color(0xFFFFB0C9)
    val onSecondaryDark = Color(0xFF5C1333)
    val secondaryContainerDark = Color(0xFF7C2D4C)
    val onSecondaryContainerDark = Color(0xFFFF9ABC)
    val tertiaryDark = Color(0xFFE9DDFF)
    val onTertiaryDark = Color(0xFF381E72)
    val tertiaryContainerDark = Color(0xFF5B4397)
    val onTertiaryContainerDark = Color(0xFFEBDCFF)
    val errorDark = Color(0xFFFFB4AB)
    val onErrorDark = Color(0xFF690005)
    val errorContainerDark = Color(0xFF93000A)
    val onErrorContainerDark = Color(0xFFFFDAD6)
    val backgroundDark = Color(0xFF141317)
    val onBackgroundDark = Color(0xFFE6E1E7)
    val surfaceDark = Color(0xFF141317)
    val onSurfaceDark = Color(0xFFE6E1E7)
    val surfaceVariantDark = Color(0xFF363438)
    val onSurfaceVariantDark = Color(0xFFCAC4D0)
    val outlineDark = Color(0xFF948F9A)
    val outlineVariantDark = Color(0xFF49454F)
    val scrimDark = Color(0xFF000000)
    val inverseSurfaceDark = Color(0xFFE6E1E7)
    val inverseOnSurfaceDark = Color(0xFF323034)
    val inversePrimaryDark = Color(0xFF665590)
    val surfaceDimDark = Color(0xFF141317)
    val surfaceBrightDark = Color(0xFF3B383D)
    val surfaceContainerLowestDark = Color(0xFF0F0E11)
    val surfaceContainerLowDark = Color(0xFF1C1B1F)
    val surfaceContainerDark = Color(0xFF211F23)
    val surfaceContainerHighDark = Color(0xFF2B292D)
    val surfaceContainerHighestDark = Color(0xFF363438)
}

private val LightColorScheme = lightColorScheme(
    primary = Ax360eColors.primaryLight,
    onPrimary = Ax360eColors.onPrimaryLight,
    primaryContainer = Ax360eColors.primaryContainerLight,
    onPrimaryContainer = Ax360eColors.onPrimaryContainerLight,
    secondary = Ax360eColors.secondaryLight,
    onSecondary = Ax360eColors.onSecondaryLight,
    secondaryContainer = Ax360eColors.secondaryContainerLight,
    onSecondaryContainer = Ax360eColors.onSecondaryContainerLight,
    tertiary = Ax360eColors.tertiaryLight,
    onTertiary = Ax360eColors.onTertiaryLight,
    tertiaryContainer = Ax360eColors.tertiaryContainerLight,
    onTertiaryContainer = Ax360eColors.onTertiaryContainerLight,
    error = Ax360eColors.errorLight,
    onError = Ax360eColors.onErrorLight,
    errorContainer = Ax360eColors.errorContainerLight,
    onErrorContainer = Ax360eColors.onErrorContainerLight,
    background = Ax360eColors.backgroundLight,
    onBackground = Ax360eColors.onBackgroundLight,
    surface = Ax360eColors.surfaceLight,
    onSurface = Ax360eColors.onSurfaceLight,
    surfaceVariant = Ax360eColors.surfaceVariantLight,
    onSurfaceVariant = Ax360eColors.onSurfaceVariantLight,
    outline = Ax360eColors.outlineLight,
    outlineVariant = Ax360eColors.outlineVariantLight,
    scrim = Ax360eColors.scrimLight,
    inverseSurface = Ax360eColors.inverseSurfaceLight,
    inverseOnSurface = Ax360eColors.inverseOnSurfaceLight,
    inversePrimary = Ax360eColors.inversePrimaryLight,
    surfaceDim = Ax360eColors.surfaceDimLight,
    surfaceBright = Ax360eColors.surfaceBrightLight,
    surfaceContainerLowest = Ax360eColors.surfaceContainerLowestLight,
    surfaceContainerLow = Ax360eColors.surfaceContainerLowLight,
    surfaceContainer = Ax360eColors.surfaceContainerLight,
    surfaceContainerHigh = Ax360eColors.surfaceContainerHighLight,
    surfaceContainerHighest = Ax360eColors.surfaceContainerHighestLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = Ax360eColors.primaryDark,
    onPrimary = Ax360eColors.onPrimaryDark,
    primaryContainer = Ax360eColors.primaryContainerDark,
    onPrimaryContainer = Ax360eColors.onPrimaryContainerDark,
    secondary = Ax360eColors.secondaryDark,
    onSecondary = Ax360eColors.onSecondaryDark,
    secondaryContainer = Ax360eColors.secondaryContainerDark,
    onSecondaryContainer = Ax360eColors.onSecondaryContainerDark,
    tertiary = Ax360eColors.tertiaryDark,
    onTertiary = Ax360eColors.onTertiaryDark,
    tertiaryContainer = Ax360eColors.tertiaryContainerDark,
    onTertiaryContainer = Ax360eColors.onTertiaryContainerDark,
    error = Ax360eColors.errorDark,
    onError = Ax360eColors.onErrorDark,
    errorContainer = Ax360eColors.errorContainerDark,
    onErrorContainer = Ax360eColors.onErrorContainerDark,
    background = Ax360eColors.backgroundDark,
    onBackground = Ax360eColors.onBackgroundDark,
    surface = Ax360eColors.surfaceDark,
    onSurface = Ax360eColors.onSurfaceDark,
    surfaceVariant = Ax360eColors.surfaceVariantDark,
    onSurfaceVariant = Ax360eColors.onSurfaceVariantDark,
    outline = Ax360eColors.outlineDark,
    outlineVariant = Ax360eColors.outlineVariantDark,
    scrim = Ax360eColors.scrimDark,
    inverseSurface = Ax360eColors.inverseSurfaceDark,
    inverseOnSurface = Ax360eColors.inverseOnSurfaceDark,
    inversePrimary = Ax360eColors.inversePrimaryDark,
    surfaceDim = Ax360eColors.surfaceDimDark,
    surfaceBright = Ax360eColors.surfaceBrightDark,
    surfaceContainerLowest = Ax360eColors.surfaceContainerLowestDark,
    surfaceContainerLow = Ax360eColors.surfaceContainerLowDark,
    surfaceContainer = Ax360eColors.surfaceContainerDark,
    surfaceContainerHigh = Ax360eColors.surfaceContainerHighDark,
    surfaceContainerHighest = Ax360eColors.surfaceContainerHighestDark,
)

@Composable
fun Ax360eTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // [M3] Material You: honour the user's wallpaper on Android 12+. This is
    // the headline feature of M3 dynamic color and is what makes the app feel
    // truly native. Pre-Android 12 falls back to the refined violet palette.
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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
            activity?.window?.apply {
                statusBarColor = android.graphics.Color.TRANSPARENT
                navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isNavigationBarContrastEnforced = false
                }
                val insetsController = WindowInsetsControllerCompat(this, decorView)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Ax360eTypography,
        shapes = Ax360eShapes,
        content = content
    )
}
