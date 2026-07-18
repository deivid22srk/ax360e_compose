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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import kotlinx.coroutines.delay
import aenu.ax360e.Emulator
import aenu.ax360e.R
import aenu.ax360e.Utils
import aenu.ax360e.ui.components.preference.PreferenceGroupCard
import aenu.ax360e.ui.components.preference.PreferenceHeader
import aenu.ax360e.ui.components.preference.RegularPreference
import aenu.ax360e.ui.components.preference.SwitchPreference
import aenu.ax360e.ui.components.preference.ValuePreference
import aenu.emulator.Emulator as NativeEmulator
import java.io.File

private const val VULKAN_LIB_PATH_KEY = "Vulkan|vulkan_lib_path"

/** Icon per top-level section, used inside the simplified settings cards. */
private fun sectionIcon(sectionKey: String) = when (sectionKey) {
    "APU" -> Icons.Default.VolumeUp
    "CPU" -> Icons.Default.Memory
    "GPU" -> Icons.Default.GraphicEq
    "Display" -> Icons.Default.Visibility
    "Video" -> Icons.Default.SportsEsports
    "Vulkan" -> Icons.Default.SystemUpdateAlt
    "General" -> Icons.Default.Settings
    "HID" -> Icons.Default.SportsEsports
    "Kernel" -> Icons.Default.Memory
    "Storage" -> Icons.Default.Storage
    "Memory" -> Icons.Default.Memory
    "Content" -> Icons.Default.PrivacyTip
    "Logging" -> Icons.Default.Tune
    "UI" -> Icons.Default.Tune
    "XConfig" -> Icons.Default.Tune
    else -> Icons.Default.Folder
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorSettingsScreen(
    configPath: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    LaunchedEffect(Unit) { SettingsTree.init(context) }

    var libraryReady by remember { mutableStateOf(Emulator.ensure_library_loaded()) }

    val effectivePath = remember(configPath) {
        if (configPath != null) {
            configPath
        } else {
            Application.ensure_global_config_file().absolutePath
        }
    }
    val isGlobal = configPath == null

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
    var advancedMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var loadError by remember {
        mutableStateOf(
            if (!libraryReady) "Native library not loaded"
            else if (session.config == null) "Config file not available: $effectivePath"
            else null
        )
    }

    LaunchedEffect(libraryReady, config) {
        if (config == null) {
            delay(500)
            if (!libraryReady) {
                val ok = Emulator.ensure_library_loaded()
                if (ok) {
                    libraryReady = true
                }
            }
            if (libraryReady) {
                if (isGlobal) Application.ensure_global_config_file()
                val reopened = openConfigOrNull(effectivePath, true)
                if (reopened != null) {
                    session.config = reopened
                    config = reopened
                    loadError = null
                }
            }
        }
    }

    fun flushAndRelease() {
        val c = session.config ?: return
        runCatching { c.close_config_file() }
            .onFailure {
                loadError = it.message ?: "Failed to save settings"
            }
        session.config = null
        session.dirty = false
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

    val isSearching = searchQuery.trim().isNotEmpty()

    BackHandler {
        when {
            isSearching -> searchQuery = ""
            currentScreen.isNotEmpty() -> currentScreen = currentScreen.dropLast(1)
            else -> leaveSettings()
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
                        text = if (isSearching) ""
                        else if (currentScreen.isEmpty())
                            stringResource(R.string.settings)
                        else currentScreen.last(),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            isSearching -> searchQuery = ""
                            currentScreen.isEmpty() -> leaveSettings()
                            else -> currentScreen = currentScreen.dropLast(1)
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (currentScreen.isEmpty() && !isSearching) {
                        FilterChip(
                            selected = advancedMode,
                            onClick = { advancedMode = !advancedMode },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            },
                            label = { Text("Advanced") },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
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

        // Search bar shown on the root level (and inside sections when advancedMode is on).
        val showSearchBar = currentScreen.isEmpty() || isSearching
        // Pre-compute the (possibly filtered) entries and search results in the
        // composable scope — remember() can't be called inside LazyColumn's
        // LazyListScope because that scope is not @Composable.
        val q = if (isSearching) searchQuery else null
        val entries = remember(currentScreen, refreshToken, advancedMode, q) {
            SettingsTree.getEntriesFiltered(currentScreen, advancedMode, q)
        }
        val searchResults = remember(searchQuery, refreshToken) {
            if (isSearching) SettingsTree.search(searchQuery) else emptyList()
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (showSearchBar) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search settings…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = null)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp)
                    )
                }
            }

            if (isSearching) {
                // Flat search results across all sections
                if (searchResults.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No settings match \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "${searchResults.size} result${if (searchResults.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(searchResults, key = { "${it.first.key}|${it.second.key}::$refreshToken" }) { (section, entry) ->
                        Column {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                            )
                            SearchEntryRow(
                                section = section,
                                entry = entry,
                                config = config,
                                originalConfig = originalConfig,
                                onMarkDirty = { block -> markDirtyAndRefresh(block) },
                                onImportDriver = { importDriverLauncher.launch("application/zip") }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            } else if (currentScreen.isEmpty()) {
                // Root: show sections as grouped cards
                item {
                    PreferenceHeader(
                        text = stringResource(R.string.settings),
                        trailing = {
                            Text(
                                text = if (advancedMode) "Advanced" else "Essential",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
                if (!advancedMode) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            ),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(
                                text = "Showing the most useful settings. Turn on Advanced to see every option (CPU debug flags, GPU trace dumps, memory protection overrides, etc.).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
                items(entries, key = { "${it.key}::$refreshToken" }) { entry ->
                    if (entry is SettingsEntry.Section) {
                        RegularPreference(
                            title = entry.title,
                            subtitle = "${entry.children.size} item${if (entry.children.size == 1) "" else "s"}",
                            icon = sectionIcon(entry.key),
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
                }
            } else {
                // Inside a section: group leaves into a single card for the M3 look
                val sectionName = currentScreen.last()
                item {
                    PreferenceHeader(text = sectionName)
                }
                item {
                    PreferenceGroupCard {
                        entries.forEachIndexed { idx, entry ->
                            SettingsEntryRow(
                                entry = entry,
                                config = config,
                                originalConfig = originalConfig,
                                onMarkDirty = { block -> markDirtyAndRefresh(block) },
                                onImportDriver = { importDriverLauncher.launch("application/zip") }
                            )
                            if (idx < entries.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                )
                            }
                        }
                    }
                }
            }

            // Reset / use global config actions, only at root level
            if (currentScreen.isEmpty() && !isSearching) {
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
}

private class SettingsSession(var config: NativeEmulator.Config?) {
    var dirty: Boolean = false
}

private fun openConfigOrNull(path: String, libraryReady: Boolean): NativeEmulator.Config? {
    if (!libraryReady) return null
    return runCatching {
        val file = File(path)
        if (!file.exists()) {
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
private fun SettingsEntryRow(
    entry: SettingsEntry,
    config: NativeEmulator.Config?,
    originalConfig: NativeEmulator.Config?,
    onMarkDirty: ((NativeEmulator.Config) -> Unit) -> Unit,
    onImportDriver: () -> Unit
) {
    when (entry) {
        is SettingsEntry.Section -> {
            RegularPreference(
                title = entry.title,
                subtitle = null,
                icon = sectionIcon(entry.key),
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {}
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
                    onMarkDirty { it.save_config_entry(entry.key, newVal.toString()) }
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
                    onMarkDirty { it.save_config_entry(entry.key, newVal.toString()) }
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
                    onMarkDirty { it.save_config_entry(entry.key, newVal) }
                }
            )
        }
        is SettingsEntry.StrLeaf -> {
            val value = config?.load_config_entry(entry.key)
            if (entry.key == VULKAN_LIB_PATH_KEY) {
                DriverPathPreferenceRow(
                    title = entry.title,
                    value = value,
                    onImportClick = onImportDriver,
                    onValueChange = { newVal ->
                        onMarkDirty { it.save_config_entry(entry.key, newVal) }
                    },
                    onClear = {
                        onMarkDirty { it.save_config_entry(entry.key, "") }
                    }
                )
            } else {
                StrLeafPreferenceRow(
                    title = entry.title,
                    value = value,
                    onValueChange = { newVal ->
                        onMarkDirty { it.save_config_entry(entry.key, newVal) }
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchEntryRow(
    section: SettingsEntry.Section,
    entry: SettingsEntry,
    config: NativeEmulator.Config?,
    originalConfig: NativeEmulator.Config?,
    onMarkDirty: ((NativeEmulator.Config) -> Unit) -> Unit,
    onImportDriver: () -> Unit
) {
    SettingsEntryRow(
        entry = entry,
        config = config,
        originalConfig = originalConfig,
        onMarkDirty = onMarkDirty,
        onImportDriver = onImportDriver
    )
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
