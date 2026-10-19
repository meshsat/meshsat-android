package net.meshsat.android.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.LaunchedEffect
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
import net.meshsat.android.ui.components.goToSettingsText
import net.meshsat.android.ui.components.rememberPermissionAsk
import net.meshsat.android.ble.MeshtasticBle
import net.meshsat.android.bt.IridiumSpp
import net.meshsat.android.crypto.AesGcmCrypto
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.ConversationKey
import net.meshsat.android.data.ConversationKeyRepository
import net.meshsat.android.data.ConversationSummary
import net.meshsat.android.data.Message
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.engine.SatelliteLimits
import net.meshsat.android.service.GatewayService
import net.meshsat.android.hubmsg.HubMsgStatus
import net.meshsat.android.ui.theme.ColorCellular
import net.meshsat.android.ui.theme.ColorHub
import net.meshsat.android.ui.theme.ColorIridium
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.OffWhite
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.Peers
import net.meshsat.android.ui.theme.MeshSatTextMuted
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.meshsat.android.ui.components.collectOrNull

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The Messages tab. A conversation opens as its own screen (route chat/{peer}), so Back returns here
 * instead of leaving Messages (MESHSAT-1249).
 */
@Composable
fun MessagesScreen(openChat: (String) -> Unit = {}) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val convKeyRepo = remember { ConversationKeyRepository(db.conversationKeyDao(), net.meshsat.android.crypto.SecureKeyStore.getInstance(context)) }

    var viewMode by remember { mutableStateOf("conversations") } // default to conversations
    var searchQuery by remember { mutableStateOf("") }
    var showNewMessage by remember { mutableStateOf(false) }
    if (showNewMessage) {
        NewMessageDialog(
            onPick = { peer -> showNewMessage = false; openChat(peer) },
            onDismiss = { showNewMessage = false },
        )
    }
    var selectedTab by remember { mutableStateOf("all") }

    // SMS permission state; Android may refuse without asking (MESHSAT-1295)
    val sms = rememberPermissionAsk(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS))
    val smsPermissionGranted = sms.granted


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
            "lxmf" -> allMessages.filter { it.transport == "lxmf" }
            "hub" -> allMessages.filter { it.transport == "hub" }
            else -> allMessages
        }
    }

    val allConversations by db.messageDao().getConversations().collectAsState(initial = emptyList())
    // The transport filter applies to chats too; it used to work only in All Messages.
    val conversations = remember(allConversations, selectedTab) {
        if (selectedTab == "all") allConversations else allConversations.filter { it.transport == selectedTab }
    }

    // Stats
    val nodeCount = GatewayService.meshtasticBle?.nodes.collectOrNull()?.value?.size ?: 0
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

        // SMS permission banner; the Google Play edition has no SMS to ask for (MESHSAT-1335)
        if (net.meshsat.android.sms.SmsCapability.included && !smsPermissionGranted) {
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
                        text = if (sms.needsSettings) goToSettingsText("SMS")
                        else "Grant SMS permission to send and receive SMS messages through MeshSat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
                Button(
                    onClick = sms.ask,
                    colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                ) {
                    Text(if (sms.needsSettings) "Settings" else "Enable", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Transport tab bar; it scrolls, since six do not fit a phone's width
        Row(
            modifier = Modifier.padding(bottom = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The Hub tab shows once the Hub route is on or a Hub message is stored (MESHSAT-1584).
            val hubShown = GatewayService.hubMsgRoute != null || allConversations.any { it.transport == "hub" }
            listOf("all" to "All", "mesh" to "Mesh", "iridium" to "Satellite", "lxmf" to "Reticulum", "hub" to "Hub", "sms" to "SMS")
                .filter { it.first != "sms" || net.meshsat.android.sms.SmsCapability.included }
                .filter { it.first != "hub" || hubShown }
                .forEach { (key, label) ->
                FilterChip(
                    selected = selectedTab == key,
                    onClick = { selectedTab = key },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (key) {
                            "mesh" -> ColorMesh.copy(alpha = 0.2f)
                            "iridium" -> ColorIridium.copy(alpha = 0.2f)
                            "sms" -> ColorCellular.copy(alpha = 0.2f)
                            "hub" -> ColorHub.copy(alpha = 0.2f)
                            else -> MeshSatSurfaceLight
                        },
                        selectedLabelColor = when (key) {
                            "mesh" -> ColorMesh
                            "iridium" -> ColorIridium
                            "sms" -> ColorCellular
                            "hub" -> ColorHub
                            else -> OffWhite
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
                    selectedContainerColor = MeshSatSurfaceLight,
                    selectedLabelColor = OffWhite,
                ),
            )
            FilterChip(
                selected = viewMode == "all",
                onClick = { viewMode = "all" },
                label = { Text("All Messages", style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshSatSurfaceLight,
                    selectedLabelColor = OffWhite,
                ),
            )
            // A message the app itself sends (satellite or mesh), even from an empty inbox.
            FilterChip(
                selected = false,
                onClick = { showNewMessage = true },
                label = { Text("New message", style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MeshSatSurfaceLight,
                    selectedLabelColor = OffWhite,
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
                            onClick = { openChat(conv.sender) },
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
        "lxmf" -> MeshSatTeal
        "hub" -> ColorHub
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
                Text(
                    text = Peers.displayName(conv.sender, GatewayService.meshtasticBle?.nodes?.value.orEmpty()),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = Words.transport(conv.transport),
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
                    color = MeshSatTextSecondary,
                    modifier = Modifier
                        .background(MeshSatSurfaceLight, RoundedCornerShape(4.dp))
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
fun ConversationChatView(
    peer: String,
    db: AppDatabase,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val convKeyRepo = remember { ConversationKeyRepository(db.conversationKeyDao(), net.meshsat.android.crypto.SecureKeyStore.getInstance(context)) }

    val messages by db.messageDao().getConversation(peer).collectAsState(initial = emptyList())
    val globalKey by settings.encryptionKey.collectAsState(initial = "")

    // Per-conversation encryption key (decrypted transparently via SecureKeyStore)
    val allConvKeys by convKeyRepo.getAll().collectAsState(initial = emptyList())
    val convKey = allConvKeys.find { it.sender == peer }
    val activeKey = convKey?.hexKey?.ifEmpty { null } ?: globalKey.ifEmpty { null }

    var keyInput by remember(convKey) { mutableStateOf(convKey?.hexKey ?: "") }
    var showKeySection by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    // Compose bar state. The route follows who the chat is with (MESHSAT-1249): a mesh node over
    // the mesh, a phone number by SMS, the satellite conversation by satellite.
    var composeText by remember { mutableStateOf("") }
    var sendTransport by remember { mutableStateOf(Peers.defaultTransport(peer)) }
    // An LXMF message goes Direct (a link, waits for the satellite round trip), lets the router
    // choose (Auto: opportunistic off the satellite), Opportunistic (one packet, no link), or
    // Propagated (parked at a propagation node until the peer syncs). Direct is the safe default (MESHSAT-1553).
    var lxmfMethod by remember { mutableStateOf("direct") }
    val meshNodes = GatewayService.meshtasticBle?.nodes.collectOrNull()?.value.orEmpty()

    val meshConnected = GatewayService.meshtasticBle?.state.collectOrNull()?.value == MeshtasticBle.State.Connected
    val iridiumConnected = GatewayService.iridiumSpp?.state.collectOrNull()?.value == IridiumSpp.State.Connected

    // A reply goes back the way the conversation came in, when that way can reach this peer.
    val primaryTransport = messages.firstOrNull { it.direction == "rx" }?.transport ?: Peers.defaultTransport(peer)
    LaunchedEffect(primaryTransport) {
        if (primaryTransport in Peers.transportsFor(peer)) sendTransport = primaryTransport
    }
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
                Text(
                    text = Peers.displayName(peer, meshNodes),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Peers.detail(peer) ?: Words.transport(sendTransport),
                    style = MaterialTheme.typography.bodySmall,
                    color = Words.transportColor(sendTransport),
                )
            }
            // A Hub chat has no conversation key: the text goes over TLS to the Hub and from it.
            if (!Peers.isHub(peer)) IconButton(onClick = { showKeySection = !showKeySection }) {
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
                        convKeyRepo.upsert(sender = peer, hexKey = key)
                    }
                },
                onRemove = {
                    scope.launch { convKeyRepo.deleteBySender(peer) }
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            // A satellite message that cannot fit one frame is stopped here, not after it has been
            // queued and billed for (MESHSAT-1280). The keyboard's Send key goes through this too.
            val composeBytes = composeText.trim().toByteArray(Charsets.UTF_8).size
            val fitsTheLink = when (sendTransport) {
                "iridium" -> SatelliteLimits.fits(composeBytes)
                "hub" -> composeBytes <= net.meshsat.android.hubmsg.HubMsgWire.MAX_BODY
                else -> true
            }
            val send = {
                if (composeText.isNotBlank() && fitsTheLink) {
                    sendMessage(context, composeText.trim(), sendTransport, peer, meshConnected, iridiumConnected, lxmfMethod)
                    composeText = ""
                }
            }
            if (sendTransport == "lxmf") {
                val propagationKnown = GatewayService.rnsStack?.lxmf?.propagationNode() != null
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp).horizontalScroll(rememberScrollState()),
                ) {
                    listOf("direct" to "Direct", "auto" to "Auto", "opportunistic" to "Opportunistic", "propagated" to "Propagated").forEach { (key, label) ->
                        FilterChip(
                            selected = lxmfMethod == key,
                            onClick = { lxmfMethod = key },
                            enabled = key != "propagated" || propagationKnown,
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = composeText,
                    onValueChange = { composeText = it },
                    placeholder = {
                        Text(
                            when (sendTransport) {
                                "iridium" -> "Message by satellite"
                                "lxmf" -> "Message by Reticulum"
                                "hub" -> "Message through the Hub"
                                "mesh" -> if (peer == Peers.MESH_ALL) "Message everyone on the mesh" else "Message on the mesh"
                                else -> "Text message"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MeshSatTextSecondary,
                        unfocusedBorderColor = MeshSatBorder,
                    ),
                )
                IconButton(
                    onClick = send,
                    enabled = composeText.isNotBlank() && fitsTheLink,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (composeText.isNotBlank() && fitsTheLink) MeshSatTeal else MeshSatTextMuted,
                    )
                }
            }
            Text(
                text = composeHint(sendTransport, composeText, peer, meshConnected, iridiumConnected),
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

/**
 * The line under the message box: how this message goes, and for a satellite message its size and
 * cost before it is sent (Rock7 bills up to 50 bytes per credit; the satellite limit is 340 bytes).
 */
private fun composeHint(transport: String, text: String, peer: String, meshUp: Boolean, satUp: Boolean): String {
    val bytes = text.trim().toByteArray(Charsets.UTF_8).size
    return when (transport) {
        "iridium" -> {
            if (!SatelliteLimits.fits(bytes)) return SatelliteLimits.tooLong(bytes)
            val size = if (bytes == 0) "" else " $bytes bytes, ${Words.count((bytes + 49) / 50, "credit")}."
            (if (satUp) "By satellite." else "By satellite, when the modem is back.") + size
        }
        "lxmf" -> {
            val name = Peers.lxmfName(peer)
            val known = GatewayService.rnsStack?.node?.paths?.has(ByteArray(16) { i -> peer.substring(2 * i, 2 * i + 2).toInt(16).toByte() }) == true
            when {
                name != null && known -> "By Reticulum to $name. Direct waits for the far end, even by satellite."
                known -> "By Reticulum. Direct waits for the far end, even by satellite."
                else -> "By Reticulum, once a path to this address is found."
            }
        }
        "hub" -> when {
            GatewayService.hubMsgRoute == null -> "Messages through the Hub are off. Turn them on in Settings, Hub."
            bytes > net.meshsat.android.hubmsg.HubMsgWire.MAX_BODY -> "Too long for the Hub: $bytes bytes, the limit is ${net.meshsat.android.hubmsg.HubMsgWire.MAX_BODY}."
            GatewayService.hubReporter?.isConnected != true -> "Through the Hub to its MeshSat app, once this phone is back on the Hub."
            else -> "Through the Hub to its MeshSat app. Two checks once that app has it."
        }
        "mesh" -> when {
            !meshUp -> "On the mesh, when your node is connected."
            peer == Peers.MESH_ALL -> "To everyone on the mesh channel."
            else -> "Directly to this node on the mesh."
        }
        else -> if (bytes == 0) "By SMS from this phone." else "By SMS from this phone, ${text.trim().length} characters."
    }
}

private fun sendMessage(
    context: Context,
    text: String,
    transport: String,
    peer: String,
    meshConnected: Boolean,
    iridiumConnected: Boolean,
    lxmfMethod: String = "direct",
) {
    when (transport) {
        "hub" -> {
            // Through the Hub to the contact's MeshSat app; the row shows what the Hub reports (MESHSAT-1584).
            context.startService(
                Intent(context, GatewayService::class.java)
                    .setAction(GatewayService.ACTION_SEND_HUB)
                    .putExtra(GatewayService.EXTRA_TEXT, text)
                    .putExtra(GatewayService.EXTRA_RECIPIENT, peer)
            )
        }
        "lxmf" -> {
            // A Reticulum message to an LXMF address; the row shows queued, sent, delivered or failed.
            context.startService(
                Intent(context, GatewayService::class.java)
                    .setAction(GatewayService.ACTION_SEND_LXMF)
                    .putExtra(GatewayService.EXTRA_TEXT, text)
                    .putExtra(GatewayService.EXTRA_RECIPIENT, peer)
                    .putExtra(GatewayService.EXTRA_LXMF_METHOD, lxmfMethod)
            )
        }
        "sms" -> {
            // Send SMS to the peer (encryption handled in GatewayService)
            // The message appears in the chat with its state; no toast claims success early.
            context.startService(
                Intent(context, GatewayService::class.java)
                    .setAction(GatewayService.ACTION_SEND_SMS)
                    .putExtra(GatewayService.EXTRA_TEXT, text)
                    .putExtra(GatewayService.EXTRA_RECIPIENT, peer)
            )
        }
        "mesh" -> {
            if (!meshConnected) {
                Toast.makeText(context, "Not sent: your MeshSat node is not connected. Connect it in Setup.", Toast.LENGTH_LONG).show()
                return
            }
            // To this node directly, or to everyone on the channel (MESHSAT-1249: replies used to
            // go to the whole channel and vanish from the conversation).
            context.startService(
                Intent(context, GatewayService::class.java)
                    .setAction(GatewayService.ACTION_SEND_MESH)
                    .putExtra(GatewayService.EXTRA_TEXT, text)
                    .putExtra(GatewayService.EXTRA_RECIPIENT, if (Peers.isMeshNode(peer)) peer else Peers.MESH_ALL)
            )
        }
        "iridium" -> {
            // Queued even without the modem: it goes out once a session succeeds (MESHSAT-1243).
            context.startService(
                Intent(context, GatewayService::class.java)
                    .setAction(GatewayService.ACTION_SEND_IRIDIUM)
                    .putExtra(GatewayService.EXTRA_TEXT, text)
                    .putExtra(GatewayService.EXTRA_RECIPIENT, peer)
            )
        }
    }
}

/** The delivery badge of a forwarded message: an Iridium send shows where it is in the queue. */
private fun deliveryLabel(forwardedTo: String): String = when {
    forwardedTo.startsWith("hub:") -> hubLabel(forwardedTo)
    else -> otherLabel(forwardedTo)
}

/** What is known about a message sent through the Hub, from the events it reported (MESHSAT-1584). */
private fun hubLabel(s: String): String = when {
    s == HubMsgStatus.QUEUED -> "Queued"
    s == HubMsgStatus.ACCEPTED -> "Sent to the Hub"
    s == HubMsgStatus.FORWARDED -> "The Hub passed it on"
    s == HubMsgStatus.BUSY -> "Not stored yet, the other app was busy"
    s == HubMsgStatus.DELIVERED -> "Delivered"
    s == HubMsgStatus.EXPIRED -> "Expired before it arrived"
    s == HubMsgStatus.UNKNOWN -> "Outcome unknown"
    s == HubMsgStatus.REFUSED_PREFIX + "not_sent" -> "Not sent"
    s.startsWith(HubMsgStatus.REFUSED_PREFIX) -> "Refused by the Hub: " + s.removePrefix(HubMsgStatus.REFUSED_PREFIX).replace('_', ' ')
    s.startsWith(HubMsgStatus.FAILED_PREFIX) -> "Failed: " + s.removePrefix(HubMsgStatus.FAILED_PREFIX).replace('_', ' ')
    else -> "Sent to the Hub"
}

private fun otherLabel(forwardedTo: String): String = when (forwardedTo) {
    GatewayService.IRIDIUM_QUEUED, GatewayService.LXMF_QUEUED -> "Queued"
    GatewayService.LXMF_SENT -> "Sent"
    GatewayService.LXMF_DELIVERED -> "Delivered"
    GatewayService.LXMF_PROPAGATED -> "At the propagation node"
    GatewayService.LXMF_FAILED -> "Failed"
    GatewayService.IRIDIUM_UNCONFIRMED -> "May have been sent"
    "iridium:sbd", net.meshsat.android.sms.SmsStatusReceiver.SENT -> "Sent"
    GatewayService.IRIDIUM_DELIVERED -> "The Hub has it"
    net.meshsat.android.sms.SmsStatusReceiver.DELIVERED -> "Delivered"
    net.meshsat.android.sms.SmsStatusReceiver.SENDING -> "Sending"
    "iridium:failed", net.meshsat.android.sms.SmsStatusReceiver.FAILED -> "Failed"
    else -> "Forwarded"
}

/**
 * The delivery mark on a message the phone sent, as chat apps show it: a clock while an Iridium
 * message waits for the modem, one check once it has left the phone (for Iridium: the satellite
 * network accepted it), a red mark when it failed, and two checks only on a confirmation from the
 * far end: the Hub's receipt for a satellite message, the carrier's delivery report for an SMS
 * (MESHSAT-1246). A mesh message never gets a second check.
 */
@Composable
private fun DeliveryMark(forwardedTo: String) {
    val (icon, tint, label) = if (forwardedTo.startsWith("hub:")) hubMark(forwardedTo) else when (forwardedTo) {
        GatewayService.IRIDIUM_QUEUED, GatewayService.LXMF_QUEUED, net.meshsat.android.sms.SmsStatusReceiver.SENDING ->
            Triple(Icons.Default.Schedule, MeshSatTextMuted, "Queued")
        GatewayService.LXMF_FAILED -> Triple(Icons.Default.ErrorOutline, MeshSatRed, "Failed")
        // Two checks only on the peer's proof, as LXMF clients show it.
        GatewayService.LXMF_DELIVERED -> Triple(Icons.Default.DoneAll, MeshSatTeal, "Delivered")
        // One check: the propagation node holds it for the peer's next sync.
        GatewayService.LXMF_PROPAGATED -> Triple(Icons.Default.Done, MeshSatTextMuted, "At the propagation node")
        // The link dropped after the upload: it may have arrived, and it is being sent again.
        GatewayService.IRIDIUM_UNCONFIRMED -> Triple(Icons.AutoMirrored.Filled.HelpOutline, MeshSatAmber, "May have been sent")
        "iridium:failed", net.meshsat.android.sms.SmsStatusReceiver.FAILED ->
            Triple(Icons.Default.ErrorOutline, MeshSatRed, "Failed")
        // The second tick, only on a confirmation: the Hub's receipt for a satellite message, the
        // carrier's delivery report for an SMS (MESHSAT-1246).
        GatewayService.IRIDIUM_DELIVERED, net.meshsat.android.sms.SmsStatusReceiver.DELIVERED ->
            Triple(Icons.Default.DoneAll, MeshSatTeal, "Delivered")
        else -> Triple(Icons.Default.Done, MeshSatTeal, "Sent")
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
}

/** A Hub message's mark: two checks only on the recipient app's own word that it stored it. */
private fun hubMark(s: String) = when {
    s == HubMsgStatus.QUEUED -> Triple(Icons.Default.Schedule, MeshSatTextMuted, "Queued")
    s == HubMsgStatus.ACCEPTED -> Triple(Icons.Default.Done, MeshSatTextMuted, "Sent to the Hub")
    s == HubMsgStatus.FORWARDED -> Triple(Icons.Default.Done, MeshSatTeal, "The Hub passed it on")
    s == HubMsgStatus.DELIVERED -> Triple(Icons.Default.DoneAll, MeshSatTeal, "Delivered")
    s == HubMsgStatus.BUSY || s == HubMsgStatus.UNKNOWN -> Triple(Icons.AutoMirrored.Filled.HelpOutline, MeshSatAmber, hubLabel(s))
    else -> Triple(Icons.Default.ErrorOutline, MeshSatRed, hubLabel(s))
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
        "mesh" -> ColorMesh; "iridium" -> ColorIridium; "sms" -> ColorCellular; "lxmf" -> MeshSatTeal; "hub" -> ColorHub; else -> MeshSatTextMuted
    }

    // Show decrypted text if SmsReceiver already decrypted it (stored in msg.text).
    // Only re-decrypt from rawText if msg.text still contains ciphertext. [MESHSAT-447]
    val displayText = if (msg.encrypted && msg.text.isNotEmpty() && msg.text != msg.rawText) {
        msg.text // already decrypted by SmsReceiver
    } else if (msg.encrypted && msg.rawText.isNotEmpty()) {
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
                    if (msg.forwarded && !isSelf) {
                        Text(
                            text = "Forwarded",
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
                    if (isSelf) DeliveryMark(msg.forwardedTo)
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
    val convKeyRepo = remember { ConversationKeyRepository(db.conversationKeyDao(), net.meshsat.android.crypto.SecureKeyStore.getInstance(context)) }

    val allConvKeys by convKeyRepo.getAll().collectAsState(initial = emptyList())
    val globalKey by settings.encryptionKey.collectAsState(initial = "")
    val convKey = allConvKeys.find { it.sender == msg.sender }
    val activeKey = convKey?.hexKey?.ifEmpty { null } ?: globalKey.ifEmpty { null }

    val transportColor = when (msg.transport) {
        "mesh" -> ColorMesh; "iridium" -> ColorIridium; "sms" -> ColorCellular; else -> MeshSatTextMuted
    }

    // Show decrypted text if SmsReceiver already decrypted it. [MESHSAT-447]
    val displayText = if (msg.encrypted && msg.text.isNotEmpty() && msg.text != msg.rawText) {
        msg.text
    } else if (msg.encrypted && msg.rawText.isNotEmpty()) {
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
                        text = deliveryLabel(msg.forwardedTo),
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


/**
 * Who a new message is for (MESHSAT-1249): the satellite, everyone on the mesh, a node the phone
 * has heard, or a phone number. It used to open a chat with the literal recipient "self", so an SMS
 * written there went to "self".
 */
@Composable
private fun NewMessageDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val nodes = GatewayService.meshtasticBle?.nodes.collectOrNull()?.value.orEmpty()
    val myNum = GatewayService.meshtasticBle?.myInfo.collectOrNull()?.value?.myNodeNum ?: 0L
    val imei = GatewayService.iridiumSpp?.modemInfo.collectOrNull()?.value?.imei.orEmpty()
    var number by remember { mutableStateOf("") }
    val numberOk = number.trim().let { n -> n.length >= 6 && n.all { it.isDigit() || it == '+' || it == ' ' } }
    // A Reticulum (LXMF) address, as Sideband and CrossTalk show it: 32 hex characters (MESHSAT-1553).
    var lxmfAddress by remember { mutableStateOf("") }
    val lxmfClean = lxmfAddress.trim().lowercase().removePrefix("<").removeSuffix(">").replace(" ", "")
    val lxmfOk = Peers.isLxmf(lxmfClean)
    val lxmfPeers = remember { GatewayService.rnsStack?.lxmf?.peers().orEmpty().sortedByDescending { it.lastSeenMs } }
    // Another MeshSat app through the Hub: a card that carries its bridge id, or the id typed in (MESHSAT-1584).
    val hubOn = GatewayService.hubMsgRoute != null
    val context = LocalContext.current
    val cards by AppDatabase.getInstance(context).contactDao().observeAll().collectAsState(initial = emptyList())
    var hubId by remember { mutableStateOf("") }
    val hubPeer = Peers.hubPeer(hubId.trim().removePrefix(Peers.HUB_PREFIX))
    val hubOk = hubOn && hubId.isNotBlank() && Peers.isHub(hubPeer) && hubId.none { it.isWhitespace() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("New message") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                NewMessageRow(
                    title = "Satellite",
                    detail = "Through Rock7 to the Hub, from anywhere with a view of the sky",
                    color = ColorIridium,
                    onClick = { onPick(imei.ifBlank { Peers.SATELLITE }) },
                )
                NewMessageRow(
                    title = "Everyone on the mesh",
                    detail = "Every node on your channel",
                    color = ColorMesh,
                    onClick = { onPick(Peers.MESH_ALL) },
                )
                nodes.filter { it.nodeNum != myNum }.sortedByDescending { it.lastHeard }.take(20).forEach { node ->
                    val id = net.meshsat.android.ble.MeshtasticProtocol.formatNodeId(node.nodeNum)
                    NewMessageRow(
                        title = node.longName.ifBlank { "Node $id" },
                        detail = "On the mesh, $id",
                        color = ColorMesh,
                        onClick = { onPick(id) },
                    )
                }
                if (hubOn) cards.filter { it.bridgeId.isNotBlank() }.forEach { c ->
                    NewMessageRow(
                        title = c.name,
                        detail = "Through the Hub to their MeshSat app, ${c.bridgeId}",
                        color = ColorHub,
                        onClick = { onPick(Peers.hubPeer(c.bridgeId)) },
                    )
                }
                lxmfPeers.take(20).forEach { p ->
                    NewMessageRow(
                        title = p.displayName?.ifBlank { null } ?: "Reticulum ${p.destHash.take(8)}",
                        detail = "By Reticulum, ${p.destHash.take(16)}... ${Words.count(p.hops, "hop")} away",
                        color = MeshSatTeal,
                        onClick = { onPick(p.destHash) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lxmfAddress,
                    onValueChange = { lxmfAddress = it },
                    label = { Text("Or a Reticulum (LXMF) address, 32 hex characters") },
                    singleLine = true,
                    isError = lxmfAddress.isNotBlank() && !lxmfOk,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hubOn) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hubId,
                        onValueChange = { hubId = it },
                        label = { Text("Or a MeshSat app's Hub bridge id") },
                        singleLine = true,
                        isError = hubId.isNotBlank() && !hubOk,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // A phone number is an SMS peer; the Google Play edition has no SMS (MESHSAT-1335).
                if (net.meshsat.android.sms.SmsCapability.included) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it },
                        label = { Text("Or a phone number, e.g. +31612345678") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (lxmfOk) {
                TextButton(onClick = { onPick(lxmfClean) }) { Text("Message this address") }
            } else if (hubOk) {
                TextButton(onClick = { onPick(hubPeer) }) { Text("Message through the Hub") }
            } else if (net.meshsat.android.sms.SmsCapability.included) {
                TextButton(onClick = { onPick(number.trim().replace(" ", "")) }, enabled = numberOk) {
                    Text("Text this number")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MeshSatTextSecondary) }
        },
    )
}

@Composable
private fun NewMessageRow(title: String, detail: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MeshSatTextSecondary)
        }
    }
}
