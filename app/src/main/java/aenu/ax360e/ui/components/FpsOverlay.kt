package aenu.ax360e.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import aenu.ax360e.Emulator
import aenu.ax360e.ui.model.UiPreferences
import kotlinx.coroutines.delay

/**
 * Live FPS overlay rendered on top of the emulator surface.
 *
 * Polls [Emulator.get_guest_frame_counter] (which returns the xenia-canary
 * GraphicsSystem vblank counter) twice with a 500ms interval and computes
 * frames-per-second = (delta_counter / delta_time_seconds).
 *
 * The overlay is only visible when the user has enabled it via the main
 * screen overflow menu (UiPreferences.isFpsVisible).
 */
@Composable
fun FpsOverlay(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val visible = UiPreferences.isFpsVisible(context)
    if (!visible) return

    var fps by remember { mutableFloatStateOf(0f) }
    var lastCounter by remember { mutableLongStateOf(0L) }
    var lastTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        // Initial sample
        lastCounter = Emulator.get?.get_guest_frame_counter()?.toLong() ?: 0L
        lastTime = SystemClock.elapsedRealtimeNanos()
        while (true) {
            delay(500)
            val currentCounter = Emulator.get?.get_guest_frame_counter()?.toLong() ?: 0L
            val currentTime = SystemClock.elapsedRealtimeNanos()
            val deltaFrames = currentCounter - lastCounter
            val deltaTimeSec = (currentTime - lastTime) / 1_000_000_000.0
            if (deltaTimeSec > 0) {
                fps = (deltaFrames / deltaTimeSec).toFloat()
            }
            lastCounter = currentCounter
            lastTime = currentTime
        }
    }

    Box(
        modifier = modifier
            .padding(8.dp)
            .background(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (fps > 0) String.format("%.0f FPS", fps) else "-- FPS",
            color = if (fps >= 55f) Color(0xFF4CDADA)
            else if (fps >= 30f) Color(0xFFFFD54F)
            else Color(0xFFFF8A80),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp
        )
    }
}
