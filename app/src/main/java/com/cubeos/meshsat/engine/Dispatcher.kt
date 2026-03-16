package com.cubeos.meshsat.engine

import android.util.Log
import com.cubeos.meshsat.channel.ChannelRegistry
import com.cubeos.meshsat.data.MessageDeliveryDao
import com.cubeos.meshsat.data.MessageDeliveryEntity
import com.cubeos.meshsat.rules.AccessEvaluator
import com.cubeos.meshsat.rules.RouteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Dispatcher evaluates access rules and fans out messages to per-channel delivery workers.
 * Port of Go's engine.Dispatcher — uses Kotlin coroutines instead of goroutines.
 *
 * Phase C additions: InterfaceManager integration for state-driven hold/unhold,
 * SequenceTracker for per-interface monotonic sequence numbers,
 * ACK tracking for at-least-once delivery semantics (QoS >= 1).
 *
 * @param deliveryDao Room DAO for the message_deliveries table.
 * @param accessEvaluator Access rule evaluator (ingress/egress).
 * @param failoverResolver Resolves failover group IDs to concrete interfaces.
 * @param registry Channel registry for retry config and capabilities.
 * @param deliveryCallback Called by workers to actually send a message to a transport.
 * @param scope Coroutine scope for background work.
 * @param sequenceTracker Per-interface monotonic sequence counter (Phase C).
 */
class Dispatcher(
    private val deliveryDao: MessageDeliveryDao,
    private val accessEvaluator: AccessEvaluator,
    private val failoverResolver: FailoverResolver?,
    private val registry: ChannelRegistry,
    private val deliveryCallback: DeliveryCallback,
    private val scope: CoroutineScope,
    private val sequenceTracker: SequenceTracker = SequenceTracker(),
) {
    /** Callback for actual message delivery to a transport. */
    fun interface DeliveryCallback {
        /** Send a message to the given interface. Returns null on success, error message on failure. */
        suspend fun deliver(interfaceId: String, payload: ByteArray, textPreview: String): String?
    }

    // Loop prevention metrics
    val hopLimitDrops = AtomicLong(0)
    val visitedSetDrops = AtomicLong(0)
    val selfLoopDrops = AtomicLong(0)
    val deliveryDedups = AtomicLong(0)

    private var maxHops = DEFAULT_MAX_HOPS
    private var maxQueueDepth = DEFAULT_MAX_QUEUE_DEPTH

    // Content-hash dedup cache
    private val dedupLock = ReentrantLock()
    private val dedupCache = LinkedHashMap<String, Long>(64, 0.75f, true)
    private val dedupTtlMs = DEDUP_TTL.inWholeMilliseconds

    // Per-interface worker jobs
    private val workers = ConcurrentHashMap<String, Job>()

    /**
     * Start the dispatcher: launch per-interface workers and background reapers.
     * @param interfaces Map of interface ID → channel type for all enabled interfaces.
     */
    fun start(interfaces: Map<String, String>) {
        scope.launch {
            // Cancel runaway deliveries
            val cancelled = deliveryDao.cancelRunaway()
            if (cancelled > 0) Log.w(TAG, "Cancelled $cancelled runaway deliveries")

            // Recover stale 'sending' deliveries
            val recovered = deliveryDao.recoverStale()
            if (recovered > 0) Log.i(TAG, "Recovered $recovered stale deliveries")
        }

        // Start a delivery worker for each interface
        for ((ifaceId, _) in interfaces) {
            startWorker(ifaceId)
        }

        // Start dedup pruner (every 2 minutes)
        scope.launch {
            while (isActive) {
                delay(2.minutes)
                pruneDedup()
            }
        }

        // Start TTL reaper (every 60 seconds)
        scope.launch {
            while (isActive) {
                delay(60.seconds)
                val expired = deliveryDao.expireDeliveries()
                if (expired > 0) Log.i(TAG, "TTL reaper: expired $expired deliveries")
            }
        }

        Log.i(TAG, "Dispatcher started with ${interfaces.size} interface workers")
    }

    /** Start a delivery worker for a specific interface (e.g. when it comes online). */
    fun startWorker(interfaceId: String) {
        if (workers.containsKey(interfaceId)) return

        val job = scope.launch {
            // Unhold deliveries when interface comes online
            val unheld = deliveryDao.unholdForChannel(interfaceId)
            if (unheld > 0) Log.i(TAG, "Unheld $unheld deliveries for $interfaceId")

            // Poll loop
            while (isActive) {
                delay(2.seconds)
                processBatch(interfaceId)
            }
        }
        workers[interfaceId] = job
        Log.i(TAG, "Worker started for $interfaceId")
    }

    /** Stop a delivery worker (e.g. when interface goes offline). */
    fun stopWorker(interfaceId: String) {
        workers.remove(interfaceId)?.cancel()

        scope.launch {
            val held = deliveryDao.holdForChannel(interfaceId)
            if (held > 0) Log.i(TAG, "Held $held deliveries for $interfaceId (offline)")
        }
    }

    /** Stop all workers. */
    fun stop() {
        workers.values.forEach { it.cancel() }
        workers.clear()
    }

    /**
     * Handle an InterfaceManager state change.
     * Called from InterfaceManager's state change callback to drive hold/unhold.
     */
    fun onInterfaceStateChange(interfaceId: String, channelType: String, old: InterfaceState, new: InterfaceState) {
        when {
            new == InterfaceState.Online && old != InterfaceState.Online -> {
                startWorker(interfaceId)
            }
            new != InterfaceState.Online && old == InterfaceState.Online -> {
                stopWorker(interfaceId)
            }
            new == InterfaceState.Error && old != InterfaceState.Online -> {
                // Already offline, ensure held
                scope.launch {
                    deliveryDao.holdForChannel(interfaceId)
                }
            }
        }
    }

    /**
     * Evaluate access rules for a message arriving on an interface.
     * Creates delivery ledger entries for each matched rule.
     * Returns the number of deliveries created.
     */
    suspend fun dispatchAccess(
        sourceInterface: String,
        msg: RouteMessage,
        payload: ByteArray,
    ): Int {
        // Max hop count
        if (msg.visited.size >= maxHops) {
            hopLimitDrops.incrementAndGet()
            Log.w(TAG, "Max hops exceeded (${msg.visited.size}/$maxHops), dropping")
            return 0
        }

        val matches = accessEvaluator.evaluateIngress(sourceInterface, msg)
        if (matches.isEmpty()) return 0

        val msgRef = "${System.currentTimeMillis()}-${(System.nanoTime() % 100000)}"

        var count = 0
        for (match in matches) {
            // Resolve failover groups
            var destInterface = match.forwardTo
            if (failoverResolver != null) {
                val resolved = failoverResolver.resolve(match.forwardTo)
                if (resolved.isEmpty()) {
                    Log.w(TAG, "Failover: no available interface for ${match.forwardTo}")
                    continue
                }
                destInterface = resolved
            }

            // Post-resolution loop prevention
            if (destInterface == sourceInterface) {
                selfLoopDrops.incrementAndGet()
                continue
            }
            if (destInterface in msg.visited) {
                visitedSetDrops.incrementAndGet()
                Log.d(TAG, "Visited set: $destInterface already visited")
                continue
            }

            // Resolve retry config from channel registry
            val channelType = destInterface.substringBeforeLast('_')
            val desc = registry.get(channelType)
            var maxRetries = 3
            if (desc != null && desc.retryConfig.enabled) {
                maxRetries = desc.retryConfig.maxRetries
            }

            val preview = if (msg.text.length > 200) msg.text.take(200) else msg.text

            // Build visited set
            val visitedSet = buildList {
                add(sourceInterface)
                addAll(msg.visited)
            }.distinct()
            val visitedJson = JSONArray(visitedSet).toString()

            // Parse forward_options for TTL
            var ttlSeconds = 0
            val fwdOpts = match.rule.forwardOptions
            if (fwdOpts.isNotEmpty() && fwdOpts != "{}") {
                try {
                    ttlSeconds = JSONObject(fwdOpts).optInt("ttl_seconds", 0)
                } catch (_: Exception) {}
            }
            // Apply per-channel default TTL
            if (ttlSeconds == 0 && desc != null && desc.defaultTtl.inWholeSeconds > 0) {
                ttlSeconds = desc.defaultTtl.inWholeSeconds.toInt()
            }

            // Content-hash dedup
            if (isDeliveryDuplicate(destInterface, payload)) {
                deliveryDedups.incrementAndGet()
                Log.d(TAG, "Dedup: same payload to $destInterface, skipping")
                continue
            }

            // Queue depth check
            if (maxQueueDepth > 0) {
                val depth = deliveryDao.queueDepth(destInterface)
                if (depth >= maxQueueDepth) {
                    Log.w(TAG, "Queue full for $destInterface ($depth/$maxQueueDepth)")
                    continue
                }
            }

            // QoS 0: no retries
            if (match.rule.qosLevel == 0) maxRetries = 0

            val expiresAt = if (ttlSeconds > 0 && match.rule.priority > 0) {
                System.currentTimeMillis() + ttlSeconds * 1000L
            } else {
                null
            }

            val delivery = MessageDeliveryEntity(
                msgRef = msgRef,
                ruleId = match.rule.id,
                channel = destInterface,
                status = "queued",
                priority = match.rule.priority,
                payload = payload,
                textPreview = preview,
                maxRetries = maxRetries,
                visited = visitedJson,
                ttlSeconds = ttlSeconds,
                expiresAt = expiresAt,
                qosLevel = match.rule.qosLevel,
            )

            try {
                deliveryDao.insert(delivery)
                count++
                Log.i(TAG, "Delivery queued: rule=${match.rule.id} dest=$destInterface ref=$msgRef")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert delivery: ${e.message}")
            }
        }

        return count
    }

    // --- Worker logic ---

    private suspend fun processBatch(channelId: String) {
        val now = System.currentTimeMillis()
        val deliveries = try {
            deliveryDao.getPending(channelId, now)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pending for $channelId: ${e.message}")
            return
        }

        for (del in deliveries) {
            deliver(channelId, del)
        }
    }

    private suspend fun deliver(channelId: String, del: MessageDeliveryEntity) {
        // Re-check status (may have changed)
        val fresh = deliveryDao.getById(del.id) ?: return
        if (fresh.status in listOf("sent", "dead", "cancelled", "delivered")) return

        // Egress rule check
        if (accessEvaluator.hasEgressRules(channelId)) {
            val egressMsg = RouteMessage(text = del.textPreview)
            val matches = accessEvaluator.evaluateEgress(channelId, egressMsg)
            if (matches.isEmpty()) {
                deliveryDao.setStatus(del.id, "denied", "egress rules denied")
                Log.i(TAG, "Delivery ${del.id} denied by egress rules on $channelId")
                return
            }
        }

        // TTL check before send (P0 exempt)
        if (del.priority > 0 && del.expiresAt != null && System.currentTimeMillis() > del.expiresAt) {
            deliveryDao.setStatus(del.id, "expired", "TTL expired before send")
            return
        }

        // Mark as sending
        deliveryDao.setStatus(del.id, "sending")

        // Attempt delivery via callback
        val payload = del.payload ?: del.textPreview.toByteArray()
        val error = deliveryCallback.deliver(channelId, payload, del.textPreview)

        if (error != null) {
            handleFailure(channelId, del, error)
        } else {
            handleSuccess(channelId, del)
        }
    }

    private suspend fun handleSuccess(channelId: String, del: MessageDeliveryEntity) {
        // Assign egress sequence number
        val seqNum = sequenceTracker.nextEgressSeq(channelId)
        deliveryDao.setSeqNum(del.id, seqNum)

        deliveryDao.setStatus(del.id, "sent")

        // For QoS >= 1, mark ACK as pending (at-least-once semantics)
        if (del.qosLevel >= 1) {
            deliveryDao.setAckPending(del.id)
            Log.i(TAG, "Delivery ${del.id} sent to $channelId (seq=$seqNum, ack=pending)")
        } else {
            Log.i(TAG, "Delivery ${del.id} sent to $channelId (seq=$seqNum, qos=0)")
        }
    }

    private suspend fun handleFailure(channelId: String, del: MessageDeliveryEntity, error: String) {
        // QoS 0: no retry
        if (del.qosLevel == 0) {
            deliveryDao.setStatus(del.id, "dead", error)
            Log.i(TAG, "QoS 0 delivery ${del.id} failed (no retry): $error")
            return
        }

        val newRetries = del.retries + 1
        if (del.maxRetries > 0 && newRetries >= del.maxRetries) {
            deliveryDao.setStatus(del.id, "dead", error)
            Log.w(TAG, "Delivery ${del.id} exhausted retries ($newRetries): $error")
            return
        }

        // Schedule retry with backoff
        val nextRetry = calculateNextRetry(channelId, newRetries)
        deliveryDao.scheduleRetry(del.id, newRetries, nextRetry, error)
        Log.w(TAG, "Delivery ${del.id} retry $newRetries scheduled for $channelId")
    }

    private fun calculateNextRetry(channelId: String, retries: Int): Long {
        val channelType = channelId.substringBeforeLast('_')
        val desc = registry.get(channelType)
        val config = desc?.retryConfig

        val initialWait = config?.initialWait ?: 5.seconds
        val maxWait = config?.maxWait ?: 5.minutes
        val backoffFunc = config?.backoffFunc ?: "linear"

        var wait = when (backoffFunc) {
            "isu" -> maxOf(initialWait, 3.minutes)
            "exponential" -> {
                var w = initialWait
                repeat(retries - 1) { w *= 2 }
                w
            }
            else -> initialWait * retries
        }

        if (wait > maxWait) wait = maxWait

        return System.currentTimeMillis() + wait.inWholeMilliseconds
    }

    // --- Dedup ---

    private fun isDeliveryDuplicate(destInterface: String, payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$destInterface|".toByteArray())
        digest.update(payload)
        val key = digest.digest().take(12).joinToString("") { "%02x".format(it) }

        dedupLock.withLock {
            val existing = dedupCache[key]
            val now = System.currentTimeMillis()
            if (existing != null && now - existing < dedupTtlMs) {
                return true
            }
            dedupCache[key] = now
            // Evict oldest if too large
            if (dedupCache.size > MAX_DEDUP_ENTRIES) {
                val oldest = dedupCache.keys.first()
                dedupCache.remove(oldest)
            }
            return false
        }
    }

    private fun pruneDedup() {
        dedupLock.withLock {
            val now = System.currentTimeMillis()
            dedupCache.entries.removeAll { now - it.value > dedupTtlMs }
        }
    }

    companion object {
        private const val TAG = "Dispatcher"
        private const val DEFAULT_MAX_HOPS = 8
        private const val DEFAULT_MAX_QUEUE_DEPTH = 500
        private const val MAX_DEDUP_ENTRIES = 1000
        private val DEDUP_TTL = 5.minutes
    }
}
