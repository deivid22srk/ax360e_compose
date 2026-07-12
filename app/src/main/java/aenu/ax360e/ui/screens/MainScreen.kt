package aenu.ax360e.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import aenu.ax360e.AboutActivity
import aenu.ax360e.AiRemoteControlActivity
import aenu.ax360e.EmulatorActivity
import aenu.ax360e.EmulatorSettings
import aenu.ax360e.KeyMapActivity
import aenu.ax360e.LogViewerActivity
import aenu.ax360e.R
import aenu.ax360e.VirtualControlEdit
import aenu.ax360e.ui.components.EmptyState
import aenu.ax360e.ui.components.GameCard
import aenu.ax360e.ui.model.GameListLoader
import kotlinx.coroutines.launch

private data class DrawerDestination(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val viewModel: MainViewModel = viewModel(
        factory = remember { MainViewModel.factory(app) }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullRefreshState = rememberPullToRefreshState()

    val openDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            GameListLoader.saveGameDir(context, uri)
            viewModel.refresh()
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.set_game_dir))
            }
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

    fun closeDrawerThen(block: () -> Unit) {
        scope.launch {
            drawerState.close()
            block()
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val destinations = listOf(
        DrawerDestination(
            label = stringResource(R.string.set_game_dir),
            icon = Icons.Default.FolderOpen,
            onClick = { closeDrawerThen { openDirLauncher.launch(null) } }
        ),
        DrawerDestination(
            label = stringResource(R.string.settings),
            icon = Icons.Default.Settings,
            onClick = {
                closeDrawerThen {
                    context.startActivity(Intent(context, EmulatorSettings::class.java))
                }
            }
        ),
        DrawerDestination(
            label = stringResource(R.string.key_mappers),
            icon = Icons.Default.Gamepad,
            onClick = {
                closeDrawerThen {
                    context.startActivity(Intent(context, KeyMapActivity::class.java))
                }
            }
        ),
        DrawerDestination(
            label = stringResource(R.string.virtual_pad_edit),
            icon = Icons.Default.Create,
            onClick = {
                closeDrawerThen {
                    context.startActivity(Intent(context, VirtualControlEdit::class.java))
                }
            }
        ),
        DrawerDestination(
            label = stringResource(R.string.open_file_manager),
            icon = Icons.Default.FolderOpen,
            onClick = { closeDrawerThen { openFileManager() } }
        ),
        DrawerDestination(
            label = "Emulator Logs",
            icon = Icons.Default.Description,
            onClick = {
                closeDrawerThen {
                    context.startActivity(Intent(context, LogViewerActivity::class.java))
                }
            }
        ),
        DrawerDestination(
            label = "AI Remote Control",
            icon = Icons.Default.Settings,
            onClick = {
                closeDrawerThen {
                    context.startActivity(Intent(context, AiRemoteControlActivity::class.java))
                }
            }
        ),
        DrawerDestination(
            label = stringResource(R.string.about),
            icon = Icons.Default.Info,
            onClick = {
                closeDrawerThen {
                    context.startActivity(Intent(context, AboutActivity::class.java))
                }
            }
        )
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Xbox 360 · Material You",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    destinations.forEach { dest ->
                        NavigationDrawerItem(
                            label = { Text(dest.label) },
                            selected = false,
                            onClick = dest.onClick,
                            icon = {
                                Icon(dest.icon, contentDescription = null)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh_list)
                            )
                        }
                        IconButton(
                            onClick = {
                                context.startActivity(Intent(context, EmulatorSettings::class.java))
                            }
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { openDirLauncher.launch(null) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.set_game_dir))
                }
            }
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.games.isNotEmpty(),
                onRefresh = { viewModel.refresh() },
                state = pullRefreshState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullRefreshState,
                        isRefreshing = state.isLoading && state.games.isNotEmpty(),
                        modifier = Modifier.align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                when {
                    state.isLoading && state.games.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.game_list_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    state.games.isEmpty() -> {
                        EmptyState(
                            icon = Icons.Default.SportsEsports,
                            title = stringResource(R.string.empty_game_list),
                            subtitle = if (state.hasGameDir) {
                                stringResource(R.string.refresh_list)
                            } else {
                                stringResource(R.string.set_game_dir)
                            },
                            actionLabel = stringResource(R.string.set_game_dir),
                            onActionClick = { openDirLauncher.launch(null) },
                            secondaryActionLabel = if (state.hasGameDir) {
                                stringResource(R.string.refresh_list)
                            } else null,
                            onSecondaryActionClick = if (state.hasGameDir) {
                                { viewModel.refresh() }
                            } else null
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 168.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 8.dp,
                                bottom = 88.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
