package com.cubeos.meshsat.tak

import android.content.Context
import android.content.Intent
import android.util.Log
import com.cubeos.meshsat.mqtt.MqttTransport

/**
 * TAK/CoT integration — generates CoT events and broadcasts to ATAK + Hub.
 *
 * Two output paths:
 * 1. ATAK intent broadcast (if ATAK is installed on the same device)
 * 2. MQTT publish to Hub (meshsat/{deviceId}/tak/cot/out)
 *
 * CoT format matches Bridge (tak_cot.go) exactly for interoperability.
 */
class TakIntegration(
    private val context: Context,
    private val mqtt: MqttTransport?,
    private val deviceId: String,
    callsignPrefix: String = "MESHSAT",
    private var atakBroadcastEnabled: Boolean = true,
    private var mqttExportEnabled: Boolean = true,
) {
    companion object {
        private const val TAG = "TakIntegration"

        /** ATAK broadcasts CoT events via this action. */
        private const val ATAK_COT_ACTION = "com.atakmap.android.maps.COT_PLACED"

        /** ATAK package name for install check. */
        private const val ATAK_PACKAGE = "com.atakmap.app.civ"
        private const val ATAK_MIL_PACKAGE = "com.atakmap.app"
    }

    private val uid = "MESHSAT-$deviceId"
    val callsign: String = CotBuilder.callsign(deviceId, callsignPrefix)

    /** Send a position update as CoT PLI. */
    fun sendPosition(
        lat: Double,
        lon: Double,
        alt: Double = 0.0,
        course: Double = 0.0,
        speed: Double = 0.0,
        battery: String = "",
    ) {
        val ev = CotBuilder.position(uid, callsign, lat, lon, alt, course, speed, battery)
        emit(ev)
    }

    /** Send an SOS emergency event. */
    fun sendSOS(lat: Double, lon: Double, alt: Double = 0.0, reason: String = "SOS") {
        val ev = CotBuilder.sos(uid, callsign, lat, lon, alt, reason)
        emit(ev)
        Log.w(TAG, "SOS CoT event emitted: $reason")
    }

    /** Send a dead man's switch timeout alarm. */
    fun sendDeadman(lat: Double, lon: Double, timeoutSec: Int) {
        val ev = CotBuilder.deadman(uid, callsign, lat, lon, timeoutSec)
        emit(ev)
        Log.w(TAG, "Dead man CoT event emitted: ${timeoutSec}s timeout")
    }

    /** Send a chat/text message as GeoChat event. */
    fun sendChat(text: String) {
        val ev = CotBuilder.chat(uid, callsign, text)
        emit(ev)
    }

    /** Send telemetry data as sensor event. */
    fun sendTelemetry(lat: Double, lon: Double, data: String) {
        val ev = CotBuilder.telemetry(uid, callsign, lat, lon, data)
        emit(ev)
    }

    /** Parse an inbound CoT XML string into a CotEvent. */
    fun parseInbound(xml: String): CotEvent? = CotXml.parse(xml)

    /** Format a CotEvent as a human-readable summary for the message list. */
    fun formatForDisplay(ev: CotEvent): String {
        val cs = ev.detail?.contact?.callsign ?: "unknown"
        return when {
            ev.type.startsWith("a-") && ev.point.lat != 0.0 ->
                "[TAK:$cs] %.6f,%.6f".format(ev.point.lat, ev.point.lon)
            ev.detail?.emergency != null ->
                "[TAK:$cs] EMERGENCY: ${ev.detail.emergency!!.text}"
            ev.detail?.remarks != null ->
                "[TAK:$cs] ${ev.detail.remarks!!.text}"
            else -> "[TAK:$cs] ${ev.type} event"
        }
    }

    /** Update output toggles at runtime (called from Settings). */
    fun updateOutputFlags(atakBroadcast: Boolean, mqttExport: Boolean) {
        atakBroadcastEnabled = atakBroadcast
        mqttExportEnabled = mqttExport
    }

    private fun emit(ev: CotEvent) {
        val xml = CotXml.marshal(ev)

        // Broadcast to ATAK if installed and enabled
        if (atakBroadcastEnabled) broadcastToAtak(xml)

        // Publish to Hub via MQTT if enabled
        if (mqttExportEnabled) {
            mqtt?.publishRaw(
                "meshsat/$deviceId/tak/cot/out",
                1, // QoS at-least-once
                false,
                xml,
            )
        }

        Log.d(TAG, "CoT emitted: type=${ev.type} uid=${ev.uid}")
    }

    private fun broadcastToAtak(xml: String) {
        if (!isAtakInstalled()) return
        try {
            val intent = Intent(ATAK_COT_ACTION).apply {
                putExtra("xml", xml)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Broadcast CoT to ATAK")
        } catch (e: Exception) {
            Log.w(TAG, "ATAK broadcast failed: ${e.message}")
        }
    }

    private fun isAtakInstalled(): Boolean {
        val pm = context.packageManager
        return try {
            pm.getPackageInfo(ATAK_PACKAGE, 0)
            true
        } catch (_: Exception) {
            try {
                pm.getPackageInfo(ATAK_MIL_PACKAGE, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
