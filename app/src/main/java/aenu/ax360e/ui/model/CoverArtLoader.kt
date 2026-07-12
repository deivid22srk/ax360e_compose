package aenu.ax360e.ui.model

import android.content.Context
import android.util.Log
import androidx.collection.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates cover-art loading with three layers:
 *
 *   1. In-memory LRU ([memoryCache]) — survives config changes within a process
 *   2. Disk cache ([CoverArtCache]) — survives process restarts
 *   3. Network fetch ([CoverArtRepository]) — slowest, hit last
 *
 * Concurrency: per-game mutex prevents the same cover from being fetched twice
 * when the LazyColumn re-issues the same key during scroll.
 *
 * The TGDB API key is read from SharedPreferences on every call so the user
 * can change it at runtime without restarting the app.
 */
object CoverArtLoader {

    private const val TAG = "CoverArtLoader"
    private const val PREFS_NAME = "cover_art_prefs"
    private const val PREF_API_KEY = "tgdb_api_key"

    private const val MEMORY_CACHE_SIZE = 64 // entries; each entry is a small ByteArray

    private val memoryCache = LruCache<String, ByteArray?>(MEMORY_CACHE_SIZE)
    private val inflightLocks = HashMap<String, Mutex>()
    private val inflightLocksLock = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Loads cover bytes for [gameName]. Returns null if no cover is available
     * (network failure, API key missing, game not found on TGDB).
     *
     * The result is cached: subsequent calls for the same name are O(1).
     * A "missing" sentinel is persisted to disk so we don't hammer TGDB on
     * every refresh for games that genuinely have no art.
     */
    suspend fun load(context: Context, gameName: String): ByteArray? {
        if (gameName.isBlank()) return null

        // 1. Memory
        memoryCache.get(gameName)?.let { return it }

        // 2. Disk
        val appCtx = context.applicationContext
        CoverArtCache.readBytes(appCtx, gameName)?.let {
            memoryCache.put(gameName, it)
            return it
        }

        // If we already know TGDB doesn't have this cover, bail out fast
        // (until the user clears the cache or changes the API key).
        if (CoverArtCache.isKnownMissing(appCtx, gameName)) {
            memoryCache.put(gameName, null)
            return null
        }

        // 3. Network — guarded by a per-game mutex so concurrent callers
        // for the same game share the same fetch.
        val mutex = inflightLocksLock.withLock {
            inflightLocks.getOrPut(gameName) { Mutex() }
        }

        val result = mutex.withLock {
            // Re-check memory after acquiring the lock — another caller
            // may have just finished fetching.
            memoryCache.get(gameName)?.let { return@withLock it }

            val apiKey = readApiKey(appCtx)
            val bytes = CoverArtRepository.fetchCoverBytes(gameName, apiKey)
            if (bytes != null) {
                CoverArtCache.writeBytes(appCtx, gameName, bytes)
            } else {
                CoverArtCache.markMissing(appCtx, gameName)
            }
            memoryCache.put(gameName, bytes)
            bytes
        }

        // Cleanup the mutex entry once the lock is released — prevents the
        // map from growing without bound as the user scrolls.
        inflightLocksLock.withLock {
            // Only remove if still owned by us AND not held by anyone.
            // (Mutex doesn't expose isLocked, so we just remove — worst case
            // a concurrent caller creates a new mutex and re-fetches once.)
            inflightLocks.remove(gameName)
        }

        return result
    }

    /**
     * Asynchronously refresh the cover for [gameName]. Results are pushed into
     * the cache; callers re-collecting [load] on the next composition will see
     * the new value. Useful when the user has just entered an API key and we
     * want to retroactively fetch covers for the visible list.
     */
    fun refreshAsync(context: Context, gameName: String): Job {
        val appCtx = context.applicationContext
        return scope.launch {
            // Force network re-fetch: clear memory + disk caches first.
            // The .miss sentinel is cleared by CoverArtCache.clearAll but
            // we don't want to wipe the *entire* disk cache for one refresh,
            // so just remove this game's entries individually.
            memoryCache.remove(gameName)
            runCatching {
                val dir = java.io.File(appCtx.filesDir, "covers")
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val k = md.digest(gameName.trim().lowercase().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                java.io.File(dir, "$k.bin").takeIf { it.exists() }?.delete()
                java.io.File(dir, "$k.miss").takeIf { it.exists() }?.delete()
            }
            load(appCtx, gameName)
        }
    }

    fun clearAllCaches(context: Context) {
        val appCtx = context.applicationContext
        memoryCache.evictAll()
        CoverArtCache.clearAll(appCtx)
    }

    fun saveApiKey(context: Context, key: String) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_API_KEY, key.trim())
            .apply()
        // API key change invalidates every "missing" marker we may have stored
        // under the previous key — clear them so lookups are retried.
        CoverArtCache.clearAll(appCtx)
        memoryCache.evictAll()
    }

    fun readApiKey(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_API_KEY, "") ?: ""
    }

    fun hasApiKey(context: Context): Boolean = readApiKey(context).isNotBlank()
}
