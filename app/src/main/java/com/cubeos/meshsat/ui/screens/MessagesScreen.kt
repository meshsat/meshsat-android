package com.cubeos.meshsat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.ConversationKey
import com.cubeos.meshsat.data.ConversationSummary
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    var viewMode by remember { mutableStateOf("all") } // "all" or "conversations"
    var selectedSender by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // When a conversation is selected, show filtered messages
    if (selectedSender != null) {
        ConversationDetailView(
            sender = selectedSender!!,
            db = db,
            onBack = { selectedSender = null },
        )
        return
    }

    val messages by (if (searchQuery.isBlank()) {
        db.messageDao().getRecent(100)
    } else {
        db.messageDao().search(searchQuery, 100)
    }).collectAsState(initial = emptyList())

    val conversations by db.messageDao().getConversations().collectAsState(initial = emptyList())

    var composeText by remember { mutableStateOf("") }
    var sendTransport by remember { mutableStateOf("mesh") }
    var transportExpanded by remember { mutableStateOf(false) }

    val meshConnected = GatewayService.meshtasticBle?.state?.collectAsState()?.value == MeshtasticBle.State.Connected
    val iridiumConnected = GatewayService.iridiumSpp?.state?.collectAsState()?.value == IridiumSpp.State.Connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Messages",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // View mode toggle
        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = viewMode == "all",
                onClick = { viewMode = "all" },
                label = { Text("All", style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                    selectedLabelColor = MeshSatTeal,
                ),
            )
            FilterChip(
                selected = viewMode == "conversations",
                onClick = { viewMode = "conversations" },
                label = { Text("Conversations", style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                    selectedLabelColor = MeshSatTeal,
                ),
            )
        }

        if (viewMode == "all") {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search messages...", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = MeshSatTextMuted) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MeshSatTextMuted)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            // Compose bar
            ComposeBar(
                composeText = composeText,
                onComposeTextChange = { composeText = it },
                sendTransport = sendTransport,
                onSendTransportChange = { sendTransport = it },
                transportExpanded = transportExpanded,
                onTransportExpandedChange = { transportExpanded = it },
                meshConnected = meshConnected,
                iridiumConnected = iridiumConnected,
                onSend = {
                    composeText = ""
                },
            )

            // Message list
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No messages yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MeshSatTextMuted,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageCard(msg)
                    }
                }
            }
        } else {
            // Conversations view
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MeshSatTextMuted,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = { it.sender }) { conv ->
                        ConversationCard(
                            conv = conv,
                            onClick = { selectedSender = conv.sender },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeBar(
    composeText: String,
    onComposeTextChange: (String) -> Unit,
    sendTransport: String,
    onSendTransportChange: (String) -> Unit,
    transportExpanded: Boolean,
    onTransportExpandedChange: (Boolean) -> Unit,
    meshConnected: Boolean,
    iridiumConnected: Boolean,
    onSend: () -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = transportExpanded,
            onExpandedChange = { onTransportExpandedChange(!transportExpanded) },
            modifier = Modifier.weight(0.35f),
        ) {
            OutlinedTextField(
                value = sendTransport.uppercase(),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(transportExpanded) },
                modifier = Modifier.menuAnchor(),
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )
            ExposedDropdownMenu(
                expanded = transportExpanded,
                onDismissRequest = { onTransportExpandedChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("MESH") },
                    onClick = { onSendTransportChange("mesh"); onTransportExpandedChange(false) },
                    enabled = meshConnected,
                )
                DropdownMenuItem(
                    text = { Text("IRIDIUM") },
                    onClick = { onSendTransportChange("iridium"); onTransportExpandedChange(false) },
                    enabled = iridiumConnected,
                )
            }
        }

        OutlinedTextField(
            value = composeText,
            onValueChange = onComposeTextChange,
            placeholder = { Text("Message...", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier.weight(0.55f),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MeshSatTeal,
                unfocusedBorderColor = MeshSatBorder,
            ),
        )

        IconButton(
            onClick = {
                if (composeText.isBlank()) return@IconButton
                when (sendTransport) {
                    "mesh" -> {
                        if (!meshConnected) {
                            Toast.makeText(context, "Mesh not connected", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        GatewayService.meshtasticBle?.let { ble ->
                            val proto = com.cubeos.meshsat.ble.MeshtasticProtocol.encodeTextMessage(composeText)
                            ble.sendToRadio(proto)
                        }
                    }
                    "iridium" -> {
                        if (!iridiumConnected) {
                            Toast.makeText(context, "Iridium not connected", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                    }
                }
                Toast.makeText(context, "Sent via ${sendTransport.uppercase()}", Toast.LENGTH_SHORT).show()
                onSend()
            },
            modifier = Modifier.weight(0.1f),
        ) {
            Icon(Icons.Default.Send, contentDescription = "Send", tint = MeshSatTeal)
        }
    }
}

@Composable
private fun ConversationCard(conv: ConversationSummary, onClick: () -> Unit) {
    val transportColor = when (conv.transport) {
        "mesh" -> ColorMesh
        "iridium" -> ColorIridium
        "sms" -> ColorCellular
        else -> MeshSatTextMuted
    }
    val timeStr = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(conv.lastTimestamp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = conv.sender,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = conv.transport.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = transportColor,
                    modifier = Modifier
                        .background(transportColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (conv.hasEncrypted) {
                    Text(
                        text = "ENC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatAmber,
                        modifier = Modifier
                            .background(MeshSatAmber.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = "${conv.messageCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTeal,
                modifier = Modifier
                    .background(MeshSatTeal.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Text(
            text = conv.lastMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextMuted,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            text = timeStr,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ConversationDetailView(
    sender: String,
    db: AppDatabase,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by db.messageDao().getBySender(sender).collectAsState(initial = emptyList())

    // Per-conversation encryption key
    val allConvKeys by db.conversationKeyDao().getAll().collectAsState(initial = emptyList())
    val convKey = allConvKeys.find { it.sender == sender }
    var keyInput by remember(convKey) { mutableStateOf(convKey?.hexKey ?: "") }
    var showKeySection by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Header with back button and key toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MeshSatTeal)
            }
            Text(
                text = sender,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showKeySection = !showKeySection }) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Encryption key",
                    tint = if (convKey != null) MeshSatAmber else MeshSatTextMuted,
                )
            }
        }

        // Per-conversation key management (collapsible)
        if (showKeySection) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Conversation Encryption Key",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Set a per-conversation AES-256-GCM key. Overrides the global key for this sender.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Hex key (64 chars)", style = MaterialTheme.typography.bodySmall) },
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
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Generate", style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            if (keyInput.length == 64 && keyInput.all { it in "0123456789abcdefABCDEF" }) {
                                scope.launch {
                                    db.conversationKeyDao().upsert(
                                        ConversationKey(sender = sender, hexKey = keyInput)
                                    )
                                }
                                Toast.makeText(context, "Key saved for $sender", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Invalid key — must be 64 hex chars", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Save", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (keyInput.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("MeshSat Key", keyInput))
                                Toast.makeText(context, "Key copied", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Copy", style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (clip.length == 64 && clip.all { it in "0123456789abcdefABCDEF" }) {
                                keyInput = clip
                                Toast.makeText(context, "Key pasted", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Clipboard doesn't contain a valid 64-char hex key", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Paste", style = MaterialTheme.typography.bodySmall)
                    }

                    if (convKey != null) {
                        Button(
                            onClick = {
                                scope.launch { db.conversationKeyDao().deleteBySender(sender) }
                                keyInput = ""
                                Toast.makeText(context, "Key removed — will use global key", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Remove", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageCard(msg)
            }
        }
    }
}

@Composable
private fun MessageCard(msg: Message) {
    val context = LocalContext.current
    val transportColor = when (msg.transport) {
        "mesh" -> ColorMesh
        "iridium" -> ColorIridium
        "sms" -> ColorCellular
        else -> MeshSatTextMuted
    }

    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp))

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Transport badge
                Text(
                    text = msg.transport.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = transportColor,
                    modifier = Modifier
                        .background(
                            transportColor.copy(alpha = 0.15f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )

                // Direction badge
                Text(
                    text = if (msg.direction == "rx") "RX" else "TX",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.direction == "rx") MeshSatTeal else MeshSatAmber,
                    modifier = Modifier
                        .background(
                            (if (msg.direction == "rx") MeshSatTeal else MeshSatAmber).copy(alpha = 0.15f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )

                // Encrypted badge
                if (msg.encrypted) {
                    Text(
                        text = "ENC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatAmber,
                        modifier = Modifier
                            .background(
                                MeshSatAmber.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                // Forwarded badge
                if (msg.forwarded) {
                    Text(
                        text = "FWD",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                        modifier = Modifier
                            .background(
                                MeshSatTextMuted.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )

                // Copy button — copies raw ciphertext if encrypted, otherwise plaintext
                IconButton(
                    onClick = {
                        val copyText = if (msg.encrypted && msg.rawText.isNotEmpty()) msg.rawText else msg.text
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("MeshSat Message", copyText))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy message",
                        tint = MeshSatTextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Sender
        Text(
            text = msg.sender,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Message text (selectable)
        SelectionContainer {
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Show raw ciphertext below if message was encrypted but text differs (decrypted)
        if (msg.encrypted && msg.rawText.isNotEmpty() && msg.rawText != msg.text) {
            SelectionContainer {
                Text(
                    text = msg.rawText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshSatTextMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
