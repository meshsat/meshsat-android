package net.meshsat.android.rns

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An RNS TCPServerInterface: accepts any number of TCPClientInterface peers
 * (rnsd, Sideband, CrossTalk, the interop peers) and exchanges HDLC-framed
 * packets with each. A transmit goes to every connected peer, as upstream's
 * server interface does. Plain threads, no Android or coroutine dependency, so
 * the interop tests and the app use the same class. [MESHSAT-1553]
 */
class TcpServerInterface(
    val id: String = "tcp_server_0",
    private val bindAddress: String = "127.0.0.1",
    private val log: (String) -> Unit = {},
) {
    @Volatile var onReceive: ((raw: ByteArray) -> Unit)? = null

    private var server: ServerSocket? = null
    private val peers = CopyOnWriteArrayList<Peer>()
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0
    val isOnline: Boolean get() = running
    fun peerCount(): Int = peers.size

    private inner class Peer(val socket: Socket) {
        val out = socket.getOutputStream()
        val reader = Hdlc.FrameReader()
        fun run() {
            try {
                val input = socket.getInputStream()
                val buf = ByteArray(16384)
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (frame in reader.feed(buf.copyOf(n))) onReceive?.invoke(frame)
                }
            } catch (_: IOException) {
            } finally {
                peers.remove(this)
                try { socket.close() } catch (_: IOException) {}
                log("rns: tcp peer left $id (${peers.size} left)")
            }
        }
        fun send(raw: ByteArray): Boolean = try {
            synchronized(out) { out.write(Hdlc.frame(raw)); out.flush() }
            true
        } catch (_: IOException) { peers.remove(this); try { socket.close() } catch (_: IOException) {}; false }
    }

    /** Listens on [port] (0 picks a free one); returns the port. */
    fun start(port: Int = 0): Int {
        val s = ServerSocket(port, 16, InetAddress.getByName(bindAddress))
        server = s
        running = true
        Thread({
            while (running) {
                val sock = try { s.accept() } catch (_: IOException) { break }
                sock.tcpNoDelay = true
                sock.keepAlive = true
                val p = Peer(sock)
                peers.add(p)
                log("rns: tcp peer joined $id from ${sock.inetAddress.hostAddress} (${peers.size})")
                Thread({ p.run() }, "$id-peer").apply { isDaemon = true }.start()
            }
        }, "$id-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        for (p in peers) try { p.socket.close() } catch (_: IOException) {}
        peers.clear()
    }

    /** Sends to every peer. No peer is not a failure (upstream's server interface does the same, nothing). */
    fun send(raw: ByteArray): Boolean {
        if (peers.isEmpty()) return true
        var any = false
        for (p in peers) if (p.send(raw)) any = true
        return any
    }
}
