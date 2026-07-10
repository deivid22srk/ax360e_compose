package aenu.ax360e

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import aenu.ax360e.ui.screens.EmulatorSettingsScreen
import aenu.ax360e.ui.theme.Ax360eTheme

class EmulatorSettings : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
        setContent {
            Ax360eTheme {
                EmulatorSettingsScreen(
                    configPath = configPath,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_CONFIG_PATH = "config_path"
    }
}
