package aenu.ax360e

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import aenu.ax360e.ui.screens.EmulatorLogScreen
import aenu.ax360e.ui.theme.Ax360eTheme

/**
 * Activity hosting the EmulatorLogScreen.
 *
 * Launched from the MainScreen overflow menu. Shows the list of captured
 * per-game logs and allows viewing/copying/sharing/deleting them.
 */
class LogViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ax360eTheme {
                EmulatorLogScreen(onBack = { finish() })
            }
        }
    }
}
