package aenu.ax360e

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import aenu.ax360e.ui.screens.AiRemoteControlScreen
import aenu.ax360e.ui.theme.Ax360eTheme

/**
 * Hosts [AiRemoteControlScreen]. Reached from the main screen overflow menu
 * ("AI Remote Control") so the user can configure / toggle the bridge.
 */
class AiRemoteControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ax360eTheme {
                AiRemoteControlScreen(onBack = { finish() })
            }
        }
    }
}
