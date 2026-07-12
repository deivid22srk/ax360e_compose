package aenu.ax360e.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
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
import aenu.ax360e.R
import aenu.ax360e.Utils
import aenu.ax360e.ui.components.preference.PreferenceHeader
import aenu.ax360e.ui.components.preference.RegularPreference
import aenu.ax360e.ui.components.preference.SwitchPreference
import aenu.ax360e.ui.components.preference.ValuePreference
import aenu.emulator.Emulator as NativeEmulator
import java.io.File

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
                NativeEmulator.Config.open_config_file(effectivePath)
            else null
        }.getOrNull()
    }
    val originalConfig = remember {
        runCatching {
            NativeEmulator.Config.open_config_from_string(
                Application.load_default_config_str(context)
            )
        }.getOrNull()
    }

    var currentScreen by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshToken by remember { mutableStateOf(0) }

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

        val entries = remember(currentScreen, refreshToken) {
            SettingsTree.getEntries(currentScreen)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (currentScreen.isEmpty()) {
                item {
                    PreferenceHeader(text = stringResource(R.string.settings))
                }
            }

            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is SettingsEntry.Section -> {
                        RegularPreference(
                            title = entry.title,
                            subtitle = null,
                            icon = Icons.Default.Folder,
                            trailing = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { currentScreen = currentScreen + entry.title }
                        )
                    }
                    is SettingsEntry.Bool -> {
                        val stored = config.load_config_entry(entry.key)
                        val value = stored?.toBooleanStrictOrNull()
                        val defaultVal = originalConfig?.load_config_entry(entry.key)
                        val modified = defaultVal != value?.toString()
                        SwitchPreference(
                            title = entry.title,
                            checked = value == true,
                            onCheckedChange = { newVal ->
                                config.save_config_entry(entry.key, newVal.toString())
                                refreshToken++
                            },
                            subtitle = if (modified) "Modified" else null,
                            icon = Icons.Default.Tune,
                            enabled = value != null
                        )
                    }
                    is SettingsEntry.Int -> {
                        val value = config.load_config_entry(entry.key)?.toIntOrNull()
                        val defaultVal = originalConfig?.load_config_entry(entry.key)
                        val modified = defaultVal != value?.toString()
                        IntPreferenceRow(
                            title = entry.title,
                            value = value,
                            min = entry.min,
                            max = entry.max,
                            modified = modified,
                            onValueChange = { newVal ->
                                config.save_config_entry(entry.key, newVal.toString())
                                refreshToken++
                            }
                        )
                    }
                    is SettingsEntry.StrArr -> {
                        val value = config.load_config_entry(entry.key)
                        val defaultVal = originalConfig?.load_config_entry(entry.key)
                        val modified = defaultVal != value
                        StrArrPreferenceRow(
                            title = entry.title,
                            value = value,
                            entries = entry.entries,
                            values = entry.values,
                            modified = modified,
                            onValueChange = { newVal ->
                                config.save_config_entry(entry.key, newVal)
                                refreshToken++
                            }
                        )
                    }
                    is SettingsEntry.StrLeaf -> {
                        val value = config.load_config_entry(entry.key)
                        StrLeafPreferenceRow(
                            title = entry.title,
                            value = value,
                            onValueChange = { newVal ->
                                config.save_config_entry(entry.key, newVal)
                                refreshToken++
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
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
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
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
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun IntPreferenceRow(
    title: String,
    value: Int?,
    min: Int,
    max: Int,
    modified: Boolean,
    onValueChange: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    ValuePreference(
        title = title,
        value = value?.toString() ?: "—",
        onClick = { showDialog = true },
        subtitle = if (modified) "Modified · $min..$max" else "$min..$max",
        icon = Icons.Default.Tune,
        highlightValue = modified
    )
    if (showDialog) {
        var textValue by remember { mutableStateOf(value?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    Text(
                        "Range: $min .. $max",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it.filter { c -> c.isDigit() || c == '-' } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
private fun StrArrPreferenceRow(
    title: String,
    value: String?,
    entries: List<String>,
    values: List<String>,
    modified: Boolean,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val display = entries.getOrNull(values.indexOf(value)) ?: value ?: ""
    ValuePreference(
        title = title,
        value = display,
        onClick = { showDialog = true },
        subtitle = if (modified) "Modified" else null,
        icon = Icons.Default.Tune,
        highlightValue = modified
    )
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
                            color = if (values[i] == value)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
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

@Composable
private fun StrLeafPreferenceRow(
    title: String,
    value: String?,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    ValuePreference(
        title = title,
        value = value ?: "—",
        onClick = { showDialog = true },
        icon = Icons.Default.Tune
    )
    if (showDialog) {
        var textValue by remember { mutableStateOf(value ?: "") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(textValue)
                    showDialog = false
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
