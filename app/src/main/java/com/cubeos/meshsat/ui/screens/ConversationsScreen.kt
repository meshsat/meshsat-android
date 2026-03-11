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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.cubeos.meshsat.data.AppDatabase
import com.cubeos.meshsat.data.ConversationSummary
import com.cubeos.meshsat.data.Message
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

@Composable
fun ConversationsScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)

    var selectedSender by remember { mutableStateOf<String?>(null) }

    if (selectedSender != null) {
        ConversationThread(
            sender = selectedSender!!,
            onBack = { selectedSender = null },
        )
    } else {
        ConversationList(
            onSelect = { selectedSender = it },
        )
    }
}

@Composable
private fun ConversationList(onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val conversations by db.messageDao().getConversations().collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Conversations",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MeshSatTextMuted,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversations, key = { it.sender }) { conv ->
                    ConversationCard(conv, onClick = { onSelect(conv.sender) })
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
    val timeStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        .format(Date(conv.lastTimestamp))

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
                    style = MaterialTheme.typography.titleSmall,
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
            Text(text = timeStr, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = conv.lastMessage.take(80),
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = "${conv.messageCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )
        }
    }
}

@Composable
private fun ConversationThread(sender: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val messages by db.messageDao().getBySender(sender).collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MeshSatTeal,
                )
            }
            Text(
                text = sender,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            reverseLayout = true,
        ) {
            items(messages, key = { it.id }) { msg ->
                ThreadMessage(msg)
            }
        }
    }
}

@Composable
private fun ThreadMessage(msg: Message) {
    val context = LocalContext.current
    val isSelf = msg.direction == "tx"
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    val bgColor = if (isSelf) MeshSatTeal.copy(alpha = 0.15f) else MeshSatSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .background(bgColor, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isSelf) "TX" else "RX",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelf) MeshSatTeal else MeshSatAmber,
                    )
                    if (msg.encrypted) {
                        Text(
                            text = "ENC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatAmber,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = timeStr, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                    IconButton(
                        onClick = {
                            val copyText = if (msg.encrypted && msg.rawText.isNotEmpty()) msg.rawText else msg.text
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MeshSat", copyText))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MeshSatTextMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
