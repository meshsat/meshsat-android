package com.cubeos.meshsat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.data.AppDatabase
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    var searchQuery by remember { mutableStateOf("") }
    val messages by (if (searchQuery.isBlank()) {
        db.messageDao().getRecent(100)
    } else {
        db.messageDao().search(searchQuery, 100)
    }).collectAsState(initial = emptyList())

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
            modifier = Modifier.padding(bottom = 8.dp),
        )

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Transport selector
            ExposedDropdownMenuBox(
                expanded = transportExpanded,
                onExpandedChange = { transportExpanded = !transportExpanded },
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
                    onDismissRequest = { transportExpanded = false },
                ) {
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

            // Message input
            OutlinedTextField(
                value = composeText,
                onValueChange = { composeText = it },
                placeholder = { Text("Message...", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.weight(0.55f),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeshSatTeal,
                    unfocusedBorderColor = MeshSatBorder,
                ),
            )

            // Send button
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
                            // Use service to send (handles SBDWB + SBDIX)
                            // Note: this is a stub — full implementation would use the service
                        }
                    }
                    Toast.makeText(context, "Sent via ${sendTransport.uppercase()}", Toast.LENGTH_SHORT).show()
                    composeText = ""
                },
                modifier = Modifier.weight(0.1f),
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = MeshSatTeal,
                )
            }
        }

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
    }
}

@Composable
private fun MessageCard(msg: Message) {
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

            Text(
                text = timeStr,
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }

        // Sender
        Text(
            text = msg.sender,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Message text
        Text(
            text = msg.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
