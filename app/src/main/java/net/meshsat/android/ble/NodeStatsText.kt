package net.meshsat.android.ble

import net.meshsat.android.ble.IridiumPipeContract.NodeStats
import net.meshsat.android.ble.IridiumPipeContract.Owner
import net.meshsat.android.bt.IridiumSpp

/**
 * The node health card's wording (Setup > Satellite, MESHSAT-1378), free of Android types so
 * it has a test and iOS can copy it row for row. Each entry is a label and its value.
 */
object NodeStatsText {
    /** The card's rows, in order. */
    fun rows(s: NodeStats): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Modem" to buildString {
            append(
                when (s.owner) {
                    Owner.Phone -> "Held by this phone"
                    Owner.Node -> "Used by the node"
                    Owner.None -> "Free"
                    Owner.Unknown -> "Unknown owner"
                },
            )
            append(if (s.flags.modemAnswers) ", answers" else ", not answering")
        }
        if (s.flags.sessionInFlight) rows += "Session" to "In flight now"
        rows += "Signal" to when (s.csq) {
            null -> "Never read"
            else -> "${s.csq} of 5" + (s.csqAgeS?.let { ", ${ago(it)}" } ?: "")
        }
        rows += "Sessions since boot" to s.sessions.toString()
        rows += "Last session" to when (s.lastMoStatus) {
            null -> "None yet"
            else -> buildString {
                append("MO ${s.lastMoStatus}, ${IridiumSpp.moStatusText(s.lastMoStatus)}, MOMSN ${s.lastMomsn}")
                s.lastSessionAgeS?.let { append(", ${ago(it)}") }
            }
        }
        val waiting = when {
            s.lastMtQueued > 0 -> "${s.lastMtQueued} waiting at the gateway"
            s.flags.messageWaiting -> "A message is waiting at the gateway"
            else -> null
        }
        if (waiting != null) rows += "Gateway" to waiting
        if (s.daySessionsCap > 0 || s.nodeSessions > 0) {
            rows += "Node's own routing" to
                "${s.daySessionsUsed} of ${s.daySessionsCap} sessions today, sent ${s.nodeSent}, received ${s.nodeReceived}"
        }
        rows += "Node uptime" to duration(s.uptimeS)
        if (s.watchdogReboots > 0) rows += "Bluetooth watchdog reboots" to s.watchdogReboots.toString()
        if (s.phoneBytesDropped > 0) rows += "Bytes the node could not take" to s.phoneBytesDropped.toString()
        return rows
    }

    /** A line under the rows when something needs attention, or null. */
    fun warning(s: NodeStats): String? = when {
        s.flags.bufferCongested -> "The node's incoming buffer is nearly full: the phone writes faster than the modem takes."
        !s.flags.modemAnswers -> "The node's modem is not answering AT commands."
        else -> null
    }

    /** "just now", "12 s ago", "4 min ago", "2 h ago", "3 d ago". */
    fun ago(seconds: Long): String = when {
        seconds < 5 -> "just now"
        seconds < 60 -> "$seconds s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86_400} d ago"
    }

    /** "30 s", "45 min", "2 h 14 min", "3 d 2 h". */
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86_400 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        else -> "${seconds / 86_400} d ${(seconds % 86_400) / 3600} h"
    }
}
