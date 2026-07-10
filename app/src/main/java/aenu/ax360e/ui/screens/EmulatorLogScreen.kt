package aenu.ax360e.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import aenu.ax360e.ui.model.EmulatorLogRepository
import aenu.ax360e.ui.model.GameLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screen for browsing and viewing per-game emulator logs.
 *
 * Xenia-canary writes all XELOG* output to a file (configured via the
 * `--log_file` launch argument in src/xenia/base/logging.cc:449). When
 * a game session ends, EmulatorActivity calls
 * EmulatorLogRepository.captureGameLog() to copy the active `xe.log`
 * to `{app_data_dir}/ax360e/game_logs/{game_name}_{timestamp}.log`.
 *
 * This screen lists all captured logs, allows viewing the full content,
 * copying to clipboard, sharing via Intent, and deleting.
 *
 * Log line format (from logging.cc:317):
 *   `{prefix_char}> {thread_id_hex} {message}`
 * prefix_char values:
 *   '!' = Error, 'w' = Warning, 'i' = Info, 'd' = Debug,
 *   'C' = CPU, 'A' = APU, 'G' = GPU, 'K' = Kernel, 'F' = FileSystem
 */
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

    // Load log list
    LaunchedEffect(Unit) {
        logs = withContext(Dispatchers.IO) {
            EmulatorLogRepository.listLogs(context)
        }
    }

    fun refreshLogs() {
        scope.launch(Dispatchers.IO) {
            val newLogs = EmulatorLogRepository.listLogs(context)
            withContext(Dispatchers.Main) { logs = newLogs }
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
        scope.launch(Dispatchers.IO) {
            // Export to a file in the Downloads directory via SAF
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "${entry.gameName}_${entry.formattedTimestamp.replace(":", "_").replace(" ", "_")}.log")
            }
            withContext(Dispatchers.Main) {
                // The Activity must handle this intent via onActivityResult
                // For now, fall back to share
                shareLog(entry)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emulator Logs") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedLog != null) {
                            selectedLog = null
                            logContent = ""
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (selectedLog != null) {
                        // Viewing a single log
                        IconButton(onClick = { copyToClipboard(logContent) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = { shareLog(selectedLog!!) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    } else if (logs.isNotEmpty()) {
                        // List view
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
            // Single log viewer
            LogContentView(
                entry = selectedLog!!,
                content = logContent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            // Log list
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "No logs yet",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Logs are captured automatically when you exit a game.\nEach session saves the xenia-canary output (XELOGE/W/I/D) to a per-game file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs, key = { it.file.absolutePath }) { entry ->
                        LogEntryRow(
                            entry = entry,
                            onClick = { openLog(entry) },
                            onShare = { shareLog(entry) },
                            onDelete = { showDeleteDialog = entry }
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all logs?") },
            text = { Text("This will permanently delete all ${logs.size} saved log(s). This cannot be undone.") },
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
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
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
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogEntryRow(
    entry: GameLogEntry,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
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
        // Header with game info
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
                Text(
                    text = entry.gameName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${entry.formattedTimestamp} • ${entry.formattedSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Log content
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
