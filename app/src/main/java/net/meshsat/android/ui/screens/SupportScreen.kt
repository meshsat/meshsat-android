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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
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
    // Set while the user picks among several mail apps.
    var choosing by remember { mutableStateOf<MailChoice?>(null) }

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
                    val draft = mailDraft(context, topic, message, zip)
                    val apps = mailApps(context)
                    when (apps.size) {
                        0 -> shareMail(context, draft)
                        1 -> sendWith(context, apps.first().pkg, draft)
                        else -> choosing = MailChoice(draft, apps)
                    }
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

    choosing?.let { choice ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text("Send with") },
            text = {
                Column {
                    Text(
                        text = "Pick the mail app you are signed in to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    choice.apps.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    choosing = null
                                    sendWith(context, app.pkg, choice.draft)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.pkg)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val icon = remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Spacer(modifier = Modifier.size(32.dp))
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

/** What the mail says, built once and handed to whichever app the user picks. */
private class MailDraft(
    val subject: String,
    val body: String,
    /** The body without the "debug.zip is attached" line, for an app that takes no file. */
    val bodyWithoutZip: String,
    val zipUri: Uri?,
    val zipName: String?,
)

private class MailChoice(val draft: MailDraft, val apps: List<SupportBundle.MailApp>)

private fun mailDraft(context: Context, topic: SupportBundle.Topic, message: String, zip: File?): MailDraft {
    val uri = zip?.let { zipUri(context, it) }
    fun body(attached: Boolean) = SupportBundle.mailBody(
        message, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
        SupportReport.edition, SupportReport.android, SupportReport.device, attached,
    )
    return MailDraft(
        subject = SupportBundle.subject(topic, BuildConfig.VERSION_NAME),
        body = body(uri != null),
        bodyWithoutZip = body(false),
        zipUri = uri,
        zipName = zip?.name,
    )
}

/**
 * The mail as a typed share (application/zip, or text/plain without a zip). Mail apps declare
 * their ACTION_SEND filters with a type, so an untyped intent matches none of them: on the
 * owner's Pixel (Android 17, 7 Nov 2026) the system chooser over an untyped send with a mailto
 * selector offered Proton Mail alone, not Thunderbird, and opened it by itself.
 */
private fun sendIntent(draft: MailDraft, pkg: String?): Intent = Intent(Intent.ACTION_SEND).apply {
    type = if (draft.zipUri != null) "application/zip" else "text/plain"
    putExtra(Intent.EXTRA_EMAIL, arrayOf(SupportBundle.SUPPORT_ADDRESS))
    putExtra(Intent.EXTRA_SUBJECT, draft.subject)
    putExtra(Intent.EXTRA_TEXT, draft.body)
    if (draft.zipUri != null) {
        putExtra(Intent.EXTRA_STREAM, draft.zipUri)
        clipData = ClipData.newRawUri(draft.zipName, draft.zipUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (pkg != null) setPackage(pkg)
}

/**
 * The phone's mail apps: those that answer mailto (the manifest's <queries> makes them visible
 * on Android 11+). The app lists them itself instead of leaving the pick to Android, which
 * opened a Proton Mail that is not signed in on the owner's Pixel (6 and 7 Nov 2026).
 */
@Suppress("DEPRECATION")
private fun mailApps(context: Context): List<SupportBundle.MailApp> {
    val pm = context.packageManager
    val found = queryActivities(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))).map { ri ->
        SupportBundle.MailApp(ri.activityInfo.packageName, pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString())
    }
    return SupportBundle.mailApps(found, context.packageName)
}

/**
 * The mail in the app the user picked, with the zip. A mail app that answers mailto but takes no
 * file gets a mailto link with the subject and the body instead, and the user is told the zip
 * did not go along.
 */
private fun sendWith(context: Context, pkg: String, draft: MailDraft) {
    val typed = sendIntent(draft, pkg)
    if (queryActivities(context, typed).isNotEmpty()) {
        try {
            context.startActivity(typed)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w("MeshSat", "Support: $pkg did not take the mail: ${e.message}")
        }
    }
    val link = Intent(
        Intent.ACTION_SENDTO,
        Uri.parse(SupportBundle.mailtoUri(SupportBundle.SUPPORT_ADDRESS, draft.subject, draft.bodyWithoutZip)),
    ).setPackage(pkg)
    try {
        context.startActivity(link)
        if (draft.zipUri != null) {
            Toast.makeText(context, "That app takes no file. Send the debug.zip with \"Share debug.zip another way\".", Toast.LENGTH_LONG).show()
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can send it. Write to ${SupportBundle.SUPPORT_ADDRESS}.", Toast.LENGTH_LONG).show()
    }
}

/** No mail app on the phone: any app that can send the text and the zip. */
private fun shareMail(context: Context, draft: MailDraft) {
    try {
        context.startActivity(Intent.createChooser(sendIntent(draft, null), "Send feedback with"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can send it. Write to ${SupportBundle.SUPPORT_ADDRESS}.", Toast.LENGTH_LONG).show()
    }
}

@Suppress("DEPRECATION")
private fun queryActivities(context: Context, intent: Intent): List<android.content.pm.ResolveInfo> {
    val pm = context.packageManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        pm.queryIntentActivities(intent, 0)
    }
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
