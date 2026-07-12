package aenu.ax360e.ui.model

import aenu.ax360e.Application
import aenu.ax360e.Emulator
import aenu.ax360e.Utils
import aenu.emulator.Emulator as NativeEmulator
import java.io.File

/**
 * Helper around Logging|log_level and related JIT verbosity flags in the
 * global Xenia config. Mirrors official xenia-canary cvars:
 *
 *  Logging|log_level  0=error, 1=warning, 2=info, 3=debug, 4=trace
 *  CPU|disassemble_functions
 *  CPU|dump_translated_hir_functions
 *  CPU|trace_functions
 *
 * Changes take effect on the next emulator boot (cvars are loaded at start).
 */
object LoggingConfigHelper {

    const val KEY_LOG_LEVEL = "Logging|log_level"
    const val KEY_DISASSEMBLE = "CPU|disassemble_functions"
    const val KEY_DUMP_HIR = "CPU|dump_translated_hir_functions"
    const val KEY_TRACE_FUNCTIONS = "CPU|trace_functions"

    private val LEVEL_NAMES = listOf("error", "warning", "info", "debug", "trace")

    fun levelName(level: Int): String =
        LEVEL_NAMES.getOrElse(level.coerceIn(0, LEVEL_NAMES.lastIndex)) { "info" }

    fun readLogLevel(): Int {
        if (!Emulator.ensure_library_loaded()) return 2
        return runCatching {
            val path = Application.ensure_global_config_file().absolutePath
            val cfg = NativeEmulator.Config.open_config_file(path)
            try {
                cfg.load_config_entry(KEY_LOG_LEVEL)?.toIntOrNull() ?: 2
            } finally {
                // Don't flush — read-only open still needs close to free native handle.
                // close_config_file rewrites the file with the same contents.
                cfg.close_config_file()
            }
        }.getOrDefault(2)
    }

    fun setLogLevel(level: Int): Boolean {
        val clamped = level.coerceIn(0, 4)
        return mutateConfig { cfg ->
            cfg.save_config_entry(KEY_LOG_LEVEL, clamped.toString())
        }
    }

    /**
     * Turns on the CPU flags that make the A64 JIT dump per-function detail
     * into xe.log when combined with log_level >= 3.
     */
    fun enableJitFunctionDetail(enabled: Boolean = true): Boolean {
        val value = enabled.toString()
        return mutateConfig { cfg ->
            cfg.save_config_entry(KEY_DISASSEMBLE, value)
            cfg.save_config_entry(KEY_DUMP_HIR, value)
            cfg.save_config_entry(KEY_TRACE_FUNCTIONS, value)
            // Ensure log_level is at least debug so the new XELOGD/CPU lines appear.
            val current = cfg.load_config_entry(KEY_LOG_LEVEL)?.toIntOrNull() ?: 2
            if (enabled && current < 3) {
                cfg.save_config_entry(KEY_LOG_LEVEL, "3")
            }
        }
    }

    private fun mutateConfig(block: (NativeEmulator.Config) -> Unit): Boolean {
        if (!Emulator.ensure_library_loaded()) return false
        return runCatching {
            val file = Application.ensure_global_config_file()
            if (!file.exists()) {
                Utils.copy_file(Application.get_default_config_file(), file)
            }
            val cfg = NativeEmulator.Config.open_config_file(file.absolutePath)
            try {
                block(cfg)
            } finally {
                cfg.close_config_file()
            }
            true
        }.getOrDefault(false)
    }
}
