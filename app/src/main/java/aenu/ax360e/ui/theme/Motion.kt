package aenu.ax360e.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * Material You motion tokens.
 *
 * Reflects the M3 duration + easing reference so animations feel consistent
 * across the app: emphasized for hero transitions, standard for routine UI.
 *
 * Reference: https://m3.material.io/styles/motion/easing-and-duration
 */
object Motion {
    /** Standard M3 emphasized easing. */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val DurationShort1 = 75
    const val DurationShort2 = 100
    const val DurationShort3 = 150
    const val DurationShort4 = 200
    const val DurationMedium1 = 250
    const val DurationMedium2 = 300
    const val DurationMedium3 = 350
    const val DurationMedium4 = 400
    const val DurationLong1 = 450
    const val DurationLong2 = 500

    fun <T> emphasized(
        durationMillis: Int = DurationMedium2,
        delayMillis: Int = 0
    ): DurationBasedAnimationSpec<T> = tween(
        durationMillis = durationMillis,
        delayMillis = delayMillis,
        easing = EmphasizedEasing
    )

    fun <T> standard(
        durationMillis: Int = DurationShort4,
        delayMillis: Int = 0
    ): FiniteAnimationSpec<T> = tween(
        durationMillis = durationMillis,
        delayMillis = delayMillis,
        easing = StandardEasing
    )
}
