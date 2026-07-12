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
 * Material You palette aligned with RPCSX-ui-android surface containers,
 * tinted toward Xbox green accents for aX360e identity.
 */
object Ax360eColors {
    val primaryLight = Color(0xFF006E1C)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFF94F990)
    val onPrimaryContainerLight = Color(0xFF002204)
    val secondaryLight = Color(0xFF52634F)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFD5E8CF)
    val onSecondaryContainerLight = Color(0xFF101F0F)
    val tertiaryLight = Color(0xFF38656A)
    val onTertiaryLight = Color(0xFFFFFFFF)
    val tertiaryContainerLight = Color(0xFFBCEBF0)
    val onTertiaryContainerLight = Color(0xFF002023)
    val errorLight = Color(0xFFBA1A1A)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFFDAD6)
    val onErrorContainerLight = Color(0xFF410002)
    val backgroundLight = Color(0xFFFCFDF6)
    val onBackgroundLight = Color(0xFF1A1C19)
    val surfaceLight = Color(0xFFFCFDF6)
    val onSurfaceLight = Color(0xFF1A1C19)
    val surfaceVariantLight = Color(0xFFDEE5D8)
    val onSurfaceVariantLight = Color(0xFF424940)
    val outlineLight = Color(0xFF72796F)
    val outlineVariantLight = Color(0xFFC2C9BC)
    val scrimLight = Color(0xFF000000)
    val inverseSurfaceLight = Color(0xFF2F312D)
    val inverseOnSurfaceLight = Color(0xFFF0F1EB)
    val inversePrimaryLight = Color(0xFF78DC77)
    val surfaceDimLight = Color(0xFFDADAD4)
    val surfaceBrightLight = Color(0xFFFCFDF6)
    val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    val surfaceContainerLowLight = Color(0xFFF6F7F0)
    val surfaceContainerLight = Color(0xFFF0F1EA)
    val surfaceContainerHighLight = Color(0xFFEAEBE4)
    val surfaceContainerHighestLight = Color(0xFFE5E6DF)

    val primaryDark = Color(0xFF78DC77)
    val onPrimaryDark = Color(0xFF00390A)
    val primaryContainerDark = Color(0xFF005313)
    val onPrimaryContainerDark = Color(0xFF94F990)
    val secondaryDark = Color(0xFFB9CCB4)
    val onSecondaryDark = Color(0xFF253423)
    val secondaryContainerDark = Color(0xFF3B4B39)
    val onSecondaryContainerDark = Color(0xFFD5E8CF)
    val tertiaryDark = Color(0xFFA0CFD4)
    val onTertiaryDark = Color(0xFF00363B)
    val tertiaryContainerDark = Color(0xFF1F4D52)
    val onTertiaryContainerDark = Color(0xFFBCEBF0)
    val errorDark = Color(0xFFFFB4AB)
    val onErrorDark = Color(0xFF690005)
    val errorContainerDark = Color(0xFF93000A)
    val onErrorContainerDark = Color(0xFFFFDAD6)
    val backgroundDark = Color(0xFF121411)
    val onBackgroundDark = Color(0xFFE2E3DC)
    val surfaceDark = Color(0xFF121411)
    val onSurfaceDark = Color(0xFFE2E3DC)
    val surfaceVariantDark = Color(0xFF424940)
    val onSurfaceVariantDark = Color(0xFFC2C9BC)
    val outlineDark = Color(0xFF8C9388)
    val outlineVariantDark = Color(0xFF424940)
    val scrimDark = Color(0xFF000000)
    val inverseSurfaceDark = Color(0xFFE2E3DC)
    val inverseOnSurfaceDark = Color(0xFF2F312D)
    val inversePrimaryDark = Color(0xFF006E1C)
    val surfaceDimDark = Color(0xFF121411)
    val surfaceBrightDark = Color(0xFF383A36)
    val surfaceContainerLowestDark = Color(0xFF0C0F0C)
    val surfaceContainerLowDark = Color(0xFF1A1C19)
    val surfaceContainerDark = Color(0xFF1E201C)
    val surfaceContainerHighDark = Color(0xFF282B27)
    val surfaceContainerHighestDark = Color(0xFF333531)
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
