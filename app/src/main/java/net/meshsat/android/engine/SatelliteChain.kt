package net.meshsat.android.engine

/**
 * The kits' encrypted satellite chains over IMT, byte for byte as a MeshSat Bridge writes and
 * reads them, behind the protocol version byte 0x01 that is added or taken off exactly once:
 *
 * - [Chain.H], the H chain (HeMB-NG S3-2): AES-256-GCM (12-byte nonce, ciphertext, 16-byte tag)
 *   of a smaz2g frame with the Meshtastic word table. No AAD and no base64; the smaz2g mode byte
 *   is inside the encryption. For n UTF-8 bytes and SMAZ2 output z the wire is 30 + min(n, z).
 * - [Chain.LEGACY], the kits' chain before it and the agreed rollback, as parallax's
 *   iridium_imt_0 runs it: smaz2 with the DEFAULT table (the step names no dict), AES-256-GCM,
 *   then base64.
 *
 * [MESHSAT-1623]
 */
object SatelliteChain {
    const val PROTOCOL_VERSION: Byte = 0x01

    /**
     * The key the kits' chains name (encrypt key_ref), stored under exactly this channel:address
     * by a key bundle's iridium entry for "shared". Never looked up by the address alone: another
     * channel's entry may be called "shared" too.
     */
    const val KEY_REF = "iridium:shared"

    /** H chain: version byte, nonce, mode byte and tag, added to the shorter of n and z. */
    const val OVERHEAD = 30

    /** One IMT message, and so the longest wire a chain may make (the 9704 driver refuses more). */
    const val MAX_WIRE_BYTES = net.meshsat.android.bt.Iridium9704Spp.IMT_MAX_SIZE

    /**
     * The setting's values: as the text is with nothing declared, the same declared, the H chain,
     * or the legacy chain. Both encrypted chains declare sealed.
     */
    const val MODE_UNDECLARED = ""
    const val MODE_PLAIN = "plain"
    const val MODE_SEALED = "sealed"
    const val MODE_LEGACY = "legacy"

    enum class Chain {
        H, LEGACY;

        fun steps(keyHex: String): List<TransformSpec> = when (this) {
            H -> listOf(
                TransformSpec("smaz2g", mapOf("dict" to "meshtastic")),
                TransformSpec("encrypt", mapOf("key" to keyHex)),
            )
            LEGACY -> listOf(
                TransformSpec("smaz2"),
                TransformSpec("encrypt", mapOf("key" to keyHex)),
                TransformSpec("base64"),
            )
        }

        val other: Chain get() = if (this == H) LEGACY else H
    }

    /** The chain a setting sends with, or null when it sends the text as it is. */
    fun chainOf(mode: String?): Chain? = when (mode) {
        MODE_SEALED -> Chain.H
        MODE_LEGACY -> Chain.LEGACY
        else -> null
    }

    /** The H chain's steps, as the Bridge's vectors name them. */
    fun steps(keyHex: String): List<TransformSpec> = Chain.H.steps(keyHex)

    /**
     * The wire for [payload] on [chain]. Throws [TransformException] when it cannot be sealed (no
     * key, a key that is not 32 bytes of hex, too long): a message meant to be sealed is never sent
     * as plaintext.
     */
    fun seal(
        payload: ByteArray,
        keyHex: String?,
        pipeline: TransformPipeline = TransformPipeline(),
        chain: Chain = Chain.H,
    ): ByteArray {
        val wire = byteArrayOf(PROTOCOL_VERSION) + pipeline.applyEgress(payload, chain.steps(checkKey(keyHex)))
        if (wire.size > MAX_WIRE_BYTES) {
            throw TransformException("too long for one satellite message: ${wire.size} bytes sealed, $MAX_WIRE_BYTES at most")
        }
        return wire
    }

    /**
     * The payload of [wire] on [chain]. Throws [TransformException] when it does not open: no
     * version byte, no key or a wrong one, changed or cut bytes, a malformed frame.
     */
    fun open(
        wire: ByteArray,
        keyHex: String?,
        pipeline: TransformPipeline = TransformPipeline(),
        chain: Chain = Chain.H,
    ): ByteArray {
        if (wire.isEmpty() || wire[0] != PROTOCOL_VERSION) {
            throw TransformException("satellite chain: no protocol version byte")
        }
        return pipeline.applyIngress(wire.copyOfRange(1, wire.size), chain.steps(checkKey(keyHex)))
    }

    private val HEX_KEY = Regex("^[0-9a-fA-F]{64}$")

    private fun checkKey(keyHex: String?): String =
        keyHex?.takeIf { HEX_KEY.matches(it) } ?: throw TransformException("satellite chain: no key for $KEY_REF")

    /**
     * The kits' key: the one stored under [KEY_REF] exactly, never another channel's "shared".
     * Null when there is none, and the send fails rather than seal with a wrong key.
     */
    suspend fun key(lookup: suspend (String) -> String?): String? = lookup(KEY_REF)?.takeIf { HEX_KEY.matches(it) }

    /**
     * A key's check value: the first 4 bytes of SHA-256 over its 32 bytes, in hex, as a Bridge's
     * key list gives it (key_check). Two ends that show the same check hold the same key, and the
     * check shows nothing of the key itself.
     */
    fun keyCheck(keyHex: String): String {
        val raw = checkKey(keyHex).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw).copyOf(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * What the service sends through the 9704 for one delivery on setting [mode]: the text sealed
     * on the chain the setting picks, or the payload as it is when the setting picks none or the
     * delivery is not a text ([sealsDelivery]). The key is read only when it is needed. Throws
     * [TransformException] when the delivery must be sealed and cannot be.
     */
    suspend fun outbound(
        mode: String?,
        msgRef: String,
        payload: ByteArray,
        pipeline: TransformPipeline = TransformPipeline(),
        key: suspend () -> String?,
    ): ByteArray {
        val chain = chainOf(mode) ?: return payload
        if (!sealsDelivery(msgRef, payload)) return payload
        return seal(payload, key(), pipeline, chain)
    }

    /**
     * What the service makes of one IMT payload on setting [mode]: [readMt] with the chain the
     * setting picks first (H when it picks none), and a plain text behind the version byte read
     * only when the setting seals nothing. The key is read only for a versioned payload.
     */
    suspend fun inbound(payload: ByteArray, mode: String?, key: suspend () -> String?): Mt =
        readMt(
            payload,
            if (payload.firstOrNull() == PROTOCOL_VERSION) key() else null,
            preferred = chainOf(mode) ?: Chain.H,
            readsPlain = chainOf(mode) == null,
        )

    /** What an IMT message from the satellite is, once it is not a Reticulum packet. */
    sealed interface Mt {
        /** A text: [sealed] when it came through a chain and opened, on [chain]. */
        data class Text(val text: String, val sealed: Boolean, val chain: Chain? = null) : Mt

        /** A MeshSat versioned payload that did not open: never shown as a guess. */
        data class Unopened(val reason: String) : Mt
    }

    /**
     * Reads one IMT payload. One that starts with the version byte is MeshSat's: it is opened with
     * [preferred], the chain this phone is set to, and while the two ends switch chains with the
     * other one, each authenticated by its AES-GCM tag, so nothing that did not come from the key's
     * holder ever reads as text. One that opens with neither is [Mt.Unopened], unless [readsPlain]
     * (a phone whose setting seals nothing) and it is a plain text behind the version byte
     * ([versionedPlainText]), which reads as a plain text, never as sealed. A phone set to seal
     * keeps it unopened, as a Bridge's sealed link drops what does not authenticate. Anything
     * else is a plain text as before (a typed text never starts with byte 0x01).
     */
    fun readMt(
        payload: ByteArray,
        keyHex: String?,
        pipeline: TransformPipeline = TransformPipeline(),
        preferred: Chain = Chain.H,
        readsPlain: Boolean = false,
    ): Mt {
        if (payload.isEmpty() || payload[0] != PROTOCOL_VERSION) {
            return Mt.Text(payload.toString(Charsets.UTF_8), sealed = false)
        }
        val first = try {
            return Mt.Text(open(payload, keyHex, pipeline, preferred).toString(Charsets.UTF_8), sealed = true, chain = preferred)
        } catch (e: TransformException) {
            e
        }
        return try {
            Mt.Text(open(payload, keyHex, pipeline, preferred.other).toString(Charsets.UTF_8), sealed = true, chain = preferred.other)
        } catch (e: TransformException) {
            (if (readsPlain) versionedPlainText(payload) else null)?.let { Mt.Text(it, sealed = false) }
                ?: Mt.Unopened(first.message ?: "does not open")
        }
    }

    /**
     * The text of a plain message behind the version byte: the version byte, then the text's UTF-8
     * bytes, as the Hub's routes sent a text to a 9704 until MESHSAT-1675. Read only once neither
     * sealed chain opens it, and only when the rest is printable UTF-8 not shaped like a legacy
     * wire (base64 of at least a nonce, one byte and a tag), so a sealed message under a wrong key
     * never reads as text: the 30 random bytes of the shortest sealed wire pass as printable UTF-8
     * about once in 10^10, 40 once in 10^13. The Hub's compressed form (version byte, SMAZ2) is not
     * read: noise passes for it once in 60 at that length. Null otherwise. [MESHSAT-1675]
     */
    fun versionedPlainText(payload: ByteArray): String? {
        if (payload.size < 2 || payload[0] != PROTOCOL_VERSION) return null
        val text = utf8(payload.copyOfRange(1, payload.size)) ?: return null
        if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
        if (text.length >= LEGACY_MIN_WIRE_CHARS && text.length % 4 == 0 && BASE64_TEXT.matches(text)) return null
        return text
    }

    /** A legacy wire's shortest base64: 12-byte nonce, one byte, 16-byte tag. */
    private const val LEGACY_MIN_WIRE_CHARS = 40
    private val BASE64_TEXT = Regex("^[A-Za-z0-9+/_-]+={0,2}$")

    /**
     * Whether a delivery goes through the chain: a text. An SOS or a burst is a frame the Hub reads
     * by its own magic before anything else, as the Bridge's own frames bypass its chain, so it goes
     * as it is; so does any payload that is not UTF-8 text.
     */
    fun sealsDelivery(msgRef: String, payload: ByteArray): Boolean =
        !msgRef.startsWith("sos:") && !msgRef.startsWith("burst:") && isUtf8(payload)

    private fun isUtf8(b: ByteArray): Boolean = utf8(b) != null

    /** [b] as UTF-8, or null when it is not UTF-8. */
    private fun utf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(b))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }

    /**
     * What the Hub birth declares for the modem (egress_format): "sealed" for either encrypted
     * chain, "plain", or null for nothing.
     */
    fun egressFormat(mode: String?): String? = when (mode) {
        MODE_SEALED, MODE_LEGACY -> "sealed"
        MODE_PLAIN -> "plain"
        else -> null
    }
}
