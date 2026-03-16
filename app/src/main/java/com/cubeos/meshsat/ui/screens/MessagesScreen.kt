package com.cubeos.meshsat.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.crypto.AesGcmCrypto
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.ConversationKey
import com.cubeos.meshsat.data.ConversationSummary
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.data.SettingsRepository
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatRed
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

    var viewMode by remember { mutableStateOf("conversations") } // default to conversations
    var selectedSender by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("all") }

    // SMS permission state
    var smsPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        smsPermissionGranted = results.values.all { it }
        if (smsPermissionGranted) {
            Toast.makeText(context, "SMS enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "SMS permissions denied — you can grant them later in Settings", Toast.LENGTH_LONG).show()
        }
    }

    // When a conversation is selected, show chat view
    if (selectedSender != null) {
        ConversationChatView(
            peer = selectedSender!!,
            db = db,
            onBack = { selectedSender = null },
        )
        return
    }

    val allMessages by (if (searchQuery.isBlank()) {
        db.messageDao().getRecent(100)
    } else {
        db.messageDao().search(searchQuery, 100)
    }).collectAsState(initial = emptyList())

    // Filter messages by selected transport tab
    val messages = remember(allMessages, selectedTab) {
        when (selectedTab) {
            "mesh" -> allMessages.filter { it.transport == "mesh" }
            "iridium" -> allMessages.filter { it.transport == "iridium" }
            "sms" -> allMessages.filter { it.transport == "sms" }
            else -> allMessages
        }
    }

    val conversations by db.messageDao().getConversations().collectAsState(initial = emptyList())

    // Stats
    val nodeCount = GatewayService.meshtasticBle?.nodes?.collectAsState()?.value?.size ?: 0
    val startOfDay = remember {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val messagesToday by db.messageDao().countSince(startOfDay).collectAsState(initial = 0)
    val totalStored = allMessages.size

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

        // SMS permission banner
        if (!smsPermissionGranted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MeshSatAmber.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .border(1.dp, MeshSatAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SMS disabled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshSatAmber,
                    )
                    Text(
                        text = "Grant SMS permission to send and receive SMS messages through MeshSat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
                Button(
                    onClick = {
                        smsPermissionLauncher.launch(arrayOf(
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.SEND_SMS,
                        ))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                ) {
                    Text("Enable", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Transport tab bar
        Row(
            modifier = Modifier.padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("all" to "All", "mesh" to "Mesh", "iridium" to "SBD", "sms" to "SMS").forEach { (key, label) ->
                FilterChip(
                    selected = selectedTab == key,
                    onClick = { selectedTab = key },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (key) {
                            "mesh" -> ColorMesh.copy(alpha = 0.2f)
                            "iridium" -> ColorIridium.copy(alpha = 0.2f)
                            "sms" -> ColorCellular.copy(alpha = 0.2f)
                            else -> MeshSatTeal.copy(alpha = 0.2f)
                        },
                        selectedLabelColor = when (key) {
                            "mesh" -> ColorMesh
                            "iridium" -> ColorIridium
                            "sms" -> ColorCellular
                            else -> MeshSatTeal
                        },
                    ),
                )
            }
        }

        // Stats row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "$nodeCount nodes",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Text(
                text = "$messagesToday today",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
            Text(
                text = "$totalStored stored",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // View mode toggle
        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = viewMode == "conversations",
                onClick = { viewMode = "conversations" },
                label = { Text("Chats", style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshSatTeal.copy(alpha = 0.2f),
                    selectedLabelColor = MeshSatTeal,
                ),
            )
            FilterChip(
                selected = viewMode == "all",
                onClick = { viewMode = "all" },
                label = { Text("All Messages", style = MaterialTheme.typography.bodySmall) },
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

            // Message list
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No messages yet", style = MaterialTheme.typography.bodyLarge, color = MeshSatTextMuted)
                }
            } else {
                LazyColumn(
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
                    Text("No conversations yet", style = MaterialTheme.typography.bodyLarge, color = MeshSatTextMuted)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                Text(text = conv.sender, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = conv.transport.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = transportColor,
                    modifier = Modifier
                        .background(transportColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (conv.hasEncrypted) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Encrypted",
                        tint = MeshSatAmber,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = timeStr, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                Text(
                    text = "${conv.messageCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTeal,
                    modifier = Modifier
                        .background(MeshSatTeal.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Text(
            text = conv.lastMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Chat-style conversation view with message bubbles and compose bar.
 * Decrypts messages on-the-fly — if key is deleted, shows ciphertext.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationChatView(
    peer: String,
    db: AppDatabase,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }

    val messages by db.messageDao().getConversation(peer).collectAsState(initial = emptyList())
    val globalKey by settings.encryptionKey.collectAsState(initial = "")

    // Per-conversation encryption key
    val allConvKeys by db.conversationKeyDao().getAll().collectAsState(initial = emptyList())
    val convKey = allConvKeys.find { it.sender == peer }
    val activeKey = convKey?.hexKey?.ifEmpty { null } ?: globalKey.ifEmpty { null }

    var keyInput by remember(convKey) { mutableStateOf(convKey?.hexKey ?: "") }
    var showKeySection by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    // Compose bar state
    var composeText by remember { mutableStateOf("") }
    var sendTransport by remember { mutableStateOf("sms") } // default to sms for conversations
    var transportExpanded by remember { mutableStateOf(false) }

    val meshConnected = GatewayService.meshtasticBle?.state?.collectAsState()?.value == MeshtasticBle.State.Connected
    val iridiumConnected = GatewayService.iridiumSpp?.state?.collectAsState()?.value == IridiumSpp.State.Connected

    // Detect transport from conversation history
    val primaryTransport = messages.firstOrNull { it.direction == "rx" }?.transport ?: "sms"
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Header with back button, peer name, key toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MeshSatTeal)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = peer, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = primaryTransport.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (primaryTransport) {
                        "mesh" -> ColorMesh; "iridium" -> ColorIridium; else -> ColorCellular
                    },
                )
            }
            IconButton(onClick = { showKeySection = !showKeySection }) {
                Icon(
                    if (activeKey != null) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Encryption key",
                    tint = if (activeKey != null) MeshSatAmber else MeshSatTextMuted,
                )
            }
        }

        // Per-conversation key management (collapsible)
        if (showKeySection) {
            KeyManagementSection(
                convKey = convKey,
                keyInput = keyInput,
                onKeyInputChange = { keyInput = it },
                showKey = showKey,
                onShowKeyToggle = { showKey = !showKey },
                onSave = { key ->
                    scope.launch {
                        db.conversationKeyDao().upsert(ConversationKey(sender = peer, hexKey = key))
                    }
                },
                onRemove = {
                    scope.launch { db.conversationKeyDao().deleteBySender(peer) }
                    keyInput = ""
                },
            )
        }

        // Messages (reversed — newest at bottom like a chat)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(msg = msg, activeKey = activeKey)
            }
        }

        // Compose bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Transport picker
            ExposedDropdownMenuBox(
                expanded = transportExpanded,
                onExpandedChange = { transportExpanded = !transportExpanded },
                modifier = Modifier.weight(0.3f),
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
                    onDismissRequest = { transportExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("SMS") },
                        onClick = { sendTransport = "sms"; transportExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("MESH") },
                        onClick = { sendTransport = "mesh"; transportExpanded = false },
                        enabled = meshConnected,
                    )
                    DropdownMenuItem(
                        text = { Text("IRIDIUM") },
                        onClick = { sendTransport = "iridium"; transportExpanded = false },
                        enabled = iridiumConnected,
                    )
                }
            }

            OutlinedTextField(
                value = composeText,
                onValueChange = { composeText = it },
                placeholder = { Text("Message...", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (composeText.isNotBlank()) {
                        sendMessage(context, composeText, sendTransport, peer, meshConnected, iridiumConnected)
                        composeText = ""
                    }
                }),
                modifier = Modifier.weight(0.6f),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            IconButton(
                onClick = {
                    if (composeText.isBlank()) return@IconButton
                    sendMessage(context, composeText, sendTransport, peer, meshConnected, iridiumConnected)
                    composeText = ""
                },
                modifier = Modifier.weight(0.1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MeshSatTeal)
            }
        }
    }
}

private fun sendMessage(
    context: Context,
    text: String,
    transport: String,
    peer: String,
    meshConnected: Boolean,
    iridiumConnected: Boolean,
) {
    when (transport) {
        "sms" -> {
            // Send SMS to the peer (encryption handled in GatewayService)
            context.startService(
                Intent(context, GatewayService::class.java)
                    .setAction(GatewayService.ACTION_SEND_SMS)
                    .putExtra(GatewayService.EXTRA_TEXT, text)
                    .putExtra(GatewayService.EXTRA_RECIPIENT, peer)
            )
            Toast.makeText(context, "Sending SMS to $peer", Toast.LENGTH_SHORT).show()
        }
        "mesh" -> {
            if (!meshConnected) {
                Toast.makeText(context, "Mesh not connected", Toast.LENGTH_SHORT).show()
                return
            }
            context.startService(
                Intent(context, GatewayService::class.java)
                    .setAction(GatewayService.ACTION_SEND_MESH)
                    .putExtra(GatewayService.EXTRA_TEXT, text)
            )
            Toast.makeText(context, "Sent via MESH", Toast.LENGTH_SHORT).show()
        }
        "iridium" -> {
            if (!iridiumConnected) {
                Toast.makeText(context, "Iridium not connected", Toast.LENGTH_SHORT).show()
                return
            }
            context.startService(
                Intent(context, GatewayService::class.java)
                    .setAction(GatewayService.ACTION_SEND_IRIDIUM)
                    .putExtra(GatewayService.EXTRA_TEXT, text)
            )
            Toast.makeText(context, "Sent via IRIDIUM", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Chat bubble — decrypts on-the-fly using the active key.
 * If key is deleted/missing, shows ciphertext for encrypted messages.
 */
@Composable
private fun ChatBubble(msg: Message, activeKey: String?) {
    val context = LocalContext.current
    val isSelf = msg.direction == "tx"
    val transportColor = when (msg.transport) {
        "mesh" -> ColorMesh; "iridium" -> ColorIridium; "sms" -> ColorCellular; else -> MeshSatTextMuted
    }

    // Decrypt on-the-fly for encrypted messages
    val displayText = if (msg.encrypted && msg.rawText.isNotEmpty()) {
        if (activeKey != null) {
            try {
                AesGcmCrypto.decryptFromBase64(msg.rawText.trim(), activeKey)
            } catch (_: Exception) {
                msg.rawText // key wrong — show ciphertext
            }
        } else {
            msg.rawText // no key — show ciphertext
        }
    } else {
        msg.text
    }

    val isShowingCiphertext = msg.encrypted && displayText == msg.rawText

    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .background(
                    if (isSelf) MeshSatTeal.copy(alpha = 0.15f) else MeshSatSurface,
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isSelf) 12.dp else 4.dp,
                        bottomEnd = if (isSelf) 4.dp else 12.dp,
                    ),
                )
                .border(
                    0.5f.dp,
                    if (isSelf) MeshSatTeal.copy(alpha = 0.3f) else MeshSatBorder,
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isSelf) 12.dp else 4.dp,
                        bottomEnd = if (isSelf) 4.dp else 12.dp,
                    ),
                )
                .padding(10.dp),
        ) {
            // Header row: badges + time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = msg.transport.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = transportColor,
                    )
                    if (msg.encrypted) {
                        Icon(
                            if (isShowingCiphertext) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = if (isShowingCiphertext) "Encrypted (locked)" else "Decrypted",
                            tint = if (isShowingCiphertext) MeshSatRed else MeshSatAmber,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (msg.forwarded) {
                        Text(
                            text = "FWD",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeshSatTextMuted,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = timeStr, style = MaterialTheme.typography.labelSmall, color = MeshSatTextMuted)
                    IconButton(
                        onClick = {
                            val copyText = if (msg.encrypted && msg.rawText.isNotEmpty()) msg.rawText else msg.text
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MeshSat Message", copyText))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MeshSatTextMuted, modifier = Modifier.size(12.dp))
                    }
                }
            }

            // Message text
            SelectionContainer {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                    color = if (isShowingCiphertext) MeshSatTextMuted else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun KeyManagementSection(
    convKey: ConversationKey?,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    showKey: Boolean,
    onShowKeyToggle: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Conversation Encryption Key", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "AES-256-GCM key for this conversation. Messages are encrypted/decrypted with this key.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )

        OutlinedTextField(
            value = keyInput,
            onValueChange = onKeyInputChange,
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
                onClick = onShowKeyToggle,
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                modifier = Modifier.weight(1f),
            ) { Text(if (showKey) "Hide" else "Show", style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = { onKeyInputChange(AesGcmCrypto.generateKey()) },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                modifier = Modifier.weight(1f),
            ) { Text("Generate", style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    if (keyInput.length == 64 && keyInput.all { it in "0123456789abcdefABCDEF" }) {
                        onSave(keyInput)
                        Toast.makeText(context, "Key saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Invalid key — 64 hex chars required", Toast.LENGTH_LONG).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatTeal),
                modifier = Modifier.weight(1f),
            ) { Text("Save", style = MaterialTheme.typography.bodySmall) }
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
            ) { Text("Copy", style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (clip.length == 64 && clip.all { it in "0123456789abcdefABCDEF" }) {
                        onKeyInputChange(clip)
                        Toast.makeText(context, "Key pasted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Clipboard doesn't contain a valid 64-char hex key", Toast.LENGTH_LONG).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                modifier = Modifier.weight(1f),
            ) { Text("Paste", style = MaterialTheme.typography.bodySmall) }

            if (convKey != null) {
                Button(
                    onClick = {
                        onRemove()
                        Toast.makeText(context, "Key removed — messages will show encrypted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatSurface),
                    modifier = Modifier.weight(1f),
                ) { Text("Remove", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Simple message card for "All Messages" view. Also decrypts on-the-fly. */
@Composable
private fun MessageCard(msg: Message) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val settings = remember { SettingsRepository(context) }

    val allConvKeys by db.conversationKeyDao().getAll().collectAsState(initial = emptyList())
    val globalKey by settings.encryptionKey.collectAsState(initial = "")
    val convKey = allConvKeys.find { it.sender == msg.sender }
    val activeKey = convKey?.hexKey?.ifEmpty { null } ?: globalKey.ifEmpty { null }

    val transportColor = when (msg.transport) {
        "mesh" -> ColorMesh; "iridium" -> ColorIridium; "sms" -> ColorCellular; else -> MeshSatTextMuted
    }

    // Decrypt on-the-fly
    val displayText = if (msg.encrypted && msg.rawText.isNotEmpty()) {
        if (activeKey != null) {
            try { AesGcmCrypto.decryptFromBase64(msg.rawText.trim(), activeKey) } catch (_: Exception) { msg.rawText }
        } else {
            msg.rawText
        }
    } else {
        msg.text
    }
    val isShowingCiphertext = msg.encrypted && displayText == msg.rawText

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
                Text(
                    text = msg.transport.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = transportColor,
                    modifier = Modifier
                        .background(transportColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
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
                if (msg.encrypted) {
                    Icon(
                        if (isShowingCiphertext) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = "Encryption",
                        tint = if (isShowingCiphertext) MeshSatRed else MeshSatAmber,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (msg.forwarded) {
                    Text(
                        text = "FWD",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                        modifier = Modifier
                            .background(MeshSatTextMuted.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = timeStr, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                IconButton(
                    onClick = {
                        val copyText = if (msg.encrypted && msg.rawText.isNotEmpty()) msg.rawText else msg.text
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("MeshSat Message", copyText))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MeshSatTextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }

        Text(text = msg.sender, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted, modifier = Modifier.padding(top = 4.dp))

        SelectionContainer {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
                color = if (isShowingCiphertext) MeshSatTextMuted else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

