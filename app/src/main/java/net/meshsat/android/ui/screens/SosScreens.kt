package net.meshsat.android.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberUpdatedState
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.EmergencyContact
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.service.GatewayService
import net.meshsat.android.sos.SosController
import net.meshsat.android.sos.SosMessages
import net.meshsat.android.sos.SosProgress
import net.meshsat.android.sos.SosRouteStatus
import net.meshsat.android.sos.SosRun
import net.meshsat.android.ui.components.HoldToSendButton
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatInk
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.OffWhite
import net.meshsat.android.ui.theme.SpaceBlack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The SOS (MESHSAT-1249): hold to send, an emergency contact list, sent through the retrying queue,
// a result screen per route, a banner on every screen while it is on, and a real test.

/** Where an SOS would go from this phone right now. */
private data class SosReach(
    val satellite: Boolean,
    val mesh: Boolean,
    val contacts: List<EmergencyContact>,
    val canSms: Boolean,
    val smsAllowed: Boolean,
    val hub: Boolean,
) {
    val sms: Boolean get() = canSms && contacts.isNotEmpty()
    val anywhere: Boolean get() = satellite || mesh || sms || hub

    /** "By satellite, the mesh, SMS to 2 people and the Hub." */
    fun sentence(): String {
        val parts = buildList {
            if (satellite) add("satellite")
            if (mesh) add("the mesh")
            if (sms) add("SMS to " + if (contacts.size == 1) contacts[0].name.ifBlank { "1 person" } else "${contacts.size} people")
            if (hub) add("the Hub")
        }
        if (parts.isEmpty()) {
            return if (canSms) "An SOS has nowhere to go yet. Add emergency contacts, or connect your node."
            else "An SOS has nowhere to go yet. Connect your MeshSat node, or set up the Hub."
        }
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "Sends your position by $list, and keeps trying until you cancel."
    }
}

@Composable
private fun rememberSosReach(): SosReach {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val contacts by settings.sosContacts.collectAsState(initial = emptyList())
    val imei by settings.lastModemImei.collectAsState(initial = "")
    // Collected unconditionally: a collectAsState inside a ?. chain appears in the middle of this
    // function once the service has made its modem object, and Compose then read another slot's
    // value as ModemInfo (ClassCastException on the emulator, MESHSAT-1249).
    val spp = GatewayService.iridiumSpp
    val liveImei by remember(spp) { spp?.modemInfo?.map { it.imei } ?: flowOf("") }.collectAsState(initial = "")
    val node by settings.meshtasticBleAddress.collectAsState(initial = "")
    val canSms = remember { net.meshsat.android.sms.SmsCapability.canSend(context) }
    val smsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    return SosReach(
        satellite = liveImei.isNotBlank() || imei.isNotBlank(),
        mesh = node.isNotBlank(),
        contacts = contacts,
        canSms = canSms,
        smsAllowed = smsAllowed,
        hub = GatewayService.hubReporter != null,
    )
}

private fun startSos(context: Context, test: Boolean) {
    context.startService(
        Intent(context, GatewayService::class.java)
            .setAction(GatewayService.ACTION_SOS_ACTIVATE)
            .putExtra(GatewayService.EXTRA_SOS_TEST, test)
    )
}

private fun cancelSos(context: Context) {
    context.startService(Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_SOS_CANCEL))
}

private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

/** The SOS card on Home: hold to send, or where the SOS in progress stands. */
@Composable
fun SosCard(navigate: (String) -> Unit) {
    val context = LocalContext.current
    val run by SosController.run.collectAsState()
    val reach = rememberSosReach()
    var confirmSend by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmTest by remember { mutableStateOf(false) }
    val active = run?.takeIf { it.active }

    val borderColor = when {
        active == null -> MeshSatBorder
        active.test -> MeshSatAmber
        else -> MeshSatRed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (active == null) {
            Text("SOS", style = MaterialTheme.typography.titleMedium)
            Text(reach.sentence(), style = MaterialTheme.typography.bodyMedium, color = MeshSatTextSecondary)
            if (reach.anywhere) {
                HoldToSendButton(
                    label = "Hold 3 seconds for SOS",
                    color = MeshSatRed,
                    onComplete = { startSos(context, test = false) },
                    onAccessibleActivate = { confirmSend = true },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { navigate(if (reach.canSms) "setup/safety" else "setup/node") }) {
                    Text(
                        when {
                            !reach.canSms -> "Connect your node"
                            reach.contacts.isEmpty() -> "Add emergency contacts"
                            else -> "Emergency contacts"
                        },
                        color = OffWhite,
                    )
                }
                if (reach.anywhere) {
                    TextButton(onClick = { confirmTest = true }) { Text("Test the alarm", color = OffWhite) }
                }
            }
        } else {
            val statuses = rememberSosStatuses(active)
            Text(
                if (active.test) "Alarm test running" else "SOS is on since ${clock(active.id)}",
                style = MaterialTheme.typography.titleMedium,
                color = if (active.test) MeshSatAmber else MeshSatRed,
            )
            Text(SosProgress.summary(statuses), style = MaterialTheme.typography.bodyMedium, color = MeshSatTextSecondary)
            // A real emergency during a test: the hold still works, and replaces the test.
            if (active.test) {
                HoldToSendButton(
                    label = "Hold 3 seconds for SOS",
                    color = MeshSatRed,
                    onComplete = { startSos(context, test = false) },
                    onAccessibleActivate = { confirmSend = true },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { navigate("sos") },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurfaceLight, contentColor = OffWhite),
                    modifier = Modifier.weight(1f),
                ) { Text("See where it went") }
                OutlinedButton(
                    onClick = { if (active.test) cancelSos(context) else confirmCancel = true },
                    modifier = Modifier.weight(1f),
                ) { Text(if (active.test) "Stop test" else "Cancel SOS", color = OffWhite) }
            }
        }
    }

    if (confirmSend) SendSosDialog(onDismiss = { confirmSend = false }, onSend = { confirmSend = false; startSos(context, test = false) })
    if (confirmCancel) CancelSosDialog(onDismiss = { confirmCancel = false }, onCancel = { confirmCancel = false; cancelSos(context) })
    if (confirmTest) TestAlarmDialog(reach, onDismiss = { confirmTest = false }, onTest = { confirmTest = false; startSos(context, test = true) })
}

/** The live status of every route of [run]. */
@Composable
private fun rememberSosStatuses(run: SosRun): List<SosRouteStatus> {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val flow = remember(run.id) { db.messageDeliveryDao().observeByRefPrefix(run.refPrefix) }
    val deliveries by flow.collectAsState(initial = emptyList())
    return SosProgress.routes(run, deliveries)
}

/** A strip under the status bar on every screen while an SOS or a test is on. */
@Composable
fun SosBanner(onOpen: () -> Unit) {
    val run by SosController.run.collectAsState()
    val active = run?.takeIf { it.active } ?: return
    val color = if (active.test) MeshSatAmber else MeshSatRed
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            if (active.test) "Alarm test running. Tap to see it." else "SOS is on. Tap to see where it went, or to cancel.",
            style = MaterialTheme.typography.bodyMedium,
            color = SpaceBlack,
        )
    }
}

/** The result screen: where each route of the SOS stands, and Cancel. */
@Composable
fun SosScreen(navigate: (String) -> Unit) {
    val context = LocalContext.current
    val run by SosController.run.collectAsState()
    var confirmCancel by remember { mutableStateOf(false) }
    val r = run
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (r == null) {
            Text("No SOS has been sent from this phone.", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { navigate("setup/safety") }) { Text("Emergency contacts and alarm test", color = OffWhite) }
            return@Column
        }
        val statuses = rememberSosStatuses(r)
        val (title, color) = when {
            r.test && r.active -> "Alarm test running" to MeshSatAmber
            r.test -> "Alarm test finished" to MeshSatTextSecondary
            r.cancelledAt != null -> "SOS cancelled at ${clock(r.cancelledAt)}" to MeshSatTextSecondary
            else -> "SOS is on" to MeshSatRed
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = color)
        val how = if (r.trigger == "checkin") "by the check-in timer" else "from this phone"
        Text(
            "Started at ${clock(r.id)} $how. " + (r.fix?.let { "Position ${SosMessages.coordinates(it)}" + (it.accuracyM?.let { a -> ", within ${Math.round(a)} m." } ?: ".") } ?: "Position unknown."),
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextSecondary,
        )
        if (r.cancelledAt != null && !r.test) {
            Text(
                "Every route that carried the SOS is sending \"${SosMessages.cancelText(r.name)}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextSecondary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp)),
        ) {
            if (statuses.isEmpty()) {
                Text("Nothing could be sent.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            statuses.forEach { SosRouteRow(it, showCancel = r.cancelledAt != null && !r.test) }
        }

        if (r.skipped.isNotEmpty()) {
            Text("Not used", style = MaterialTheme.typography.titleSmall, color = MeshSatTextSecondary)
            r.skipped.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted) }
        }

        if (r.active) {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { if (r.test) cancelSos(context) else confirmCancel = true },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurfaceLight, contentColor = OffWhite),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (r.test) "Stop test" else "Cancel SOS: I am safe") }
        }
        TextButton(onClick = { navigate("setup/safety") }) { Text("Emergency contacts and alarm test", color = OffWhite) }
    }
    if (confirmCancel) CancelSosDialog(onDismiss = { confirmCancel = false }, onCancel = { confirmCancel = false; cancelSos(context) })
}

@Composable
private fun SosRouteRow(s: SosRouteStatus, showCancel: Boolean) {
    val (icon, tint) = stateIcon(s.state)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label, style = MaterialTheme.typography.bodyLarge)
            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
            if (showCancel && s.cancel != null) {
                Text(
                    "Cancellation: " + when (s.cancel) {
                        SosRouteStatus.State.Sent -> "sent"
                        SosRouteStatus.State.Sending -> "sending"
                        SosRouteStatus.State.Waiting -> "waiting to send"
                        SosRouteStatus.State.Stopped -> "stopped"
                        SosRouteStatus.State.Failed -> "failed"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }
    }
}

private fun stateIcon(state: SosRouteStatus.State): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> = when (state) {
    SosRouteStatus.State.Sent -> Icons.Outlined.Done to MeshSatGreen
    SosRouteStatus.State.Sending -> Icons.Outlined.Sync to MeshSatAmber
    SosRouteStatus.State.Waiting -> Icons.Outlined.Schedule to MeshSatAmber
    SosRouteStatus.State.Stopped -> Icons.Outlined.Block to MeshSatTextMuted
    SosRouteStatus.State.Failed -> Icons.Outlined.ErrorOutline to MeshSatRed
}

@Composable
private fun SendSosDialog(onDismiss: () -> Unit, onSend: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("Send an SOS?") },
        text = { Text("Your position goes out on every route this phone has, and the phone keeps trying until you cancel.") },
        confirmButton = {
            Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed, contentColor = MeshSatInk)) { Text("Send SOS") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Don't send", color = OffWhite) } },
    )
}

@Composable
private fun CancelSosDialog(onDismiss: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("Cancel the SOS?") },
        text = { Text("Nothing more goes out, and everyone who got the SOS is told you are safe.") },
        confirmButton = { Button(onClick = onCancel) { Text("Cancel SOS") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it on", color = OffWhite) } },
    )
}

@Composable
private fun TestAlarmDialog(reach: SosReach, onDismiss: () -> Unit, onTest: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val name by settings.sosName.collectAsState(initial = "")
    val callsign by settings.hubCallsign.collectAsState(initial = "")
    val text = SosMessages.testText(name.ifBlank { callsign })
    // What each route carries, and what it costs: the satellite leg is a 31-byte position report
    // when the phone has a fix (one credit), the text otherwise.
    val parts = buildList {
        if (reach.satellite) add("a position report to the Hub by satellite, 1 credit")
        if (reach.mesh) add("the text on the mesh")
        if (reach.sms) add(if (reach.contacts.size == 1) "the text by SMS to ${reach.contacts[0].name.ifBlank { "1 contact" }}, at your carrier's rate" else "the text by SMS to ${reach.contacts.size} contacts, at your carrier's rate")
        if (reach.hub) add("a test event to the Hub online")
    }
    val list = if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString("; ") + "; and " + parts.last()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("Test the alarm?") },
        text = {
            Text(
                "The test text is \"$text\". It goes as $list. " +
                    "Nobody is alarmed, and the Hub does not raise an SOS."
            )
        },
        confirmButton = { Button(onClick = onTest) { Text("Send the test") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = OffWhite) } },
    )
}

/** Setup > Safety: who an SOS goes to, the name it gives, and the test. */
@Composable
fun SosSettingsCard() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val contacts by settings.sosContacts.collectAsState(initial = emptyList())
    val savedName by settings.sosName.collectAsState(initial = "")
    val callsign by settings.hubCallsign.collectAsState(initial = "")
    val reach = rememberSosReach()
    var name by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var phoneError by remember { mutableStateOf<String?>(null) }
    var confirmTest by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val run by SosController.run.collectAsState()
    val latestContacts by rememberUpdatedState(contacts)
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val picked = readPickedContact(context, uri)
        if (picked == null) {
            phoneError = "Could not read that contact. Type the number instead."
            typing = true
            return@rememberLauncherForActivityResult
        }
        when (val r = EmergencyContact.adding(latestContacts, picked.first, picked.second)) {
            is EmergencyContact.Companion.Added.No -> phoneError = r.why
            is EmergencyContact.Companion.Added.Ok -> {
                phoneError = null
                scope.launch { settings.setSosContacts(r.list) }
            }
        }
    }

    LaunchedEffect(savedName) { if (name == null) name = savedName }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SOS", style = MaterialTheme.typography.titleMedium)
        Text(reach.sentence(), style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)

        OutlinedTextField(
            value = name.orEmpty(),
            onValueChange = { v ->
                val clean = v.take(SosMessages.MAX_NAME)
                name = clean
                scope.launch { settings.setSosName(clean.trim()) }
            },
            label = { Text("Your name in an SOS") },
            placeholder = { Text(callsign.ifBlank { "A MeshSat user" }) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (reach.canSms) {
            Text("Emergency contacts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Each one gets an SMS with your position and a map link from this phone's SIM, whenever it has a signal.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            if (reach.canSms && !reach.smsAllowed) {
                Text("SMS is not allowed yet: allow it in Setup, SMS.", style = MaterialTheme.typography.bodySmall, color = MeshSatAmber)
            }
            contacts.forEach { c ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(MeshSatSurfaceLight, RoundedCornerShape(6.dp)).padding(start = 12.dp),
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(c.name.ifBlank { c.phone }, style = MaterialTheme.typography.bodyMedium)
                        if (c.name.isNotBlank()) Text(c.phone, style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
                    }
                    IconButton(onClick = { scope.launch { settings.setSosContacts(contacts - c) } }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove ${c.name.ifBlank { c.phone }}", tint = MeshSatTextSecondary)
                    }
                }
            }
            if (contacts.size < EmergencyContact.MAX) {
                // The phone's own contacts first (owner, 21 Sep 2026): nobody knows a number by
                // heart, and a number typed under stress is a number typed wrong. The system picker
                // has its own search and hands over only the one row the person chose, so this
                // needs no permission to read the address book.
                Button(onClick = {
                    phoneError = null
                    try {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    } catch (e: ActivityNotFoundException) {
                        typing = true
                        phoneError = "This phone has no contacts app. Type the number instead."
                    }
                }) { Text("Choose from your contacts") }
                if (!typing) {
                    phoneError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MeshSatAmber) }
                    TextButton(onClick = { typing = true }) { Text("Or type a number", color = MeshSatTextSecondary) }
                } else {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.replace("\t", " ").replace("\n", " ").take(40) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newPhone,
                        onValueChange = { newPhone = it.take(24); phoneError = null },
                        label = { Text("Phone number, with country code") },
                        placeholder = { Text("+31 6 1234 5678") },
                        singleLine = true,
                        isError = phoneError != null,
                        supportingText = phoneError?.let { e -> { Text(e) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            when (val r = EmergencyContact.adding(contacts, newName, newPhone)) {
                                is EmergencyContact.Companion.Added.No -> phoneError = r.why
                                is EmergencyContact.Companion.Added.Ok -> {
                                    scope.launch { settings.setSosContacts(r.list) }
                                    newName = ""
                                    newPhone = ""
                                    typing = false
                                }
                            }
                        },
                        enabled = newPhone.isNotBlank(),
                    ) { Text("Add this number", color = OffWhite) }
                }
            }

        } else {
            Text("This device cannot send SMS, so an SOS goes by satellite, the mesh and the Hub only.", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        }

        Text("Test the alarm", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Sends a test on every route an SOS would take, and shows what got through. It says it is a test, and raises nothing at the Hub.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
        val canTest = reach.anywhere && run?.active != true
        OutlinedButton(onClick = { confirmTest = true }, enabled = canTest) {
            Text("Test the alarm", color = if (canTest) OffWhite else MeshSatTextMuted)
        }
        if (!reach.anywhere) {
            Text("A test needs somewhere to go first.", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        }
    }
    if (confirmTest) TestAlarmDialog(reach, onDismiss = { confirmTest = false }, onTest = { confirmTest = false; startSos(context, test = true) })
}

/**
 * The name and number of the one row the system's contact picker handed back. The picker grants
 * read access to that row alone, so no contacts permission is declared or asked for.
 */
private fun readPickedContact(context: Context, uri: Uri): Pair<String, String>? = try {
    context.contentResolver.query(
        uri,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
        null, null, null,
    )?.use { c ->
        if (!c.moveToFirst()) null else (c.getString(0).orEmpty() to c.getString(1).orEmpty())
    }
} catch (e: Exception) {
    null
}

