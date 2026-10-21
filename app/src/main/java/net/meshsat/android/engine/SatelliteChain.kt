package net.meshsat.android.engine

/**
 * The kits' encrypted satellite chain over IMT (HeMB-NG S3-2, the "H chain"), byte for byte as
 * a MeshSat Bridge writes and reads it: the protocol version byte 0x01, then AES-256-GCM
 * (12-byte nonce, ciphertext, 16-byte tag) of a smaz2g frame with the Meshtastic word table.
 * No AAD and no base64. The version byte is outside the encryption and is added or taken off
 * exactly once; the smaz2g mode byte is inside it. For n UTF-8 bytes and SMAZ2 output z the wire
 * is 30 + min(n, z) bytes. [MESHSAT-1623]
 */
object SatelliteChain {
    const val PROTOCOL_VERSION: Byte = 0x01

    /** The key the kits' chain names (encrypt key_ref): a key bundle's iridium entry for "shared". */
    const val KEY_REF = "iridium:shared"
    const val KEY_ADDRESS = "shared"

    /** Version byte, nonce, mode byte and tag: what the chain adds to the shorter of n and z. */
    const val OVERHEAD = 30

    /** One IMT message, and so the longest wire the chain may make (the 9704 driver refuses more). */
    const val MAX_WIRE_BYTES = net.meshsat.android.bt.Iridium9704Spp.IMT_MAX_SIZE

    /** The setting's values: as the text is with nothing declared, the same declared, or the chain. */
    const val MODE_UNDECLARED = ""
    const val MODE_PLAIN = "plain"
    const val MODE_SEALED = "sealed"

    /** The chain's steps: smaz2g with the Meshtastic table, then encrypt with [keyHex]. */
    fun steps(keyHex: String): List<TransformSpec> = listOf(
        TransformSpec("smaz2g", mapOf("dict" to "meshtastic")),
        TransformSpec("encrypt", mapOf("key" to keyHex)),
    )

    /**
     * The wire for [payload]. Throws [TransformException] when it cannot be sealed (no key, a key
     * that is not 32 bytes of hex): a message meant to be sealed is never sent as plaintext.
     */
    fun seal(payload: ByteArray, keyHex: String?, pipeline: TransformPipeline = TransformPipeline()): ByteArray {
        val wire = byteArrayOf(PROTOCOL_VERSION) + pipeline.applyEgress(payload, steps(checkKey(keyHex)))
        if (wire.size > MAX_WIRE_BYTES) {
            throw TransformException("too long for one satellite message: ${wire.size} bytes sealed, $MAX_WIRE_BYTES at most")
        }
        return wire
    }

    /**
     * The payload of [wire]. Throws [TransformException] when it does not open: no version byte,
     * no key or a wrong one, changed or cut bytes, a malformed frame.
     */
    fun open(wire: ByteArray, keyHex: String?, pipeline: TransformPipeline = TransformPipeline()): ByteArray {
        if (wire.isEmpty() || wire[0] != PROTOCOL_VERSION) {
            throw TransformException("satellite chain: no protocol version byte")
        }
        return pipeline.applyIngress(wire.copyOfRange(1, wire.size), steps(checkKey(keyHex)))
    }

    private val HEX_KEY = Regex("^[0-9a-fA-F]{64}$")

    private fun checkKey(keyHex: String?): String =
        keyHex?.takeIf { HEX_KEY.matches(it) } ?: throw TransformException("satellite chain: no key for $KEY_REF")

    /** What an IMT message from the satellite is, once it is not a Reticulum packet. */
    sealed interface Mt {
        /** A text: [sealed] when it came through the chain and opened. */
        data class Text(val text: String, val sealed: Boolean) : Mt

        /** A MeshSat versioned payload that did not open: never shown as a guess. */
        data class Unopened(val reason: String) : Mt
    }

    /**
     * Reads one IMT payload. One that starts with the version byte is MeshSat's and is opened with
     * the chain, or reported [Mt.Unopened]; anything else is a plain text as before (a typed text
     * never starts with byte 0x01).
     */
    fun readMt(payload: ByteArray, keyHex: String?, pipeline: TransformPipeline = TransformPipeline()): Mt {
        if (payload.isEmpty() || payload[0] != PROTOCOL_VERSION) {
            return Mt.Text(payload.toString(Charsets.UTF_8), sealed = false)
        }
        return try {
            Mt.Text(open(payload, keyHex, pipeline).toString(Charsets.UTF_8), sealed = true)
        } catch (e: TransformException) {
            Mt.Unopened(e.message ?: "does not open")
        }
    }

    /**
     * Whether a delivery goes through the chain: a text. An SOS or a burst is a frame the Hub reads
     * by its own magic before anything else, as the Bridge's own frames bypass its chain, so it goes
     * as it is; so does any payload that is not UTF-8 text.
     */
    fun sealsDelivery(msgRef: String, payload: ByteArray): Boolean =
        !msgRef.startsWith("sos:") && !msgRef.startsWith("burst:") && isUtf8(payload)

    private fun isUtf8(b: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(b))
        true
    } catch (e: java.nio.charset.CharacterCodingException) {
        false
    }

    /** What the Hub birth declares for the modem (egress_format): "sealed", "plain", or null for nothing. */
    fun egressFormat(mode: String?): String? = when (mode) {
        MODE_SEALED -> "sealed"
        MODE_PLAIN -> "plain"
        else -> null
    }
}
