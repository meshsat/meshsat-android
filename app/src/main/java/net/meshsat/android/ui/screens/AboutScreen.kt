package net.meshsat.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.meshsat.android.BuildConfig
import net.meshsat.android.sms.SmsCapability
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted

@Composable
fun AboutScreen() {
    val targetSdk = LocalContext.current.applicationInfo.targetSdkVersion
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "MeshSat Android",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyLarge,
            color = MeshSatTeal,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            text = "Mobile gateway for Meshtastic mesh + Iridium satellite" + if (SmsCapability.included) " + SMS" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        InfoSection("Transports") {
            InfoItem("Meshtastic", "BLE (Bluetooth Low Energy)")
            InfoItem("Iridium 9603N", "The MeshSat node's BLE pipe")
            InfoItem("RockBLOCK 9704", "Bluetooth SPP via HC-05/HC-06")
            if (SmsCapability.included) InfoItem("Cellular SMS", "Native Android SMS")
        }

        Spacer(modifier = Modifier.height(12.dp))

        InfoSection("Encryption") {
            InfoItem("Algorithm", "AES-256-GCM")
            InfoItem("Wire format", "[12B nonce][ciphertext+tag]")
            InfoItem("SMS format", "Base64-encoded wire format")
            InfoItem("Compatible with", "MeshSat Pi transform pipeline")
        }

        Spacer(modifier = Modifier.height(12.dp))

        InfoSection("Build") {
            InfoItem("Package", BuildConfig.APPLICATION_ID)
            // Two editions since 2.19.0 (MESHSAT-1335): Google Play's SMS policy keeps SMS out
            // of the one it distributes. The edition on a screenshot tells support what to expect.
            InfoItem("Edition", if (SmsCapability.included) "Full (F-Droid, GitHub)" else "Google Play, without SMS")
            InfoItem("Build type", BuildConfig.BUILD_TYPE)
            InfoItem("Min SDK", "26 (Android 8.0)")
            InfoItem("Target SDK", targetSdk.toString())
        }

        Spacer(modifier = Modifier.height(12.dp))

        InfoSection("License") {
            Text(
                text = "GNU General Public License v3.0",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Part of the MeshSat project: meshsat.net",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
