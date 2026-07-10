package aenu.ax360e

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import aenu.ax360e.ui.screens.KeyMapScreen
import aenu.ax360e.ui.theme.Ax360eTheme

class KeyMapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ax360eTheme {
                KeyMapScreen(onBack = { finish() })
            }
        }
    }
}
