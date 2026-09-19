package net.meshsat.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.meshsat.android.ble.ModemLink
import net.meshsat.android.ble.PipeInputStream
import net.meshsat.android.bt.IridiumSpp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The 9603 driver over a byte link, against a scripted modem that behaves like the one on
 * the MeshSat node (MESHSAT-1236): echo on until ATE0, READY then a binary phase for SBDWB,
 * binary SBDRB frames, and the cost rules the driver enforces for every caller.
 */
class IridiumSppOverPipeTest {

    /** A scripted RockBLOCK 9603 behind the pipe. */
    private class FakeModem : ModemLink {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        var echo = true
        var sbdixReply = "+SBDIX: 0, 219, 0, 0, 0, 0"
        var sbdsxReply = "+SBDSX: 0, 218, 0, -1, 0, 0"
        var mt: ByteArray = ByteArray(0)
        /** Commands to ignore first, as a modem still powering up does. */
        @Volatile var silentFor = 0
        var moWritten: ByteArray? = null
        private var receiver: ((ByteArray) -> Unit)? = null
        private val line = StringBuilder()
        private var binaryLeft = 0
        private val binary = ByteArrayOutputStream()

        override val input = PipeInputStream()

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = onByte(b.toByte())
        }

        override fun setReceiver(receiver: ((ByteArray) -> Unit)?) {
            this.receiver = receiver
        }

        /** Something the modem says on its own, e.g. SBDRING. */
        fun unsolicited(text: String) = send(text.toByteArray(Charsets.US_ASCII))

        private fun send(bytes: ByteArray) {
            input.offer(bytes)
            receiver?.invoke(bytes)
        }

        private fun reply(command: String, body: String) {
            val echoed = if (echo) "$command\r" else ""
            send((echoed + body).toByteArray(Charsets.US_ASCII))
        }

        private fun onByte(b: Byte) {
            if (binaryLeft > 0) {
                binary.write(b.toInt())
                if (--binaryLeft == 0) {
                    val all = binary.toByteArray()
                    val data = all.copyOf(all.size - 2)
                    val sum = ((all[all.size - 2].toInt() and 0xFF) shl 8) or (all[all.size - 1].toInt() and 0xFF)
                    val ok = data.sumOf { it.toInt() and 0xFF } and 0xFFFF == sum
                    if (ok) moWritten = data
                    send((if (ok) "\r\n0\r\n\r\nOK\r\n" else "\r\n2\r\n\r\nOK\r\n").toByteArray())
                }
                return
            }
            val c = b.toInt().toChar()
            if (c != '\r') {
                line.append(c)
                return
            }
            val command = line.toString()
            line.setLength(0)
            commands.add(command)
            if (silentFor > 0) {
                silentFor--
                return
            }
            when {
                command == "ATE0" -> { reply(command, "\r\nOK\r\n"); echo = false }
                command == "AT+CGMI" -> reply(command, "\r\nIridium\r\n\r\nOK\r\n")
                command == "AT+CGMM" -> reply(command, "\r\nIRIDIUM 9600 Family SBD Transceiver\r\n\r\nOK\r\n")
                command == "AT+CGSN" -> reply(command, "\r\n300434067943980\r\n\r\nOK\r\n")
                command == "AT+CSQ" -> reply(command, "\r\n+CSQ:4\r\n\r\nOK\r\n")
                command == "AT+SBDSX" -> reply(command, "\r\n$sbdsxReply\r\n\r\nOK\r\n")
                command.startsWith("AT+SBDWB=") -> {
                    binaryLeft = command.removePrefix("AT+SBDWB=").toInt() + 2
                    binary.reset()
                    reply(command, "READY\r\n")
                }
                command == "AT+SBDIX" -> reply(command, "\r\n$sbdixReply\r\n\r\nOK\r\n")
                command == "AT+SBDD0" -> reply(command, "\r\n0\r\n\r\nOK\r\n")
                command == "AT+SBDD2" -> { moWritten = null; mt = ByteArray(0); reply(command, "\r\n0\r\n\r\nOK\r\n") }
                command == "AT+SBDTC" -> {
                    mt = moWritten ?: ByteArray(0)
                    reply(command, "\r\nSBDTC: Outbound SBD Copied to Inbound SBD: size = ${mt.size}\r\n\r\nOK\r\n")
                }
                command == "AT+SBDRB" -> {
                    val sum = mt.sumOf { it.toInt() and 0xFF } and 0xFFFF
                    val frame = byteArrayOf((mt.size shr 8).toByte(), mt.size.toByte()) + mt +
                        byteArrayOf((sum shr 8).toByte(), sum.toByte())
                    val echoed = if (echo) "$command\r".toByteArray() else ByteArray(0)
                    send(echoed + frame + "\r\nOK\r\n".toByteArray())
                }
                else -> reply(command, "\r\nOK\r\n")
            }
        }
    }

    private val baseline = System.currentTimeMillis()
    @Volatile private var now = baseline

    /** Runs [block] with the clock racing ahead (a second every 10 ms), so timeouts pass quickly. */
    private fun <T> fastClock(block: () -> T): T {
        val running = AtomicBoolean(true)
        val ticker = Thread { while (running.get()) { now += 1_000; Thread.sleep(10) } }.apply { start() }
        try {
            return block()
        } finally {
            running.set(false)
            ticker.join()
        }
    }

    private fun attached(modem: FakeModem): IridiumSpp {
        val spp = IridiumSpp(clock = { now + (System.currentTimeMillis() - baseline) })
        spp.attach(modem)
        val deadline = System.currentTimeMillis() + 10_000
        while (spp.state.value != IridiumSpp.State.Connected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(IridiumSpp.State.Connected, spp.state.value)
        return spp
    }

    @Test
    fun `the probe turns flow control off first and reads the IMEI through the echo`() {
        val modem = FakeModem()
        val spp = attached(modem)
        assertEquals("AT&K0", modem.commands.first())
        assertTrue(modem.commands.indexOf("ATE0") < modem.commands.indexOf("AT+CGSN"))
        assertTrue(modem.commands.contains("AT+SBDMTA=1"))
        assertEquals("300434067943980", spp.modemInfo.value.imei)
        assertEquals(4, spp.signal.value)
    }

    @Test
    fun `a modem still powering up is asked again until it answers, then probed`() {
        val modem = FakeModem().apply { silentFor = 3 }
        val spp = fastClock { attached(modem) }
        assertEquals(listOf("AT&K0", "AT&K0", "AT&K0", "AT&K0"), modem.commands.take(4))
        assertEquals("300434067943980", spp.modemInfo.value.imei)
        assertFalse(spp.modemSilent.value)
    }

    @Test
    fun `a node without a modem is never reported connected, and detach stops the checks`() {
        val modem = FakeModem().apply { silentFor = Int.MAX_VALUE }
        val spp = IridiumSpp(clock = { now + (System.currentTimeMillis() - baseline) })
        fastClock {
            spp.attach(modem)
            val deadline = System.currentTimeMillis() + 10_000
            while (!spp.modemSilent.value && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
        assertTrue(spp.modemSilent.value)
        assertEquals(IridiumSpp.State.Connecting, spp.state.value)
        assertTrue(modem.commands.all { it == "AT&K0" })
        spp.detach()
        fastClock { Thread.sleep(100) } // a command already on its way at detach lands now
        val sent = modem.commands.size
        fastClock { Thread.sleep(300) }
        assertEquals(sent, modem.commands.size)
        assertFalse(spp.modemSilent.value)
    }

    @Test
    fun `SBDWB sends the payload with its checksum, binary-safe`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        val payload = byteArrayOf(0x00, 0x0D, 0x0A, 0xFF.toByte(), 0x94.toByte(), 0xC3.toByte()) + ByteArray(94) { it.toByte() }
        assertTrue(spp.writeMoBuffer(payload))
        assertArrayEquals(payload, modem.moWritten)
    }

    @Test
    fun `SBDRB returns the MT bytes, with and without echo`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        modem.mt = byteArrayOf(0x21, 0x0D, 0x0A, 0x00, 0x4F, 0x4B)
        assertArrayEquals(modem.mt, spp.readMtBinary())
        modem.echo = true
        assertArrayEquals(modem.mt, spp.readMtBinary())
        modem.mt = ByteArray(0)
        assertArrayEquals(ByteArray(0), spp.readMtBinary())
        assertNull(spp.readMtBuffer())
    }

    @Test
    fun `a successful SBDIX clears the MO buffer`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        val result = spp.sbdix()
        assertTrue(result!!.moSuccess)
        assertEquals("AT+SBDD0", modem.commands.last())
    }

    @Test
    fun `after status 32 no SBDIX goes out for three minutes`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        modem.sbdixReply = "+SBDIX: 32, 218, 0, 0, 0, 0"
        assertEquals(32, spp.sbdix()!!.moStatus)
        val sent = modem.commands.count { it == "AT+SBDIX" }

        assertNull(spp.sbdix())
        assertEquals(sent, modem.commands.count { it == "AT+SBDIX" })

        now += IridiumSpp.SBDIX_HOLD_MS
        modem.sbdixReply = "+SBDIX: 0, 219, 0, 0, 0, 0"
        assertTrue(spp.sbdix()!!.moSuccess)
        assertEquals(sent + 1, modem.commands.count { it == "AT+SBDIX" })
    }

    @Test
    fun `an unsolicited SBDRING is reported, without sending anything`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        val rang = AtomicBoolean(false)
        val job = CoroutineScope(Dispatchers.Default).launch { spp.ringAlerts.collect { rang.set(true) } }
        Thread.sleep(100)
        val before = modem.commands.size
        modem.unsolicited("SBDRING\r\n")
        val deadline = System.currentTimeMillis() + 2_000
        while (!rang.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        job.cancel()
        assertTrue(rang.get())
        assertEquals(before, modem.commands.size)
    }

    @Test
    fun `the loopback round-trips binary through both buffers, clears them and never opens a session`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        assertTrue(spp.loopbackTest(270))
        assertTrue(modem.commands.contains("AT+SBDTC"))
        assertEquals("AT+SBDD2", modem.commands.last())
        assertFalse(modem.commands.contains("AT+SBDIX"))
        assertNull(modem.moWritten)
    }

    @Test
    fun `an empty mailbox check is one session and reports no messages`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        val got = mutableListOf<ByteArray>()
        val result = spp.checkMailbox { got.add(it) }
        assertEquals(IridiumSpp.MailboxResult.Checked(received = 0, stillQueued = 0, sentOutgoing = false), result)
        assertEquals(1, modem.commands.count { it == "AT+SBDIX" })
        assertTrue(got.isEmpty())
    }

    @Test
    fun `a waiting MT is fetched and handed over, and what still waits is reported`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        modem.sbdixReply = "+SBDIX: 0, 219, 1, 7, 5, 2"
        modem.mt = "hello".toByteArray()
        val got = mutableListOf<String>()
        val result = spp.checkMailbox { got.add(String(it)) }
        assertEquals(IridiumSpp.MailboxResult.Checked(received = 1, stillQueued = 2, sentOutgoing = false), result)
        assertEquals(listOf("hello"), got)
    }

    @Test
    fun `an MT already in the buffer is read for free, and a waiting MO goes out in the session`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        modem.sbdsxReply = "+SBDSX: 1, 218, 1, 6, 0, 0"
        modem.mt = "earlier".toByteArray()
        val got = mutableListOf<String>()
        val result = spp.checkMailbox { got.add(String(it)) }
        assertEquals(IridiumSpp.MailboxResult.Checked(received = 1, stillQueued = 0, sentOutgoing = true), result)
        assertEquals(listOf("earlier"), got)
        assertTrue(modem.commands.indexOf("AT+SBDRB") < modem.commands.indexOf("AT+SBDIX"))
    }

    @Test
    fun `no network is reported, and the next check within the hold sends nothing`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        modem.sbdixReply = "+SBDIX: 32, 218, 0, 0, 0, 0"
        assertEquals(IridiumSpp.MailboxResult.SessionFailed(32), spp.checkMailbox { })
        val sessions = modem.commands.count { it == "AT+SBDIX" }
        val held = spp.checkMailbox { }
        assertTrue(held is IridiumSpp.MailboxResult.Held && held.seconds > 0)
        assertEquals(sessions, modem.commands.count { it == "AT+SBDIX" })
    }

    @Test
    fun `a mailbox check without the modem sends nothing`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        spp.detach()
        val before = modem.commands.size
        assertEquals(IridiumSpp.MailboxResult.NotConnected, spp.checkMailbox { })
        assertEquals(before, modem.commands.size)
    }

    @Test
    fun `after detach nothing is sent`() = runBlocking {
        val modem = FakeModem()
        val spp = attached(modem)
        spp.detach()
        val before = modem.commands.size
        assertNull(spp.sbdix())
        assertFalse(spp.writeMoBuffer(byteArrayOf(1)))
        assertEquals(before, modem.commands.size)
    }
}

