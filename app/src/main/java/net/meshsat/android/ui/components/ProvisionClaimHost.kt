package net.meshsat.android.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.meshsat.android.crypto.ProvisionClaim
import net.meshsat.android.ui.theme.MeshSatInk
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted

/**
 * Seconds since [startedMs], ticking once a second while shown.
 */
@Composable
fun waitedSeconds(startedMs: Long): Long {
    val now by produceState(System.currentTimeMillis(), startedMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    return ((now - startedMs) / 1000).coerceAtLeast(0)
}

/**
 * Shows the provisioning claim wherever the person is in the app (MESHSAT-1306): the wait
 * for the Hub, the credentials to confirm, and the outcome.
 */
@Composable
fun ProvisionClaimHost() {
    val context = LocalContext.current
    val state by ProvisionClaim.state.collectAsState()
    // "Hide" puts the wait away; the Hub card in Setup keeps showing it.
    var hiddenSince by remember { mutableStateOf(0L) }

    when (val s = state) {
        is ProvisionClaim.State.Idle -> Unit

        is ProvisionClaim.State.Waiting -> if (hiddenSince != s.startedMs) {
            val waited = waitedSeconds(s.startedMs)
            AlertDialog(
                onDismissRequest = { hiddenSince = s.startedMs },
                title = { Text("Getting the Hub's settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MeshSatTeal)
                            Text("Waiting for the Hub, $waited s", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "The Hub gives this phone its new password once all its servers accept it. " +
                                "That usually takes about a minute. You can leave this screen; the phone keeps asking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { hiddenSince = s.startedMs }) { Text("Hide") }
                },
                dismissButton = {
                    TextButton(onClick = { ProvisionClaim.dismiss() }) { Text("Cancel") }
                },
            )
        }

        is ProvisionClaim.State.Ready -> {
            val bundle = s.bundle
            AlertDialog(
                onDismissRequest = { ProvisionClaim.dismiss() },
                title = { Text("Use these Hub settings?") },
                text = {
                    Column {
                        Text(
                            "This phone becomes bridge \"${bundle.bridgeId}\" on the Hub. " +
                                "Its current Hub settings are replaced.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Hub: ${bundle.mqttUrl}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                        if (bundle.certExpiry.isNotBlank()) {
                            Text("Certificate expires: ${bundle.certExpiry}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                        }
                        if (bundle.reticulumTcp.isNotBlank()) {
                            Text("Reticulum: ${bundle.reticulumTcp}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { ProvisionClaim.apply(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal, contentColor = MeshSatInk),
                    ) { Text("Provision") }
                },
                dismissButton = {
                    TextButton(onClick = { ProvisionClaim.dismiss() }) { Text("Cancel") }
                },
            )
        }

        is ProvisionClaim.State.Applied -> LaunchedEffect(s) {
            Toast.makeText(context, "Hub provisioned: ${s.bridgeId}. Connecting to the Hub.", Toast.LENGTH_LONG).show()
            ProvisionClaim.dismiss()
        }

        is ProvisionClaim.State.Failed -> AlertDialog(
            onDismissRequest = { ProvisionClaim.dismiss() },
            title = { Text("No settings from the Hub") },
            text = { Text(s.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { ProvisionClaim.dismiss() }) { Text("OK") }
            },
        )
    }
}
