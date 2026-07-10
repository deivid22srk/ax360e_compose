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

        if (!Application.should_delay_load()) {
            startUi()
            return
        }

        // Show loading dialog while native library loads asynchronously
        val dialog = ProgressTask.create_progress_dialog(this, getString(R.string.loading))
        dialog.show()
        Thread {
            try {
                Thread.sleep(500)
                Emulator.load_library()
                Thread.sleep(100)
                Handler(mainLooper).post {
                    dialog.dismiss()
                    startUi()
                }
            } catch (_: InterruptedException) {
                runOnUiThread { dialog.dismiss() }
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
