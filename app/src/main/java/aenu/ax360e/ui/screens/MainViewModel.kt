package aenu.ax360e.ui.screens

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import aenu.ax360e.EmulatorActivity
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
        val Factory = viewModelFactory {
            initializer {
                val app = (this[androidx.lifecycle.viewmodel.compose.APPLICATION_KEY] as Application)
                MainViewModel(app)
            }
        }
    }
}
