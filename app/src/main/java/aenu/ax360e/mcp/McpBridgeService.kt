package aenu.ax360e.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import aenu.ax360e.MainActivity
import aenu.ax360e.R
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that hosts [McpBridgeClient] while the user has
 * enabled AI Remote Control.
 *
 * The service runs in the main app process (not the :emu process) so it
 * survives emulator activity recreation and can keep streaming logs even
 * when a game is being played. The xenia-canary logger writes to
 * `xe.log` in the app data dir; we tail that file from a background
 * thread and push new lines to the bridge.
 *
 * Lifecycle:
 *  - User toggles "AI Remote Control" ON in Settings → [startForegroundService]
 *  - User toggles OFF → [stopService]
 *  - Service creates notification + starts [McpBridgeClient] + starts log tailer
 *  - Service is sticky: if the system kills it, it restarts automatically
 *    and reconnects to the bridge.
 */
class McpBridgeService : Service() {

    companion object {
        private const val TAG = "McpBridgeService"
        private const val CHANNEL_ID = "mcp_bridge_service"
        private const val NOTIFICATION_ID = 0xA001
        private const val LOG_TAIL_INTERVAL_MS = 1000L

        /**
         * Convenience for starting/stopping the service from the settings UI.
         * If [enabled] is true and the API key is configured, the service
         * starts; otherwise it stops.
         */
        fun applyEnabledState(context: Context, enabled: Boolean) {
            val intent = Intent(context, McpBridgeService::class.java)
            if (enabled) {
                if (McpBridgeClient.getBridgeApiKey(context).isBlank()) {
                    Log.w(TAG, "Cannot start MCP bridge: API key is empty")
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }
    }

    private lateinit var client: McpBridgeClient
    private lateinit var handler: McpCommandHandler

    private val tailerRunning = AtomicBoolean(false)
    private var tailerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        handler = McpCommandHandler(applicationContext)
        client = McpBridgeClient(applicationContext, handler)
        startForeground(NOTIFICATION_ID, buildNotification("ax360e AI bridge running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Verify the user hasn't toggled us off in the meantime.
        if (!McpBridgeClient.isEnabled(applicationContext)) {
            Log.i(TAG, "Service started but user has disabled the bridge — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        client.start()
        startLogTail()
        return START_STICKY  // restart if killed
    }

    override fun onDestroy() {
        stopLogTail()
        client.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Log tailer
    // -------------------------------------------------------------------------

    /**
     * Tail the xenia-canary log file (`xe.log`) in the app data dir and
     * push every new line to the bridge. We don't use FileObserver because
     * it has reliability issues on external storage; a simple poll-and-read
     * loop with BufferedReader.readLine() is more robust.
     */
    private fun startLogTail() {
        if (!tailerRunning.compareAndSet(false, true)) return
        tailerThread = Thread {
            var reader: BufferedReader? = null
            var file: File? = null
            var lastSize = 0L

            while (tailerRunning.get()) {
                try {
                    val current = aenu.ax360e.ui.model.EmulatorLogRepository.getActiveLogFile()
                    if (current != file) {
                        // File was rotated / recreated — reopen
                        try { reader?.close() } catch (_: Exception) {}
                        reader = null
                        file = current
                        lastSize = 0L
                    }
                    if (file != null && file.exists()) {
                        val currentSize = file.length()
                        if (currentSize < lastSize) {
                            // Log was truncated/rotated — reopen from start
                            try { reader?.close() } catch (_: Exception) {}
                            reader = null
                            lastSize = 0L
                        }
                        if (reader == null) {
                            try {
                                reader = BufferedReader(FileReader(file))
                                // Skip the bytes we've already seen
                                var toSkip = lastSize
                                while (toSkip > 0) {
                                    val skipped = reader.skip(toSkip)
                                    if (skipped <= 0) break
                                    toSkip -= skipped
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to open log file: ${e.message}")
                                reader = null
                            }
                        }
                        if (reader != null) {
                            var line = reader.readLine()
                            while (line != null && tailerRunning.get()) {
                                val level = logLevelOf(line)
                                client.pushLog(level, line)
                                lastSize += (line.length + 1).toLong()
                                line = reader.readLine()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Log tail error: ${e.message}")
                }

                try {
                    Thread.sleep(LOG_TAIL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            try { reader?.close() } catch (_: Exception) {}
        }.apply {
            name = "McpLogTail"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun stopLogTail() {
        tailerRunning.set(false)
        tailerThread?.interrupt()
        tailerThread = null
    }

    /** Map xenia-canary log line prefix to a generic level name. */
    private fun logLevelOf(line: String): String {
        if (line.length < 2 || line[1] != '>') return "info"
        return when (line[0]) {
            '!' -> "error"
            'w' -> "warn"
            'i' -> "info"
            'd' -> "debug"
            'C', 'A', 'G', 'K', 'F' -> "info"  // subsystem markers
            else -> "info"
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Remote Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for the AI bridge service."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ax360e AI Bridge")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }
}
