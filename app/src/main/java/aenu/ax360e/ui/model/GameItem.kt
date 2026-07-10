package aenu.ax360e.ui.model

import aenu.ax360e.Emulator

/**
 * UI model for a game entry in the game list.
 */
data class GameItem(
    val uri: String,
    val name: String,
    val icon: ByteArray?
) {
    fun toGameInfo(): Emulator.GameInfo {
        val info = Emulator.GameInfo()
        info.uri = uri
        info.name = name
        info.icon = icon
        return info
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameItem) return false
        return uri == other.uri && name == other.name
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}
