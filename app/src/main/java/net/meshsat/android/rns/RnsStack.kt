package net.meshsat.android.rns

import android.util.Log
import net.meshsat.android.hemb.HembFrame
import net.meshsat.android.lxmf.LxMessage
import net.meshsat.android.lxmf.LxMethod
import net.meshsat.android.lxmf.LxmfRouter
import net.meshsat.android.reticulum.RnsInterface
import net.meshsat.android.routing.Identity
import net.meshsat.android.routing.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The upstream-compatible Reticulum node and the LXMF endpoint on the app's
 * interfaces (the Bridge's rnsstack). Every packet an interface receives is
 * offered to the node first through [inbound]; what it declines falls through to
 * the MeshSat-private stack (meshsat.node, HeMB, time sync, the Hub relay).
 * [MESHSAT-1553]
 */
class RnsStack(
    identity: Identity,
    private val interfaces: () -> Map<String, RnsInterface>,
    private val scope: CoroutineScope,
    private val keyStore: KeyValueStore,
    displayName: String,
    /** Every accepted inbound LXMF message. */
    onMessage: (LxMessage) -> Unit,
    /** An outbound LXMF message changed state. */
    onOutboundState: (LxmfRouter.Outbound) -> Unit,
) {
    val rnsIdentity: RnsIdentity = RnsIdentity.fromRouting(identity)
    val node: Node
    val lxmf: LxmfRouter

    private val sendChannels = ConcurrentHashMap<String, Channel<ByteArray>>()
    private var announceJob: Job? = null
    private var syncJob: Job? = null

    init {
        val tx = object : Transmitter {
            override fun transmit(ifaceId: String, raw: ByteArray): Boolean {
                val iface = interfaces()[ifaceId] ?: return false
                // One queue per interface keeps the order the node handed packets over in.
                val ch = sendChannels.getOrPut(ifaceId) {
                    Channel<ByteArray>(256).also { c ->
                        scope.launch {
                            for (p in c) {
                                val err = try { iface.send(p) } catch (e: Exception) { e.message ?: "send failed" }
                                if (err != null) Log.w(TAG, "send on $ifaceId failed: $err")
                            }
                        }
                    }
                }
                return ch.trySend(raw).isSuccess
            }
            override fun floodable(): List<String> =
                interfaces().filter { (id, i) -> i.isOnline && i.isBidirectional && !isPaidInterface(id) }.keys.sorted()
            override fun hwMtu(ifaceId: String): Int = interfaces()[ifaceId]?.mtu ?: 0
            override fun bitrate(ifaceId: String): Int = when {
                ifaceId.startsWith("mesh") -> LORA_BITRATE   // the announce cap keeps the LoRa mesh usable
                else -> 0
            }
            override fun online(ifaceId: String): Boolean = interfaces()[ifaceId]?.isOnline == true
        }
        node = Node(NodeConfig(rnsIdentity, forwardHeader1From = setOf("mesh_rns_0"), log = { Log.d(TAG, it) }), tx)
        val ratchets = keyStore.get(KEY_RATCHETS)?.split(",")?.filter { it.length == 2 * Rns.RATCHET_LEN }?.map { it.unhex() } ?: emptyList()
        val ratchetsLatest = keyStore.get(KEY_RATCHETS_LATEST)?.toLongOrNull() ?: 0L
        lxmf = LxmfRouter(node, LxmfRouter.Config(
            identity = rnsIdentity,
            displayName = displayName,
            stampCost = 0,
            ratchets = ratchets,
            ratchetsLatestMs = ratchetsLatest,
            onRatchetsChanged = { list, latest ->
                keyStore.set(KEY_RATCHETS, list.joinToString(",") { it.hex() })
                keyStore.set(KEY_RATCHETS_LATEST, latest.toString())
            },
            onMessage = onMessage,
            onOutboundState = onOutboundState,
            log = { Log.i(TAG, it) },
        ))
    }

    /** Our LXMF address, what a Sideband user types to reach this phone. */
    val lxmfAddress: String get() = lxmf.hashHex

    /**
     * Offers a received packet to the node. True when it consumed it; false for
     * HeMB frames and everything the node does not handle, which the legacy
     * stack then takes.
     */
    fun inbound(ifaceId: String, raw: ByteArray): Boolean {
        if (raw.isNotEmpty() && HembFrame.isHembFrame(raw)) return false
        return try { node.inbound(raw, ifaceId) } catch (e: Exception) { Log.w(TAG, "node inbound failed on $ifaceId: $e"); false }
    }

    fun start(announceIntervalMs: Long) {
        node.start()
        lxmf.start()
        announceJob = scope.launch {
            delay(5_000)
            while (isActive) {
                announce()
                delay(announceIntervalMs)
            }
        }
        Log.i(TAG, "Reticulum node ${node.idHashHex.take(8)} started, LXMF address $lxmfAddress (${lxmf.displayName})")
    }

    fun stop() {
        announceJob?.cancel()
        syncJob?.cancel()
        lxmf.stop()
        node.stop()
        for (c in sendChannels.values) c.close()
        sendChannels.clear()
    }

    /** Announces the LXMF destination on every floodable interface and every paid interface open to it. */
    fun announce(): Boolean {
        val sent = lxmf.announce()
        Log.i(TAG, if (sent) "LXMF announce sent" else "LXMF announce: no interface to send on")
        return sent
    }

    /**
     * Opens the paid interfaces (the satellite) to the listed destinations plus our
     * own LXMF address, at most one announce per [minIntervalMs] per destination,
     * and counts every packet arriving over them as [extraHops] more hops, so that
     * a stock client's link and receipt timeouts cover the satellite round trip.
     */
    fun setPaidPolicy(dests: List<ByteArray>, extraHops: Int, minIntervalMs: Long) {
        val all = (dests + listOf(lxmf.hash)).distinctBy { it.hex() }
        val policy = PaidPolicy(all, minIntervalMs, bitrate = 0, extraHops = extraHops.coerceIn(0, PaidPolicy.MAX_EXTRA_HOPS))
        for (id in interfaces().keys.filter { isPaidInterface(it) }) node.setPaidPolicy(id, policy)
        Log.i(TAG, "paid link policy: ${all.size} destinations, extra hops $extraHops, every ${minIntervalMs / 60_000} min, on ${interfaces().keys.filter { isPaidInterface(it) }}")
    }

    /** The propagation node choice from settings: "" = the nearest heard, "none" = off, else a 32-hex address. */
    fun setPropagationNode(choice: String) {
        val c = choice.trim().lowercase()
        when {
            c == "none" -> { lxmf.autoSelectPropagationNode = false; lxmf.setPropagationNode(null) }
            c.length == 32 && c.all { it in "0123456789abcdef" } -> { lxmf.autoSelectPropagationNode = true; lxmf.setPropagationNode(c.unhex()) }
            else -> { lxmf.autoSelectPropagationNode = true; lxmf.setPropagationNode(null) }
        }
    }

    /** Syncs from the propagation node every [intervalMs] (0 stops), the first time half a minute in. */
    fun startSyncLoop(intervalMs: Long) {
        syncJob?.cancel()
        if (intervalMs <= 0) { syncJob = null; return }
        syncJob = scope.launch {
            delay(30_000)
            while (isActive) {
                if (lxmf.propagationNode() != null) lxmf.sync()
                delay(intervalMs)
            }
        }
    }

    /** Sends an LXMF message; [method] null = auto (direct across the satellite). */
    fun send(dest: ByteArray, content: String, title: String = "", method: LxMethod? = null): LxmfRouter.Outbound =
        lxmf.send(dest, content.toByteArray(Charsets.UTF_8), title.toByteArray(Charsets.UTF_8), method = method)

    companion object {
        private const val TAG = "RnsStack"
        const val KEY_RATCHETS = "lxmf_ratchets"
        const val KEY_RATCHETS_LATEST = "lxmf_ratchets_latest_ms"
        /** LongFast is about a kilobit a second; the 2 % announce cap works from this. */
        const val LORA_BITRATE = 1000
        private val PAID_PREFIXES = listOf("iridium", "sms", "cellular")

        /** Paid bearers never carry broadcast traffic: a policy opens them to named destinations. */
        fun isPaidInterface(id: String): Boolean = PAID_PREFIXES.any { id.startsWith(it) }

        /** The LXMF delivery hash of a routing identity, so settings can show it before the service runs. */
        fun lxmfAddressOf(identity: Identity): String = RnsIdentity.fromRouting(identity).destHash(LxMessage.DELIVERY_NAME).hex()
    }
}
