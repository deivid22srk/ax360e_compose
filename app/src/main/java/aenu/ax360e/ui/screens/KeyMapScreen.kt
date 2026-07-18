package aenu.ax360e.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import aenu.ax360e.KeyMapConfig
import aenu.ax360e.R
import aenu.ax360e.ui.components.preference.PreferenceGroupCard
import aenu.ax360e.ui.components.preference.PreferenceHeader
import aenu.ax360e.ui.components.preference.SwitchPreference
import aenu.ax360e.ui.components.preference.ValuePreference

private data class KeyMapEntry(
    val nameResId: Int,
    val defaultKeyCode: Int
)

/** Logical group for the rebinding list — improves scannability. */
private enum class KeyMapGroup(val title: String) {
    DPAD("D-pad"),
    FACE("Face buttons (A / B / X / Y)"),
    MENU("Menu"),
    BUMPERS("Bumpers & Triggers"),
    STICKS("Thumbsticks")
}

private val KEY_MAP_ENTRIES: List<Pair<KeyMapGroup, KeyMapEntry>> = listOf(
    KeyMapGroup.DPAD to KeyMapEntry(R.string.left, KeyEvent.KEYCODE_DPAD_LEFT),
    KeyMapGroup.DPAD to KeyMapEntry(R.string.up, KeyEvent.KEYCODE_DPAD_UP),
    KeyMapGroup.DPAD to KeyMapEntry(R.string.right, KeyEvent.KEYCODE_DPAD_RIGHT),
    KeyMapGroup.DPAD to KeyMapEntry(R.string.down, KeyEvent.KEYCODE_DPAD_DOWN),

    KeyMapGroup.FACE to KeyMapEntry(R.string.a, 96),
    KeyMapGroup.FACE to KeyMapEntry(R.string.b, 97),
    KeyMapGroup.FACE to KeyMapEntry(R.string.x, 99),
    KeyMapGroup.FACE to KeyMapEntry(R.string.y, 100),

    KeyMapGroup.MENU to KeyMapEntry(R.string.back, 109),
    KeyMapGroup.MENU to KeyMapEntry(R.string.start, 108),

    KeyMapGroup.BUMPERS to KeyMapEntry(R.string.lshoulder, 102),
    KeyMapGroup.BUMPERS to KeyMapEntry(R.string.rshoulder, 103),
    KeyMapGroup.BUMPERS to KeyMapEntry(R.string.ltrigger, 0),
    KeyMapGroup.BUMPERS to KeyMapEntry(R.string.rtrigger, 0),

    KeyMapGroup.STICKS to KeyMapEntry(R.string.lthumbpress, 104),
    KeyMapGroup.STICKS to KeyMapEntry(R.string.rthumbpress, 105)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyMapScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var keyValues by remember { mutableStateOf(loadKeyValues(prefs)) }
    var vibratorEnabled by remember {
        mutableStateOf(prefs.getBoolean("enable_vibrator", false))
    }
    var waitingForKeyIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.key_mappers),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    PreferenceHeader(text = stringResource(R.string.key_mappers))
                }

                // Grouped cards per category
                KeyMapGroup.entries.forEach { group ->
                    val groupEntries = KEY_MAP_ENTRIES.mapIndexedNotNull { idx, pair ->
                        if (pair.first == group) idx to pair.second else null
                    }
                    if (groupEntries.isEmpty()) return@forEach

                    item {
                        PreferenceGroupCard {
                            groupEntries.forEachIndexed { pos, (idx, entry) ->
                                val keyCode = keyValues[idx]
                                ValuePreference(
                                    title = stringResource(entry.nameResId),
                                    value = if (keyCode == 0) "—" else keyCode.toString(),
                                    onClick = { waitingForKeyIndex = idx },
                                    subtitle = "Tap to rebind",
                                    icon = Icons.Default.Gamepad
                                )
                                if (pos < groupEntries.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    PreferenceHeader(text = "Feedback")
                }
                item {
                    PreferenceGroupCard {
                        SwitchPreference(
                            title = stringResource(R.string.enable_vibrator),
                            checked = vibratorEnabled,
                            onCheckedChange = {
                                vibratorEnabled = it
                                prefs.edit().putBoolean("enable_vibrator", it).apply()
                            },
                            icon = Icons.Default.Vibration
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    val editor = prefs.edit()
                    KEY_MAP_ENTRIES.forEachIndexed { i, e ->
                        editor.putInt(KeyMapConfig.KEY_NAMEIDS[i].toString(), e.second.defaultKeyCode)
                    }
                    editor.apply()
                    keyValues = loadKeyValues(prefs)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.reset_as_default))
            }
        }
    }

    waitingForKeyIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { waitingForKeyIndex = null },
            title = { Text(stringResource(R.string.press_a_key)) },
            text = { Text("Press a hardware key to bind, or Clear to remove the binding.") },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    prefs.edit()
                        .putInt(KeyMapConfig.KEY_NAMEIDS[idx].toString(), 0)
                        .apply()
                    keyValues = loadKeyValues(prefs)
                    waitingForKeyIndex = null
                }) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.clear))
                }
            },
            modifier = Modifier.onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    val code = ev.key.nativeKeyCode
                    if (code != 0 && code != KeyEvent.KEYCODE_BACK) {
                        prefs.edit()
                            .putInt(KeyMapConfig.KEY_NAMEIDS[idx].toString(), code)
                            .apply()
                        keyValues = loadKeyValues(prefs)
                        waitingForKeyIndex = null
                        true
                    } else false
                } else false
            }
        )
    }
}

private fun loadKeyValues(prefs: android.content.SharedPreferences): IntArray {
    return IntArray(KeyMapConfig.KEY_NAMEIDS.size) { i ->
        prefs.getInt(
            KeyMapConfig.KEY_NAMEIDS[i].toString(),
            KeyMapConfig.DEFAULT_KEYMAPPERS[i]
        )
    }
}
