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
     * Should be called from EmulatorActivity.onDestroy() or onPause()
     * when the user exits a game.
     *
     * @param gameName the display name of the game that was running
     * @return the saved log file, or null if the active log doesn't exist
     *         or is empty
     */
    fun captureGameLog(context: Context, gameName: String): File? {
        val activeLog = getActiveLogFile()
        if (!activeLog.exists() || activeLog.length() == 0L) {
            return null
        }

        val logsDir = getLogsDir(context)
        val sanitizedName = sanitizeFileName(gameName)
        val timestamp = dateFormatter.format(Date())
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
        val baseName = fileName.removeSuffix(".log")
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
