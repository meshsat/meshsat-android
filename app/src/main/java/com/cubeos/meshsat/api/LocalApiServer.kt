package com.cubeos.meshsat.api

import android.util.Log
import com.cubeos.meshsat.channel.ChannelRegistry
import com.cubeos.meshsat.config.ConfigManager
import com.cubeos.meshsat.data.AuditLogDao
import com.cubeos.meshsat.data.MessageDeliveryDao
import com.cubeos.meshsat.engine.DeadManSwitch
import com.cubeos.meshsat.engine.GeofenceMonitor
import com.cubeos.meshsat.engine.HealthScorer
import com.cubeos.meshsat.engine.InterfaceManager
import com.cubeos.meshsat.engine.SigningService
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
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
    private val geofenceMonitor: GeofenceMonitor?,
    private val deadManSwitch: DeadManSwitch?,
    private val signingService: SigningService?,
    private val configManager: ConfigManager?,
    private val restartCallback: (() -> Unit)? = null,
    private val smsSendCallback: ((to: String, text: String) -> Unit)? = null,
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

            // System
            method == Method.POST && uri == "/api/system/restart" -> handleRestart()

            else -> jsonError(Response.Status.NOT_FOUND, "not found: $uri")
        }
    }

    // --- Health ---

    private fun handleHealth(): Response {
        val ifaces = interfaceManager?.getAllStatus() ?: emptyList()
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
