package net.meshsat.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.kit.KitApiClient
import net.meshsat.android.kit.KitConnection
import net.meshsat.android.kit.KitProbe
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.OffWhite

/**
 * Setup > Field kits on your network (MESHSAT-1738): each kit this phone reaches directly, its
 * address on the LAN and the API token the kit may ask for. The token is kept in the encrypted
 * preferences and sent as the Bearer on every direct call; a kit reached through the Hub relay
 * needs none. Check asks the kit for `/api/status`; a 401 becomes the one line that asks for the
 * token.
 */
@Composable
fun KitsSection(settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val kits by settings.kits.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Field kits on your network", style = MaterialTheme.typography.titleMedium)
        Text(
            "A kit on the same WiFi answers at its address on port 6050. A kit with an API token set " +
                "answers only with it; the token is kept on this phone, encrypted, and sent with every direct " +
                "request to that kit. Through the Hub (Setup > Hub > Reach a kit through the Hub) no token is needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
        if (kits.isEmpty()) {
            Text("No kit added yet.", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        }
        kits.forEach { kit ->
            KitCard(kit, settings)
        }
        OutlinedButton(onClick = { scope.launch { settings.putKit(KitConnection.create()) } }) {
            Text("Add a kit", color = OffWhite)
        }
    }
}

@Composable
private fun KitCard(kit: KitConnection, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var name by remember(kit.id, kit.name) { mutableStateOf(kit.name) }
    var url by remember(kit.id, kit.url) { mutableStateOf(kit.url) }
    var token by remember(kit.id) { mutableStateOf(settings.kitApiToken(kit.id)) }
    var showToken by remember(kit.id) { mutableStateOf(false) }
    var result by remember(kit.id) { mutableStateOf<KitProbe.Result?>(null) }
    var checking by remember(kit.id) { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshSatTeal, unfocusedBorderColor = MeshSatBorder)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MeshSatBorder, RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name", style = MaterialTheme.typography.bodySmall) },
            placeholder = { Text("tesseract", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium, colors = fieldColors,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Address on the LAN", style = MaterialTheme.typography.bodySmall) },
            placeholder = { Text("192.168.1.20 or kit-a.local:6050", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium, colors = fieldColors,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token (only if the kit has one)", style = MaterialTheme.typography.bodySmall) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium, colors = fieldColors,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showToken) "Hide the token" else "Show the token",
                        tint = MeshSatTextMuted,
                    )
                }
            },
        )
        result?.let { r ->
            Text(
                KitProbe.line(r),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    KitProbe.asksForToken(r) -> MeshSatAmber
                    r is KitProbe.Result.Reachable -> MeshSatGreen
                    else -> MeshSatRed
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    settings.setKitApiToken(kit.id, token)
                    scope.launch { settings.putKit(kit.copy(name = name.trim(), url = url.trim())) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) { Text("Save") }
            OutlinedButton(
                enabled = !checking && url.isNotBlank(),
                onClick = {
                    checking = true
                    scope.launch {
                        val t = token.trim()
                        result = withContext(Dispatchers.IO) {
                            KitProbe.check(KitApiClient(url, t), hasToken = t.isNotEmpty())
                        }
                        checking = false
                    }
                },
            ) { Text(if (checking) "Checking" else "Check", color = OffWhite) }
            TextButton(onClick = { scope.launch { settings.removeKit(kit.id) } }) {
                Text("Remove", color = MeshSatRed)
            }
        }
    }
}
