package aenu.ax360e.ui.model

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for downloaded cover art.
 *
 * Stored as individual files under `filesDir/covers/<sha256(name)>.bin`
 * — we use a hash so weird characters in game names don't break the filesystem.
 *
 * Sentinel file `<sha>.miss` records "we already tried and TGDB has no cover";
 * this prevents re-querying the network for games that are known to have no art.
 */
object CoverArtCache {

    private const val TAG = "CoverArtCache"
    private const val DIR_NAME = "covers"
    private const val EXT_BIN = ".bin"
    private const val EXT_MISS = ".miss"

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun key(name: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val raw = md.digest(name.trim().lowercase().toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun readBytes(context: Context, gameName: String): ByteArray? {
        return runCatching {
            val f = File(dir(context), key(gameName) + EXT_BIN)
            if (!f.exists() || f.length() == 0L) null else f.readBytes()
        }.onFailure { Log.w(TAG, "readBytes failed: ${it.message}") }.getOrNull()
    }

    fun isKnownMissing(context: Context, gameName: String): Boolean {
        return runCatching {
            File(dir(context), key(gameName) + EXT_MISS).exists()
        }.getOrDefault(false)
    }

    fun writeBytes(context: Context, gameName: String, bytes: ByteArray) {
        runCatching {
            val f = File(dir(context), key(gameName) + EXT_BIN)
            f.writeBytes(bytes)
            // Clear any prior "missing" marker once we actually have art.
            File(dir(context), key(gameName) + EXT_MISS).takeIf { it.exists() }?.delete()
        }.onFailure { Log.w(TAG, "writeBytes failed: ${it.message}") }
    }

    fun markMissing(context: Context, gameName: String) {
        runCatching {
            File(dir(context), key(gameName) + EXT_MISS).apply {
                if (!exists()) createNewFile()
            }
        }.onFailure { Log.w(TAG, "markMissing failed: ${it.message}") }
    }

    /**
     * Clears every cached cover AND every "missing" marker.
     * Use when the user changes the API key — previously-failed lookups
     * should be retried against the new key.
     */
    fun clearAll(context: Context) {
        runCatching {
            dir(context).listFiles()?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "clearAll failed: ${it.message}") }
    }
}
