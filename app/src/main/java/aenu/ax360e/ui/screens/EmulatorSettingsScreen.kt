package aenu.ax360e.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import aenu.ax360e.Application
import aenu.ax360e.Emulator
import aenu.ax360e.R
import aenu.ax360e.Utils
import java.io.File

/**
 * Pure-Compose settings screen. Reads the same configuration file format as the
 * original XML-based preference screen but renders everything as Material 3
 * Expressive components. We deliberately avoid androidx.preference so we no
 * longer depend on any XML preference resources at runtime.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorSettingsScreen(
    configPath: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SettingsTree.init(context) }
    val effectivePath = remember(configPath) {
        configPath ?: Application.get_global_config_file().absolutePath
    }
    val isGlobal = configPath == null

    val config = remember(effectivePath) {
        runCatching {
            if (File(effectivePath).exists())
                Emulator.Config.open_config_file(effectivePath)
            else null
        }.getOrNull()
    }
    val originalConfig = remember {
        runCatching {
            Emulator.Config.open_config_from_string(
                Application.load_default_config_str(context)
            )
        }.getOrNull()
    }

    var currentScreen by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingValueKey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (currentScreen.isEmpty())
                            stringResource(R.string.settings)
                        else currentScreen.last()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentScreen.isEmpty()) onBack()
                        else currentScreen = currentScreen.dropLast(1)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        if (config == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Config file not available: $effectivePath",
                    color = MaterialTheme.colorScheme.error
                )
            }
            return@Scaffold
        }

        val entries = remember(currentScreen) {
            SettingsTree.getEntries(currentScreen)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is SettingsEntry.Section -> {
                        SectionRow(
                            title = entry.title,
                            onClick = { currentScreen = currentScreen + entry.title }
                        )
                    }
                    is SettingsEntry.Bool -> {
                        val value = remember(entry.key) {
                            config.load_config_entry(entry.key)?.toBooleanStrictOrNull()
                        }
                        BoolRow(
                            title = entry.title,
                            value = value,
                            modified = originalConfig?.load_config_entry(entry.key) != value?.toString(),
                            onValueChange = { newVal ->
                                config.save_config_entry(entry.key, newVal.toString())
                            }
                        )
                    }
                    is SettingsEntry.Int -> {
                        val value = remember(entry.key) {
                            config.load_config_entry(entry.key)?.toIntOrNull()
                        }
                        IntRow(
                            title = entry.title,
                            value = value,
                            min = entry.min,
                            max = entry.max,
                            modified = originalConfig?.load_config_entry(entry.key) != value?.toString(),
                            onValueChange = { newVal ->
                                config.save_config_entry(entry.key, newVal.toString())
                            }
                        )
                    }
                    is SettingsEntry.StrArr -> {
                        val value = remember(entry.key) {
                            config.load_config_entry(entry.key)
                        }
                        StrArrRow(
                            title = entry.title,
                            value = value,
                            entries = entry.entries,
                            values = entry.values,
                            modified = originalConfig?.load_config_entry(entry.key) != value,
                            onValueChange = { newVal ->
                                config.save_config_entry(entry.key, newVal)
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        // Reset to default config
                        config.close_config_file()
                        Utils.copy_file(
                            Application.get_default_config_file(),
                            File(effectivePath)
                        )
                        onBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.reset_as_default))
                }
                if (!isGlobal) {
                    OutlinedButton(
                        onClick = {
                            config.close_config_file()
                            File(effectivePath).delete()
                            onBack()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.use_global_config))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionRow(title: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun BoolRow(
    title: String,
    value: Boolean?,
    modified: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (modified) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = value == true,
            enabled = value != null,
            onCheckedChange = onValueChange
        )
    }
}

@Composable
private fun IntRow(
    title: String,
    value: Int?,
    min: Int,
    max: Int,
    modified: Boolean,
    onValueChange: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        onClick = { showDialog = true },
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (modified) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value?.toString() ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showDialog) {
        var textValue by remember { mutableStateOf(value?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    Text("Range: $min .. $max", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it.filter { c -> c.isDigit() || c == '-' } },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    textValue.toIntOrNull()?.let { v ->
                        if (v in min..max) {
                            onValueChange(v)
                            showDialog = false
                        }
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun StrArrRow(
    title: String,
    value: String?,
    entries: List<String>,
    values: List<String>,
    modified: Boolean,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        onClick = { showDialog = true },
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (modified) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = entries.getOrNull(values.indexOf(value)) ?: value ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    entries.forEachIndexed { i, label ->
                        Surface(
                            onClick = {
                                onValueChange(values[i])
                                showDialog = false
                            },
                            color = if (values[i] == value) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
