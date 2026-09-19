package net.meshsat.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.meshsat.android.bt.IridiumSpp
import net.meshsat.android.bt.IridiumSpp.MailboxResult
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted

/**
 * "Check Mailbox" for the Iridium 9603 (MESHSAT-400): one billed satellite session, run in
 * the service, after the user confirmed the cost. Shows the outcome of the last check.
 */
@Composable
fun CheckMailboxButton(modifier: Modifier = Modifier) {
    val check by GatewayService.mailbox.collectAsState()
    val modemState = GatewayService.iridiumSpp?.state.collectOrNull()?.value ?: IridiumSpp.State.Disconnected
    var confirming by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Button(
            onClick = { confirming = true },
            enabled = !check.running && modemState == IridiumSpp.State.Connected,
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (check.running) "Checking mailbox..." else "Check Mailbox",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        check.result?.takeIf { !check.running }?.let { result ->
            Text(
                text = describeMailboxResult(result),
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Check the satellite mailbox?") },
            text = {
                Text(
                    "This opens an Iridium session, which can take up to 90 seconds. It uses at " +
                        "least 1 credit, even when no message is waiting. A message waiting to be " +
                        "sent goes out in the same session.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirming = false
                        GatewayService.checkIridiumMailbox()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                ) { Text("Check") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/** The outcome of a mailbox check in the user's terms. */
fun describeMailboxResult(result: MailboxResult): String = when (result) {
    MailboxResult.NotConnected -> "The modem is not connected."
    is MailboxResult.Held -> "Held after a failed session: try again in ${result.seconds} s."
    is MailboxResult.SessionFailed ->
        if (result.moStatus == 32) "No network: the modem sees no satellite. No credit used."
        else "The session failed (status ${result.moStatus})."
    MailboxResult.NoAnswer -> "The modem did not answer."
    is MailboxResult.Checked -> buildString {
        append(
            when (result.received) {
                0 -> "No new messages."
                1 -> "1 message received."
                else -> "${result.received} messages received."
            }
        )
        if (result.stillQueued > 0) append(" ${result.stillQueued} more waiting.")
    }
}
