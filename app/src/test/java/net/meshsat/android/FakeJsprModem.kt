package net.meshsat.android

import net.meshsat.android.ble.ModemLink
import net.meshsat.android.bt.Iridium9704Spp
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * A RockBLOCK 9704 answering JSPR as the app's driver uses it, over the same byte streams the
 * node's BLE pipe hands the driver (MESHSAT-1623): the init, an MO it asks for in segments and
 * keeps as the bytes arrived, and an MT it sends in segments with the CRC-16 the driver checks.
 */
internal class FakeJsprModem(private val imei: String, private val segment: Int = 1446) : ModemLink {
    private val toDriver = LinkedBlockingQueue<Int>()
    override val input: InputStream = object : InputStream() {
        override fun read(): Int = toDriver.take()
        override fun available(): Int = toDriver.size
    }
    override val output: OutputStream = object : OutputStream() {
        private val line = StringBuilder()
        override fun write(b: Int) {
            if (b == '\r'.code) {
                request(line.toString()); line.setLength(0)
            } else line.append(b.toChar())
        }
    }
    override fun setReceiver(receiver: ((ByteArray) -> Unit)?) {}

    /** Every MO the driver completed, as the modem received it (payload and its two CRC bytes). */
    val mos = LinkedBlockingQueue<ByteArray>()

    /** Ask for one malformed segment (negative start) before the real ones, as a faulty modem might. */
    @Volatile var badSegmentFirst = false

    /** False: GET constellationState goes unanswered, as over a busy pipe (MESHSAT-1670). */
    @Volatile var answerConstellation = true

    /** The bars an answered GET constellationState reports. */
    @Volatile var signalBars = 3

    /** Ask for the segments this long after accepting the MO, from another thread (MESHSAT-1674). */
    @Volatile var segmentDelayMs = 0L

    /** Hand the phone this MT just before the delayed segment request, so it is being handled when the request comes. */
    @Volatile var mtBeforeSegment: ByteArray? = null

    /** When the last segment request went to the driver, and when its answer came back (System.nanoTime). */
    @Volatile var segmentAskedAtNs = 0L
    @Volatile var segmentAnsweredAtNs = 0L

    /** The message_id of the last segment answer. */
    @Volatile var lastSegmentMessageId = -1

    private var moLength = 0
    private var moId = 0
    private val moBytes = ByteArrayOutputStream()

    private fun send(line: String) = "$line\r".toByteArray(Charsets.US_ASCII).forEach { toDriver.put(it.toInt() and 0xFF) }

    @Synchronized
    private fun request(request: String) {
        val parts = request.split(" ", limit = 3)
        if (parts.size < 2) return
        val (method, target) = parts
        val body = if (parts.size == 3) JSONObject(parts[2]) else JSONObject()
        when {
            method == "GET" && target == "apiVersion" -> send("""200 apiVersion {"active_version": {"major": 1, "minor": 0, "patch": 0}}""")
            method == "GET" && target == "simConfig" -> send("""200 simConfig {"interface": "internal"}""")
            method == "GET" && target == "operationalState" -> send("""200 operationalState {"state": "active"}""")
            method == "GET" && target == "hwInfo" -> send("""200 hwInfo {"imei": "$imei", "serial_number": "TEST", "hw_version": "test", "board_temp": 20}""")
            method == "GET" && target == "firmware" && body.optString("slot") == "primary" ->
                send("""200 firmware {"slot": "primary", "validity": 1, "version": {"major": 5, "minor": 2, "patch": 1, "build_info": "fake"}, "hash": "abc"}""")
            method == "GET" && target == "constellationState" ->
                if (answerConstellation) send("""200 constellationState {"constellation_visible": true, "signal_bars": $signalBars}""")
            method == "PUT" && target == "messageOriginate" -> {
                moLength = body.getInt("message_length"); moId++; moBytes.reset()
                send("""200 messageOriginate {"topic_id": ${body.getInt("topic_id")}, "message_response": "message_accepted", "message_id": $moId}""")
                if (badSegmentFirst) send("""299 messageOriginateSegment {"topic_id": 244, "message_id": $moId, "segment_length": 4, "segment_start": -1}""")
                val delayMs = segmentDelayMs
                if (delayMs <= 0) {
                    askSegments()
                } else {
                    Thread {
                        Thread.sleep(delayMs)
                        synchronized(this) {
                            mtBeforeSegment?.let { deliverMt(it, id = 77) }
                            askSegments()
                        }
                    }.start()
                }
            }
            method == "PUT" && target == "messageOriginateSegment" -> {
                segmentAnsweredAtNs = System.nanoTime()
                lastSegmentMessageId = body.optInt("message_id", -1)
                moBytes.write(Iridium9704Spp.jsprSegmentBytes(body.getString("data")))
                if (moBytes.size() >= moLength) {
                    mos.put(moBytes.toByteArray())
                    send("""299 messageOriginateStatus {"topic_id": 244, "message_id": $moId, "final_mo_status": "mo_ack_received"}""")
                }
            }
            else -> send("200 $target {}")
        }
    }

    private fun askSegments() {
        var start = 0
        segmentAskedAtNs = System.nanoTime()
        while (start < moLength) {
            val len = minOf(segment, moLength - start)
            send("""299 messageOriginateSegment {"topic_id": 244, "message_id": $moId, "segment_length": $len, "segment_start": $start}""")
            start += len
        }
    }

    /** Any line, as the modem would send it unasked: malformed ones included. */
    @Synchronized
    fun push(line: String) = send(line)

    /** The satellite hands the modem [payload] for the phone: notice, segments, then the status. */
    @Synchronized
    fun deliverMt(payload: ByteArray, id: Int = 9) {
        val crc = Iridium9704Spp.crc16(payload)
        val withCrc = payload + byteArrayOf((crc shr 8).toByte(), (crc and 0xFF).toByte())
        send("""299 messageTerminate {"topic_id": 244, "message_id": $id, "message_length_max": ${withCrc.size}}""")
        var start = 0
        while (start < withCrc.size) {
            val len = minOf(segment, withCrc.size - start)
            val data = Iridium9704Spp.jsprSegmentData(withCrc.copyOfRange(start, start + len))
            send("""299 messageTerminateSegment {"topic_id": 244, "message_id": $id, "segment_length": $len, "segment_start": $start, "data": "$data"}""")
            start += len
        }
        send("""299 messageTerminateStatus {"topic_id": 244, "message_id": $id, "final_mt_status": "complete"}""")
    }
}
