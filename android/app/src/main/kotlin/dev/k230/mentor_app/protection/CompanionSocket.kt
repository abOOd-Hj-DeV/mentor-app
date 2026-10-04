package dev.k230.mentor_app.protection

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class CompanionSocket(
    private val main: (() -> Unit) -> Unit,
    private val hello: (String) -> Map<String, Any?>,
    private val onCommand: (String, CompanionCommand, Long) -> Unit,
    private val onConnect: (String) -> Unit,
    private val onDisconnect: (String) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val current = AtomicReference<Connection?>()
    private val watchdog = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "companion-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile var connected = false
        private set
    @Volatile var error: String? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            try {
                val listener = LocalServerSocket("k230_companion")
                server = listener
                if (!running.get()) { listener.close(); return@Thread }
                while (running.get()) {
                    val socket = listener.accept()
                    if (!allowedUid(socket.peerCredentials.uid)) { socket.close(); continue }
                    val next = Connection(socket, UUID.randomUUID().toString())
                    current.getAndSet(next)?.close()
                    next.start()
                }
            } catch (_: IOException) { if (running.get()) error = "action_failed" }
        }, "companion-accept").also { it.start() }
    }

    fun send(session: String, value: Map<String, Any?>) {
        val c = current.get() ?: return
        if (c.id != session) return
        val bytes = try { CompanionProtocol.encode(value) }
        catch (_: ProtocolFailure) { error = "bounds"; c.close(); return }
        if (!c.outgoing.offer(bytes)) { error = "busy"; c.close() }
    }

    private inner class Connection(val socket: LocalSocket, val id: String) {
        val outgoing = ArrayBlockingQueue<ByteArray>(32)
        private val open = AtomicBoolean(true)
        private var reader: Thread? = null
        private var writer: Thread? = null
        private val bootGreeting = AtomicBoolean(false)
        fun start() {
            socket.soTimeout = 50
            socket.sendBufferSize = CompanionProtocol.MAX_LINE_BYTES
            connected = true
            main {
                if (current.get() === this && open.get()) {
                    try {
                        val greeting = byteArrayOf('K'.code.toByte()) + CompanionProtocol.encode(hello(id))
                        if (!outgoing.offer(greeting)) close()
                        bootGreeting.set(true)
                        onConnect(id)
                    } catch (_: Exception) { close() }
                }
            }
            val greetDeadline = watchdog.schedule({ if (!bootGreeting.get()) close() }, 500, TimeUnit.MILLISECONDS)
            writer = Thread({
                try {
                    while (open.get()) {
                        val bytes = outgoing.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        val timeout = watchdog.schedule({ close() }, 500, TimeUnit.MILLISECONDS)
                        try {
                            socket.outputStream.write(bytes)
                            socket.outputStream.flush()
                        } finally { timeout.cancel(false) }
                    }
                } catch (_: Exception) { close() }
                finally { greetDeadline.cancel(false) }
            }, "companion-write").also { it.start() }
            reader = Thread({
                val framing = BoundedLines()
                val chunk = ByteArray(1024)
                var lastSeq = 0L
                var rateStart = nowUs()
                var decisions = 0
                try {
                    while (open.get()) {
                        val count = try { socket.inputStream.read(chunk) }
                        catch (_: SocketTimeoutException) { framing.checkDeadline(nowUs()); continue }
                        if (count < 0) break
                        for (line in framing.append(chunk, count, nowUs())) {
                            val receivedUs = nowUs()
                            val cmd = CompanionProtocol.parse(line)
                            requireProtocol(cmd.sessionId == id, "session_mismatch")
                            requireProtocol(cmd.seq > lastSeq, "stale")
                            lastSeq = cmd.seq
                            if (cmd is DecisionCommand) {
                                if (nowUs() - rateStart >= 1_000_000) { rateStart = nowUs(); decisions = 0 }
                                requireProtocol(++decisions <= 10, "rate_limited")
                            }
                            // Fixed pending count also bounds the Handler queue if the UI thread stalls.
                            requireProtocol(pending.incrementAndGet() <= 32, "busy")
                            main {
                                try { if (current.get() === this && open.get()) onCommand(id, cmd, receivedUs) }
                                finally { pending.decrementAndGet() }
                            }
                        }
                    }
                } catch (e: ProtocolFailure) { error = e.code }
                catch (_: IOException) { /* fixed diagnostic codes only */ }
                finally { close() }
            }, "companion-read").also { it.start() }
        }
        private val pending = java.util.concurrent.atomic.AtomicInteger()
        fun close() {
            if (!open.compareAndSet(true, false)) return
            try { socket.close() } catch (_: IOException) { }
            reader?.interrupt(); writer?.interrupt(); outgoing.clear()
            if (current.compareAndSet(this, null)) connected = false
            main { onDisconnect(id) }
        }
        fun join() {
            if (Thread.currentThread() !== reader) reader?.join(1500)
            if (Thread.currentThread() !== writer) writer?.join(1500)
        }
    }

    override fun close() {
        running.set(false)
        val c = current.getAndSet(null)
        c?.close()
        try { server?.close() } catch (_: IOException) { }
        acceptThread?.interrupt()
        c?.join(); acceptThread?.join(1500)
        watchdog.shutdownNow()
        connected = false
    }
    companion object {
        fun allowedUid(uid: Int) = uid == 0 || uid == 2000
        private fun nowUs() = System.nanoTime() / 1000
    }
}
