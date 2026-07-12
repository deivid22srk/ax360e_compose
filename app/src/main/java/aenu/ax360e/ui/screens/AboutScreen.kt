package aenu.ax360e.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import aenu.ax360e.Emulator
import aenu.ax360e.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    var aboutText by remember {
        mutableStateOf(
            runCatching { Emulator.get?.simple_device_info() ?: "" }.getOrDefault("")
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SportsEsports,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "0.15-compose · Material You",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { aboutText = context.getString(R.string.gratitude_content) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.gratitude))
                }
                FilledTonalButton(
                    onClick = { aboutText = getUpdateLog() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.update_log))
                }
                FilledTonalButton(
                    onClick = { showLicenses = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.open_source_licenses), maxLines = 1)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    text = aboutText.ifEmpty { stringResource(R.string.device_info) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) {
                    Text(android.R.string.ok.let { context.getString(it) })
                }
            },
            text = {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            loadUrl("file:///android_asset/licenses.html")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                )
            }
        )
    }
}

private fun getUpdateLog(): String {
    return """
        0.2 (2025-09-30)
         * First official release
        0.3 (2025-10-03)
         * Fix virtual keyboard crash
         * Fix joystick issue
         * Improve STFS format detection
         * Add settings screen (not yet usable)
        0.4 (2025-10-07)
         * Complete settings
         * Add virtual keyboard editor
         * Multi-language support
        0.6 (2025-10-29)
         * Switch APU, GPU, kernel to xenia canary
        0.8 (2025-11-21)
         * Fix default config
         * Update settings
        0.9 (2025-12-13)
         * Fix key mapping
         * Improve UI
        0.10 (2026-01-04)
         * Virtual keyboard optimization
         * Switch VFS to xenia canary
         * Settings improvements
        0.11 (2026-01-09)
         * Fix a64 backend issues
        0.12 (2026-01-31)
         * Partial fixes
        0.13 (2026-02-11)
         * Partial fixes
        0.14 (2026-03-11)
         * Optimizations and fixes
         * Fix gamepad joystick
         * Fix XEX format support
         * Add ZAR format support
        1.15 (2026-04-01)
         * Optimizations and fixes
         * Support non-touch devices
         * Custom driver support (Adreno only)
        1.16 (2026-05-15)
         * Switch to xenia canary
         * Partial fixes
        1.17 (2026-06-20)
         * Partial fixes

        0.15-compose (2026-07)
         * Complete UI port to Jetpack Compose
         * Material 3 Expressive design system
         * No more XML layouts

        0.16-compose (2026-07)
         * Material You redesign inspired by RPCSX UI
         * Navigation drawer + game grid library
         * Preference-style settings components
         * Pull-to-refresh game list
    """.trimIndent()
}
