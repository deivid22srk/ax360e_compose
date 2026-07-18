package aenu.ax360e.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material You shapes — softer corners for a more elegant, friendly feel.
 *
 * Tuned from the default M3 baseline to be slightly more rounded (e.g. medium
 * 16→18, large 20→24, extraLarge 28→32) so cards, dialogs and sheets have a
 * calmer, more "Material You" presence without feeling toy-ish.
 */
val Ax360eShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
