package com.cubeos.meshsat.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.ProviderCredential
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CredentialsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val credentials by db.providerCredentialDao().getAll().collectAsState(initial = emptyList())
    var showDeleteDialog by remember { mutableStateOf<ProviderCredential?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(context.contentResolver.openInputStream(uri)))
                val pem = reader.readText()
                reader.close()

                // Parse PEM to extract certificate metadata
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(pem.byteInputStream()) as X509Certificate
                val fingerprint = cert.encoded.let { encoded ->
                    java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
                        .joinToString(":") { "%02X".format(it) }.take(23) // First 8 bytes
                }
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val notAfter = sdf.format(cert.notAfter)
                val subject = cert.subjectX500Principal.name.take(100)

                db.providerCredentialDao().upsert(
                    ProviderCredential(
                        id = UUID.randomUUID().toString(),
                        provider = "local",
                        name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.pem",
                        credType = "x509_cert",
                        encryptedData = pem.toByteArray(),
                        certNotAfter = notAfter,
                        certSubject = subject,
                        certFingerprint = fingerprint,
                        source = "local",
                    )
                )
                Toast.makeText(context, "Certificate imported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Credentials", style = MaterialTheme.typography.headlineSmall)
            Button(
                onClick = { filePickerLauncher.launch("*/*") },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import PEM")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (credentials.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No credentials stored", style = MaterialTheme.typography.titleMedium, color = MeshSatTextMuted)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Import PEM files or receive via Hub sync", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(credentials, key = { it.id }) { cred ->
                    CredentialCard(cred, onDelete = { showDeleteDialog = cred })
                }
            }
        }
    }

    showDeleteDialog?.let { cred ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Credential?") },
            text = { Text("Remove '${cred.name}' (${cred.provider})? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { db.providerCredentialDao().deleteById(cred.id) }
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CredentialCard(cred: ProviderCredential, onDelete: () -> Unit) {
    val now = System.currentTimeMillis()
    val expiryColor = when {
        cred.certNotAfter.isNullOrBlank() -> MeshSatTextMuted
        else -> {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val expiry = sdf.parse(cred.certNotAfter)?.time ?: Long.MAX_VALUE
                val daysLeft = (expiry - now) / (24 * 3600_000L)
                when {
                    daysLeft < 0 -> Color(0xFFE57373)   // expired — red
                    daysLeft < 30 -> Color(0xFFFFC107)   // near expiry — amber
                    else -> Color(0xFF4CAF50)            // valid — green
                }
            } catch (_: Exception) { MeshSatTextMuted }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(cred.name, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CredBadge(cred.provider)
                    CredBadge(cred.credType)
                    CredBadge(cred.source)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFE57373), modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (cred.certFingerprint.isNotBlank()) {
            Text("SHA-256: ${cred.certFingerprint}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MeshSatTextMuted)
        }
        if (cred.certSubject.isNotBlank()) {
            Text("Subject: ${cred.certSubject}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted, maxLines = 1)
        }
        if (!cred.certNotAfter.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (expiryColor == Color(0xFFE57373) || expiryColor == Color(0xFFFFC107)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = expiryColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text("Expires: ${cred.certNotAfter}", style = MaterialTheme.typography.bodySmall, color = expiryColor)
            }
        }
        Text("v${cred.version}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
    }
}

@Composable
private fun CredBadge(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MeshSatTeal,
        modifier = Modifier
            .background(MeshSatTeal.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
