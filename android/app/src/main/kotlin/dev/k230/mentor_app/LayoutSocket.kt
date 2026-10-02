package dev.k230.mentor_app

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class LayoutSocket : AutoCloseable {
    private val pending = AtomicReference<ByteArray?>()
    @Volatile private var running = false
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null
    @Volatile var connected = false
        private set
    @Volatile var error: String? = null
        private set
    private var thread: Thread? = null

    fun publish(snapshot: LayoutSnapshot) {
        pending.set(LayoutWire.encode(snapshot))
    }

    fun start(onConnect: () -> Unit) {
        running = true
        thread = Thread({
            try {
                val listener = LocalServerSocket(LayoutWire.SOCKET_NAME)
                server = listener
                while (running) {
                    val socket = listener.accept()
                    client = socket
                    try {
                        val uid = socket.peerCredentials.uid
                        if (uid != 2000 && uid != 0) continue
                        socket.sendBufferSize = 16384
                        socket.soTimeout = 500
                        pending.set(null)
                        connected = true
                        onConnect()
                        val output = socket.outputStream
                        while (running) {
                            pending.getAndSet(null)?.let { output.write(it); output.flush() }
                            Thread.sleep(10)
                        }
                    } catch (_: IOException) {
                    } finally {
                        connected = false
                        socket.close()
                        client = null
                    }
                }
            } catch (e: IOException) {
                if (running) error = e.message
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                connected = false
            }
        }, "layout-socket").also { it.start() }
    }

    override fun close() {
        running = false
        try { client?.close() } catch (_: IOException) { }
        try { server?.close() } catch (_: IOException) { }
        thread?.interrupt()
        thread?.join(1500)
        pending.set(null)
    }
}
