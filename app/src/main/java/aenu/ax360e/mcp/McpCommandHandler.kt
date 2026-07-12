package aenu.ax360e.mcp

import android.content.Context
import android.content.Intent
import aenu.ax360e.Application
import aenu.ax360e.Emulator
import aenu.ax360e.EmulatorActivity
import aenu.ax360e.ui.model.EmulatorLogRepository
import aenu.ax360e.ui.model.GameListLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dispatches MCP commands received from the bridge to the right app API.
 *
 * Each [handle] call runs on a worker thread, so blocking I/O (file reads,
 * DocumentFile traversal) is fine. The response is a `JSONObject` that will
 * be wrapped in a `response` frame and sent back to the bridge.
 *
 * Throws on any unrecoverable error — the caller will send an `error`
 * frame with the message.
 */
class McpCommandHandler(private val appContext: Context) {

    fun handle(command: String, args: JSONObject): JSONObject {
        return when (command) {
            "ping" -> JSONObject().put("pong", true).put("receivedAt", System.currentTimeMillis())

            "get_device_info" -> getDeviceInfo()

            "get_logs" -> getLogs(args)

            "get_crash_logs" -> getCrashLogs(args)

            "clear_logs" -> clearLogs()

            "list_games" -> listGames()

            "open_game" -> openGame(args)

            "close_game" -> closeGame()

            "force_kill_emulator" -> forceKillEmulator()

            "get_emulator_status" -> getEmulatorStatus()

            "send_key_event" -> sendKeyEvent(args)

            "get_settings" -> getSettings(args)

            else -> throw IllegalArgumentException("Unknown command: $command")
        }
    }

    private fun getDeviceInfo(): JSONObject {
        val info = JSONObject()
        info.put("manufacturer", android.os.Build.MANUFACTURER ?: "unknown")
        info.put("model", android.os.Build.MODEL ?: "unknown")
        info.put("device", android.os.Build.DEVICE ?: "unknown")
        info.put("androidVersion", android.os.Build.VERSION.RELEASE ?: "unknown")
        info.put("sdkLevel", android.os.Build.VERSION.SDK_INT)
        info.put("abi", android.os.Build.SUPPORTED_ABIS?.joinToString(",") ?: "unknown")
        info.put("appId", appContext.packageName)

        // Hardware info via native side (only if native lib is loaded)
        val hardware = JSONObject()
        try {
            if (Emulator.get != null) {
                val raw = Emulator.get.simple_device_info()
                if (!raw.isNullOrEmpty()) {
                    // simple_device_info returns a multi-line human-readable string
                    // (CPU name + features + GPU info). Wrap it as a string field.
                    hardware.put("native", raw)
                }
            }
        } catch (_: Throwable) {
            // Native lib may not be loaded yet — fine.
        }
        info.put("hardware", hardware)

        // App version
        try {
            val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.put("appVersionName", pi.versionName ?: "unknown")
            info.put("appVersionCode", pi.longVersionCode)
        } catch (_: Throwable) {
            info.put("appVersionName", "unknown")
        }

        // Storage root
        info.put("storageRoot", Application.get_app_data_dir().absolutePath)

        return info
    }

    private fun getLogs(args: JSONObject): JSONObject {
        val level = args.optString("level", "all")
        val limit = args.optInt("limit", 500).coerceIn(1, 5000)
        val filter = args.optString("filter", "").trim()

        // The xenia-canary logger writes to {app_data_dir}/ax360e/xe.log
        val activeLog = EmulatorLogRepository.getActiveLogFile()
        if (!activeLog.exists() || activeLog.length() == 0L) {
            return JSONObject()
                .put("lines", JSONArray())
                .put("source", "active")
                .put("sizeBytes", 0L)
                .put("truncated", false)
        }

        // Read last 512 KiB (most recent errors are at the end).
        val maxBytes = 512L * 1024
        val content = EmulatorLogRepository.readLog(activeLog, maxBytes)
        val rawLines = content.lines()

        // Apply level filter (xenia log prefix: '!','w','i','d','C','A','G','K','F')
        val levelPrefixes = when (level) {
            "error" -> setOf('!')
            "warn" -> setOf('w', '!')
            "info" -> setOf('i', 'w', '!')
            "debug" -> setOf('i', 'd', 'w', '!', 'C', 'A', 'G', 'K', 'F')
            else -> null
        }

        var filtered = rawLines
        if (levelPrefixes != null) {
            filtered = filtered.filter { line ->
                line.length >= 2 && line[1] == '>' && line[0] in levelPrefixes
            }
        }
        if (filter.isNotEmpty()) {
            filtered = filtered.filter { it.contains(filter, ignoreCase = true) }
        }

        // Take last `limit` lines (most recent)
        val result = filtered.takeLast(limit)
        val arr = JSONArray()
        result.forEach { arr.put(it) }

        return JSONObject()
            .put("lines", arr)
            .put("source", "active")
            .put("sizeBytes", activeLog.length())
            .put("truncated", filtered.size > limit)
            .put("returnedLines", result.size)
    }

    private fun listGames(): JSONObject {
        val games = GameListLoader.loadGames(appContext)
        val arr = JSONArray()
        games.forEach { g ->
            arr.put(JSONObject()
                .put("uri", g.uri)
                .put("name", g.name)
            )
        }
        return JSONObject().put("games", arr).put("count", games.size)
    }

    private fun openGame(args: JSONObject): JSONObject {
        val uri = args.optString("uri")
        if (uri.isEmpty()) {
            throw IllegalArgumentException("Missing 'uri' argument")
        }
        val intent = Intent("aenu.intent.action.AX360E").apply {
            setPackage(appContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EmulatorActivity.EXTRA_GAME_URI, uri)
        }
        appContext.startActivity(intent)
        return JSONObject()
            .put("ok", true)
            .put("uri", uri)
            .put("message", "Emulator activity launched")
    }

    private fun closeGame(): JSONObject {
        val intent = Intent(appContext, EmulatorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        // We can't directly finish another activity from a service context,
        // but we can launch the main activity which forces the emulator
        // activity (running in :emu process) to be backgrounded. The
        // :emu process will be killed by the system shortly after.
        val mainIntent = Intent(appContext, aenu.ax360e.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        appContext.startActivity(mainIntent)
        return JSONObject().put("ok", true).put("message", "Sent home; emulator activity will be killed")
    }

    private fun getEmulatorStatus(): JSONObject {
        val status = JSONObject()
        val isLoaded = Emulator.get != null
        status.put("nativeLoaded", isLoaded)
        status.put("isRunning", isLoaded && try {
            !Emulator.get.is_paused
        } catch (_: Throwable) { false })
        status.put("isPaused", isLoaded && try {
            Emulator.get.is_paused
        } catch (_: Throwable) { false })

        var fps = 0
        if (isLoaded) {
            try { fps = Emulator.get.get_fps() } catch (_: Throwable) {}
        }
        status.put("fps", fps)

        // Current game is tracked by the service via session-game-name files
        val prefs = appContext.getSharedPreferences(
            "emulator_log_prefs", Context.MODE_PRIVATE
        )
        val currentGame = prefs.getString("last_game_name", null)
        status.put("currentGame", currentGame ?: JSONObject.NULL)

        val sessionStart = prefs.getLong("last_session_start", 0L)
        status.put("sessionStartMs", sessionStart)
        if (sessionStart > 0) {
            status.put("sessionDurationMs", System.currentTimeMillis() - sessionStart)
        }

        return status
    }

    private fun sendKeyEvent(args: JSONObject): JSONObject {
        val keyCode = args.optInt("keyCode", -1)
        val pressed = args.optBoolean("pressed")
        val value = args.optInt("value", 0)
        if (keyCode < 0) throw IllegalArgumentException("Missing or invalid 'keyCode'")
        if (Emulator.get == null) throw IllegalStateException("Native library not loaded")

        Emulator.get.key_event(keyCode, pressed, value)
        return JSONObject()
            .put("ok", true)
            .put("keyCode", keyCode)
            .put("pressed", pressed)
            .put("value", value)
    }

    private fun getSettings(args: JSONObject): JSONObject {
        val scope = args.optString("scope", "global")
        val configPath = when (scope) {
            "game" -> {
                // Per-game config path is stored by the app when launching
                // a game. If no game is running, return an error.
                val prefs = appContext.getSharedPreferences(
                    "emulator_log_prefs", Context.MODE_PRIVATE
                )
                val game = prefs.getString("last_game_name", null)
                    ?: throw IllegalStateException("No game session active for per-game config")
                "${Application.get_app_data_dir().absolutePath}/ax360e/config/${game}.config.toml"
            }
            else -> Application.get_global_config_file().absolutePath
        }

        val file = java.io.File(configPath)
        if (!file.exists()) {
            return JSONObject()
                .put("scope", scope)
                .put("path", configPath)
                .put("exists", false)
                .put("content", "")
        }
        val content = file.readText()
        return JSONObject()
            .put("scope", scope)
            .put("path", configPath)
            .put("exists", true)
            .put("content", content)
    }

    /**
     * List and read crash logs captured by EmulatorLogRepository.
     *
     * Crash logs are saved when a game session ends abnormally. The
     * repository keeps up to 50 logs; this command can either list them
     * (default) or read a specific file by name.
     */
    private fun getCrashLogs(args: JSONObject): JSONObject {
        val action = args.optString("action", "list")  // "list" or "read"
        val logs = EmulatorLogRepository.listLogs(appContext)

        if (action == "list") {
            val arr = JSONArray()
            logs.forEach { entry ->
                arr.put(JSONObject()
                    .put("fileName", entry.file.name)
                    .put("gameName", entry.gameName)
                    .put("timestamp", entry.timestamp)
                    .put("formattedTimestamp", entry.formattedTimestamp)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("formattedSize", entry.formattedSize)
                    .put("isCrash", entry.file.name.contains("_crash_"))
                )
            }
            return JSONObject()
                .put("action", "list")
                .put("logs", arr)
                .put("count", logs.size)
                .put("logsDir", EmulatorLogRepository.getLogsDir(appContext).absolutePath)
        }

        if (action == "read") {
            val fileName = args.optString("fileName")
            if (fileName.isEmpty()) throw IllegalArgumentException("Missing 'fileName' for read action")
            val logsDir = EmulatorLogRepository.getLogsDir(appContext)
            val target = java.io.File(logsDir, fileName)
            if (!target.exists()) {
                return JSONObject().put("ok", false).put("error", "File not found: $fileName")
            }
            // Read up to 1 MB
            val content = EmulatorLogRepository.readLog(target, maxBytes = 1L * 1024 * 1024)
            // Return last N lines if too big
            val maxLines = args.optInt("maxLines", 500)
            val lines = content.lines()
            val returned = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            return JSONObject()
                .put("ok", true)
                .put("fileName", fileName)
                .put("sizeBytes", target.length())
                .put("totalLines", lines.size)
                .put("returnedLines", returned.size)
                .put("truncated", lines.size > maxLines)
                .put("lines", JSONArray().apply { returned.forEach { put(it) } })
        }

        if (action == "read_active") {
            // Read the current active xe.log (may contain incomplete data if
            // the process crashed without flushing)
            val active = EmulatorLogRepository.getActiveLogFile()
            if (!active.exists() || active.length() == 0L) {
                return JSONObject().put("ok", false).put("error", "No active log file")
            }
            val content = EmulatorLogRepository.readLog(active, maxBytes = 2L * 1024 * 1024)
            val maxLines = args.optInt("maxLines", 1000)
            val lines = content.lines()
            val returned = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            return JSONObject()
                .put("ok", true)
                .put("fileName", active.name)
                .put("path", active.absolutePath)
                .put("sizeBytes", active.length())
                .put("lastModified", active.lastModified())
                .put("totalLines", lines.size)
                .put("returnedLines", returned.size)
                .put("truncated", lines.size > maxLines)
                .put("lines", JSONArray().apply { returned.forEach { put(it) } })
        }

        throw IllegalArgumentException("Unknown action: $action (expected: list, read, read_active)")
    }

    /**
     * Clear all saved crash/game logs. Useful for starting fresh before
     * a debugging session.
     */
    private fun clearLogs(): JSONObject {
        val count = EmulatorLogRepository.clearAllLogs(appContext)
        return JSONObject()
            .put("ok", true)
            .put("deletedCount", count)
    }

    /**
     * Force-kill the :emu process. The emulator activity runs in a separate
     * process (android:process=":emu" in the manifest). When a game hangs
     * (e.g. stuck on a loading screen with a deadlocked guest thread),
     * the only way to recover is to kill that process. We can't directly
     * kill another process from the main process without permission, but
     * we can launch the main activity with CLEAR_TOP which forces the
     * emulator activity to be backgrounded; Android will kill the :emu
     * process shortly after due to its foreground service being gone.
     *
     * For a more immediate kill, we use ActivityManager.killBackgroundProcesses
     * which works for processes without a visible activity.
     */
    private fun forceKillEmulator(): JSONObject {
        val results = JSONObject()

        // Method 1: Bring main activity to front, clearing the back stack.
        // This backgrounds the emulator activity, causing its process to be
        // killed by the system.
        try {
            val mainIntent = Intent(appContext, aenu.ax360e.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            appContext.startActivity(mainIntent)
            results.put("broughtMainToForeground", true)
        } catch (e: Exception) {
            results.put("broughtMainToForeground", false)
            results.put("bringToFrontError", e.message ?: "unknown")
        }

        // Method 2: Try to kill background processes (works if :emu is no
        // longer the foreground activity).
        try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(appContext.packageName)
            results.put("killBackgroundProcesses", true)
        } catch (e: Exception) {
            results.put("killBackgroundProcesses", false)
            results.put("killError", e.message ?: "unknown")
        }

        // Clear the session info so the next open_game starts fresh
        try {
            EmulatorLogRepository.clearSession(appContext)
            results.put("clearedSession", true)
        } catch (_: Exception) {
            results.put("clearedSession", false)
        }

        results.put("ok", true)
        results.put("message", "Force-kill requested. The :emu process should die within a few seconds.")
        return results
    }
}
