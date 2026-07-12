package aenu.ax360e.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client that connects out to the Render-hosted MCP bridge.
 *
 * The phone initiates the connection (so we don't need NAT traversal).
 * On connect, sends a `register` frame with device info. Then waits for
 * `command` frames from the bridge, dispatches them to [McpCommandHandler],
 * and sends back `response` / `error` frames.
 *
 * Reconnection policy: exponential backoff starting at 2s, capped at 60s,
 * reset on successful register. The connection is restarted whenever the
 * app process is alive and the user has toggled the service on.
 */
class McpBridgeClient(
    private val context: Context,
    private val handler: McpCommandHandler,
) {

    companion object {
        private const val TAG = "McpBridgeClient"
        private const val PREF_BRIDGE_URL = "mcp_bridge_url"
        private const val PREF_BRIDGE_API_KEY = "mcp_bridge_api_key"
        private const val PREF_ENABLED = "mcp_bridge_enabled"
        private const val DEFAULT_BRIDGE_URL = "https://ax360e-mcp-bridge.onrender.com"

        /**
         * Read the user-configured bridge URL. Falls back to the default
         * Render URL if the user hasn't customized it.
         */
        fun getBridgeUrl(context: Context): String {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getString(PREF_BRIDGE_URL, DEFAULT_BRIDGE_URL)
                ?.trim()
                ?.removeSuffix("/")
                ?: DEFAULT_BRIDGE_URL
        }

        fun setBridgeUrl(context: Context, url: String) {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_BRIDGE_URL, url.trim().removeSuffix("/"))
                .apply()
        }

        fun getBridgeApiKey(context: Context): String {
            return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_BRIDGE_API_KEY, "") ?: ""
        }

        fun setBridgeApiKey(context: Context, key: String) {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_BRIDGE_API_KEY, key.trim())
                .apply()
        }

        fun isEnabled(context: Context): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLED, false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(PREF_ENABLED, enabled)
                .apply()
        }
    }

    private val running = AtomicBoolean(false)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // keep WS open forever
        .build()

    @Volatile private var activeSocket: WebSocket? = null
    @Volatile private var reconnectDelayMs = 2000L
    private val pendingAcks = ConcurrentHashMap<String, (Boolean) -> Unit>()

    /** Start the connect + reconnect loop. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.i(TAG, "MCP bridge client starting")
        connectLoop()
    }

    /** Stop and prevent reconnection until [start] is called again. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "MCP bridge client stopping")
        activeSocket?.close(1000, "Client stopping")
        activeSocket = null
    }

    private fun connectLoop() {
        if (!running.get()) return
        val url = getBridgeUrl(context)
        val apiKey = getBridgeApiKey(context)
        if (apiKey.isBlank()) {
            Log.w(TAG, "Bridge API key is empty — not connecting. Set it in Settings.")
            scheduleReconnect()
            return
        }
        val wsUrl = url.replaceFirst("^http://".toRegex(), "ws://")
            .replaceFirst("^https://".toRegex(), "wss://") +
            "/ws/device?apiKey=" + apiKey

        val request = Request.Builder().url(wsUrl).build()
        Log.i(TAG, "Connecting to $wsUrl (apiKey hidden)")

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket open")
                activeSocket = webSocket
                sendRegister(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                activeSocket = null
                if (running.get()) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                activeSocket = null
                if (running.get()) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        val delay = reconnectDelayMs
        reconnectDelayMs = minOf(reconnectDelayMs * 2, 60_000L)
        Thread {
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (running.get()) connectLoop()
        }.apply {
            name = "McpBridgeReconnect"
            isDaemon = true
            start()
        }
    }

    private fun sendRegister(ws: WebSocket) {
        val reg = JSONObject().apply {
            put("type", "register")
            put("deviceId", getDeviceId())
            put("deviceName", android.os.Build.MODEL ?: "unknown")
            put("appVersion", getAppVersion())
            // currentGame is tracked by McpBridgeService and pushed via
            // status_update frames; sending null here is fine.
            put("currentGame", JSONObject.NULL)
        }
        ws.send(reg.toString())
    }

    private fun handleMessage(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON from bridge: $text")
            return
        }
        when (msg.optString("type")) {
            "registered" -> {
                Log.i(TAG, "Registered as device ${msg.optString("deviceId")}")
                reconnectDelayMs = 2000L // reset backoff on success
            }
            "command" -> dispatchCommand(msg)
            "error" -> Log.w(TAG, "Bridge error: ${msg.optString("error")}")
            else -> Log.v(TAG, "Unknown message: $text")
        }
    }

    private fun dispatchCommand(msg: JSONObject) {
        val commandId = msg.optString("commandId")
        val command = msg.optString("command")
        val args = msg.optJSONObject("args") ?: JSONObject()
        val ws = activeSocket
        if (ws == null) {
            sendError(commandId, "No active WebSocket")
            return
        }

        // Run on a worker thread so we never block the WS reader.
        Thread {
            val result: JSONObject
            try {
                result = handler.handle(command, args)
            } catch (e: Throwable) {
                Log.e(TAG, "Command '$command' failed", e)
                sendError(commandId, e.message ?: e.javaClass.simpleName)
                return@Thread
            }
            val response = JSONObject().apply {
                put("type", "response")
                put("commandId", commandId)
                put("result", result)
            }
            try {
                ws.send(response.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send response for $command", e)
            }
        }.apply {
            name = "McpCmd-$command"
            isDaemon = true
            start()
        }
    }

    private fun sendError(commandId: String, message: String) {
        val ws = activeSocket ?: return
        val response = JSONObject().apply {
            put("type", "response")
            put("commandId", commandId)
            put("error", message)
        }
        try {
            ws.send(response.toString())
        } catch (_: Exception) {}
    }

    /**
     * Push a log line to the bridge for buffering. The bridge keeps a ring
     * buffer per device so the AI can fetch recent logs without needing a
     * live stream.
     */
    fun pushLog(level: String, line: String) {
        val ws = activeSocket ?: return
        val frame = JSONObject().apply {
            put("type", "log")
            put("level", level)
            put("line", line)
        }
        try {
            ws.send(frame.toString())
        } catch (_: Exception) {}
    }

    /** Push a status update when the running game changes. */
    fun pushStatusUpdate(currentGame: String?) {
        val ws = activeSocket ?: return
        val frame = JSONObject().apply {
            put("type", "status_update")
            put("currentGame", currentGame ?: JSONObject.NULL)
        }
        try {
            ws.send(frame.toString())
        } catch (_: Exception) {}
    }

    private fun getDeviceId(): String {
        val prefs: SharedPreferences =
            context.getSharedPreferences("mcp_device_id", Context.MODE_PRIVATE)
        var id = prefs.getString("id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("id", id).apply()
        }
        return id
    }

    private fun getAppVersion(): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
