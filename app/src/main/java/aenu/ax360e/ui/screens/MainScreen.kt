package aenu.ax360e.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import aenu.ax360e.EmulatorActivity
import aenu.ax360e.EmulatorSettings
import aenu.ax360e.KeyMapActivity
import aenu.ax360e.AboutActivity
import aenu.ax360e.VirtualControlEdit
import aenu.ax360e.R
import aenu.ax360e.ui.model.GameListLoader
import aenu.ax360e.ui.model.UiPreferences
import aenu.ax360e.ui.components.GameCard
import aenu.ax360e.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val viewModel: MainViewModel = viewModel(
        factory = remember { MainViewModel.factory(app) }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var fpsVisible by remember { mutableStateOf(UiPreferences.isFpsVisible(context)) }

    val openDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            GameListLoader.saveGameDir(context, uri)
            viewModel.refresh()
        }
    }

    fun openFileManager() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName("com.android.documentsui", "com.android.documentsui.files.FilesActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setClassName(
                        "com.google.android.documentsui",
                        "com.android.documentsui.files.FilesActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_list))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.set_game_dir)) },
                                onClick = {
                                    menuExpanded = false
                                    openDirLauncher.launch(null)
                                },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(Intent(context, EmulatorSettings::class.java))
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.key_mappers)) },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(Intent(context, KeyMapActivity::class.java))
                                },
                                leadingIcon = { Icon(Icons.Default.Gamepad, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.virtual_pad_edit)) },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(Intent(context, VirtualControlEdit::class.java))
                                },
                                leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_file_manager)) },
                                onClick = {
                                    menuExpanded = false
                                    openFileManager()
                                },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                            )
                            // FPS counter toggle - shows a live FPS overlay on
                            // top of the emulator surface (top-left corner).
                            // Polls GraphicsSystem vblank counter via JNI.
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (fpsVisible) "Hide FPS Counter"
                                               else "Show FPS Counter"
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    fpsVisible = !fpsVisible
                                    UiPreferences.setFpsVisible(context, fpsVisible)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = if (fpsVisible)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.about)) },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(Intent(context, AboutActivity::class.java))
                                },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                state.games.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.SportsEsports,
                        title = stringResource(R.string.empty_game_list),
                        subtitle = if (state.hasGameDir) null else stringResource(R.string.set_game_dir),
                        actionLabel = if (!state.hasGameDir) stringResource(R.string.set_game_dir) else null,
                        onActionClick = if (!state.hasGameDir) {
                            { openDirLauncher.launch(null) }
                        } else null
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.games, key = { it.uri }) { game ->
                            GameCard(
                                game = game,
                                onClick = {
                                    val intent = Intent("aenu.intent.action.AX360E").apply {
                                        setPackage(context.packageName)
                                        putExtra(EmulatorActivity.EXTRA_GAME_URI, game.uri)
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}
