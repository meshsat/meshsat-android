package net.meshsat.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.meshsat.android.ui.theme.OffWhite
import kotlin.math.ceil

/**
 * A button that acts only after it has been held for [holdMs] (MESHSAT-1249): the SOS. It fills from
 * the left while held, counts the seconds down, and empties again if let go early, so a pocket or a
 * stray tap sends nothing. With TalkBack, where holding is awkward, its click action calls
 * [onAccessibleActivate] instead, which asks for a confirmation.
 */
@Composable
fun HoldToSendButton(
    label: String,
    color: Color,
    onComplete: () -> Unit,
    onAccessibleActivate: () -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Long = 3_000L,
) {
    val progress = remember { Animatable(0f) }
    val complete by rememberUpdatedState(onComplete)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val left = ceil((1f - progress.value) * holdMs / 1000f).toInt().coerceAtLeast(1)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .drawBehind {
                drawRect(color.copy(alpha = 0.55f), size = Size(size.width * progress.value, size.height))
            }
            .border(2.dp, color, shape)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) {
                    onAccessibleActivate()
                    true
                }
            }
            .pointerInput(holdMs) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // Timed by the clock, not by an animation: with the system's animations
                        // turned off an animation ends at once, and a tap would send.
                        val fill = scope.launch {
                            val start = SystemClock.elapsedRealtime()
                            while (true) {
                                val p = ((SystemClock.elapsedRealtime() - start).toFloat() / holdMs).coerceAtMost(1f)
                                progress.snapTo(p)
                                if (p >= 1f) break
                                delay(16)
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            complete()
                            progress.snapTo(0f)
                        }
                        tryAwaitRelease()
                        holding = false
                        if (fill.isActive) {
                            fill.cancel()
                            scope.launch { progress.animateTo(0f, tween(durationMillis = 250)) }
                        }
                    },
                )
            },
    ) {
        Text(
            text = if (holding) "Keep holding: $left" else label,
            style = MaterialTheme.typography.titleMedium,
            color = if (progress.value > 0.5f) OffWhite else color,
        )
    }
}
