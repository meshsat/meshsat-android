package net.meshsat.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import net.meshsat.android.BuildConfig
import net.meshsat.android.support.SupportBundle
import net.meshsat.android.support.SupportReport
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatInk
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import java.io.File

/**
 * Setup > Support (MESHSAT-1806): feedback to the MeshSat Support desk, with the debug.zip
 * attached if the user wants it. The message goes through the user's own email app, so the app
 * opens no connection of its own and the user sees everything before it is sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var topic by rememberSaveable { mutableStateOf(SupportBundle.Topic.Problem) }
    var message by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Tell us what happened, what you expected and what you were doing at the time. " +
                "Your email app opens with the message addressed to ${SupportBundle.SUPPORT_ADDRESS}, " +
                "so you see everything before it is sent.",
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextSecondary,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SupportBundle.Topic.entries.forEach { t ->
                FilterChip(selected = topic == t, onClick = { topic = t }, label = { Text(t.label) })
            }
        }

        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Your message") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 12,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MeshSatTeal,
                unfocusedBorderColor = MeshSatBorder,
            ),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Attach debug.zip", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "The app's log, the state of each link, the queue's counts, crash reports and the " +
                        "node's log. Phone numbers, email addresses, IMEIs and keys are blanked out; node names, " +
                        "positions and the times of messages stay in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = attach, onCheckedChange = { attach = it })
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    val zip = if (attach) makeZip(context) else null
                    // A zip that could not be made is said, and the mail still goes without it.
                    if (attach && zip == null) Toast.makeText(context, "The debug.zip could not be made; sending without it.", Toast.LENGTH_LONG).show()
                    openMail(context, topic, message, zip)
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal, contentColor = MeshSatInk),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MeshSatInk)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Collecting the logs")
            } else {
                Text("Send feedback")
            }
        }

        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    val zip = makeZip(context)
                    if (zip == null) {
                        Toast.makeText(context, "The debug.zip could not be made.", Toast.LENGTH_LONG).show()
                    } else {
                        shareZip(context, zip)
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Share debug.zip another way")
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "No email app on this phone? Write to ${SupportBundle.SUPPORT_ADDRESS} from any device.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
        TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("MeshSat support", SupportBundle.SUPPORT_ADDRESS))
            Toast.makeText(context, "Address copied.", Toast.LENGTH_SHORT).show()
        }) {
            Text("Copy the address")
        }
        Text(
            text = "A security problem? Report it privately to security@meshsat.net, not in feedback.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

private suspend fun makeZip(context: Context): File? = try {
    SupportReport.writeZip(context)
} catch (e: Exception) {
    Log.w("MeshSat", "Support: the debug.zip could not be made: ${e.message}")
    null
}

private fun zipUri(context: Context, zip: File): Uri =
    FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.support", zip)

/**
 * The user's mail apps, addressed to the desk: a picker of the apps that handle mailto (a
 * selector on ACTION_SEND), so the user takes the one they are signed in to. Android's own pick
 * opened Proton Mail's "Add account" on the owner's Pixel, which also has Thunderbird (6 Nov
 * 2026). Without any mail app, every app that can send; without that, the address.
 */
private fun openMail(context: Context, topic: SupportBundle.Topic, message: String, zip: File?) {
    val uri = zip?.let { zipUri(context, it) }
    // No type on this intent: Android resolves the mailto selector below with the outer intent's
    // type, and a mail app's mailto filter declares none, so a typed intent found no app at all
    // and the mail went to the share sheet (seen on the API 30 emulator, 6 Nov 2026).
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SupportBundle.SUPPORT_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, SupportBundle.subject(topic, BuildConfig.VERSION_NAME))
        putExtra(
            Intent.EXTRA_TEXT,
            SupportBundle.mailBody(
                message, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                SupportReport.edition, SupportReport.android, SupportReport.device, uri != null,
            ),
        )
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
    val target = if (hasApp(context, mailto)) {
        Intent(send).apply { selector = mailto }
    } else {
        Intent(send).apply { type = if (uri != null) "application/zip" else "text/plain" }
    }
    try {
        context.startActivity(Intent.createChooser(target, "Send feedback with"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can send it. Write to ${SupportBundle.SUPPORT_ADDRESS}.", Toast.LENGTH_LONG).show()
    }
}

@Suppress("DEPRECATION")
private fun hasApp(context: Context, intent: Intent): Boolean {
    val pm = context.packageManager
    val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        pm.queryIntentActivities(intent, 0)
    }
    return found.isNotEmpty()
}

private fun shareZip(context: Context, zip: File) {
    val uri = zipUri(context, zip)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "MeshSat Android ${BuildConfig.VERSION_NAME} debug.zip")
        clipData = ClipData.newRawUri(zip.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Share debug.zip"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can take the file.", Toast.LENGTH_LONG).show()
    }
}
