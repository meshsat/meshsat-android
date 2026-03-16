package com.cubeos.meshsat.codec

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Canned message codebook — forward and reverse lookup for numbered military brevity phrases.
 * Port of meshsat/internal/codec/canned.go.
 *
 * Wire format: [0xCA] [1B message_id] — 2 bytes total.
 */
class CannedCodebook(entries: Map<Int, String>) {

    private val lock = ReentrantReadWriteLock()
    private val forward = HashMap<Int, String>(entries.size)
    private val reverse = HashMap<String, Int>(entries.size)

    init {
        for ((id, text) in entries) {
            forward[id] = text
            reverse[text] = id
        }
    }

    /** Decode a 2-byte canned message frame. */
    fun decode(data: ByteArray): String {
        require(data.size >= 2) { "codec: canned data too short" }
        require(data[0] == HEADER_CANNED) { "codec: invalid canned header" }
        val id = data[1].toInt() and 0xFF
        return lock.read {
            forward[id] ?: throw IllegalArgumentException("codec: unknown canned message ID $id")
        }
    }

    /** Look up message ID by text. Returns null if not found. */
    fun lookupByText(text: String): Int? = lock.read { reverse[text] }

    companion object {
        /** Magic prefix for a canned message frame. */
        const val HEADER_CANNED: Byte = 0xCA.toByte()

        /** Built-in military brevity codebook (30 messages). */
        val DEFAULT_ENTRIES: Map<Int, String> = mapOf(
            1 to "Copy.",
            2 to "Roger.",
            3 to "Negative.",
            4 to "Affirmative.",
            5 to "Stand by.",
            6 to "All clear.",
            7 to "Moving out.",
            8 to "Returning to base.",
            9 to "Position confirmed.",
            10 to "Mission complete.",
            11 to "Need resupply.",
            12 to "Requesting backup.",
            13 to "Medical emergency.",
            14 to "Evacuate immediately.",
            15 to "Hold position.",
            16 to "Proceed to waypoint.",
            17 to "Enemy contact.",
            18 to "All personnel accounted for.",
            19 to "Weather deteriorating.",
            20 to "Low battery warning.",
            21 to "Signal lost.",
            22 to "Relay message.",
            23 to "Check in.",
            24 to "Going silent.",
            25 to "SOS — need immediate help.",
            26 to "Camp established.",
            27 to "Trail blocked — rerouting.",
            28 to "Water source found.",
            29 to "Shelter located.",
            30 to "Search area clear — no findings.",
        )

        /** Singleton default codebook. */
        val default: CannedCodebook = CannedCodebook(DEFAULT_ENTRIES)

        /** Encode a canned message ID to a 2-byte wire frame. */
        fun encode(id: Int): ByteArray = byteArrayOf(HEADER_CANNED, id.toByte())

        /** Decode using the default codebook. */
        fun decodeDefault(data: ByteArray): String = default.decode(data)

        /** Check whether data starts with the canned message header. */
        fun isCanned(data: ByteArray): Boolean = data.isNotEmpty() && data[0] == HEADER_CANNED

        /** Look up message ID by text in the default codebook. */
        fun lookupByTextDefault(text: String): Int? = default.lookupByText(text)
    }
}
