package net.meshsat.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import net.meshsat.android.R
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.theme.MeshSatBg
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatInk
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.OffWhite

/**
 * Shown before Android's permission prompts whenever some are missing (MESHSAT-1249): the first
 * launch used to open straight onto four system dialogs with no word on what they were for.
 */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MeshSatBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.brand_lockup),
            contentDescription = "MeshSat",
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.height(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("Keeping people connected when the network is not.", style = MaterialTheme.typography.headlineMedium)
        Text(
            "This phone becomes a gateway. With a MeshSat node it sends and receives over the mesh radio and by " +
                "satellite, and by SMS while there is a mobile signal.",
            style = MaterialTheme.typography.bodyLarge,
            color = MeshSatTextSecondary,
        )
        Text("What the app asks for, and why", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        PermissionReason(Icons.Outlined.Bluetooth, "Nearby devices", "To find your MeshSat node and talk to it over Bluetooth.")
        PermissionReason(Icons.Outlined.LocationOn, "Location", "Your position for an SOS and the map. Android also asks for it before an app may look for Bluetooth radios.")
        PermissionReason(Icons.Outlined.Notifications, "Notifications", "Incoming messages, the satellite signal in the status bar, and an SOS in progress.")
        PermissionReason(Icons.Outlined.Sms, "SMS, later", "Asked for only when you set up SMS or emergency contacts.")
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal, contentColor = MeshSatInk),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Continue") }
        Text(
            "You can change any of these later in Android's settings for MeshSat.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}

@Composable
private fun PermissionReason(icon: ImageVector, title: String, why: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MeshSatTextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodyMedium, color = MeshSatTextSecondary)
        }
    }
}

/**
 * Home's "Getting started" list (MESHSAT-1249): the few things that make the app useful, each done
 * or one tap from done. It goes once everything is done, or when dismissed.
 */
@Composable
fun SetupChecklistCard(navigate: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val dismissed by settings.checklistDismissed.collectAsState(initial = true)
    val node by settings.meshtasticBleAddress.collectAsState(initial = "")
    val hubUrl by settings.hubUrl.collectAsState(initial = "")
    val contacts by settings.sosContacts.collectAsState(initial = emptyList())
    val canSms = remember { net.meshsat.android.sms.SmsCapability.canSend(context) }
    val smsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    data class Step(val title: String, val detail: String, val done: Boolean, val route: String)
    val steps = buildList {
        add(Step("Pair your MeshSat node", "The radios: mesh and satellite", node.isNotBlank(), "setup/node"))
        add(Step("Scan the Hub's QR code", "Optional: the control room", hubUrl.isNotBlank() || GatewayService.hubReporter != null, "setup/hub"))
        if (canSms) add(Step("Allow SMS", "Messages and SOS by the phone's own SIM", smsAllowed, "setup/sms"))
        if (canSms) add(Step("Add emergency contacts", "Who an SOS goes to by SMS", contacts.isNotEmpty(), "setup/safety"))
    }
    if (dismissed || steps.all { it.done }) return
    val done = steps.count { it.done }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Getting started", style = MaterialTheme.typography.titleMedium)
                Text("$done of ${steps.size} done", style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
            }
            TextButton(onClick = { scope.launch { settings.setChecklistDismissed(true) } }) {
                Text("Hide", color = OffWhite)
            }
        }
        steps.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !step.done) { navigate(step.route) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    if (step.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (step.done) "Done" else "To do",
                    tint = if (step.done) MeshSatGreen else MeshSatTextMuted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.bodyLarge, color = if (step.done) MeshSatTextSecondary else OffWhite)
                    Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                }
            }
        }
    }
}
