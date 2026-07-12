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

    // [PERF] Debug mode flags. When "Modo Debug" is OFF (the default), all of
    // these are forced to false. When ON, they're set to true to help
    // investigate guest crashes / JIT issues. These flags add real overhead
    // (extra checks in every JIT function, MMIO instrumentation on every
    // load/store) so they must stay OFF in production for best FPS.
    const val KEY_BREAK_ON_DEBUGBREAK = "CPU|break_on_debugbreak"
    const val KEY_BREAK_ON_UNIMPLEMENTED = "CPU|break_on_unimplemented_instructions"
    const val KEY_RECORD_MMIO_EXCEPTIONS = "CPU|record_mmio_access_exceptions"
    const val KEY_EMIT_MMIO_AWARE_STORES = "CPU|emit_mmio_aware_stores_for_recorded_exception_addresses"
    const val KEY_EMIT_INLINE_MMIO_CHECKS = "CPU|emit_inline_mmio_checks"
    const val KEY_STORE_ALL_CONTEXT_VALUES = "CPU|store_all_context_values"
    const val KEY_TRACE_FUNCTION_COVERAGE = "CPU|trace_function_coverage"
    const val KEY_TRACE_FUNCTION_DATA = "CPU|trace_function_data"
    const val KEY_TRACE_FUNCTION_REFERENCES = "CPU|trace_function_references"
    const val KEY_DISASSEMBLE_FUNCTIONS = "CPU|disassemble_functions"

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

    /**
     * [PERF] Returns true if ANY debug-mode flag is currently enabled in the
     * config. Used to show the state of the "Modo Debug" toggle in the UI.
     */
    fun isDebugMode(): Boolean {
        if (!Emulator.ensure_library_loaded()) return false
        return runCatching {
            val path = Application.ensure_global_config_file().absolutePath
            val cfg = NativeEmulator.Config.open_config_file(path)
            try {
                listOf(
                    KEY_BREAK_ON_DEBUGBREAK,
                    KEY_BREAK_ON_UNIMPLEMENTED,
                    KEY_RECORD_MMIO_EXCEPTIONS,
                    KEY_EMIT_MMIO_AWARE_STORES,
                    KEY_EMIT_INLINE_MMIO_CHECKS,
                    KEY_STORE_ALL_CONTEXT_VALUES,
                    KEY_TRACE_FUNCTION_COVERAGE,
                    KEY_TRACE_FUNCTION_DATA,
                    KEY_TRACE_FUNCTION_REFERENCES,
                    KEY_DISASSEMBLE_FUNCTIONS,
                    KEY_TRACE_FUNCTIONS,
                    KEY_DUMP_HIR
                ).any { key ->
                    cfg.load_config_entry(key)?.toBooleanStrictOrNull() == true
                }
            } finally {
                cfg.close_config_file()
            }
        }.getOrDefault(false)
    }

    /**
     * [PERF] Master "Modo Debug" toggle.
     *
     * When [enabled] is true, turns ON all the debug/instrumentation flags so
     * you can investigate guest crashes, JIT issues, MMIO access patterns etc.
     *
     * When [enabled] is false, turns OFF all of them — restoring the lean
     * production config that gives the best FPS. This is the default state
     * for normal gameplay.
     *
     * The flags toggled are:
     *  - break_on_debugbreak
     *  - break_on_unimplemented_instructions
     *  - record_mmio_access_exceptions (A64 backend doesn't use this data — pure overhead)
     *  - emit_mmio_aware_stores_for_recorded_exception_addresses (same)
     *  - emit_inline_mmio_checks (heavy: compare+branch on every load/store)
     *  - store_all_context_values (strips dead context stores otherwise)
     *  - trace_function_coverage / trace_function_data / trace_function_references
     *  - disassemble_functions / trace_functions / dump_translated_hir_functions
     *
     * Returns true if the config was successfully mutated.
     */
    fun setDebugMode(enabled: Boolean): Boolean {
        return mutateConfig { cfg ->
            val value = enabled.toString()
            cfg.save_config_entry(KEY_BREAK_ON_DEBUGBREAK, value)
            cfg.save_config_entry(KEY_BREAK_ON_UNIMPLEMENTED, value)
            cfg.save_config_entry(KEY_RECORD_MMIO_EXCEPTIONS, value)
            cfg.save_config_entry(KEY_EMIT_MMIO_AWARE_STORES, value)
            cfg.save_config_entry(KEY_EMIT_INLINE_MMIO_CHECKS, value)
            cfg.save_config_entry(KEY_STORE_ALL_CONTEXT_VALUES, value)
            cfg.save_config_entry(KEY_TRACE_FUNCTION_COVERAGE, value)
            cfg.save_config_entry(KEY_TRACE_FUNCTION_DATA, value)
            cfg.save_config_entry(KEY_TRACE_FUNCTION_REFERENCES, value)
            cfg.save_config_entry(KEY_DISASSEMBLE_FUNCTIONS, value)
            cfg.save_config_entry(KEY_TRACE_FUNCTIONS, value)
            // HIR file dumps are always off unless explicitly requested
            // (they flood the filesystem with per-function HIR files).
            cfg.save_config_entry(KEY_DUMP_HIR, "false")

            // When enabling debug mode, bump log_level to at least debug (3)
            // so the verbose output actually shows up in xe.log.
            if (enabled) {
                val current = cfg.load_config_entry(KEY_LOG_LEVEL)?.toIntOrNull() ?: 2
                if (current < 3) {
                    cfg.save_config_entry(KEY_LOG_LEVEL, "3")
                }
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
