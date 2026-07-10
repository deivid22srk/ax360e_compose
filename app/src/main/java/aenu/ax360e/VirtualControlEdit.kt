package aenu.ax360e

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import aenu.ax360e.ui.theme.Ax360eTheme

class VirtualControlEdit : ComponentActivity() {

    private var vc: VirtualControl? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Utils.enable_fullscreen(window)
        enableEdgeToEdge()

        setContent {
            Ax360eTheme {
                VirtualControlEditScreen(
                    onVcCreated = { vc = it },
                    onShowMenu = { showEditMenu() }
                )
            }
        }
    }

    private var menuVisible = false

    private fun showEditMenu() {
        menuVisible = true
        // Re-compose to show modal sheet
        setContent {
            Ax360eTheme {
                VirtualControlEditScreen(
                    onVcCreated = { vc = it },
                    onShowMenu = {},
                    showMenuInitial = true
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VirtualControlEditScreen(
        onVcCreated: (VirtualControl) -> Unit,
        onShowMenu: () -> Unit,
        showMenuInitial: Boolean = false
    ) {
        var showMenu by remember { mutableStateOf(showMenuInitial) }
        val context = LocalContext.current

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    VirtualControl.Edit(ctx as android.app.Activity).also { vc ->
                        onVcCreated(vc)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Floating button to open menu
            androidx.compose.material3.FloatingActionButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Text("≡")
            }
        }

        if (showMenu) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                sheetState = sheetState
            ) {
                EditMenuContent(
                    vc = vc,
                    onClose = { showMenu = false },
                    onSaveQuit = {
                        vc?.save_config(Application.get_virtual_control_config_file())
                        finish()
                    }
                )
            }
        }
    }

    @Composable
    private fun EditMenuContent(
        vc: VirtualControl?,
        onClose: () -> Unit,
        onSaveQuit: () -> Unit
    ) {
        val context = LocalContext.current
        val scaleText = stringResource(R.string.scale_rate) + ": "

        var joystickScale by remember { mutableFloatStateOf(vc?.find_component("joystick_l")?.get_scale()?.toFloat() ?: 1f) }
        var dpadScale by remember { mutableFloatStateOf(vc?.find_component("dpad")?.get_scale()?.toFloat() ?: 1f) }
        var abxyScale by remember { mutableFloatStateOf(vc?.find_component("a")?.get_scale()?.toFloat() ?: 1f) }
        var sbScale by remember { mutableFloatStateOf(vc?.find_component("start")?.get_scale()?.toFloat() ?: 1f) }
        var lrScale by remember { mutableFloatStateOf(vc?.find_component("shoulder_l")?.get_scale()?.toFloat() ?: 1f) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.joystick), style = MaterialTheme.typography.titleMedium)
            Text("$scaleText$joystickScale", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = joystickScale,
                onValueChange = { joystickScale = it },
                valueRange = 0.2f..3f,
                onValueChangeFinished = {
                    vc?.find_component("joystick_l")?.set_scale(joystickScale.toDouble())
                    vc?.find_component("joystick_r")?.set_scale(joystickScale.toDouble())
                    vc?.invalidate()
                }
            )

            Text(stringResource(R.string.dpad), style = MaterialTheme.typography.titleMedium)
            Text("$scaleText$dpadScale", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = dpadScale,
                onValueChange = { dpadScale = it },
                valueRange = 0.2f..3f,
                onValueChangeFinished = {
                    vc?.find_component("dpad")?.set_scale(dpadScale.toDouble())
                    vc?.invalidate()
                }
            )

            Text(stringResource(R.string.abxy), style = MaterialTheme.typography.titleMedium)
            Text("$scaleText$abxyScale", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = abxyScale,
                onValueChange = { abxyScale = it },
                valueRange = 0.2f..3f,
                onValueChangeFinished = {
                    listOf("a", "b", "x", "y").forEach {
                        vc?.find_component(it)?.set_scale(abxyScale.toDouble())
                    }
                    vc?.invalidate()
                }
            )

            Text(stringResource(R.string.start_back), style = MaterialTheme.typography.titleMedium)
            Text("$scaleText$sbScale", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = sbScale,
                onValueChange = { sbScale = it },
                valueRange = 0.2f..3f,
                onValueChangeFinished = {
                    vc?.find_component("start")?.set_scale(sbScale.toDouble())
                    vc?.find_component("back")?.set_scale(sbScale.toDouble())
                    vc?.invalidate()
                }
            )

            Text(stringResource(R.string.lr), style = MaterialTheme.typography.titleMedium)
            Text("$scaleText$lrScale", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = lrScale,
                onValueChange = { lrScale = it },
                valueRange = 0.2f..3f,
                onValueChangeFinished = {
                    listOf("shoulder_l", "shoulder_r", "thumb_press_l", "thumb_press_r", "trigger_l", "trigger_r").forEach {
                        vc?.find_component(it)?.set_scale(lrScale.toDouble())
                    }
                    vc?.invalidate()
                }
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        vc?.load_config(vc?.default_config())
                        vc?.invalidate()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.reset_as_default))
                }
                Button(
                    onClick = onSaveQuit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.save_and_quit))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Trigger menu via recomposition
        showEditMenu()
    }
}
