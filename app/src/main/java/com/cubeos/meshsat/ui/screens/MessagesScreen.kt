package com.cubeos.meshsat.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.Message
import com.cubeos.meshsat.ui.theme.ColorCellular
import com.cubeos.meshsat.ui.theme.ColorIridium
import com.cubeos.meshsat.ui.theme.ColorMesh
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted
import com.cubeos.meshsat.ui.theme.MeshSatTeal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessagesScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val messages by db.messageDao().getRecent(100).collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Messages",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
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
