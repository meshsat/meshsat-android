package net.meshsat.android.api

import android.util.Log
import com.geeksville.mesh.ChannelProtos
import com.google.protobuf.ByteString
import net.meshsat.android.channel.ChannelRegistry
import net.meshsat.android.config.ConfigManager
import net.meshsat.android.data.AuditLogDao
import net.meshsat.android.data.MessageDeliveryDao
import net.meshsat.android.data.TelemetryDao
import net.meshsat.android.data.TelemetryEntity
import net.meshsat.android.engine.DeadManSwitch
import net.meshsat.android.engine.GeofenceMonitor
import net.meshsat.android.engine.HealthScorer
import net.meshsat.android.engine.InterfaceState
import net.meshsat.android.engine.InterfaceManager
import net.meshsat.android.engine.SigningService
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight local REST API server for MeshSat Android.
 * Runs on localhost only (127.0.0.1) for automation and scripting.
 * Port of meshsat/internal/api/ — exposes interfaces, rules, deliveries, health, geofences, audit.
 *
 * Uses NanoHTTPD for zero-dependency HTTP serving.
 */
class LocalApiServer(
    port: Int = DEFAULT_PORT,
    private val scope: CoroutineScope,
    private val interfaceManager: InterfaceManager?,
    private val channelRegistry: ChannelRegistry?,
    private val healthScorer: HealthScorer?,
    private val deliveryDao: MessageDeliveryDao?,
    private val auditLogDao: AuditLogDao?,
    private val telemetryDao: TelemetryDao? = null,
    private val geofenceMonitor: GeofenceMonitor?,
    private val deadManSwitch: DeadManSwitch?,
    private val signingService: SigningService?,
    private val configManager: ConfigManager?,
    private val restartCallback: (() -> Unit)? = null,
    private val smsSendCallback: ((to: String, text: String) -> Unit)? = null,
    private val hubSettingsCallback: ((Map<String, String>) -> Unit)? = null,
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        val method = session.method

        return try {
            route(method, uri, session)
        } catch (e: Exception) {
            Log.e(TAG, "API error: ${method.name} $uri: ${e.message}")
            jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "internal error")
        }
    }

    private fun route(method: Method, uri: String, session: IHTTPSession): Response {
        return when {
            // Health
            method == Method.GET && uri == "/api/health" -> handleHealth()

            // Interfaces
            method == Method.GET && uri == "/api/interfaces" -> handleGetInterfaces()
            method == Method.GET && uri == "/api/interfaces/health" -> handleGetInterfaceHealth()

            // Deliveries
            method == Method.GET && uri == "/api/deliveries/stats" -> handleDeliveryStats()
            method == Method.GET && uri == "/api/deliveries/recent" -> handleRecentDeliveries(session)

            // Geofences
            method == Method.GET && uri == "/api/geofences" -> handleGetGeofences()

            // Dead man's switch
            method == Method.GET && uri == "/api/deadman" -> handleGetDeadman()

            // Audit
            method == Method.GET && uri == "/api/audit" -> handleGetAuditLog(session)
            method == Method.GET && uri == "/api/audit/verify" -> handleVerifyAuditChain(session)
            method == Method.GET && uri == "/api/audit/signer" -> handleGetSignerId()

            // Config
            method == Method.GET && uri == "/api/config/export" -> handleConfigExport()
            method == Method.POST && uri == "/api/config/import" -> handleConfigImport(session)
            method == Method.POST && uri == "/api/config/diff" -> handleConfigDiff(session)

            // SMS
            method == Method.POST && uri == "/api/sms/send" -> handleSmsSend(session)
            method == Method.POST && uri == "/api/sms/auto-forward" -> handleSmsAutoForward(session)
            method == Method.GET && uri == "/api/sms/auto-forward" -> jsonOk(JSONObject().put("forward_to", net.meshsat.android.sms.SmsReceiver.autoForwardTo))

            // Settings (localhost only, for E2E automation)
            method == Method.POST && uri == "/api/settings/hub" -> handleHubSettings(session)

            // Iridium 9603 on the MeshSat node (MESHSAT-1236); both are free, no satellite session
            method == Method.GET && uri == "/api/iridium/status" -> handleIridiumStatus()
            method == Method.POST && uri == "/api/iridium/loopback" -> handleIridiumLoopback(session)
            // A drill: the live pipe stops taking writes, to watch the app recover (MESHSAT-1270)
            method == Method.POST && uri == "/api/iridium/drill/dead-pipe" -> handleIridiumDeadPipeDrill()
            // Billed: one satellite session, like the Check Mailbox button (MESHSAT-400)
            method == Method.POST && uri == "/api/iridium/mailbox" -> handleIridiumMailbox()

            // The node's whole configuration, read and written in one go (MESHSAT-1285)
            method == Method.GET && uri == "/api/mesh/config" -> handleMeshConfig()
            method == Method.POST && uri == "/api/mesh/profile" -> handleMeshProfile(session)

            // System
            method == Method.POST && uri == "/api/system/restart" -> handleRestart()

            // Telemetry (MESHSAT-494)
            method == Method.GET && uri == "/api/telemetry/crashes" -> handleTelemetry(session, "crash")
            method == Method.GET && uri == "/api/telemetry/heap" -> handleTelemetry(session, "heap")
            method == Method.GET && uri == "/api/telemetry/health" -> handleTelemetry(session, "health")
            method == Method.GET && uri == "/api/telemetry/events" -> handleTelemetry(session, "event")
            method == Method.GET && uri == "/api/telemetry" -> handleTelemetryAll(session)
            method == Method.DELETE && uri == "/api/telemetry" -> handleTelemetryClear()

            else -> jsonError(Response.Status.NOT_FOUND, "not found: $uri")
        }
    }

    // --- Health ---

    private fun handleHealth(): Response {
        // Disabled interfaces are the ones this phone has no hardware or configuration for.
        // Counting them made a phone where everything worked read 4 of 8 (MESHSAT-1261).
        val ifaces = (interfaceManager?.getAllStatus() ?: emptyList())
            .filter { it.state != InterfaceState.Disabled }
        val online = ifaces.count { it.state.isAvailable }
        val json = JSONObject().apply {
            put("status", if (online > 0) "ok" else "degraded")
            put("interfaces_online", online)
            put("interfaces_total", ifaces.size)
        }
        return jsonOk(json)
    }

    // --- Interfaces ---

    private fun handleGetInterfaces(): Response {
        val statuses = interfaceManager?.getAllStatus() ?: emptyList()
        val arr = JSONArray()
        for (s in statuses) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("channel_type", s.channelType)
                put("state", s.state.name.lowercase())
                put("error", s.error)
                put("last_online", s.lastOnline)
                put("last_activity", s.lastActivity)
                put("reconnect_attempts", s.reconnectAttempts)
            })
        }
        return jsonOk(arr)
    }

    private fun handleGetInterfaceHealth(): Response {
        val scorer = healthScorer ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "health scorer not available")
        val scores = runBlocking { scorer.scoreAll() }
        val arr = JSONArray()
        for (h in scores) {
            arr.put(JSONObject().apply {
                put("interface_id", h.interfaceId)
                put("score", h.score)
                put("signal", h.signal)
                put("success_rate", h.successRate)
                put("latency_ms", h.latencyMs)
                put("cost_score", h.costScore)
                put("available", h.available)
            })
        }
        return jsonOk(arr)
    }

    // --- Deliveries ---

    private fun handleDeliveryStats(): Response {
        val dao = deliveryDao ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "delivery dao not available")
        val stats = runBlocking { dao.stats() }
        val arr = JSONArray()
        for (s in stats) {
            arr.put(JSONObject().apply {
                put("channel", s.channel)
                put("status", s.status)
                put("count", s.cnt)
            })
        }
        return jsonOk(arr)
    }

    private fun handleRecentDeliveries(session: IHTTPSession): Response {
        val dao = deliveryDao ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "delivery dao not available")
        val limit = session.parms["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val deliveries = runBlocking { dao.getRecentSync(limit) }
        val arr = JSONArray()
        for (d in deliveries) {
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("msg_ref", d.msgRef)
                put("channel", d.channel)
                put("status", d.status)
                put("priority", d.priority)
                put("text_preview", d.textPreview)
                put("retries", d.retries)
                put("last_error", d.lastError)
                put("qos_level", d.qosLevel)
                put("seq_num", d.seqNum)
                put("ack_status", d.ackStatus ?: JSONObject.NULL)
                put("created_at", d.createdAt)
                put("updated_at", d.updatedAt)
            })
        }
        return jsonOk(arr)
    }

    // --- Geofences ---

    private fun handleGetGeofences(): Response {
        val monitor = geofenceMonitor ?: return jsonOk(JSONArray())
        val zones = monitor.getZones()
        val arr = JSONArray()
        for (z in zones) {
            arr.put(JSONObject().apply {
                put("id", z.id)
                put("name", z.name)
                put("alert_on", z.alertOn)
                put("message", z.message)
                val polyArr = JSONArray()
                for (p in z.polygon) {
                    polyArr.put(JSONObject().apply {
                        put("lat", p.lat)
                        put("lon", p.lon)
                    })
                }
                put("polygon", polyArr)
            })
        }
        return jsonOk(arr)
    }

    // --- Dead man's switch ---

    private fun handleGetDeadman(): Response {
        val dms = deadManSwitch ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "dead man's switch not available")
        val json = JSONObject().apply {
            put("enabled", dms.isEnabled())
            put("triggered", dms.isTriggered())
            put("last_activity", dms.lastActivity())
            put("timeout_seconds", dms.getTimeout().inWholeSeconds)
        }
        return jsonOk(json)
    }

    // --- Audit ---

    private fun handleGetAuditLog(session: IHTTPSession): Response {
        val dao = auditLogDao ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "audit log not available")
        val limit = session.parms["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
        val interfaceId = session.parms["interface_id"]

        val entries = runBlocking {
            if (interfaceId != null) {
                dao.getByInterface(interfaceId, limit)
            } else {
                dao.getRecent(limit)
            }
        }

        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("timestamp", e.timestamp)
                put("interface_id", e.interfaceId ?: JSONObject.NULL)
                put("direction", e.direction ?: JSONObject.NULL)
                put("event_type", e.eventType)
                put("delivery_id", e.deliveryId ?: JSONObject.NULL)
                put("rule_id", e.ruleId ?: JSONObject.NULL)
                put("detail", e.detail)
                put("prev_hash", e.prevHash)
                put("hash", e.hash)
            })
        }
        return jsonOk(arr)
    }

    private fun handleVerifyAuditChain(session: IHTTPSession): Response {
        val signing = signingService ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "signing service not available")
        val limit = session.parms["limit"]?.toIntOrNull()?.coerceIn(1, 10000) ?: 1000
        val (valid, brokenAt) = runBlocking { signing.verifyChain(limit) }
        val json = JSONObject().apply {
            put("verified", brokenAt == -1)
            put("valid", valid)
            put("checked", valid + if (brokenAt >= 0) 1 else 0)
            put("broken_at", brokenAt)
        }
        return jsonOk(json)
    }

    private fun handleGetSignerId(): Response {
        val signing = signingService ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "signing service not available")
        val json = JSONObject().apply {
            put("signer_id", signing.signerId)
        }
        return jsonOk(json)
    }

    // --- Config ---

    private fun handleConfigExport(): Response {
        val mgr = configManager ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "config manager not available")
        val config = runBlocking { mgr.export() }
        return newFixedLengthResponse(Response.Status.OK, "application/json", config)
    }

    private fun handleConfigImport(session: IHTTPSession): Response {
        val mgr = configManager ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "config manager not available")
        val body = readBody(session)
        val counts = runBlocking { mgr.import(body) }
        val json = JSONObject()
        for ((k, v) in counts) json.put(k, v)
        return jsonOk(json)
    }

    private fun handleConfigDiff(session: IHTTPSession): Response {
        val mgr = configManager ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "config manager not available")
        val body = readBody(session)
        val diff = runBlocking { mgr.diff(body) }
        return jsonOk(diff.toJson())
    }

    // --- Settings ---

    private fun handleHubSettings(session: IHTTPSession): Response {
        val cb = hubSettingsCallback
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "settings not available")
        val body = readBody(session)
        val json = JSONObject(body)
        val settings = mutableMapOf<String, String>()
        json.keys().forEach { key -> settings[key] = json.optString(key, "") }
        cb(settings)
        return jsonOk(JSONObject().apply { put("status", "ok"); put("keys", settings.size) })
    }

    // --- Telemetry (MESHSAT-494) ---

    private fun handleTelemetry(session: IHTTPSession, type: String): Response {
        val dao = telemetryDao ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "telemetry not available")
        val limit = session.parms["limit"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 100
        val entries = runBlocking { dao.getByType(type, limit) }
        return jsonOk(telemetryToJson(entries))
    }

    private fun handleTelemetryAll(session: IHTTPSession): Response {
        val dao = telemetryDao ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "telemetry not available")
        val limit = session.parms["limit"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 200
        val entries = runBlocking { dao.getRecent(limit) }
        val counts = runBlocking {
            mapOf(
                "crash" to dao.countByType("crash"),
                "heap" to dao.countByType("heap"),
                "health" to dao.countByType("health"),
                "event" to dao.countByType("event"),
            )
        }
        val json = JSONObject().apply {
            put("counts", JSONObject(counts as Map<*, *>))
            put("entries", telemetryToJson(entries))
        }
        return jsonOk(json)
    }

    private fun handleTelemetryClear(): Response {
        val dao = telemetryDao ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "telemetry not available")
        runBlocking { dao.deleteAll() }
        return jsonOk(JSONObject().put("status", "cleared"))
    }

    private fun telemetryToJson(entries: List<TelemetryEntity>): JSONArray {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("timestamp", e.timestamp)
                put("type", e.type)
                put("tag", e.tag)
                put("severity", e.severity)
                put("message", e.message)
                // detail is already a JSON string — parse it so clients don't get doubly-encoded
                put("detail", try { JSONObject(e.detail) } catch (_: Exception) { e.detail })
            })
        }
        return arr
    }

    // --- Iridium (MESHSAT-1236) ---

    private fun handleIridiumStatus(): Response {
        val spp = net.meshsat.android.service.GatewayService.iridiumSpp
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "iridium not available")
        val pipe = net.meshsat.android.service.GatewayService.meshtasticBle?.iridiumPipe?.value
        return jsonOk(JSONObject().apply {
            put("state", spp.state.value.name)
            put("node_pipe", pipe != null)
            put("owner", pipe?.owner?.value?.name ?: "")
            put("imei", spp.modemInfo.value.imei)
            put("signal", spp.signal.value)
            put("sbdix_hold_ms", spp.sbdixHoldRemainingMs())
            put("link_broken", spp.linkBroken.value)
        })
    }

    /**
     * Make the pipe to the node refuse every write until the link is rebuilt. Free: nothing
     * reaches the modem. The app is expected to notice within three writes, take iridium_0
     * offline, reconnect to the node and claim the modem again, without a person.
     */
    private fun handleIridiumDeadPipeDrill(): Response {
        val pipe = net.meshsat.android.service.GatewayService.meshtasticBle?.iridiumPipe?.value
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "no pipe to the node")
        pipe.refuseWrites = true
        return jsonOk(JSONObject().put("refusing_writes", true))
    }

    /** SBDWB -> SBDTC -> SBDRB through the node's pipe; ?size=1..270, default 100. */
    private fun handleIridiumLoopback(session: IHTTPSession): Response {
        val spp = net.meshsat.android.service.GatewayService.iridiumSpp
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "iridium not available")
        val size = session.parms["size"]?.toIntOrNull()?.coerceIn(1, 270) ?: 100
        val started = System.currentTimeMillis()
        val ok = runBlocking { spp.loopbackTest(size) }
        return jsonOk(JSONObject().apply {
            put("ok", ok)
            put("bytes", size)
            put("ms", System.currentTimeMillis() - started)
        })
    }

    private fun nodeSections(ble: net.meshsat.android.ble.MeshtasticBle): net.meshsat.android.ble.NodeSections {
        val primary = ble.channels.value.firstOrNull { it.index == 0 }?.let { ch ->
            ChannelProtos.Channel.newBuilder()
                .setIndex(0)
                .setRoleValue(ch.role)
                .setSettings(
                    ch.settings ?: ChannelProtos.ChannelSettings.newBuilder()
                        .setName(ch.name)
                        .setPsk(ByteString.copyFrom(ch.psk))
                        .build(),
                )
                .build()
        }
        return net.meshsat.android.ble.NodeSections(
            device = ble.deviceConfig.value,
            lora = ble.loraConfig.value,
            position = ble.positionConfig.value,
            power = ble.powerConfig.value,
            bluetooth = ble.bluetoothConfig.value,
            network = ble.networkConfig.value,
            primaryChannel = primary,
        )
    }

    /** Everything the node has reported about itself. A channel key is shown as a fingerprint only. */
    private fun handleMeshConfig(): Response {
        val ble = net.meshsat.android.service.GatewayService.meshtasticBle
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "mesh not available")
        val now = nodeSections(ble)
        return jsonOk(JSONObject().apply {
            put("connected", ble.state.value == net.meshsat.android.ble.MeshtasticBle.State.Connected)
            put("long_name", ble.ownerName.value)
            put("short_name", ble.ownerShortName.value)
            put("firmware", ble.deviceMetadata.value?.firmwareVersion ?: "")
            now.device?.let {
                put("device", JSONObject()
                    .put("role", it.role.name)
                    .put("rebroadcast_mode", it.rebroadcastMode.name)
                    .put("node_info_broadcast_secs", it.nodeInfoBroadcastSecs))
            }
            now.lora?.let {
                put("lora", JSONObject()
                    .put("region", it.region.name)
                    .put("use_preset", it.usePreset)
                    .put("preset", it.modemPreset.name)
                    .put("tx_power", it.txPower)
                    .put("tx_enabled", it.txEnabled)
                    .put("hop_limit", it.hopLimit)
                    .put("rx_boosted_gain", it.sx126XRxBoostedGain)
                    .put("ignore_mqtt", it.ignoreMqtt)
                    .put("channel_num", it.channelNum)
                    .put("override_frequency", it.overrideFrequency.toDouble()))
            }
            now.position?.let {
                put("position", JSONObject()
                    .put("gps_mode", it.gpsMode.name)
                    .put("fixed_position", it.fixedPosition)
                    .put("broadcast_secs", it.positionBroadcastSecs)
                    .put("smart", it.positionBroadcastSmartEnabled))
            }
            now.power?.let {
                put("power", JSONObject()
                    .put("power_saving", it.isPowerSaving)
                    .put("sds_secs", it.sdsSecs.toLong() and 0xFFFFFFFFL)
                    .put("ls_secs", it.lsSecs))
            }
            now.bluetooth?.let {
                put("bluetooth", JSONObject()
                    .put("enabled", it.enabled)
                    .put("mode", it.mode.name)
                    .put("fixed_pin_set", it.fixedPin != 0))
            }
            now.network?.let {
                put("network", JSONObject()
                    .put("wifi_enabled", it.wifiEnabled)
                    .put("ntp_server", it.ntpServer))
            }
            put("channels", JSONArray().apply {
                ble.channels.value.forEach { ch ->
                    put(JSONObject()
                        .put("index", ch.index)
                        .put("role", ch.role)
                        .put("name", ch.name)
                        .put("key", net.meshsat.android.ble.NodeProfiles.keyFingerprint(ch.psk))
                        .put("uplink", ch.uplinkEnabled)
                        .put("downlink", ch.downlinkEnabled)
                        .put("position_precision", ch.settings?.moduleSettings?.positionPrecision ?: -1))
                }
            })
        })
    }

    /**
     * Apply a node profile: one edit, one save, one restart of the node. Fields left out of the
     * body are left alone on the node. The channel key is taken as base64 and never echoed.
     */
    private fun handleMeshProfile(session: IHTTPSession): Response {
        val ble = net.meshsat.android.service.GatewayService.meshtasticBle
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "mesh not available")
        val me = ble.myInfo.value?.myNodeNum ?: 0L
        if (ble.state.value != net.meshsat.android.ble.MeshtasticBle.State.Connected || me == 0L) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "not connected to a node")
        }
        val body = try {
            JSONObject(readBody(session))
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "body is not JSON")
        }
        fun str(k: String) = if (body.has(k)) body.getString(k) else null
        fun int(k: String) = if (body.has(k)) body.getInt(k) else null
        fun bool(k: String) = if (body.has(k)) body.getBoolean(k) else null
        val psk = try {
            str("channel_psk_base64")?.let { java.util.Base64.getDecoder().decode(it) }
        } catch (e: IllegalArgumentException) {
            return jsonError(Response.Status.BAD_REQUEST, "channel_psk_base64 is not base64")
        }
        val profile = net.meshsat.android.ble.NodeProfile(
            longName = str("long_name"),
            shortName = str("short_name"),
            role = str("role"),
            nodeInfoBroadcastSecs = int("node_info_broadcast_secs"),
            region = str("region"),
            preset = str("preset"),
            txPower = int("tx_power"),
            txEnabled = bool("tx_enabled"),
            hopLimit = int("hop_limit"),
            rxBoostedGain = bool("rx_boosted_gain"),
            ignoreMqtt = bool("ignore_mqtt"),
            channelName = str("channel_name"),
            channelPsk = psk,
            channelUplink = bool("channel_uplink"),
            channelDownlink = bool("channel_downlink"),
            channelPositionPrecision = int("channel_position_precision"),
            gpsMode = str("gps_mode"),
            positionBroadcastSecs = int("position_broadcast_secs"),
            positionSmart = bool("position_smart"),
            powerSaving = bool("power_saving"),
            sdsSecs = if (body.has("sds_secs")) body.getLong("sds_secs") else null,
            bluetoothEnabled = bool("bluetooth_enabled"),
            bluetoothFixedPin = int("bluetooth_fixed_pin"),
            ntpServer = str("ntp_server"),
        )
        return when (val plan = net.meshsat.android.ble.NodeProfiles.plan(nodeSections(ble), profile)) {
            is net.meshsat.android.ble.NodeProfiles.Plan.Refused ->
                jsonError(Response.Status.BAD_REQUEST, plan.reason)
            is net.meshsat.android.ble.NodeProfiles.Plan.Ready -> {
                plan.messages.forEach {
                    ble.sendToRadio(net.meshsat.android.ble.MeshtasticProtoAdapter.buildAdmin(me, it))
                }
                android.util.Log.i("MeshSat", "Node profile sent: ${plan.sections.joinToString()}")
                jsonOk(JSONObject()
                    .put("sent", JSONArray(plan.sections))
                    .put("messages", plan.messages.size)
                    .put("note", "The node saves and restarts; read /api/mesh/config after it is back."))
            }
        }
    }

    /** Start a mailbox check and wait for its outcome, up to two minutes. */
    private fun handleIridiumMailbox(): Response {
        val gw = net.meshsat.android.service.GatewayService
        if (!gw.checkIridiumMailbox()) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "a mailbox check is already running, or the service is down")
        }
        val done = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(120_000) { gw.mailbox.first { !it.running } }
        } ?: return jsonError(Response.Status.INTERNAL_ERROR, "no outcome within two minutes")
        val result = done.result ?: return jsonError(Response.Status.INTERNAL_ERROR, "no outcome")
        return jsonOk(JSONObject().apply {
            put("result", result::class.simpleName)
            put("text", net.meshsat.android.ui.components.describeMailboxResult(result))
            when (result) {
                is net.meshsat.android.bt.IridiumSpp.MailboxResult.Checked -> {
                    put("received", result.received)
                    put("still_queued", result.stillQueued)
                }
                is net.meshsat.android.bt.IridiumSpp.MailboxResult.SessionFailed -> put("mo_status", result.moStatus)
                is net.meshsat.android.bt.IridiumSpp.MailboxResult.Held -> put("held_s", result.seconds)
                else -> {}
            }
        })
    }

    // --- System ---

    private fun handleRestart(): Response {
        restartCallback?.invoke()
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "restart not available")
        return jsonOk(JSONObject().apply { put("status", "restarting") })
    }

    // --- Helpers ---

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return ""
        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = session.inputStream.read(buf, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    // --- SMS Send --- [MESHSAT-447]

    private fun handleSmsSend(session: IHTTPSession): Response {
        if (smsSendCallback == null) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "SMS not available")
        }
        val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "empty body")
        val json = JSONObject(body)
        val to = json.optString("to", "")
        val text = json.optString("text", "")
        if (to.isBlank() || text.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "to and text are required")
        }
        smsSendCallback.invoke(to, text)
        return jsonOk(JSONObject().put("status", "sent").put("to", to))
    }

    private fun handleSmsAutoForward(session: IHTTPSession): Response {
        val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "empty body")
        val json = JSONObject(body)
        val forwardTo = json.optString("forward_to", "")
        net.meshsat.android.sms.SmsReceiver.autoForwardTo = forwardTo
        return jsonOk(JSONObject().put("status", "ok").put("forward_to", forwardTo))
    }

    // --- Helpers ---

    private fun jsonOk(json: JSONObject): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun jsonOk(arr: JSONArray): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun jsonError(status: Response.Status, message: String): Response {
        val json = JSONObject().put("error", message)
        return newFixedLengthResponse(status, "application/json", json.toString())
    }

    companion object {
        private const val TAG = "LocalApiServer"
        const val DEFAULT_PORT = 6051
    }
}
