package aenu.ax360e

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.PreferenceManager
import android.util.SparseIntArray
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import aenu.ax360e.ui.theme.Ax360eTheme

class EmulatorActivity : Activity(), SurfaceHolder.Callback, View.OnGenericMotionListener {

    companion object {
        const val EXTRA_GAME_URI = "game_uri"
        private const val DELAY_ON_CREATE = 0xaeae0001
    }

    private var surfaceView: SurfaceView? = null
    private var virtualControl: VirtualControl? = null
    private val keysMap = SparseIntArray()
    private var vibrator: Vibrator? = null
    private var vibrationEffect: VibrationEffect? = null
    private var started = false
    private var delayDialog: Dialog? = null

    private val delayOnCreate = Handler(Handler.Callback { msg ->
        if (msg.what != DELAY_ON_CREATE) return@Callback false
        delayDialog?.dismiss()
        delayDialog = null
        on_create()
        true
    })

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (delayDialog != null) return
        AlertDialog.Builder(this)
            .setPositiveButton(R.string.quit) { d, _ -> d.cancel(); finish() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Utils.enable_fullscreen(window)

        if (!Application.should_delay_load()) {
            on_create()
            return
        }

        delayDialog = ProgressTask.create_progress_dialog(this, getString(R.string.loading))
        delayDialog?.show()
        Thread {
            try {
                Thread.sleep(500)
                Emulator.load_library()
                Thread.sleep(100)
                delayOnCreate.sendEmptyMessage(DELAY_ON_CREATE)
            } catch (_: InterruptedException) {
            }
        }.start()
    }

    private fun on_create() {
        val uri = intent.getStringExtra(EXTRA_GAME_URI) ?: return
        val path = aenu.emulator.Emulator.Path.from(uri, -1)
        Emulator.get.setup_context(this)
        Emulator.get.setup_document_file_tree(
            DocumentFile.fromTreeUri(this, GameListLoaderHolder.loadGameDir(this) ?: return)
        )
        Emulator.get.setup_game_path(path)
        Emulator.get.setup_launch_args(
            arrayOf(
                "--storage_root=" + Application.get_app_data_dir().absolutePath,
                "--config=" + Application.get_global_config_file().absolutePath,
                "--log_file=" + Application.get_app_data_dir().absolutePath + "/xe.log"
            )
        )
        Emulator.get.setup_uri_info_list_file(Application.get_uri_info_list_file().absolutePath)

        setContent {
            Ax360eTheme {
                EmulatorScreen(
                    onSurfaceCreated = { sv ->
                        surfaceView = sv
                        sv.holder.addCallback(this@EmulatorActivity)
                        sv.isFocusable = true
                        sv.isFocusableInTouchMode = true
                        sv.requestFocus()
                        sv.setOnGenericMotionListener(this@EmulatorActivity)
                    },
                    onVirtualControlCreated = { vc ->
                        virtualControl = vc
                    }
                )
            }
        }

        loadKeyMapAndVibrator()
    }

    private fun loadKeyMapAndVibrator() {
        val sPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        keysMap.clear()
        for (i in KeyMapConfig.KEY_NAMEIDS.indices) {
            val keyName = KeyMapConfig.KEY_NAMEIDS[i].toString()
            val keyCode = sPrefs.getInt(keyName, KeyMapConfig.DEFAULT_KEYMAPPERS[i])
            keysMap.put(keyCode, KeyMapConfig.KEY_VALUES[i])
        }
        if (sPrefs.getBoolean("enable_vibrator", false)) {
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrationEffect = VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private fun vibrate() {
        vibrator?.vibrate(vibrationEffect)
    }

    override fun onDestroy() {
        super.onDestroy()
        System.exit(0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val gameKey = keysMap.get(keyCode, -1)
        if (gameKey == -1) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            vibrate()
            Emulator.get.key_event(gameKey, true, VirtualControl.KEY_VALUE_UNUSED)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val gameKey = keysMap.get(keyCode, -1)
        if (gameKey != -1) {
            Emulator.get.key_event(gameKey, false, VirtualControl.KEY_VALUE_UNUSED)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!started) {
            started = true
            Emulator.get.setup_surface(holder.surface)
            try {
                Emulator.get.boot()
            } catch (e: aenu.emulator.Emulator.BootException) {
                throw RuntimeException(e)
            }
        } else {
            Emulator.get.setup_surface(holder.surface)
            Emulator.get.surface_changed()
            if (Emulator.get.is_paused) Emulator.get.resume()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!started) return
        if (width == 0 || height == 0) return
        Emulator.get.change_surface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!started) return
        Emulator.get.setup_surface(null)
    }

    private fun handleDpad(event: InputEvent): Boolean {
        var pressed = false
        if (event is MotionEvent) {
            val xaxis = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val yaxis = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (xaxis == -1.0f) {
                Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_LEFT, true, VirtualControl.KEY_VALUE_UNUSED)
                Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_RIGHT, false, VirtualControl.KEY_VALUE_UNUSED)
                vibrate(); pressed = true
            } else if (xaxis == 1.0f) {
                Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_RIGHT, true, VirtualControl.KEY_VALUE_UNUSED)
                Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_LEFT, false, VirtualControl.KEY_VALUE_UNUSED)
                vibrate(); pressed = true
            }
            if (yaxis == -1.0f) {
                Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_UP, true, VirtualControl.KEY_VALUE_UNUSED)
                Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_DOWN, false, VirtualControl.KEY_VALUE_UNUSED)
                vibrate(); pressed = true
            } else if (yaxis == 1.0f) {
                Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_DOWN, true, VirtualControl.KEY_VALUE_UNUSED)
                Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_UP, false, VirtualControl.KEY_VALUE_UNUSED)
                vibrate(); pressed = true
            }
        } else if (event is KeyEvent) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_LEFT, true, VirtualControl.KEY_VALUE_UNUSED)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_RIGHT, false, VirtualControl.KEY_VALUE_UNUSED)
                    vibrate(); pressed = true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_RIGHT, true, VirtualControl.KEY_VALUE_UNUSED)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_LEFT, false, VirtualControl.KEY_VALUE_UNUSED)
                    vibrate(); pressed = true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_UP, true, VirtualControl.KEY_VALUE_UNUSED)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_DOWN, false, VirtualControl.KEY_VALUE_UNUSED)
                    vibrate(); pressed = true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_DOWN, true, VirtualControl.KEY_VALUE_UNUSED)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_UP, false, VirtualControl.KEY_VALUE_UNUSED)
                    vibrate(); pressed = true
                }
            }
        }
        if (pressed) return true
        Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_LEFT, false, VirtualControl.KEY_VALUE_UNUSED)
        Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_UP, false, VirtualControl.KEY_VALUE_UNUSED)
        Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_RIGHT, false, VirtualControl.KEY_VALUE_UNUSED)
        Emulator.get.key_event(VirtualControl.KEY_CODE_DPAD_DOWN, false, VirtualControl.KEY_VALUE_UNUSED)
        return false
    }

    private fun isDpadDevice(event: MotionEvent): Boolean {
        return event.source and InputDevice.SOURCE_DPAD != InputDevice.SOURCE_DPAD
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        if (isDpadDevice(event) && handleDpad(event)) return true
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            val laxisX = event.getAxisValue(MotionEvent.AXIS_X)
            val laxisY = event.getAxisValue(MotionEvent.AXIS_Y)
            val raxisX = event.getAxisValue(MotionEvent.AXIS_Z)
            val raxisY = event.getAxisValue(MotionEvent.AXIS_RZ)
            val _0: Short = 0

            // Left joystick
            if (laxisX != 0f) {
                if (laxisX < 0) {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_RIGHT, false, _0)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_LEFT, true, (laxisX * 32768.0f).toInt().toShort())
                } else {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_LEFT, false, _0)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_RIGHT, true, (Math.abs(laxisX) * 32767.0f).toInt().toShort())
                }
            } else {
                Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_RIGHT, false, _0)
                Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_LEFT, false, _0)
            }
            if (laxisY != 0f) {
                if (laxisY < 0) {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_DOWN, false, _0)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_UP, true, ((-laxisY) * 32767.0f).toInt().toShort())
                } else {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_UP, false, _0)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_DOWN, true, ((-laxisY) * 32768.0f).toInt().toShort())
                }
            } else {
                Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_DOWN, false, _0)
                Emulator.get.key_event(VirtualControl.KEY_CODE_LTHUMB_UP, false, _0)
            }

            // Right joystick
            if (raxisX != 0f) {
                if (raxisX < 0) {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_RIGHT, false, _0)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_LEFT, true, (raxisX * 32768.0f).toInt().toShort())
                } else {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_LEFT, false, _0)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_RIGHT, true, (raxisX * 32767.0f).toInt().toShort())
                }
            } else {
                Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_RIGHT, false, _0)
                Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_LEFT, false, _0)
            }
            if (raxisY != 0f) {
                if (raxisY < 0) {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_DOWN, false, _0)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_UP, true, ((-raxisY) * 32767.0f).toInt().toShort())
                } else {
                    Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_UP, false, _0)
                    Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_DOWN, true, ((-raxisY) * 32768.0f).toInt().toShort())
                }
            } else {
                Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_DOWN, false, _0)
                Emulator.get.key_event(VirtualControl.KEY_CODE_RTHUMB_UP, false, _0)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}

@Composable
private fun EmulatorScreen(
    onSurfaceCreated: (SurfaceView) -> Unit,
    onVirtualControlCreated: (VirtualControl) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    onSurfaceCreated(sv)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        AndroidView(
            factory = { ctx ->
                VirtualControl(ctx).also { vc ->
                    onVirtualControlCreated(vc)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Bridge to GameListLoader (Kotlin side) for backward compatibility with old Java calls.
 */
private object GameListLoaderHolder {
    fun loadGameDir(activity: Activity): android.net.Uri? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val uriStr = prefs.getString("game_dir", null) ?: return null
        val uri = android.net.Uri.parse(uriStr)
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        return uri
    }
}
