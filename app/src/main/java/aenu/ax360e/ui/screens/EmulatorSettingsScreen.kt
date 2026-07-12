package aenu.ax360e.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SystemUpdateAlt
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import aenu.ax360e.Application
import aenu.ax360e.Emulator
import aenu.ax360e.R
import aenu.ax360e.Utils
import aenu.ax360e.ui.components.preference.PreferenceHeader
import aenu.ax360e.ui.components.preference.RegularPreference
import aenu.ax360e.ui.components.preference.SwitchPreference
import aenu.ax360e.ui.components.preference.ValuePreference
import aenu.emulator.Emulator as NativeEmulator
import java.io.File

private const val VULKAN_LIB_PATH_KEY = "Vulkan|vulkan_lib_path"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorSettingsScreen(
    configPath: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    LaunchedEffect(Unit) { SettingsTree.init(context) }

    val libraryReady = remember {
        Emulator.ensure_library_loaded()
    }

    val effectivePath = remember(configPath) {
        if (configPath != null) {
            configPath
        } else {
            Application.ensure_global_config_file().absolutePath
        }
    }
    val isGlobal = configPath == null

    // Holder keeps the latest native handle for dispose even if composition
    // snapshots are stale (DisposableEffect(Unit) only captures once).
    val session = remember(effectivePath) {
        SettingsSession(openConfigOrNull(effectivePath, libraryReady))
    }
    var config by remember(effectivePath) { mutableStateOf(session.config) }
    val originalConfig = remember(libraryReady) {
        if (!libraryReady) null
        else runCatching {
            NativeEmulator.Config.open_config_from_string(
                Application.load_default_config_str(context)
            )
        }.getOrNull()
    }

    var currentScreen by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshToken by remember { mutableStateOf(0) }
    var loadError by remember {
        mutableStateOf(
            if (!libraryReady) "Native library not loaded"
            else if (session.config == null) "Config file not available: $effectivePath"
            else null
        )
    }

    fun flushAndRelease() {
        val c = session.config ?: return
        runCatching { c.close_config_file() }
            .onFailure {
                loadError = it.message ?: "Failed to save settings"
            }
        session.config = null
        session.dirty = false
        config = null
    }

    fun markDirtyAndRefresh(block: (NativeEmulator.Config) -> Unit) {
        val c = session.config ?: return
        block(c)
        session.dirty = true
        refreshToken++
    }

    fun leaveSettings() {
        flushAndRelease()
        onBack()
    }

    DisposableEffect(session) {
        onDispose {
            session.config?.let { c ->
                runCatching { c.close_config_file() }
                session.config = null
                session.dirty = false
            }
        }
    }

    BackHandler {
        if (currentScreen.isNotEmpty()) {
            currentScreen = currentScreen.dropLast(1)
        } else {
            leaveSettings()
        }
    }

    val importDriverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null || activity == null) return@rememberLauncherForActivityResult
        val ok = Utils.install_custom_driver_from_zip(activity, uri) { installedPath ->
            markDirtyAndRefresh { c ->
                c.save_config_entry(VULKAN_LIB_PATH_KEY, installedPath)
            }
            Toast.makeText(
                context,
                context.getString(R.string.driver_library_path_dialog_add_hint) + "\n$installedPath",
                Toast.LENGTH_LONG
            ).show()
        }
        if (!ok) {
            Toast.makeText(context, "Failed to import driver", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (currentScreen.isEmpty())
                            stringResource(R.string.settings)
                        else currentScreen.last(),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentScreen.isEmpty()) leaveSettings()
                        else currentScreen = currentScreen.dropLast(1)
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = loadError ?: "Config file not available: $effectivePath",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        // Retry seed + open after a failed first attempt.
                        Emulator.ensure_library_loaded()
                        if (isGlobal) Application.ensure_global_config_file()
                        val reopened = openConfigOrNull(effectivePath, true)
                        session.config = reopened
                        config = reopened
                        loadError = if (reopened == null) {
                            "Config file not available: $effectivePath"
                        } else null
                    }) {
                        Text("Retry")
                    }
                }
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

            // [REFRESH FIX] Add refreshToken to the items() key so the LazyColumn
            // recomposes every entry whenever a setting is changed. Previously
            // only `it.key` was the key, which meant the per-item content lambdas
            // were cached and never recomputed when refreshToken incremented —
            // so the Switch/Slider/TextField visually stayed in the old state
            // until the user left the screen and came back.
            items(entries, key = { "${it.key}::$refreshToken" }) { entry ->
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
                        val stored = config?.load_config_entry(entry.key)
                        val value = stored?.toBooleanStrictOrNull()
                        val defaultVal = originalConfig?.load_config_entry(entry.key)
                        val modified = defaultVal != value?.toString()
                        SwitchPreference(
                            title = entry.title,
                            checked = value == true,
                            onCheckedChange = { newVal ->
                                markDirtyAndRefresh {
                                    it.save_config_entry(entry.key, newVal.toString())
                                }
                            },
                            subtitle = if (modified) "Modified" else null,
                            icon = Icons.Default.Tune,
                            enabled = value != null || stored == null
                        )
                    }
                    is SettingsEntry.Int -> {
                        val value = config?.load_config_entry(entry.key)?.toIntOrNull()
                        val defaultVal = originalConfig?.load_config_entry(entry.key)
                        val modified = defaultVal != value?.toString()
                        IntPreferenceRow(
                            title = entry.title,
                            value = value,
                            min = entry.min,
                            max = entry.max,
                            modified = modified,
                            onValueChange = { newVal ->
                                markDirtyAndRefresh {
                                    it.save_config_entry(entry.key, newVal.toString())
                                }
                            }
                        )
                    }
                    is SettingsEntry.StrArr -> {
                        val value = config?.load_config_entry(entry.key)
                        val defaultVal = originalConfig?.load_config_entry(entry.key)
                        val modified = defaultVal != value
                        StrArrPreferenceRow(
                            title = entry.title,
                            value = value,
                            entries = entry.entries,
                            values = entry.values,
                            modified = modified,
                            onValueChange = { newVal ->
                                markDirtyAndRefresh {
                                    it.save_config_entry(entry.key, newVal)
                                }
                            }
                        )
                    }
                    is SettingsEntry.StrLeaf -> {
                        val value = config?.load_config_entry(entry.key)
                        if (entry.key == VULKAN_LIB_PATH_KEY) {
                            DriverPathPreferenceRow(
                                title = entry.title,
                                value = value,
                                onImportClick = {
                                    importDriverLauncher.launch("application/zip")
                                },
                                onValueChange = { newVal ->
                                    markDirtyAndRefresh {
                                        it.save_config_entry(entry.key, newVal)
                                    }
                                },
                                onClear = {
                                    markDirtyAndRefresh {
                                        it.save_config_entry(entry.key, "")
                                    }
                                }
                            )
                        } else {
                            StrLeafPreferenceRow(
                                title = entry.title,
                                value = value,
                                onValueChange = { newVal ->
                                    markDirtyAndRefresh {
                                        it.save_config_entry(entry.key, newVal)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        runCatching { session.config?.close_config_file() }
                        session.config = null
                        Utils.copy_file(
                            Application.get_default_config_file(),
                            File(effectivePath)
                        )
                        val reopened = openConfigOrNull(effectivePath, libraryReady)
                        session.config = reopened
                        session.dirty = false
                        config = reopened
                        refreshToken++
                        Toast.makeText(
                            context,
                            context.getString(R.string.reset_as_default),
                            Toast.LENGTH_SHORT
                        ).show()
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
                            runCatching { session.config?.close_config_file() }
                            session.config = null
                            session.dirty = false
                            File(effectivePath).delete()
                            leaveSettings()
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

private class SettingsSession(var config: NativeEmulator.Config?) {
    var dirty: Boolean = false
}

private fun openConfigOrNull(path: String, libraryReady: Boolean): NativeEmulator.Config? {
    if (!libraryReady) return null
    return runCatching {
        val file = File(path)
        if (!file.exists()) {
            // For per-game configs the path may not exist yet; seed from defaults.
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            Utils.copy_file(Application.get_default_config_file(), file)
            if (!file.exists()) {
                Utils.save_string(file, Application.load_default_config_str(Application.ctx))
            }
        }
        if (file.exists()) NativeEmulator.Config.open_config_file(path) else null
    }.getOrNull()
}

@Composable
private fun DriverPathPreferenceRow(
    title: String,
    value: String?,
    onImportClick: () -> Unit,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit
) {
    var showManualDialog by remember { mutableStateOf(false) }
    val display = when {
        value.isNullOrBlank() -> "System default"
        else -> value.substringAfterLast('/').ifEmpty { value }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ValuePreference(
            title = title,
            value = display,
            onClick = { showManualDialog = true },
            subtitle = if (value.isNullOrBlank()) {
                stringResource(R.string.driver_library_path_dialog_add_hint)
            } else {
                value
            },
            icon = Icons.Default.SystemUpdateAlt
        )
        OutlinedButton(
            onClick = onImportClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Icon(
                Icons.Default.SystemUpdateAlt,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(stringResource(R.string.driver_library_path_dialog_add_hint))
        }
        if (!value.isNullOrBlank()) {
            TextButton(
                onClick = onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(stringResource(R.string.clear))
            }
        }
    }

    if (showManualDialog) {
        var textValue by remember { mutableStateOf(value.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.driver_library_path_dialog_add_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(textValue)
                    showManualDialog = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
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
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
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
