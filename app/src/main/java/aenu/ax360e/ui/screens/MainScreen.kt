package aenu.ax360e.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

private data class DrawerSection(
    val title: String,
    val items: List<DrawerDestination>
)

/**
 * Material You main screen.
 *
 * Layout:
 *  • CenterAlignedTopAppBar (semi-transparent + scrolledContainerColor)
 *  • ModalNavigationDrawer with gradient header (Xenon360 / Xbox 360 · M3),
 *    grouped destinations (Library / Configuration / System), and a
 *    version footer.
 *  • LazyVerticalGrid adaptive columns (min 150.dp): 2 cols phones,
 *    3-4 tablets, 5-6 landscape tablets.
 *  • ExtendedFloatingActionButton so the action label is always visible.
 */
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
    val pullRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

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

    val sections = remember(context) {
        listOf(
            DrawerSection(
                title = "Library",
                items = listOf(
                    DrawerDestination(
                        label = context.getString(R.string.set_game_dir),
                        icon = Icons.Default.FolderOpen,
                        onClick = { closeDrawerThen { openDirLauncher.launch(null) } }
                    ),
                    DrawerDestination(
                        label = context.getString(R.string.open_file_manager),
                        icon = Icons.Default.Storage,
                        onClick = { closeDrawerThen { openFileManager() } }
                    ),
                    DrawerDestination(
                        label = context.getString(R.string.refresh_list),
                        icon = Icons.Default.Refresh,
                        onClick = { closeDrawerThen { viewModel.refresh() } }
                    )
                )
            ),
            DrawerSection(
                title = "Configuration",
                items = listOf(
                    DrawerDestination(
                        label = context.getString(R.string.settings),
                        icon = Icons.Default.Settings,
                        onClick = {
                            closeDrawerThen {
                                context.startActivity(Intent(context, EmulatorSettings::class.java))
                            }
                        }
                    ),
                    DrawerDestination(
                        label = context.getString(R.string.key_mappers),
                        icon = Icons.Default.Gamepad,
                        onClick = {
                            closeDrawerThen {
                                context.startActivity(Intent(context, KeyMapActivity::class.java))
                            }
                        }
                    ),
                    DrawerDestination(
                        label = context.getString(R.string.virtual_pad_edit),
                        icon = Icons.Default.Create,
                        onClick = {
                            closeDrawerThen {
                                context.startActivity(Intent(context, VirtualControlEdit::class.java))
                            }
                        }
                    )
                )
            ),
            DrawerSection(
                title = "System",
                items = listOf(
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
                        icon = Icons.Default.SmartToy,
                        onClick = {
                            closeDrawerThen {
                                context.startActivity(Intent(context, AiRemoteControlActivity::class.java))
                            }
                        }
                    ),
                    DrawerDestination(
                        label = context.getString(R.string.about),
                        icon = Icons.Default.Info,
                        onClick = {
                            closeDrawerThen {
                                context.startActivity(Intent(context, AboutActivity::class.java))
                            }
                        }
                    )
                )
            )
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    // Gradient hero header — Xenon360 brand mark with halo
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                )
                            )
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Xenon360",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Xbox 360 · Material You",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }

                    sections.forEachIndexed { index, section ->
                        Text(
                            text = section.title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = 24.dp,
                                top = if (index == 0) 16.dp else 20.dp,
                                end = 24.dp,
                                bottom = 8.dp
                            )
                        )
                        section.items.forEach { dest ->
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = dest.label,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                selected = false,
                                onClick = dest.onClick,
                                icon = {
                                    Icon(
                                        dest.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier
                                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                                    .padding(vertical = 2.dp),
                                shape = RoundedCornerShape(28.dp),
                                colors = NavigationDrawerItemDefaults.colors(
                                    unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        if (index < sections.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "v${context.getString(R.string.app_version)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(24.dp)
                    )
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
                            text = "Xenon360",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh_list),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                context.startActivity(Intent(context, EmulatorSettings::class.java))
                            }
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { openDirLauncher.launch(null) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    text = { Text(stringResource(R.string.set_game_dir)) }
                )
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
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 96.dp
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

                AnimatedVisibility(
                    visible = state.error != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        state.error?.let { err ->
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

    if (showZarDialog && multiDiscGroups.isNotEmpty()) {
        val groupEntries = multiDiscGroups.entries.toList()
        var selectedGroup by remember { mutableStateOf(0) }

        AlertDialog(
            onDismissRequest = { showZarDialog = false },
            title = { Text("Create Multi-Disc Archive") },
            text = {
                Column {
                    Text(
                        text = "Detected ${multiDiscGroups.size} multi-disc game(s). Select one to create a .zar archive:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    groupEntries.forEachIndexed { index, entry ->
                        val isSelected = index == selectedGroup
                        androidx.compose.material3.Surface(
                            onClick = { selectedGroup = index },
                            color = if (isSelected)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "${entry.key} (${entry.value.size} discs)",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                    if (zarBuildInProgress) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Building ZAR archive...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!zarBuildInProgress) {
                            zarBuildInProgress = true
                            val selected = groupEntries[selectedGroup]
                            val gameDirUri = GameListLoader.getGameDirUri(context)
                            if (gameDirUri != null) {
                                scope.launch {
                                    val result = ZarBuilder.buildZar(
                                        context = context,
                                        discUris = selected.value,
                                        titleId = "00000000",
                                        titleName = selected.key,
                                        outputDir = gameDirUri
                                    )
                                    zarBuildInProgress = false
                                    showZarDialog = false
                                    val msg = if (result.success) {
                                        "Created ${result.outputPath} (${result.discCount} discs)"
                                    } else {
                                        "Failed: ${result.error}"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    val entries = GameListLoader.loadGames(context)
                                    games.clear()
                                    games.addAll(entries.map { entry ->
                                        GameItem(
                                            title = entry.name.removeSuffix(".iso")
                                                .removeSuffix(".xex")
                                                .removeSuffix(".zar"),
                                            uri = entry.uri,
                                            isDirectory = entry.isDirectory
                                        )
                                    })
                                    multiDiscGroups = ZarBuilder.detectMultiDiscGames(context, gameDirUri)
                                }
                            }
                        }
                    },
                    enabled = !zarBuildInProgress
                ) { Text("Create ZAR") }
            },
            dismissButton = {
                TextButton(onClick = { showZarDialog = false }) {
                    Text(android.R.string.cancel.let { context.getString(it) })
                }
            }
        )
    }
}

