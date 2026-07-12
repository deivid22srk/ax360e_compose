package aenu.ax360e.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import aenu.ax360e.mcp.McpBridgeClient
import aenu.ax360e.mcp.McpBridgeService
import aenu.ax360e.ui.components.preference.PreferenceHeader
import aenu.ax360e.ui.components.preference.RegularPreference
import aenu.ax360e.ui.components.preference.SwitchPreference

/**
 * Screen for configuring the AI Remote Control bridge.
 *
 * The user can:
 *  - Toggle the master switch on/off (starts/stops [McpBridgeService])
 *  - Edit the bridge URL (defaults to the public Render deployment)
 *  - Edit the API key (must match the BRIDGE_API_KEY env var on Render)
 *  - View connection status / open the Render dashboard
 *
 * When the user toggles the switch on for the first time on Android 13+,
 * the POST_NOTIFICATIONS permission is requested so the foreground-service
 * notification can be shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRemoteControlScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember {
        mutableStateOf(McpBridgeClient.isEnabled(context))
    }
    var bridgeUrl by remember {
        mutableStateOf(McpBridgeClient.getBridgeUrl(context))
    }
    var apiKey by remember {
        mutableStateOf(McpBridgeClient.getBridgeApiKey(context))
    }
    var showApiKey by remember { mutableStateOf(false) }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted
        if (!granted) {
            Toast.makeText(
                context,
                "Notifications permission denied — bridge will still run but you won't see the foreground notification.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun applyEnabled(next: Boolean) {
        enabled = next
        McpBridgeClient.setEnabled(context, next)
        if (next) {
            // Save current URL/key before starting service
            McpBridgeClient.setBridgeUrl(context, bridgeUrl)
            McpBridgeClient.setBridgeApiKey(context, apiKey)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (apiKey.isBlank()) {
                Toast.makeText(
                    context,
                    "API key is empty — set it before enabling the bridge",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            McpBridgeService.applyEnabledState(context, true)
            Toast.makeText(context, "AI Remote Control enabled", Toast.LENGTH_SHORT).show()
        } else {
            McpBridgeService.applyEnabledState(context, false)
            Toast.makeText(context, "AI Remote Control disabled", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveConfig() {
        McpBridgeClient.setBridgeUrl(context, bridgeUrl)
        McpBridgeClient.setBridgeApiKey(context, apiKey)
        Toast.makeText(context, "Saved. Restart the bridge to apply.", Toast.LENGTH_SHORT).show()
        // If the bridge is currently running, restart it so it picks up the new URL/key.
        if (enabled) {
            McpBridgeService.applyEnabledState(context, false)
            McpBridgeService.applyEnabledState(context, true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Remote Control") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item { PreferenceHeader(text = "Connection") }

            item {
                SwitchPreference(
                    title = "Enable AI Remote Control",
                    subtitle = if (enabled) "Bridge is active" else "Bridge is stopped",
                    icon = Icons.Default.PowerSettingsNew,
                    checked = enabled,
                    onCheckedChange = { applyEnabled(it) }
                )
            }

            item {
                HorizontalDivider()
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = bridgeUrl,
                        onValueChange = { bridgeUrl = it },
                        label = { Text("Bridge URL") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "The Render-hosted MCP bridge. Leave as default unless you self-host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        trailingIcon = {
                            TextButton(
                                onClick = { showApiKey = !showApiKey }
                            ) {
                                Text(if (showApiKey) "HIDE" else "SHOW")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Button(
                    onClick = { saveConfig() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("Save and Restart Bridge")
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                PreferenceHeader(text = "Help")
            }

            item {
                RegularPreference(
                    title = "What is this?",
                    subtitle = "Tap to open the bridge dashboard",
                    icon = Icons.Default.Cloud,
                    onClick = {
                        val url = McpBridgeClient.getBridgeUrl(context)
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(intent, "Open bridge dashboard").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }.onFailure {
                            Toast.makeText(context, "No browser app available", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = """
                            The AI Remote Control bridge lets an AI assistant (using the Model Context Protocol) connect to this device through a Render-hosted relay service. When enabled:

                            • The app maintains a WebSocket connection to the bridge.
                            • The AI can list games, open games, view live logs, send key events, and read the current settings.
                            • The bridge only works when both the app and the AI present the same API key.

                            Privacy: the bridge relays whatever the AI asks for. Disable it whenever you're not actively debugging. The WebSocket runs in a foreground service so it survives backgrounding; a persistent low-priority notification will be visible.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
                    Button(
                        onClick = {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text("Grant notification permission")
                    }
                }
            }

            item {
                TextButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("Open Android notification settings")
                }
            }
        }
    }
}
