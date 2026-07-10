package aenu.ax360e.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import aenu.ax360e.ui.model.GameItem
import aenu.ax360e.ui.model.GameListLoader

data class GameListUiState(
    val isLoading: Boolean = false,
    val games: List<GameItem> = emptyList(),
    val hasGameDir: Boolean = false,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(GameListUiState())
    val uiState: StateFlow<GameListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val games = GameListLoader.loadGames(getApplication())
                val hasDir = GameListLoader.loadGameDir(getApplication()) != null
                _uiState.value = GameListUiState(
                    isLoading = false,
                    games = games,
                    hasGameDir = hasDir,
                    error = null
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(app) as T
                }
            }
    }
}
