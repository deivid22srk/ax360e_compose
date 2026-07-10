package aenu.ax360e.ui.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import aenu.ax360e.Emulator

/**
 * Stateless loader for the game list.
 *
 * Game icon extraction mirrors the xenia-canary desktop UI logic:
 *  - For XContent containers (GOD/LIVE/PIRS/CON files), the
 *    XContentContainerHeader is read and the title_thumbnail PNG banner
 *    (176x64) is extracted via the JNI bridge Emulator.meta_info_from_uri.
 *    Falls back to the small 64x64 thumbnail for older packages.
 *  - For plain ISO and ZAR files, there is no embedded icon in the header,
 *    so the GameItem.icon remains null and the UI shows a fallback
 *    SportsEsports icon (matching the xenia-canary behavior for non-container
 *    formats).
 *  - For XEX directories, the XEX file itself has no STFS header so we also
 *    leave the icon null.
 */
object GameListLoader {

    private const val PREF_GAME_DIR = "game_dir"

    fun saveGameDir(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putString(PREF_GAME_DIR, uri.toString()).apply()
        }
    }

    fun loadGameDir(context: Context): Uri? {
        return runCatching {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val uriStr = prefs.getString(PREF_GAME_DIR, null) ?: return null
            val uri = Uri.parse(uriStr)
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            uri
        }.getOrNull()
    }

    private fun isIsoFile(name: String) = name.endsWith(".iso", ignoreCase = true)
    private fun isZarFile(name: String) = name.endsWith(".zar", ignoreCase = true)
    private fun isGodGame(name: String) = name.indexOf('.') == -1

    private fun getDefaultXexFile(dir: DocumentFile): DocumentFile? {
        val files = dir.listFiles()
        if (files.isNullOrEmpty()) return null
        return files.firstOrNull { file ->
            file.isFile && file.name?.equals("default.xex", ignoreCase = true) == true
        }
    }

    /**
     * Attempts to extract metadata (title name + icon PNG) from a XContent
     * container file. Returns null if the file is not a valid container (e.g.
     * plain ISO or raw XEX).
     */
    private fun tryExtractMeta(context: Context, uri: String): Emulator.GameInfo? {
        val emu = Emulator.get ?: return null
        return runCatching { emu.meta_info_from_uri(context, uri) }.getOrNull()
    }

    fun loadGames(context: Context): List<GameItem> {
        val uri = loadGameDir(context) ?: return emptyList()
        val isoDir = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        if (!isoDir.exists()) return emptyList()

        val result = mutableListOf<GameItem>()
        isoDir.listFiles().forEach { file ->
            if (file.isDirectory) {
                // XEX game directory: look for default.xex inside
                val xex = getDefaultXexFile(file) ?: return@forEach
                val fileUri = xex.uri.toString()
                // XEX files are not XContent containers, so meta_info_from_uri
                // will return null - we still try, just in case the file is
                // actually a packaged XEX.
                val meta = tryExtractMeta(context, fileUri)
                result.add(
                    GameItem(
                        uri = fileUri,
                        name = meta?.name ?: file.name ?: "Unknown",
                        icon = meta?.icon
                    )
                )
            } else {
                val name = file.name ?: return@forEach
                val fileUri = file.uri.toString()
                when {
                    isIsoFile(name) || isZarFile(name) -> {
                        // Try to extract metadata. For ISO/ZAR files this will
                        // usually return null (no STFS header) - in that case
                        // fall back to the file name without extension.
                        val meta = tryExtractMeta(context, fileUri)
                        result.add(
                            GameItem(
                                uri = fileUri,
                                name = meta?.name ?: name.substring(0, name.length - 4),
                                icon = meta?.icon
                            )
                        )
                    }
                    isGodGame(name) -> {
                        // GOD game (Games on Demand) - usually a STFS container.
                        // First try the generic extractor, then fall back to
                        // the legacy meta_info_from_god_game for compatibility.
                        val meta = tryExtractMeta(context, fileUri)
                            ?: runCatching {
                                Emulator.get?.meta_info_from_god_game(context, fileUri)
                            }.getOrNull()
                        if (meta != null) {
                            result.add(
                                GameItem(
                                    uri = meta.uri,
                                    name = meta.name ?: name,
                                    icon = meta.icon
                                )
                            )
                        } else {
                            // Not a valid container - still list the file so
                            // the user can attempt to launch it.
                            result.add(
                                GameItem(
                                    uri = fileUri,
                                    name = name,
                                    icon = null
                                )
                            )
                        }
                    }
                }
            }
        }
        return result
    }
}
