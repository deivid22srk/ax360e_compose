package aenu.ax360e.ui.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import aenu.ax360e.Emulator

/**
 * Stateless loader for the game list. Extracted from the old GameMetaInfoAdapter.
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

    fun loadGames(context: Context): List<GameItem> {
        val uri = loadGameDir(context) ?: return emptyList()
        val isoDir = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        if (!isoDir.exists()) return emptyList()

        val result = mutableListOf<GameItem>()
        isoDir.listFiles().forEach { file ->
            if (file.isDirectory) {
                val xex = getDefaultXexFile(file) ?: return@forEach
                result.add(
                    GameItem(
                        uri = xex.uri.toString(),
                        name = file.name ?: "Unknown",
                        icon = null
                    )
                )
            } else {
                val name = file.name ?: return@forEach
                when {
                    isIsoFile(name) || isZarFile(name) -> {
                        result.add(
                            GameItem(
                                uri = file.uri.toString(),
                                name = name.substring(0, name.length - 4),
                                icon = null
                            )
                        )
                    }
                    isGodGame(name) -> {
                        runCatching {
                            val meta = Emulator.get?.meta_info_from_god_game(context, file.uri.toString())
                            if (meta != null) {
                                result.add(
                                    GameItem(
                                        uri = meta.uri,
                                        name = meta.name ?: name,
                                        icon = meta.icon
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return result
    }
}
