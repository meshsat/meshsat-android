package net.meshsat.android.ui

import androidx.compose.ui.graphics.Color
import net.meshsat.android.ui.theme.ColorCellular
import net.meshsat.android.ui.theme.ColorHub
import net.meshsat.android.ui.theme.ColorIridium
import net.meshsat.android.ui.theme.ColorMesh
import net.meshsat.android.ui.theme.ColorRadio
import net.meshsat.android.ui.theme.MeshSatAmber
import net.meshsat.android.ui.theme.MeshSatGreen
import net.meshsat.android.ui.theme.MeshSatRed
import net.meshsat.android.ui.theme.MeshSatTextMuted
import net.meshsat.android.ui.theme.MeshSatTextSecondary

/**
 * The words the app shows for its own machinery (MESHSAT-1249), in one place so a channel or a state
 * is called the same thing on every screen. Internal ids (iridium_0, sms_0) and raw states (dead,
 * retry) never reach the user: they read Satellite, SMS, "Gave up", "Waiting to retry". The Bridge
 * does the same with its operator label dictionary ("rename jargon, keep capability").
 */
object Words {

    /** An interface or channel id, as the user knows it. */
    fun channel(id: String): String = when {
        id.startsWith("iridium9704") -> "Satellite (RockBLOCK 9704)"
        id.startsWith("iridium") -> "Satellite"
        id.startsWith("mesh") -> "Mesh"
        id.startsWith("sms") -> "SMS"
        // hub_relay before hub_0: the relay is a tunnel to another bridge, not the Hub itself.
        id.contains("relay") -> "Hub relay"
        id.startsWith("hub") -> "Hub"
        // The older per-device broker link. It was the one called "Hub" while the real Hub link
        // read "hub_0", so a rule written to "Hub" went nowhere (MESHSAT-1281).
        id.startsWith("mqtt") -> "MQTT broker"
        id.startsWith("aprs") -> "Ham radio"
        id.startsWith("tcp_rns") || id.startsWith("rns") -> "Reticulum"
        id.isBlank() -> "Unknown"
        else -> id
    }

    /** A message's transport field ("iridium", "mesh", "sms", ...), as the user knows it. */
    fun transport(t: String): String = when (t.lowercase()) {
        "iridium", "sbd", "iridium9704", "imt" -> "Satellite"
        "mesh", "meshtastic", "lora" -> "Mesh"
        "sms", "cellular" -> "SMS"
        "mqtt", "hub" -> "Hub"
        "aprs" -> "Ham radio"
        "reticulum", "rns" -> "Reticulum"
        "tak" -> "TAK"
        else -> t.replaceFirstChar { it.uppercase() }
    }

    /** The colour of a transport, from the Bridge's route lanes. */
    fun transportColor(t: String): Color = when (transport(t)) {
        "Satellite" -> ColorIridium
        "Mesh" -> ColorMesh
        "SMS" -> ColorCellular
        "Hub", "Hub relay" -> ColorHub
        "Ham radio" -> ColorRadio
        else -> MeshSatTextSecondary
    }

    /** The colour of a channel id. */
    fun channelColor(id: String): Color = transportColor(
        when {
            id.startsWith("iridium") -> "iridium"
            id.startsWith("mesh") -> "mesh"
            id.startsWith("sms") -> "sms"
            id.startsWith("mqtt") || id.startsWith("hub") || id.contains("relay") -> "hub"
            id.startsWith("aprs") -> "aprs"
            else -> id
        },
    )

    /** A delivery's status in the queue. */
    fun deliveryState(status: String): String = when (status.lowercase()) {
        "queued" -> "Waiting"
        "retry" -> "Waiting to retry"
        "held" -> "On hold until the link is back"
        "sending" -> "Sending"
        "sent" -> "Sent"
        "delivered", "acked" -> "Delivered"
        "failed" -> "Failed"
        "dead" -> "Gave up"
        "expired" -> "Expired"
        "denied" -> "Blocked by a rule"
        "cancelled" -> "Cancelled"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    /** The colour of a delivery status: working, trying, or failed. */
    fun deliveryColor(status: String): Color = when (status.lowercase()) {
        "sent", "delivered", "acked" -> MeshSatGreen
        "queued", "retry", "held", "sending" -> MeshSatAmber
        "failed", "dead", "expired", "denied" -> MeshSatRed
        else -> MeshSatTextMuted
    }

    /** An interface's link state. */
    fun linkState(state: String): String = when (state.lowercase()) {
        "online" -> "Working"
        "connecting" -> "Connecting"
        "offline" -> "Off"
        "error" -> "Not working"
        "disabled" -> "Switched off"
        else -> state.replaceFirstChar { it.uppercase() }
    }

    /** "1 message", "3 messages". */
    fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

    /** A moment in the past, relative: "just now", "4 min ago", "2 h ago", "3 days ago". */
    fun ago(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return "never"
        val s = ((nowMs - epochMs) / 1000).coerceAtLeast(0)
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> count((s / 86_400).toInt(), "day") + " ago"
        }
    }

    /** A moment ahead, relative: "now", "in 4 min", "in 2 h 10 min". */
    fun inTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val s = ((epochMs - nowMs) / 1000)
        return when {
            s <= 30 -> "now"
            s < 3600 -> "in ${(s + 30) / 60} min"
            else -> "in ${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}
