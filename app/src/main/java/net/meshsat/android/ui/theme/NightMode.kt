package net.meshsat.android.ui.theme

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import net.meshsat.android.data.SettingsRepository

/**
 * Night mode (MESHSAT-1249): the whole window in red only, dimmed, the way a red head torch keeps
 * night vision. It is a filter on the window rather than a second palette, so it reaches everything
 * drawn in it: the fixed brand and transport colours, the map tiles and the images.
 */
object NightMode {
    /** Each pixel's brightness (Rec. 601 weights at 80 %) into red; green and blue off. */
    private val RED_ONLY = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.24f, 0.47f, 0.09f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    fun apply(root: View, on: Boolean) {
        if (on) root.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = RED_ONLY })
        else root.setLayerType(View.LAYER_TYPE_NONE, null)
    }
}

/** Keeps the window's filter in step with the saved setting. */
@Composable
fun NightModeEffect() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val night by settings.nightMode.collectAsState(initial = false)
    val view = LocalView.current
    LaunchedEffect(night) { NightMode.apply(view.rootView, night) }
}
