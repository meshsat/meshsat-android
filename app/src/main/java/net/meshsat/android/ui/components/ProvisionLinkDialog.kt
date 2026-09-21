package net.meshsat.android.ui.components

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.meshsat.android.crypto.ProvisionImporter
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Confirmation for a `meshsat://provision/{bid}/{nonce}?hub={host}` deep link (MESHSAT-1235).
 *
 * The QR scanner in Settings claims first and confirms after, because the user chose
 * to scan. A link can be fired by any app or page, so here nothing is fetched until the
 * user has seen which Hub will issue the credentials and for which bridge.
 */
@Composable
fun ProvisionLinkDialog(url: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parsed = remember(url) { runCatching { ProvisionImporter.parseLink(url) } }
    var busy by remember { mutableStateOf(false) }

    val request = parsed.getOrElse { e ->
        LaunchedEffect(url) {
            Toast.makeText(context, "Provisioning link rejected: ${e.message}", Toast.LENGTH_LONG).show()
            onDone()
        }
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Provision Hub Connection") },
        text = {
            Column {
                Text(
                    "Provision this phone as bridge \"${request.bridgeId}\" with credentials " +
                        "from ${request.hubHost}?\n\n" +
                        "This will overwrite existing Hub settings. Only continue if you " +
                        "generated this link on your own Hub.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Hub: ${request.hubHost}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val bundle = withContext(Dispatchers.IO) {
                                ProvisionImporter.claimBundle(request) { attempt ->
                                    if (attempt == 1) scope.launch {
                                        Toast.makeText(context, "The Hub is getting the new credentials ready. Waiting...", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            val msg = ProvisionImporter.apply(bundle, context)
                            // Start the gateway again so it reads the new Hub settings (MESHSAT-749).
                            net.meshsat.android.service.GatewayService.scheduleRestart(context)
                            Toast.makeText(context, "$msg. Connecting to the Hub.", Toast.LENGTH_LONG).show()
                        } catch (e: ProvisionImporter.ProvisionException) {
                            Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Log.e("ProvisionLinkDialog", "Provision failed", e)
                            Toast.makeText(context, "Provision failed: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            onDone()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) { Text("Provision") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDone) { Text("Cancel") }
        },
    )
}
