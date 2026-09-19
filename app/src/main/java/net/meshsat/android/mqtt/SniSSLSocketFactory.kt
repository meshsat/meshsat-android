package net.meshsat.android.mqtt

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Delegating SSLSocketFactory that forces TLS SNI on every socket it creates.
 *
 * Paho's SSL/WSS network modules may create sockets without a hostname and
 * connect them afterwards, so the platform never learns the peer name and
 * sends no SNI. The edge routes mqtt-hub.meshsat.net by SNI at the TCP
 * layer; a ClientHello without SNI lands on the default web backend instead
 * of NATS and the handshake fails against the wrong certificate (MESHSAT-749).
 *
 * The target hostname is fixed at construction because a hostless
 * createSocket() call gives the factory nothing to derive it from.
 */
class SniSSLSocketFactory(
    private val delegate: SSLSocketFactory,
    private val sniHost: String,
) : SSLSocketFactory() {

    private fun withSni(socket: Socket): Socket {
        if (socket is SSLSocket && sniHost.isNotBlank()) {
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(sniHost))
            socket.sslParameters = params
        }
        return socket
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = withSni(delegate.createSocket())

    override fun createSocket(host: String?, port: Int): Socket =
        withSni(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        withSni(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        withSni(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        withSni(delegate.createSocket(address, port, localAddress, localPort))

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        withSni(delegate.createSocket(s, host, port, autoClose))
}
