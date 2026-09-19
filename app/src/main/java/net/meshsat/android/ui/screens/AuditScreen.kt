package net.meshsat.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.AuditLogEntity
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.theme.MeshSatBlue
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextPrimary
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.PlexMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How many entries SigningService.verifyChain() checks by default. */
private const val VERIFY_WINDOW = 1000

/** The most entries "Save a copy" writes out. */
private const val EXPORT_LIMIT = 100_000

// ═══════════════════════════════════════════════════════════════════════
// Time, in the phone's own time zone everywhere
// ═══════════════════════════════════════════════════════════════════════

/**
 * A timestamp the app stored as text in UTC ("2026-09-19T10:04:00Z" in the audit log,
 * "2026-09-19 10:04:00" on a rule's last match), as epoch ms; null if it cannot be read.
 */
internal fun parseUtcStamp(text: String?): Long? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return null
    return try {
        OffsetDateTime.parse(t).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(t.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

/** A moment as local clock time: "14:04:09" today, "18 Sep 14:04:09" before. */
internal fun localStamp(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val pattern = if (at.toLocalDate() == today) "HH:mm:ss" else "d MMM HH:mm:ss"
    return at.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

// ═══════════════════════════════════════════════════════════════════════
// Audit log words
// ═══════════════════════════════════════════════════════════════════════

private fun eventColor(eventType: String): Color = when {
    eventType.contains("forward", ignoreCase = true) ||
        eventType.contains("deliver", ignoreCase = true) -> MeshSatGreen
    eventType.contains("deny", ignoreCase = true) || eventType.contains("reject", ignoreCase = true) -> MeshSatRed
    eventType.contains("bind", ignoreCase = true) || eventType.contains("connect", ignoreCase = true) -> MeshSatBlue
    else -> MeshSatTextMuted
}

/** An audit event type (the Bridge's names, e.g. "dispatch", "oob_reject") in plain words. */
private fun auditEventLabel(eventType: String): String {
    val t = eventType.trim()
    val known = when (t.lowercase()) {
        "dispatch" -> "Queued"
        "deliver", "delivered" -> "Sent"
        "forward" -> "Passed on"
        "drop" -> "Stopped"
        "deny", "denied" -> "Blocked"
        "reject", "rejected" -> "Refused"
        "failover" -> "Switched to a backup link"
        "delivery_preempt" -> "Held back for a more urgent message"
        "sos_activated" -> "SOS started"
        "oob_command" -> "Remote command"
        "oob_reject" -> "Remote command refused"
        "oob_address_learn" -> "Learned a reply address"
        "oob_key_exported" -> "Remote control key exported"
        "connect", "connected" -> "Connected"
        "disconnect", "disconnected" -> "Disconnected"
        else -> null
    }
    if (known != null) return known
    val words = t.replace('_', ' ').replace('.', ' ').replace(':', ' ').trim()
    return if (words.isEmpty()) "Event" else words.replaceFirstChar { it.uppercase() }
}

/** Which way a message went, from the direction field. */
private fun auditDirectionLabel(direction: String): String? = when (direction.lowercase()) {
    "inbound", "in", "ingress", "rx" -> "received"
    "outbound", "out", "egress", "tx" -> "sent"
    else -> null
}

/** The links the filter offers: the ones the gateway has, or the three every phone has. */
private fun auditInterfaceIds(): List<String> {
    val ids = GatewayService.ifaceManager?.getAllStatus()?.map { it.id }.orEmpty()
    return ids.ifEmpty { listOf("mesh_0", "iridium_0", "sms_0") }
}

/** The whole log as tab-separated text, oldest first, with the hashes that make it checkable. */
private fun auditExportText(newestFirst: List<AuditLogEntity>, signerId: String?): String = buildString {
    append("MeshSat audit log\n")
    append("Saved: ").append(Instant.now().toString()).append('\n')
    if (signerId != null) append("Signing key: ").append(signerId).append('\n')
    append("Entries: ").append(newestFirst.size).append("\n\n")
    append("id\ttimestamp_utc\tinterface\tdirection\tevent\tdelivery_id\trule_id\tdetail\tprev_hash\thash\n")
    for (e in newestFirst.asReversed()) {
        append(e.id).append('\t')
        append(e.timestamp).append('\t')
        append(e.interfaceId.orEmpty()).append('\t')
        append(e.direction.orEmpty()).append('\t')
        append(e.eventType).append('\t')
        append(e.deliveryId?.toString().orEmpty()).append('\t')
        append(e.ruleId?.toString().orEmpty()).append('\t')
        append(e.detail.replace('\t', ' ').replace('\n', ' ')).append('\t')
        append(e.prevHash).append('\t')
        append(e.hash).append('\n')
    }
}

/** The outcome of "Check the log". */
private data class AuditCheck(val valid: Int, val brokenAt: Int, val brokenEntryId: Long?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val auditDao = db.auditLogDao()
    val signing = GatewayService.signingServiceRef
    val scope = rememberCoroutineScope()

    val rules by db.accessRuleDao().getAll().collectAsState(initial = emptyList())
    val ruleNames = remember(rules) { rules.associate { it.id to it.name } }
    val interfaceFilters = remember { auditInterfaceIds() }

    var events by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(100) }
    var filterInterface by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableStateOf<AuditCheck?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(limit, filterInterface) {
        withContext(Dispatchers.IO) {
            val iface = filterInterface
            events = if (iface != null) auditDao.getByInterface(iface, limit) else auditDao.getRecent(limit)
            totalCount = auditDao.count()
        }
    }

    // "Save a copy": the user picks where the file goes (no storage permission needed).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) {
            exporting = true
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        val text = auditExportText(auditDao.getRecent(EXPORT_LIMIT), signing?.signerId)
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                    } catch (_: Exception) {
                        false
                    }
                }
                exporting = false
                Toast.makeText(
                    context,
                    if (saved) "Audit log saved" else "Could not save the audit log. Try another place.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = Words.count(totalCount, "entry", "entries"),
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextSecondary,
            )

            signing?.let { svc ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MeshSat signing key", svc.signerId))
                            Toast.makeText(context, "Signing key copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = "Signing key ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                    Text(
                        text = svc.signerId.take(12) + "…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = PlexMono,
                        color = MeshSatTextPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Filter by link
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = filterInterface == null,
                onClick = {
                    filterInterface = null
                    limit = 100
                },
                label = { Text("All links") },
            )
            interfaceFilters.forEach { iface ->
                FilterChip(
                    selected = filterInterface == iface,
                    onClick = {
                        filterInterface = if (filterInterface == iface) null else iface
                        limit = 100
                    },
                    label = { Text(Words.channel(iface)) },
                    leadingIcon = {
                        Box(Modifier.size(8.dp).background(Words.channelColor(iface), CircleShape))
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Check the hash chain, and keep a copy
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (signing != null) {
                OutlinedButton(
                    onClick = {
                        verifying = true
                        scope.launch {
                            check = withContext(Dispatchers.IO) {
                                val (valid, brokenAt) = signing.verifyChain(VERIFY_WINDOW)
                                val brokenId = if (brokenAt >= 0) {
                                    auditDao.getRecent(VERIFY_WINDOW).asReversed().getOrNull(brokenAt)?.id
                                } else {
                                    null
                                }
                                AuditCheck(valid, brokenAt, brokenId)
                            }
                            verifying = false
                        }
                    },
                    enabled = !verifying,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (verifying) "Checking…" else "Check the log")
                }
            }
            OutlinedButton(
                onClick = {
                    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US)
                        .format(Instant.now().atZone(ZoneId.systemDefault()))
                    exportLauncher.launch("meshsat-audit-$stamp.txt")
                },
                enabled = !exporting && totalCount > 0,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (exporting) "Saving…" else "Save a copy")
            }
        }

        check?.let { c ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when {
                    c.brokenAt >= 0 -> {
                        Text(
                            text = "The log was changed after it was written.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatRed,
                        )
                        Text(
                            text = "Save a copy and keep it, then contact your MeshSat admin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MeshSatTextSecondary,
                        )
                        Text(
                            text = c.brokenEntryId?.let { "The first changed entry is number $it." }
                                ?: "The first changed entry is ${c.brokenAt + 1} from the oldest of the last $VERIFY_WINDOW.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                    }
                    c.valid == 0 -> Text(
                        text = "Nothing to check yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshSatTextSecondary,
                    )
                    else -> Text(
                        text = "The last ${Words.count(c.valid, "entry", "entries")} are as they were written.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MeshSatGreen,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Event list
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (filterInterface == null) "Nothing in the audit log yet." else "Nothing in the audit log for this link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MeshSatTextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(events, key = { it.id }) { event ->
                    AuditEventCard(event, ruleNames)
                }

                if (events.size >= limit) {
                    item {
                        Button(
                            onClick = { limit += 100 },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MeshSatSurface,
                                contentColor = MeshSatTextPrimary,
                            ),
                        ) {
                            Text("Show older entries")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditEventCard(event: AuditLogEntity, ruleNames: Map<Long, String>) {
    val dotColor = eventColor(event.eventType)

    // Stored in UTC; shown in the phone's time zone like every other screen.
    val timeDisplay = remember(event.timestamp) {
        parseUtcStamp(event.timestamp)?.let { localStamp(it) } ?: event.timestamp
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = auditEventLabel(event.eventType),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timeDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = PlexMono,
                    color = MeshSatTextMuted,
                )
            }

            // Link and direction: "Satellite, received"
            val link = event.interfaceId?.takeIf { it.isNotBlank() }?.let { Words.channel(it) }
            val way = event.direction?.let { auditDirectionLabel(it) }
            val where = listOfNotNull(link, way).joinToString(", ")
            if (where.isNotEmpty()) {
                Text(
                    text = where,
                    style = MaterialTheme.typography.bodySmall,
                    color = event.interfaceId?.let { Words.channelColor(it) } ?: MeshSatTextSecondary,
                )
            }

            if (event.detail.isNotBlank()) {
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Which message and which rule, by name where the rule still exists
            val refs = listOfNotNull(
                event.deliveryId?.let { "Message #$it" },
                event.ruleId?.let { id ->
                    ruleNames[id]?.takeIf { it.isNotBlank() }?.let { "Rule “$it”" } ?: "Rule #$id"
                },
            )
            if (refs.isNotEmpty()) {
                Text(
                    text = refs.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                )
            }
        }
    }
}
