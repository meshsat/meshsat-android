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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted

/**
 * Manual encrypt/decrypt screen for testing AES-256-GCM compatibility with MeshSat Pi.
 */
@Composable
fun DecryptScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val clipboard = LocalClipboardManager.current

    val encryptionKey by settings.encryptionKey.collectAsState(initial = "")

    var inputText by remember { mutableStateOf("") }
    var outputText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var lastOp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Encrypt / Decrypt",
            style = MaterialTheme.typography.headlineMedium,
        )

        if (encryptionKey.isEmpty()) {
            Text(
                text = "No encryption key configured. Go to Settings to set one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatAmber,
            )
        }

        // Input
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it; errorText = "" },
            label = { Text("Input text", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
            textStyle = MaterialTheme.typography.labelMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MeshSatTeal,
                unfocusedBorderColor = MeshSatBorder,
            ),
        )

        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (encryptionKey.isEmpty()) {
                        errorText = "No key configured"
                        return@Button
                    }
                    try {
                        outputText = AesGcmCrypto.encryptToBase64(inputText, encryptionKey)
                        lastOp = "encrypted"
                        errorText = ""
                    } catch (e: Exception) {
                        errorText = "Encrypt failed: ${e.message}"
                        outputText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                modifier = Modifier.weight(1f),
                enabled = encryptionKey.isNotEmpty(),
            ) {
                Text("Encrypt", style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    if (encryptionKey.isEmpty()) {
                        errorText = "No key configured"
                        return@Button
                    }
                    try {
                        outputText = AesGcmCrypto.decryptFromBase64(inputText.trim(), encryptionKey)
                        lastOp = "decrypted"
                        errorText = ""
                    } catch (e: Exception) {
                        errorText = "Decrypt failed: ${e.message}"
                        outputText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                modifier = Modifier.weight(1f),
                enabled = encryptionKey.isNotEmpty(),
            ) {
                Text("Decrypt", style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    val clip = clipboard.getText()?.text ?: ""
                    if (clip.isNotEmpty()) {
                        inputText = clip
                        Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                modifier = Modifier.weight(1f),
            ) {
                Text("Paste", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Error
        if (errorText.isNotEmpty()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatRed,
            )
        }

        // Output
        if (outputText.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (lastOp == "encrypted") "Encrypted (base64):" else "Decrypted:",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (lastOp == "encrypted") MeshSatTeal else MeshSatGreen,
                )
                Text(
                    text = outputText,
                    style = MaterialTheme.typography.labelMedium,
                )
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(outputText))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                ) {
                    Text("Copy", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Help text
        Text(
            text = "Paste a base64 ciphertext from an SMS to decrypt it, or type plaintext to encrypt it. Uses the AES-256-GCM key from Settings — must match the key on MeshSat Pi.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )
    }
}
