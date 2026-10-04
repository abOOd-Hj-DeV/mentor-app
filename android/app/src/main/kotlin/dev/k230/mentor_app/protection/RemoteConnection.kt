package dev.k230.mentor_app.protection

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal data class RemoteConnectionConfig(val host: String, val port: Int, val token: String, val pin: String) {
    companion object {
        fun parse(endpoint: String, token: String, pin: String): RemoteConnectionConfig {
            val uri = try { URI(endpoint) } catch (_: Exception) { throw ProtocolFailure("bounds") }
            requireProtocol(uri.scheme == "tls" && uri.host != null && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty() &&
                uri.port in 1..65535 && endpoint.length <= 512 && token.length in 32..512 &&
                token.all { it.code in 33..126 } && (pin.isEmpty() || pin.matches(Regex("[0-9a-fA-F]{64}"))))
            return RemoteConnectionConfig(uri.host, uri.port, token, pin.lowercase())
        }
    }
}

internal object RemoteCaptureProof {
    @Volatile var active = false
    private val points = LinkedHashSet<Long>()
    @Synchronized fun record(pts: Long) {
        points.add(pts)
        while (points.size > 16) points.remove(points.first())
    }
    @Synchronized fun contains(pts: Long) = active && pts in points
    @Synchronized fun clear() { active = false; points.clear() }
}

internal class RemoteConnection(
    private val config: RemoteConnectionConfig,
    private val changed: (String) -> Unit,
) : AutoCloseable {
    private data class Packet(val type: Int, val bytes: ByteArray)
    private val open = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<Packet>(32)
    private val frameQueued = AtomicBoolean(false)
    @Volatile private var tls: SSLSocket? = null
    @Volatile private var local: LocalSocket? = null
    fun start() {
        Thread({
            try {
                val socket = factory().createSocket() as SSLSocket
                tls = socket
                socket.soTimeout = 5_000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.connect(InetSocketAddress(config.host, config.port), 5_000)
                socket.startHandshake()
                socket.soTimeout = 10_000
                if (!open.get()) { socket.close(); return@Thread }
                val companion = LocalSocket()
                local = companion
                companion.connect(LocalSocketAddress("k230_companion", LocalSocketAddress.Namespace.ABSTRACT))
                val output = DataOutputStream(socket.outputStream)
                write(output, Packet(0, config.token.toByteArray(Charsets.UTF_8)))
                changed("connected")
                Thread({
                    try {
                        val input = DataInputStream(companion.inputStream)
                        requireProtocol(input.readUnsignedByte() == 'K'.code)
                        while (open.get()) {
                            val bytes = java.io.ByteArrayOutputStream()
                            do {
                                val next = input.readUnsignedByte()
                                bytes.write(next)
                                requireProtocol(bytes.size() <= CompanionProtocol.MAX_LINE_BYTES)
                            } while (next != 10)
                            if (!queue.offer(Packet(1, bytes.toByteArray()))) throw ProtocolFailure("busy")
                        }
                    } catch (_: Exception) { fail() }
                }, "remote-native-read").start()
                Thread({
                    try {
                        while (open.get()) {
                            val packet = queue.take()
                            write(output, packet)
                        }
                    } catch (_: Exception) { fail() }
                }, "remote-tls-write").start()
                val input = DataInputStream(socket.inputStream)
                while (open.get()) {
                    val type = input.readUnsignedByte()
                    val size = input.readInt()
                    if (type == 3 || type == 5) {
                        requireProtocol(size == 1 && input.readUnsignedByte() == 1)
                        if (type == 5) frameQueued.set(false)
                        continue
                    }
                    if (type == 4) {
                        requireProtocol(size == 1)
                        val complete = input.readUnsignedByte()
                        requireProtocol(complete in 0..1)
                        changed(if (complete == 1) "analyzing" else "partial")
                        continue
                    }
                    requireProtocol(type == 1)
                    requireProtocol(size in 1..CompanionProtocol.MAX_LINE_BYTES)
                    val bytes = ByteArray(size); input.readFully(bytes)
                    requireProtocol(bytes.last() == 10.toByte())
                    CompanionProtocol.parse(bytes.copyOfRange(0, bytes.size - 1))
                    companion.outputStream.write(bytes); companion.outputStream.flush()
                }
            } catch (_: Exception) { fail() }
        }, "remote-tls-read").start()
    }
    fun frame(pts: Long, screen: ScreenSnapshot, png: ByteArray) {
        if (!open.get() || tls?.isConnected != true || !frameQueued.compareAndSet(false, true)) return
        val payload = java.io.ByteArrayOutputStream()
        DataOutputStream(payload).use {
            it.writeLong(pts); it.writeInt(screen.width); it.writeInt(screen.height)
            it.writeLong(screen.epoch); it.write(screen.token.toByteArray(Charsets.US_ASCII)); it.write(png)
        }
        if (payload.size() > 8 * 1024 * 1024) { frameQueued.set(false); fail(); return }
        RemoteCaptureProof.record(pts)
        if (!queue.offer(Packet(2, payload.toByteArray()))) { frameQueued.set(false); fail() }
    }
    private fun write(output: DataOutputStream, packet: Packet) {
        output.writeByte(packet.type); output.writeInt(packet.bytes.size)
        output.write(packet.bytes); output.flush()
    }
    private fun factory(): SSLSocketFactory {
        if (config.pin.isEmpty()) return SSLSocketFactory.getDefault() as SSLSocketFactory
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw java.security.cert.CertificateException("client_certificate_unsupported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("missing_certificate")
                chain[0].checkValidity()
                val expected = config.pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                if (!MessageDigest.isEqual(expected, actual))
                    throw java.security.cert.CertificateException("certificate_mismatch")
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(manager), SecureRandom())
        }.socketFactory
    }
    private fun fail() { if (open.get()) { changed("disconnected"); close() } }
    override fun close() {
        if (!open.getAndSet(false)) return
        try { tls?.close() } catch (_: Exception) { }
        try { local?.close() } catch (_: Exception) { }
        queue.clear()
        queue.offer(Packet(0, byteArrayOf()))
    }
}
