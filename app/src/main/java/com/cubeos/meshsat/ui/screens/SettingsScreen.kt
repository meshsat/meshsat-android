package com.cubeos.meshsat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val encryptionKey by settings.encryptionKey.collectAsState(initial = "")
    val encryptionEnabled by settings.encryptionEnabled.collectAsState(initial = false)
    val autoDecrypt by settings.autoDecryptSms.collectAsState(initial = true)
    val piPhone by settings.meshsatPiPhone.collectAsState(initial = "")

    var keyInput by remember(encryptionKey) { mutableStateOf(encryptionKey) }
    var phoneInput by remember(piPhone) { mutableStateOf(piPhone) }
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
        )

        // --- Encryption Section ---
        SectionCard("Encryption") {
            // Enable encryption
            SettingRow("Encryption enabled") {
                Switch(
                    checked = encryptionEnabled,
                    onCheckedChange = { scope.launch { settings.setEncryptionEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            // Auto-decrypt SMS
            SettingRow("Auto-decrypt incoming SMS") {
                Switch(
                    checked = autoDecrypt,
                    onCheckedChange = { scope.launch { settings.setAutoDecryptSms(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MeshSatTeal),
                )
            }

            // Encryption key
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("AES-256-GCM Key (hex)", style = MaterialTheme.typography.bodySmall) },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.labelMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showKey = !showKey },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (showKey) "Hide" else "Show", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        keyInput = AesGcmCrypto.generateKey()
                        scope.launch { settings.setEncryptionKey(keyInput) }
                        Toast.makeText(context, "Key generated", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Generate", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        scope.launch { settings.setEncryptionKey(keyInput) }
                        Toast.makeText(context, "Key saved", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                text = "Paste the key from MeshSat Pi (Interfaces > cellular_0 > Show encryption key) or generate a new one and enter it on the Pi.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- MeshSat Pi Section ---
        SectionCard("MeshSat Pi") {
            OutlinedTextField(
                value = phoneInput,
                onValueChange = { phoneInput = it },
                label = { Text("Pi cellular phone number", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            Button(
                onClick = {
                    scope.launch { settings.setMeshsatPiPhone(phoneInput) }
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Text("Save", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Phone number of the SIM card in MeshSat Pi's cellular modem. Used for sending encrypted SMS to the Pi.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // --- Bluetooth Section ---
        SectionCard("Bluetooth") {
            Text(
                text = "Meshtastic BLE: Not connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextMuted,
            )
            Text(
                text = "Iridium HC-05: Not connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextMuted,
            )
            Text(
                text = "Bluetooth connection management coming soon.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        control()
    }
}
