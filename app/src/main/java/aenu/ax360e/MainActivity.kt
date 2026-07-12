package aenu.ax360e

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import aenu.ax360e.ui.screens.MainScreen
import aenu.ax360e.ui.theme.Ax360eTheme

class MainActivity : ComponentActivity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!Application.device_support_vulkan()) {
            showDeviceUnsupportedDialog()
            return
        }

        // Always ensure the native library is ready before the UI is shown so
        // Settings can open the TOML config without requiring a prior game boot.
        // On Adreno 5xx/6xx we still delay slightly (driver quirk), but we never
        // leave Emulator.get null for the rest of the session.
        if (!Application.should_delay_load()) {
            Emulator.ensure_library_loaded()
            startUi()
            return
        }

        // Show loading dialog while native library loads asynchronously
        val dialog = ProgressTask.create_progress_dialog(this, getString(R.string.loading))
        dialog.show()
        Thread {
            try {
                Thread.sleep(500)
                Emulator.ensure_library_loaded()
                Thread.sleep(100)
                Handler(mainLooper).post {
                    dialog.dismiss()
                    startUi()
                }
            } catch (_: InterruptedException) {
                runOnUiThread {
                    dialog.dismiss()
                    // Still try to show UI; Settings will retry load if needed.
                    Emulator.ensure_library_loaded()
                    startUi()
                }
            }
        }.start()
    }

    private fun startUi() {
        setContent {
            Ax360eTheme {
                MainScreen()
            }
        }
    }

    private fun showDeviceUnsupportedDialog() {
        val ab = AlertDialog.Builder(this)
        ab.setPositiveButton(R.string.quit) { d, _ -> d.cancel(); finish() }
        val d: Dialog = ab.create()
        d.setCanceledOnTouchOutside(false)
        d.setOnKeyListener { _, _, _ -> true }
        d.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
