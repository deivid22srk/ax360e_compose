package aenu.ax360e.ui.model

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * User preferences for the emulator UI. Stored in the default
 * SharedPreferences so they persist across app launches.
 */
object UiPreferences {
    private const val KEY_SHOW_FPS = "ui_show_fps"
    private const val KEY_MAX_CLOCKS = "ui_max_clocks"
    private const val KEY_RAW_CLOCK = "ui_raw_clock_source"
    private const val KEY_NO_CLOCK_SCALING = "ui_no_clock_scaling"

    fun isFpsVisible(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_SHOW_FPS, false)
    }

    fun setFpsVisible(context: Context, visible: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_SHOW_FPS, visible)
            .apply()
    }

    /**
     * Whether to force maximum GPU clocks via adrenotools (Adreno only).
     * Maps to the Vulkan|adrenotools_force_max_clocks cvar.
     */
    fun isMaxClocksEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_MAX_CLOCKS, true)
    }

    /**
     * Whether to use the raw host clock source (CNTVCT_EL0 on A64) instead of
     * the scaled guest clock. Maps to CPU|clock_source_raw.
     */
    fun isRawClockSourceEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_RAW_CLOCK, true)
    }

    /**
     * Whether to bypass time management and locking entirely. Maps to
     * CPU|clock_no_scaling.
     */
    fun isNoClockScalingEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_NO_CLOCK_SCALING, true)
    }
}
