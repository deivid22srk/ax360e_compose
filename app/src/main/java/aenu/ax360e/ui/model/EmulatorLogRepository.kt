package aenu.ax360e.ui.model

import android.content.Context
import android.net.Uri
import aenu.ax360e.Application
import java.io.File
import java.io.FilenameFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repository for per-game emulator logs.
 *
 * The xenia-canary logging system (src/xenia/base/logging.cc) writes all
 * XELOG* output to a file specified by the `--log_file` launch argument.
 * In ax360e, EmulatorActivity passes `--log_file={app_data_dir}/ax360e/xe.log`
 * so every game session overwrites the same file.
 *
 * To preserve logs per-game, [captureGameLog] should be called when the
 * EmulatorActivity is about to be destroyed (i.e., when the user exits the
 * game). It copies `xe.log` to `{logs_dir}/{game_name}_{timestamp}.log`,
 * creating a persistent record that can be viewed later via the
 * EmulatorLogScreen.
 *
 * Log line format (from logging.cc:317):
 *   `{prefix_char}> {thread_id_hex} {message}\n`
 * where prefix_char is one of:
 *   '!' = Error, 'w' = Warning, 'i' = Info, 'd' = Debug,
 *   'C' = CPU, 'A' = APU, 'G' = GPU, 'K' = Kernel, 'F' = FileSystem
 */
object EmulatorLogRepository {

    private const val LOGS_DIR = "game_logs"
    private const val ACTIVE_LOG_NAME = "xe.log"
    private const val MAX_LOGS_KEPT = 50
    private const val PREFS_NAME = "emulator_log_prefs"
    private const val PREF_LAST_GAME_NAME = "last_game_name"
    private const val PREF_LAST_SESSION_START = "last_session_start"
    private const val CRASH_LOG_AGE_MS = 30L * 60 * 1000  // 30 minutes

    private val dateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Directory where per-game logs are stored. */
    fun getLogsDir(context: Context): File {
        val dir = File(Application.get_app_data_dir(), LOGS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** The active log file that xenia-canary is currently writing to. */
    fun getActiveLogFile(): File {
        return File(Application.get_app_data_dir(), ACTIVE_LOG_NAME)
    }

    /**
     * Captures the current `xe.log` and saves it as a per-game log file.
     *
     * If [sessionTimestamp] is provided (non-zero), the file name uses that
     * timestamp instead of the current time. This allows periodic captures
     * during the same session to OVERWRITE the same file rather than creating
     * a new file each time — ensuring one log file per session, continuously
     * updated, so that even if the process crashes, the most recent periodic
     * capture has most of the log data.
     *
     * @param context Android context
     * @param gameName the display name of the game that was running
     * @param sessionTimestamp the session start timestamp (ms since epoch),
     *        or 0 to use the current time (one-shot capture)
     * @return the saved log file, or null if the active log doesn't exist
     *         or is empty
     */
    fun captureGameLog(
        context: Context,
        gameName: String,
        sessionTimestamp: Long = 0L
    ): File? {
        val activeLog = getActiveLogFile()
        if (!activeLog.exists() || activeLog.length() == 0L) {
            return null
        }

        val logsDir = getLogsDir(context)
        val sanitizedName = sanitizeFileName(gameName)
        val ts = if (sessionTimestamp > 0) sessionTimestamp else System.currentTimeMillis()
        val timestamp = dateFormatter.format(Date(ts))
        val destFile = File(logsDir, "${sanitizedName}_${timestamp}.log")

        try {
            activeLog.copyTo(destFile, overwrite = true)
            pruneOldLogs(logsDir)
            return destFile
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * [CRASH RECOVERY] Captures a leftover `xe.log` from a previous session
     * that ended abnormally (crash, force-kill, OOM kill).
     *
     * This should be called on EmulatorActivity.onCreate BEFORE booting a new
     * game. It checks:
     *   1. Does `xe.log` exist and have content?
     *   2. Was it modified recently (within [CRASH_LOG_AGE_MS])?
     *   3. Is there a stored game name from the last session?
     *
     * If all checks pass, it saves the log as
     * `{gameName}_crash_{timestamp}.log` so the user can see what happened
     * before the crash.
     *
     * @return the saved crash log file, or null if no crash log was captured
     */
    fun captureCrashLogIfPending(context: Context): File? {
        val activeLog = getActiveLogFile()
        if (!activeLog.exists() || activeLog.length() == 0L) {
            return null
        }

        val logModifiedAge = System.currentTimeMillis() - activeLog.lastModified()
        if (logModifiedAge > CRASH_LOG_AGE_MS) {
            // The log is too old — probably from a session long ago, not a crash
            return null
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gameName = prefs.getString(PREF_LAST_GAME_NAME, null) ?: return null
        val sessionStart = prefs.getLong(PREF_LAST_SESSION_START, 0L)

        // If a game log was already captured for this session (e.g., the user
        // exited normally), don't create a crash log duplicate.
        if (sessionStart > 0) {
            val logsDir = getLogsDir(context)
            val sanitizedName = sanitizeFileName(gameName)
            val sessionTs = dateFormatter.format(Date(sessionStart))
            val existingSessionLog = File(logsDir, "${sanitizedName}_${sessionTs}.log")
            if (existingSessionLog.exists() && existingSessionLog.length() > 0L) {
                // Already captured — the session ended normally. Clear the
                // pending state so we don't check again.
                prefs.edit()
                    .remove(PREF_LAST_GAME_NAME)
                    .remove(PREF_LAST_SESSION_START)
                    .apply()
                return null
            }
        }

        // Capture as crash log
        val logsDir = getLogsDir(context)
        val sanitizedName = sanitizeFileName(gameName)
        val timestamp = dateFormatter.format(Date())
        val destFile = File(logsDir, "${sanitizedName}_crash_${timestamp}.log")

        try {
            activeLog.copyTo(destFile, overwrite = true)
            pruneOldLogs(logsDir)
            // Clear the pending state
            prefs.edit()
                .remove(PREF_LAST_GAME_NAME)
                .remove(PREF_LAST_SESSION_START)
                .apply()
            return destFile
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * [CRASH RECOVERY] Stores the game name and session start time so that if
     * the process crashes, the next launch can capture the leftover xe.log
     * as a crash log.
     */
    fun saveSessionStart(context: Context, gameName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_LAST_GAME_NAME, gameName)
            .putLong(PREF_LAST_SESSION_START, System.currentTimeMillis())
            .apply()
    }

    /**
     * [CRASH RECOVERY] Clears the stored session info. Called when a game
     * session ends normally (onDestroy) so that the next launch doesn't
     * mistakenly capture a crash log.
     */
    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(PREF_LAST_GAME_NAME)
            .remove(PREF_LAST_SESSION_START)
            .apply()
    }

    /**
     * Lists all saved per-game logs, newest first.
     */
    fun listLogs(context: Context): List<GameLogEntry> {
        val logsDir = getLogsDir(context)
        val files = logsDir.listFiles(FilenameFilter { _, name ->
            name.endsWith(".log")
        }) ?: return emptyList()

        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val (gameName, timestamp) = parseLogFileName(file.name)
                GameLogEntry(
                    file = file,
                    gameName = gameName,
                    timestamp = timestamp,
                    sizeBytes = file.length()
                )
            }
    }

    /**
     * Reads the contents of a log file as a string.
     * Truncates to the last [maxBytes] bytes if the file is too large
     * (to avoid OOM on large logs).
     */
    fun readLog(file: File, maxBytes: Long = 512 * 1024): String {
        if (!file.exists()) return ""
        val length = file.length()
        return try {
            if (length <= maxBytes) {
                file.readText()
            } else {
                // Read only the last maxBytes - most recent errors are at the end
                val skipBytes = length - maxBytes
                file.inputStream().use { input ->
                    input.skip(skipBytes)
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }

    /**
     * Deletes a saved log file.
     */
    fun deleteLog(file: File): Boolean {
        return file.delete()
    }

    /**
     * Deletes all saved logs.
     */
    fun clearAllLogs(context: Context): Int {
        val logsDir = getLogsDir(context)
        val files = logsDir.listFiles() ?: return 0
        var count = 0
        for (f in files) {
            if (f.delete()) count++
        }
        return count
    }

    /**
     * Exports a log file to a user-chosen destination URI (obtained via
     * ActivityResultContracts.CreateDocument). This writes the full log
     * content to the URI using ContentResolver.openOutputStream().
     *
     * @param context Android context for ContentResolver access
     * @param sourceFile the saved log file to export
     * @param destUri the destination URI returned by the SAF picker
     * @return true if the export succeeded, false otherwise
     */
    fun exportLogToUri(context: Context, sourceFile: File, destUri: Uri): Boolean {
        return try {
            val content = readLog(sourceFile, maxBytes = Long.MAX_VALUE)
            context.contentResolver.openOutputStream(destUri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Keeps only the [MAX_LOGS_KEPT] most recent logs, deleting older ones.
     */
    private fun pruneOldLogs(logsDir: File) {
        val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size > MAX_LOGS_KEPT) {
            files.drop(MAX_LOGS_KEPT).forEach { it.delete() }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .take(64)
            .ifEmpty { "unknown" }
    }

    private fun parseLogFileName(fileName: String): Pair<String, Long> {
        // Format: {gameName}_{yyyyMMdd_HHmmss}.log
        //   OR:   {gameName}_crash_{yyyyMMdd_HHmmss}.log
        val baseName = fileName.removeSuffix(".log")

        // Check for crash log format: {gameName}_crash_{timestamp}
        if (baseName.contains("_crash_")) {
            val crashIdx = baseName.indexOf("_crash_")
            val gameName = baseName.substring(0, crashIdx)
            val timestampStr = baseName.substring(crashIdx + 7)  // after "_crash_"
            return try {
                val date = dateFormatter.parse(timestampStr)
                Pair(gameName, date?.time ?: 0L)
            } catch (e: Exception) {
                Pair(baseName, 0L)
            }
        }

        // Normal format: {gameName}_{yyyyMMdd_HHmmss}
        val lastUnderscore = baseName.lastIndexOf('_')
        if (lastUnderscore < 0) return Pair(baseName, 0L)
        val timestampStr = baseName.substring(lastUnderscore + 1)
        return try {
            val date = dateFormatter.parse(timestampStr)
            val gameName = baseName.substring(0, lastUnderscore)
            Pair(gameName, date?.time ?: 0L)
        } catch (e: Exception) {
            Pair(baseName, 0L)
        }
    }
}

/**
 * Represents a saved per-game log file.
 */
data class GameLogEntry(
    val file: File,
    val gameName: String,
    val timestamp: Long,
    val sizeBytes: Long
) {
    val formattedTimestamp: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(timestamp))

    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            else -> String.format(Locale.US, "%.1fMB", sizeBytes / 1024.0 / 1024.0)
        }
}
