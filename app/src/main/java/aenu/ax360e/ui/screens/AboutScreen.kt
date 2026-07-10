package aenu.ax360e.ui.screens

import android.app.Activity
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import aenu.ax360e.Emulator
import aenu.ax360e.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    var aboutText by remember { mutableStateOf("") }

    // Lazy-load device info on first composition
    if (aboutText.isEmpty()) {
        aboutText = runCatching {
            Emulator.get?.simple_device_info() ?: ""
        }.getOrDefault("")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = {
                    aboutText = context.getString(R.string.gratitude_content)
                }) {
                    Text(stringResource(R.string.gratitude))
                }
                TextButton(onClick = {
                    aboutText = getUpdateLog()
                }) {
                    Text(stringResource(R.string.update_log))
                }
                TextButton(onClick = { showLicenses = true }) {
                    Text(stringResource(R.string.open_source_licenses))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = aboutText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
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
    """.trimIndent()
}
