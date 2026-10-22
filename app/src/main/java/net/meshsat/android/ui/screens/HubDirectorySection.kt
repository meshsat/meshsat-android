package net.meshsat.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.meshsat.android.hub.directory.HubDirectoryStore
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTextMuted
import java.text.DateFormat
import java.util.Date

/**
 * The organisation's contacts and groups from the Hub (HeMB-NG C9.5, MESHSAT-1642): read-only,
 * signed by the Hub's directory key, which this phone pinned when it was provisioned. Hidden on a
 * phone with no Hub key. Collapsed to one line until tapped, so the nodes stay on screen.
 */
@Composable
fun HubDirectorySection() {
    val context = LocalContext.current
    val store = remember { HubDirectoryStore.get(context) }
    val dir by store.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (dir.pinnedKey.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = MeshSatSurface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Your organisation", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    dir.appliedAt == 0L && dir.refusedReason.isEmpty() -> "Not read from the Hub yet."
                    else -> "${Words.count(dir.contacts.size, "contact")}, ${Words.count(dir.groups.size, "group")} " +
                        "from the Hub" + if (open) "" else ". Tap to show."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (dir.refusedReason.isNotEmpty()) {
                Text(
                    "Last update refused: ${dir.refusedReason}. ${dir.refusedDetail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!open) return@Column
            dir.groups.forEach { g ->
                val names = g.memberIds.mapNotNull { id -> dir.contacts.firstOrNull { it.id == id }?.displayName }
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("${g.name} · ${g.kind.lowercase()}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        names.joinToString(", ").ifEmpty { "No members" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
            }
            dir.contacts.forEach { c ->
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(c.displayName, style = MaterialTheme.typography.bodyLarge)
                    listOf(c.role, c.team, c.org).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                    }
                    c.addresses.forEach { a ->
                        Text(
                            "${a.kind.lowercase()} ${a.value}" + if (a.label.isNotBlank()) " (${a.label})" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            Text(
                buildString {
                    append("Version ${dir.version} · key ${dir.keyId.take(12)} (${dir.pinSource})")
                    if (dir.checkedAt > 0) append(" · checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(dir.checkedAt))}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
