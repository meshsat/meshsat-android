package net.meshsat.android.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.PlexMono
import net.meshsat.android.ui.theme.SignalOrange

/** Where a way out stands. Not set up is [Off], never [Failed]: grey, not red. */
enum class LaneState { Working, Trying, Off, Failed }

/**
 * One way a message can leave the phone (MESHSAT-1249), drawn in the language of the MeshSat mark:
 * a working route is a solid line like the mesh's edges, an unreachable one is dotted like the orbit,
 * and a message on its way is the orange terminal dot, travelling along the line. Orange appears
 * nowhere else on the lane: it means live traffic, as on the Bridge's booth screen.
 */
@Composable
fun TransportLane(
    icon: ImageVector,
    name: String,
    color: Color,
    state: LaneState,
    metric: String?,
    detail: String,
    inFlight: Boolean,
    onClick: () -> Unit,
) {
    val tint = when (state) {
        LaneState.Working -> color
        LaneState.Trying -> color.copy(alpha = 0.75f)
        LaneState.Off -> MeshSatTextMuted
        LaneState.Failed -> MeshSatRed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (metric != null) {
                    Text(
                        text = metric,
                        fontFamily = PlexMono,
                        fontSize = 14.sp,
                        color = if (state == LaneState.Working) color else MeshSatTextSecondary,
                    )
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            LaneLine(state = state, color = color, inFlight = inFlight)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MeshSatTextMuted)
    }
}

/** The line under a lane: solid, dashed or dotted, with the orange dot while a message is on its way. */
@Composable
fun LaneLine(state: LaneState, color: Color, inFlight: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Respect "Remove animations": the dot then waits at a fixed place instead of moving.
    val reduceMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val travel = if (inFlight && !reduceMotion) {
        val t = rememberInfiniteTransition(label = "lane")
        val f by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
            label = "dot",
        )
        f
    } else {
        0.7f
    }
    val described = when (state) {
        LaneState.Working -> "working"
        LaneState.Trying -> "trying"
        LaneState.Off -> "not available"
        LaneState.Failed -> "not working"
    } + if (inFlight) ", a message is on its way" else ""
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .semantics { contentDescription = described },
    ) {
        val y = size.height / 2
        val stroke = 2.dp.toPx()
        when (state) {
            LaneState.Working -> drawLine(color.copy(alpha = 0.9f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            LaneState.Trying -> drawLine(
                color.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())),
            )
            LaneState.Off, LaneState.Failed -> {
                val dot = if (state == LaneState.Failed) MeshSatRed else MeshSatTextMuted
                val step = 9.dp.toPx()
                var x = stroke
                while (x < size.width) {
                    drawCircle(dot, radius = 1.6.dp.toPx(), center = Offset(x, y))
                    x += step
                }
            }
        }
        if (inFlight) {
            val r = 4.5.dp.toPx()
            drawCircle(SignalOrange, radius = r, center = Offset(r + (size.width - 2 * r) * travel, y))
        }
    }
}
