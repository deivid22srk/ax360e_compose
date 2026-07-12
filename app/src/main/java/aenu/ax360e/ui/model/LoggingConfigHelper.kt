package aenu.ax360e.ui.model

import aenu.ax360e.Application
import aenu.ax360e.Emulator
import aenu.ax360e.Utils
import aenu.emulator.Emulator as NativeEmulator

/**
 * Helper around Logging|log_level and related JIT verbosity flags in the
 * global Xenia config. Mirrors official xenia-canary cvars:
 *
 *  Logging|log_level  0=error, 1=warning, 2=info, 3=debug, 4=trace
 *  CPU|disassemble_functions
 *  CPU|dump_translated_hir_functions  (writes per-function HIR files; heavy)
 *  CPU|trace_functions
 *
 * Changes take effect on the next emulator boot (cvars are loaded at start).
 *
 * NOTE: dump_translated_hir_functions used to create relative hirdump_* dirs
 * in process CWD and aborted on Android (read-only FS). That path is fixed in
 * native code to storage_root/cache/hirdump, but the UI preset still leaves
 * HIR file dumping OFF by default — it is huge and slow.
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
                cfg.close_config_file()
            }
        }.getOrDefault(2)
    }

    fun setLogLevel(level: Int): Boolean {
        val clamped = level.coerceIn(0, 4)
        return mutateConfig { cfg ->
            cfg.save_config_entry(KEY_LOG_LEVEL, clamped.toString())
            // Safety: never leave HIR file dumps on when only changing level.
            // Users who previously enabled the JIT preset may have this stuck
            // true and crash on launch if native dump path is still broken.
            if (clamped <= 2) {
                cfg.save_config_entry(KEY_DUMP_HIR, "false")
            }
        }
    }

    /**
     * Safe JIT verbosity for Android:
     *  - disassemble_functions + trace_functions → more detail in xe.log
     *  - dump_translated_hir_functions stays OFF (file flood; optional via Settings)
     *  - log_level raised to at least debug (3)
     */
    fun enableJitFunctionDetail(enabled: Boolean = true): Boolean {
        return mutateConfig { cfg ->
            cfg.save_config_entry(KEY_DISASSEMBLE, enabled.toString())
            cfg.save_config_entry(KEY_TRACE_FUNCTIONS, enabled.toString())
            // Keep HIR disk dumps off by default — they are optional and heavy.
            cfg.save_config_entry(KEY_DUMP_HIR, "false")
            if (enabled) {
                val current = cfg.load_config_entry(KEY_LOG_LEVEL)?.toIntOrNull() ?: 2
                if (current < 3) {
                    cfg.save_config_entry(KEY_LOG_LEVEL, "3")
                }
            }
        }
    }

    /** Recover from a bad config that crashes on boot (forces HIR dump off). */
    fun disableHirFileDumps(): Boolean {
        return mutateConfig { cfg ->
            cfg.save_config_entry(KEY_DUMP_HIR, "false")
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
