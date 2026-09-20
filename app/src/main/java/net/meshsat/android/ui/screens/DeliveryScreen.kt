package net.meshsat.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.MessageDeliveryDao
import net.meshsat.android.data.MessageDeliveryEntity
import net.meshsat.android.ui.Words
import net.meshsat.android.ui.theme.MeshSatBorder
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatSurfaceLight
import net.meshsat.android.ui.theme.MeshSatTeal
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary
import net.meshsat.android.ui.theme.PlexMono

/**
 * The message queue (MESHSAT-1249): every message on its way out, by link, with what happened to it
 * in plain words. Routing rules > Deliveries and > Queue are built from the same parts below, so a
 * state, an urgency or a time reads the same on both screens.
 */
@Composable
fun DeliveryScreen() {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val deliveries by db.messageDeliveryDao().getRecent(200).collectAsState(initial = emptyList())

    var selectedId by remember { mutableStateOf<Long?>(null) }
    var request by remember { mutableStateOf<QueueRequest?>(null) }

    DeliveryLedger(
        deliveries = deliveries,
        onSelect = { selectedId = it.id },
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    )

    DeliveryDialogs(
        deliveries = deliveries,
        selectedId = selectedId,
        onSelectedIdChange = { selectedId = it },
        request = request,
        onRequestChange = { request = it },
    )
}

// ═══════════════════════════════════════════════════════════════════════
// Shared words for a delivery
// ═══════════════════════════════════════════════════════════════════════

/** Statuses of a message that has not gone out yet. */
internal val QUEUE_WAITING_STATUSES = setOf("queued", "retry", "held", "sending")

/** Statuses of a message that stopped without going out. */
internal val QUEUE_GAVE_UP_STATUSES = setOf("failed", "dead", "expired", "denied", "cancelled")

/** A waiting message that can still be cancelled (one being sent cannot). */
internal fun canCancelDelivery(d: MessageDeliveryEntity): Boolean = d.status in setOf("queued", "retry", "held")

/** A message that gave up and can be put back in the queue (what `retryNow` accepts). */
internal fun canRetryDelivery(d: MessageDeliveryEntity): Boolean = d.status in setOf("failed", "dead")

internal fun isSatelliteChannel(channel: String): Boolean = channel.startsWith("iridium")

/** A delivery's state; a message the user cancelled says so instead of "Gave up". */
internal fun deliveryStateText(d: MessageDeliveryEntity): String =
    if (isCancelledByUser(d)) Words.deliveryState("cancelled") else Words.deliveryState(d.status)

internal fun deliveryStateColor(d: MessageDeliveryEntity): Color =
    if (isCancelledByUser(d)) Words.deliveryColor("cancelled") else Words.deliveryColor(d.status)

private fun isCancelledByUser(d: MessageDeliveryEntity): Boolean = d.status == "dead" && d.lastError == "cancelled"

/**
 * How urgent a message is, from its priority number (lower goes first). The same words on the
 * queue, on Routing rules and in the rule editor.
 */
internal fun queueUrgencyLabel(priority: Int): String = when (priority) {
    0 -> "Critical"
    1 -> "Normal"
    else -> "Low"
}

/** What the phone does when a send fails (QoS 0 has no retries; 1 and above retry). */
internal fun queueGuaranteeLabel(qos: Int): String = when {
    qos <= 0 -> "Try once"
    qos == 1 -> "Keep trying"
    else -> "Keep trying (high)"
}

/** The last error of a delivery, in plain words where the app wrote it itself. */
internal fun deliveryProblemText(error: String): String {
    val e = error.trim()
    return when {
        e.isEmpty() -> ""
        e == "cancelled" -> "You cancelled it."
        e.startsWith("cancelled: exceeded retry limit") -> "Stopped after too many tries."
        e.startsWith("TTL expired") -> "It waited too long and expired."
        // Two very different waits, and the queue used to read the same for both (MESHSAT-615):
        // the radio being out of reach is something a person can act on, a satellite that is not
        // overhead is not.
        e.startsWith("Could not hand the message to the modem") ->
            "The phone cannot reach the node's radio. It is waiting for the radio, not for a satellite."
        e.contains("no network service") ->
            "The satellite modem found no network. It is waiting for a satellite to come over."
        e == "egress rules denied" -> "A rule on this link blocked it."
        e == "recovered after restart" -> "The app restarted while sending it, so it is tried again."
        else -> e.replaceFirstChar { it.uppercase() }
    }
}

/** Whether the other end confirmed the message. */
internal fun deliveryAckText(ack: String): String = when (ack.lowercase()) {
    "pending" -> "Waiting for confirmation"
    "acked" -> "Confirmed received"
    "nacked" -> "Refused by the other end"
    "timeout" -> "No confirmation came back"
    else -> ack.replaceFirstChar { it.uppercase() }
}

/** "Retried 2 of 3 times", or null before the first retry. */
internal fun deliveryTriesText(d: MessageDeliveryEntity): String? = when {
    d.retries <= 0 -> null
    d.maxRetries > 0 -> "Retried ${d.retries} of ${d.maxRetries} times"
    else -> "Retried ${Words.count(d.retries, "time")}"
}

/** The current time, moved on every [periodMs] so "4 min ago" does not freeze on screen. */
@Composable
internal fun rememberTickingNow(periodMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

// ═══════════════════════════════════════════════════════════════════════
// Retry and cancel, always confirmed
// ═══════════════════════════════════════════════════════════════════════

/** Retry (true) or cancel (false) one delivery, waiting for the user to confirm. */
internal class QueueRequest(val delivery: MessageDeliveryEntity, val retry: Boolean)

@Composable
internal fun ConfirmQueueRequestDialog(
    request: QueueRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val link = Words.channel(request.delivery.channel)
    val title: String
    val body: String
    val confirm: String
    if (request.retry) {
        when {
            isSatelliteChannel(request.delivery.channel) -> {
                title = "Retry by satellite?"
                body = "Each satellite attempt that gets through uses at least 1 credit. " +
                    "The message goes back in the queue and is sent at the next chance."
            }
            request.delivery.channel.startsWith("sms") -> {
                title = "Send again by SMS?"
                body = "Your carrier may charge for the text. The message goes back in the queue and is sent when SMS is working."
            }
            else -> {
                title = "Send again by $link?"
                body = "The message goes back in the queue and is sent when $link is working."
            }
        }
        confirm = "Retry"
    } else {
        title = "Cancel this message?"
        body = "It will not be sent by $link. You can retry it later from the queue."
        confirm = "Cancel message"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (request.retry) MeshSatTeal else MeshSatRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (request.retry) "Not now" else "Keep it", color = MeshSatTextSecondary)
            }
        },
    )
}

/** Carry out a confirmed request. Returns what to tell the user. */
internal suspend fun applyQueueRequest(dao: MessageDeliveryDao, request: QueueRequest): String {
    val link = Words.channel(request.delivery.channel)
    return if (request.retry) {
        dao.retryNow(request.delivery.id)
        "Back in the queue for $link"
    } else {
        val changed = dao.cancelWaiting(request.delivery.id)
        if (changed > 0) "Cancelled. It will not be sent by $link."
        else "Nothing to cancel: it has already been sent or stopped."
    }
}

/**
 * The details dialog of the selected delivery and the confirmation of a retry or cancel. The
 * selected delivery is looked up in [deliveries] by id, so the dialog follows its state live.
 */
@Composable
internal fun DeliveryDialogs(
    deliveries: List<MessageDeliveryEntity>,
    selectedId: Long?,
    onSelectedIdChange: (Long?) -> Unit,
    request: QueueRequest?,
    onRequestChange: (QueueRequest?) -> Unit,
) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val scope = rememberCoroutineScope()

    val selected = selectedId?.let { id -> deliveries.firstOrNull { it.id == id } }
    selected?.let { d ->
        DeliveryDetailsDialog(
            delivery = d,
            onDismiss = { onSelectedIdChange(null) },
            onRetry = {
                onSelectedIdChange(null)
                onRequestChange(QueueRequest(d, retry = true))
            },
            onCancel = {
                onSelectedIdChange(null)
                onRequestChange(QueueRequest(d, retry = false))
            },
        )
    }

    request?.let { r ->
        ConfirmQueueRequestDialog(
            request = r,
            onConfirm = {
                onRequestChange(null)
                scope.launch {
                    val message = try {
                        applyQueueRequest(db.messageDeliveryDao(), r)
                    } catch (e: Exception) {
                        "That did not work: ${e.message ?: "unknown error"}"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { onRequestChange(null) },
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// The list: counts by state, a filter per link, and one card per message
// ═══════════════════════════════════════════════════════════════════════

/** The state groups at the top of the list; tapping one filters by it. */
private enum class LedgerGroup(val key: String, val statuses: Set<String>) {
    Waiting("queued", setOf("queued", "retry", "held")),
    Sending("sending", setOf("sending")),
    Sent("sent", setOf("sent", "delivered")),
    Failed("failed", setOf("failed")),
    GaveUp("dead", setOf("dead", "expired", "denied", "cancelled")),
}

@Composable
internal fun DeliveryLedger(
    deliveries: List<MessageDeliveryEntity>,
    onSelect: (MessageDeliveryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filterGroup by remember { mutableStateOf<LedgerGroup?>(null) }
    var filterChannel by remember { mutableStateOf<String?>(null) }
    val now = rememberTickingNow()

    val filtered = remember(deliveries, filterGroup, filterChannel) {
        deliveries.filter { d ->
            (filterGroup == null || d.status in filterGroup!!.statuses) &&
                (filterChannel == null || d.channel == filterChannel)
        }
    }
    val groupCounts = remember(deliveries) {
        LedgerGroup.entries.associateWith { g -> deliveries.count { it.status in g.statuses } }
    }
    val channels = remember(deliveries) { deliveries.map { it.channel }.distinct().sorted() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Counts by state
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(4.dp),
        ) {
            LedgerGroup.entries.forEach { g ->
                val color = Words.deliveryColor(g.key)
                val selected = filterGroup == g
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(
                            if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { filterGroup = if (selected) null else g }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = (groupCounts[g] ?: 0).toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = PlexMono,
                        color = color,
                    )
                    Text(
                        text = Words.deliveryState(g.key),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) color else MeshSatTextMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // One filter per link; the row scrolls when there are more links than fit
        if (channels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = filterChannel == null,
                    onClick = { filterChannel = null },
                    label = { Text("All links") },
                )
                channels.forEach { ch ->
                    FilterChip(
                        selected = filterChannel == ch,
                        onClick = { filterChannel = if (filterChannel == ch) null else ch },
                        label = { Text(Words.channel(ch)) },
                        leadingIcon = {
                            Box(Modifier.size(8.dp).background(Words.channelColor(ch), CircleShape))
                        },
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (deliveries.isEmpty()) {
                        "No messages here yet. Messages you send, and messages your rules pass on, show up here."
                    } else {
                        "No messages match this filter."
                    },
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
                items(filtered, key = { it.id }) { d ->
                    DeliveryCard(delivery = d, now = now, onClick = { onSelect(d) })
                }
            }
        }
    }
}

/**
 * One message on its way: the link, its state, the text, when, and what went wrong. [onRetry] and
 * [onCancel] add buttons for a message that can be retried or cancelled; leave them null to keep
 * the actions in the details dialog.
 */
@Composable
internal fun DeliveryCard(
    delivery: MessageDeliveryEntity,
    now: Long,
    onClick: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val stateColor = deliveryStateColor(delivery)
    val problem = if (delivery.status in setOf("sent", "delivered")) "" else deliveryProblemText(delivery.lastError)
    val meta = listOfNotNull(
        Words.ago(delivery.createdAt, now),
        queueUrgencyLabel(delivery.priority).takeIf { delivery.priority == 0 },
        deliveryTriesText(delivery),
        delivery.ackStatus?.let { deliveryAckText(it) },
    ).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(Words.channelColor(delivery.channel), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                text = Words.channel(delivery.channel),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = deliveryStateText(delivery),
                style = MaterialTheme.typography.labelLarge,
                color = stateColor,
            )
        }

        if (delivery.textPreview.isNotBlank()) {
            Text(
                text = delivery.textPreview,
                style = MaterialTheme.typography.bodyMedium,
                color = MeshSatTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
        )

        if (problem.isNotEmpty()) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodySmall,
                color = if (delivery.status in QUEUE_GAVE_UP_STATUSES && !isCancelledByUser(delivery)) MeshSatRed else MeshSatTextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val showCancel = onCancel != null && canCancelDelivery(delivery)
        val showRetry = onRetry != null && canRetryDelivery(delivery)
        if (showCancel || showRetry) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (showCancel) {
                    TextButton(onClick = { onCancel?.invoke() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Cancel", color = MeshSatRed)
                    }
                }
                if (showRetry) {
                    TextButton(onClick = { onRetry?.invoke() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Retry", color = MeshSatTeal)
                    }
                }
            }
        }
    }
}

/** Everything about one message; the raw fields experts want sit behind "Show details". */
@Composable
internal fun DeliveryDetailsDialog(
    delivery: MessageDeliveryEntity,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    var showDetails by remember(delivery.id) { mutableStateOf(false) }
    val now = rememberTickingNow()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Words.channelColor(delivery.channel), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("Message by ${Words.channel(delivery.channel)}")
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DeliveryFact("Status", deliveryStateText(delivery), valueColor = deliveryStateColor(delivery))
                DeliveryFact("Urgency", queueUrgencyLabel(delivery.priority))
                DeliveryFact("Added", "${Words.ago(delivery.createdAt, now)}, ${localStamp(delivery.createdAt, now)}")
                DeliveryFact("Last change", Words.ago(delivery.updatedAt, now))
                deliveryTriesText(delivery)?.let { DeliveryFact("Tries", it) }
                if (delivery.lastError.isNotBlank()) DeliveryFact("Problem", deliveryProblemText(delivery.lastError))
                delivery.ackStatus?.let { DeliveryFact("Confirmation", deliveryAckText(it)) }
                delivery.expiresAt?.let { at ->
                    if (at > now) DeliveryFact("Gives up", Words.inTime(at, now))
                    else DeliveryFact("Expired", Words.ago(at, now))
                }

                if (delivery.textPreview.isNotBlank()) {
                    Text(
                        text = delivery.textPreview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(MeshSatSurfaceLight, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                    )
                }

                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (showDetails) "Hide details" else "Show details", color = MeshSatTextSecondary)
                }
                if (showDetails) {
                    DeliveryFact("Delivery", "#${delivery.id}", mono = true)
                    DeliveryFact("Link id", delivery.channel, mono = true)
                    DeliveryFact("Stored status", delivery.status, mono = true)
                    DeliveryFact("Priority", delivery.priority.toString(), mono = true)
                    DeliveryFact("Message ref", delivery.msgRef, mono = true)
                    delivery.ruleId?.let { DeliveryFact("Rule", "#$it", mono = true) }
                    DeliveryFact("QoS level", "${delivery.qosLevel} (${queueGuaranteeLabel(delivery.qosLevel)})", mono = true)
                    if (delivery.seqNum > 0) DeliveryFact("Sequence", delivery.seqNum.toString(), mono = true)
                    if (delivery.ttlSeconds > 0) DeliveryFact("TTL", "${delivery.ttlSeconds} s", mono = true)
                    delivery.ackStatus?.let { DeliveryFact("ACK", it, mono = true) }
                    delivery.custodyStatus?.let { DeliveryFact("Custody", it, mono = true) }
                    delivery.bundleId?.let { DeliveryFact("Bundle", it, mono = true) }
                    if (delivery.lastError.isNotBlank()) DeliveryFact("Error", delivery.lastError, mono = true)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRetryDelivery(delivery)) {
                    TextButton(onClick = onRetry) { Text("Retry", color = MeshSatTeal) }
                }
                if (canCancelDelivery(delivery)) {
                    TextButton(onClick = onCancel) { Text("Cancel message", color = MeshSatRed) }
                }
                TextButton(onClick = onDismiss) { Text("Close", color = MeshSatTextSecondary) }
            }
        },
    )
}

@Composable
private fun DeliveryFact(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    mono: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) PlexMono else null,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
