package net.meshsat.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.WarningEntity
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.OffWhite
import net.meshsat.android.ui.theme.SpaceBlack
import net.meshsat.android.warnings.WarningIntake
import net.meshsat.android.warnings.WarningNotifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A public warning a kit relayed, while the app is open (MESHSAT-1734): the whole screen, over
 * whatever was there, until Dismiss. Red for a warning, amber for a test. Dismissing marks the
 * warning acknowledged; it still stands in the list until its cancel or a newer one.
 */
@Composable
fun WarningOverlay(onOpen: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val w by db.warningDao().unacknowledged().collectAsState(initial = null)
    val warning = w ?: return
    val color = if (warning.test) MeshSatAmber else MeshSatRed

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceBlack.copy(alpha = 0.94f))
            // Nothing under it takes a tap while it shows: the scrim takes them all, quietly.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .semantics { contentDescription = "Warning" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(MeshSatSurface, RoundedCornerShape(10.dp))
                .border(3.dp, color, RoundedCornerShape(10.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                WarningNotifier.title(warning),
                style = MaterialTheme.typography.headlineSmall,
                color = color,
            )
            if (warning.test) {
                Text("A test. No action is needed.", style = MaterialTheme.typography.bodyMedium, color = MeshSatAmber)
            }
            if (warning.text.isNotBlank()) {
                Text(warning.text, style = MaterialTheme.typography.bodyLarge, color = MeshSatTextPrimary)
            }
            if (warning.label.isNotBlank()) {
                Text(warning.label, style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
            }
            Text(WarningNotifier.relayLine(warning), style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
            Text("Heard ${clock(warning.receivedAt)}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { WarningIntake(db.warningDao()).acknowledge(warning.id) } },
                    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = SpaceBlack),
                ) { Text("Dismiss") }
                OutlinedButton(onClick = {
                    scope.launch { WarningIntake(db.warningDao()).acknowledge(warning.id) }
                    onOpen()
                }) { Text("All warnings", color = OffWhite) }
            }
        }
    }
}

/** Setup > Safety > Warnings: every relayed warning this phone heard, newest first. */
@Composable
fun WarningsScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val rows by db.warningDao().recent(200).collectAsState(initial = emptyList())

    if (rows.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No warning heard yet.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "A MeshSat kit in range puts the public warnings for its area on the mesh. This phone sounds " +
                    "the alarm for each one and keeps it here; a test warning says so and uses the normal sound.",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextSecondary,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.id }) { WarningRow(it) }
    }
}

@Composable
private fun WarningRow(w: WarningEntity) {
    val (state, color) = when {
        w.isCancel -> "All-clear" to MeshSatGreen
        w.cancelledAt != 0L -> "Cancelled ${clock(w.cancelledAt)}" to MeshSatTextMuted
        w.replacedAt != 0L -> "Replaced ${clock(w.replacedAt)}" to MeshSatTextMuted
        w.test -> "Test" to MeshSatAmber
        else -> "Standing" to MeshSatRed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, if (w.standing && !w.test) MeshSatRed else MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(WarningNotifier.title(w), style = MaterialTheme.typography.titleMedium, color = if (w.standing && !w.test) MeshSatRed else MeshSatTextPrimary)
            Text(state, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (w.text.isNotBlank()) Text(w.text, style = MaterialTheme.typography.bodyMedium, color = MeshSatTextPrimary)
        if (w.label.isNotBlank()) Text(w.label, style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
        Text(
            "${WarningNotifier.relayLine(w)}, ${if (w.port == 11) "alert" else "text"}, heard ${clock(w.receivedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

private fun clock(ms: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
