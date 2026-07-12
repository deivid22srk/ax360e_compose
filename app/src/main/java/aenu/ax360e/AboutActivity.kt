package aenu.ax360e

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import aenu.ax360e.ui.screens.AboutScreen
import aenu.ax360e.ui.theme.Ax360eTheme

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ax360eTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}
