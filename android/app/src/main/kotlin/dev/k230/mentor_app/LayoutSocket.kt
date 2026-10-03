package dev.k230.mentor_app

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

internal class LayoutSocket(private val diagnostics: LayoutDiagnostics) : AutoCloseable {
    private data class PendingLayout(val bytes: ByteArray, val sequence: Long)
    private val pending = AtomicReference<PendingLayout?>()
    @Volatile private var running = false
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null
    @Volatile var connected = false
        private set
    @Volatile var error: String? = null
        private set
    private var thread: Thread? = null

    fun publish(snapshot: LayoutSnapshot) {
        pending.set(PendingLayout(LayoutWire.encode(snapshot), snapshot.sequence))
        diagnostics.published(snapshot)
    }

    fun start(onConnect: () -> Unit) {
        running = true
        thread = Thread({
            try {
                diagnostics.socketStage("listen")
                val listener = LocalServerSocket(LayoutWire.SOCKET_NAME)
                server = listener
                while (running) {
                    diagnostics.socketStage("accept")
                    val socket = listener.accept()
                    client = socket
                    try {
                        diagnostics.socketStage("peer_credentials")
                        val uid = socket.peerCredentials.uid
                        if (uid != 2000 && uid != 0) {
                            diagnostics.event("socket_rejected uid=$uid")
                            continue
                        }
                        diagnostics.event("socket_connected uid=$uid")
                        diagnostics.socketStage("configure")
                        socket.sendBufferSize = 16384
                        socket.soTimeout = 500
                        pending.set(null)
                        connected = true
                        onConnect()
                        val output = socket.outputStream
                        diagnostics.socketStage("idle")
                        while (running) {
                            pending.getAndSet(null)?.let {
                                diagnostics.socketStage("write")
                                output.write(it.bytes)
                                output.flush()
                                diagnostics.sent(it.sequence, it.bytes.size)
                                diagnostics.socketStage("idle")
                            }
                            Thread.sleep(10)
                        }
                    } catch (e: IOException) {
                        if (running) diagnostics.event("socket_client_error type=${e.javaClass.simpleName} message=${e.message}")
                    } finally {
                        diagnostics.event("socket_disconnected")
                        connected = false
                        socket.close()
                        client = null
                    }
                }
            } catch (e: IOException) {
                if (running) {
                    error = e.message
                    diagnostics.event("socket_server_error type=${e.javaClass.simpleName} message=${e.message}")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                connected = false
                diagnostics.socketStage("stopped")
                diagnostics.event("socket_thread_stopped")
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
