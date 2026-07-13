package aenu.ax360e.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import aenu.ax360e.R
import aenu.ax360e.ui.model.EmulatorLogRepository
import aenu.ax360e.ui.model.GameLogEntry
import aenu.ax360e.ui.model.LoggingConfigHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorLogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var logs by remember { mutableStateOf<List<GameLogEntry>>(emptyList()) }
    var selectedLog by remember { mutableStateOf<GameLogEntry?>(null) }
    var logContent by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<GameLogEntry?>(null) }
    var pendingExport by remember { mutableStateOf<GameLogEntry?>(null) }
    var currentLogLevel by remember { mutableIntStateOf(2) }
    var isDebugMode by remember { mutableStateOf(false) }
    var showVerbosityHelp by remember { mutableStateOf(false) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val entry = pendingExport
        pendingExport = null
        if (uri != null && entry != null) {
            scope.launch(Dispatchers.IO) {
                val success = EmulatorLogRepository.exportLogToUri(context, entry.file, uri)
                withContext(Dispatchers.Main) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (success) "Log exported successfully" else "Failed to export log"
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // Auto-recover configs that enable HIR file dumps — those used to
        // abort the emu process on Android (create_directory on read-only CWD).
        withContext(Dispatchers.IO) {
            LoggingConfigHelper.disableHirFileDumps()
            logs = EmulatorLogRepository.listLogs(context)
            currentLogLevel = LoggingConfigHelper.readLogLevel()
            isDebugMode = LoggingConfigHelper.readDebugMode()
        }
    }

    fun refreshLogs() {
        scope.launch(Dispatchers.IO) {
            val newLogs = EmulatorLogRepository.listLogs(context)
            withContext(Dispatchers.Main) { logs = newLogs }
        }
    }

    fun applyLogLevel(level: Int) {
        scope.launch(Dispatchers.IO) {
            val ok = LoggingConfigHelper.setLogLevel(level)
            val newLevel = LoggingConfigHelper.readLogLevel()
            val newDebug = LoggingConfigHelper.readDebugMode()
            withContext(Dispatchers.Main) {
                currentLogLevel = newLevel
                isDebugMode = newDebug
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (ok) context.getString(R.string.log_level_saved)
                        else "Failed to update log_level"
                    )
                }
            }
        }
    }

    fun toggleDebugMode(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val ok = LoggingConfigHelper.setDebugMode(enabled)
            val newDebug = LoggingConfigHelper.readDebugMode()
            val newLevel = LoggingConfigHelper.readLogLevel()
            withContext(Dispatchers.Main) {
                isDebugMode = newDebug
                currentLogLevel = newLevel
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (ok) "Debug mode updated"
                        else "Failed to update debug mode"
                    )
                }
            }
        }
    }

    fun applyJitDetail() {
        scope.launch(Dispatchers.IO) {
            val ok = LoggingConfigHelper.enableJitFunctionDetail(true)
            val newLevel = LoggingConfigHelper.readLogLevel()
            withContext(Dispatchers.Main) {
                currentLogLevel = newLevel
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (ok) context.getString(R.string.log_level_saved)
                        else "Failed to enable JIT detail flags"
                    )
                }
            }
        }
    }

    fun openLog(entry: GameLogEntry) {
        scope.launch(Dispatchers.IO) {
            val content = EmulatorLogRepository.readLog(entry.file)
            withContext(Dispatchers.Main) {
                selectedLog = entry
                logContent = content
            }
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Xenia Log", text))
        scope.launch { snackbarHostState.showSnackbar("Log copied to clipboard") }
    }

    fun shareLog(entry: GameLogEntry) {
        scope.launch(Dispatchers.IO) {
            val content = EmulatorLogRepository.readLog(entry.file)
            withContext(Dispatchers.Main) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, content)
                    putExtra(Intent.EXTRA_SUBJECT, "Xenia log: ${entry.gameName}")
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share log"))
            }
        }
    }

    fun exportLog(entry: GameLogEntry) {
        pendingExport = entry
        val fileName =
            "${entry.gameName}_${entry.formattedTimestamp.replace(":", "_").replace(" ", "_")}.log"
        exportLauncher.launch(fileName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedLog != null) selectedLog!!.gameName else "Emulator Logs"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedLog != null) {
                            selectedLog = null
                            logContent = ""
                        } else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (selectedLog != null) {
                        IconButton(onClick = { copyToClipboard(logContent) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = { exportLog(selectedLog!!) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Export")
                        }
                        IconButton(onClick = { shareLog(selectedLog!!) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    } else if (logs.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear all")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (selectedLog != null) {
            LogContentView(
                entry = selectedLog!!,
                content = logContent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    LogVerbosityCard(
                        currentLevel = currentLogLevel,
                        isDebugMode = isDebugMode,
                        onSelectLevel = { applyLogLevel(it) },
                        onToggleDebugMode = { toggleDebugMode(it) },
                        onEnableJitDetail = { applyJitDetail() },
                        onHelp = { showVerbosityHelp = true }
                    )
                }

                if (logs.isEmpty()) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "No logs yet",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Logs are captured automatically when you exit a game. Raise log_level above, then relaunch a game for more detail.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(logs, key = { it.file.absolutePath }) { entry ->
                        LogEntryRow(
                            entry = entry,
                            onClick = { openLog(entry) },
                            onExport = { exportLog(entry) },
                            onShare = { shareLog(entry) },
                            onDelete = { showDeleteDialog = entry }
                        )
                    }
                }
            }
        }
    }

    if (showVerbosityHelp) {
        AlertDialog(
            onDismissRequest = { showVerbosityHelp = false },
            title = { Text(stringResource(R.string.log_level_preset_title)) },
            text = {
                Text(
                    "Official Xenia cvar Logging|log_level (xenia-canary):\n" +
                        "0 error · 1 warning · 2 info (default) · 3 debug · 4 trace\n\n" +
                        "Debug/trace unlock XELOGD and CPU tracer lines. " +
                        "\"Enable JIT function detail\" also sets CPU|disassemble_functions, " +
                        "CPU|dump_translated_hir_functions and CPU|trace_functions.\n\n" +
                        "Changes apply on the next game launch. Review results in this screen " +
                        "after exiting the game (xe.log is copied per session)."
                )
            },
            confirmButton = {
                TextButton(onClick = { showVerbosityHelp = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all logs?") },
            text = { Text("This will permanently delete all ${logs.size} saved log(s).") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch(Dispatchers.IO) {
                        EmulatorLogRepository.clearAllLogs(context)
                        refreshLogs()
                    }
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    showDeleteDialog?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete this log?") },
            text = { Text("${entry.gameName}\n${entry.formattedTimestamp}") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = null
                    scope.launch(Dispatchers.IO) {
                        EmulatorLogRepository.deleteLog(entry.file)
                        refreshLogs()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LogVerbosityCard(
    currentLevel: Int,
    isDebugMode: Boolean,
    onSelectLevel: (Int) -> Unit,
    onToggleDebugMode: (Boolean) -> Unit,
    onEnableJitDetail: () -> Unit,
    onHelp: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.log_level_preset_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                TextButton(onClick = onHelp) {
                    Text("?")
                }
            }
            Text(
                text = stringResource(
                    R.string.log_level_current,
                    LoggingConfigHelper.levelName(currentLevel)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    2 to stringResource(R.string.log_level_set_info),
                    3 to stringResource(R.string.log_level_set_debug),
                    4 to stringResource(R.string.log_level_set_trace)
                ).forEach { (level, label) ->
                    FilterChip(
                        selected = currentLevel == level,
                        onClick = { onSelectLevel(level) },
                        label = { Text(label) }
                    )
                }
            }
            OutlinedButton(
                onClick = onEnableJitDetail,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.log_level_enable_jit_detail))
            }
            Text(
                text = stringResource(R.string.log_level_preset_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Debug Mode",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Enables MMIO tracing and unimplemented instruction breaks. MAY REDUCE PERFORMANCE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                androidx.compose.material3.Switch(
                    checked = isDebugMode,
                    onCheckedChange = onToggleDebugMode
                )
            }
        }
    }
}

@Composable
private fun LogEntryRow(
    entry: GameLogEntry,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.gameName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.formattedTimestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.formattedSize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Export")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LogContentView(
    entry: GameLogEntry,
    content: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = entry.gameName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${entry.formattedTimestamp} • ${entry.formattedSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (content.isEmpty()) {
                Text(
                    text = "Loading...",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = content,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
