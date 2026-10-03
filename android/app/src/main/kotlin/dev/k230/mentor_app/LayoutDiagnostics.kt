package dev.k230.mentor_app

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class LayoutDiagnostics(
    private val log: (String) -> Unit,
    private val postHeartbeat: (() -> Unit) -> Unit,
    private val mainStack: () -> String,
    private val clockUs: () -> Long = { System.nanoTime() / 1000 },
) : AutoCloseable {
    private data class Stage(val name: String, val sinceUs: Long)

    @Volatile private var active = false
    @Volatile private var collector = Stage("idle", 0)
    @Volatile private var socket = Stage("stopped", 0)
    @Volatile private var collectStartedUs = 0L
    @Volatile private var mainAliveUs = 0L
    @Volatile private var publishedUs = 0L
    @Volatile private var sentUs = 0L
    private val heartbeatPending = AtomicBoolean()
    private val publications = AtomicLong()
    private val sends = AtomicLong()
    private var monitor: Thread? = null
    private var lastReportUs = 0L
    private var lastStackUs = 0L
    private var lastSendLogUs = 0L

    fun start(session: Long) {
        if (active) return
        activate(session)
        monitor = Thread({
            try {
                while (active) {
                    Thread.sleep(250)
                    report()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "layout-diagnostics").also { it.isDaemon = true; it.start() }
    }

    internal fun activate(session: Long) {
        mainAliveUs = clockUs()
        collector = Stage("idle", mainAliveUs)
        socket = Stage("stopped", mainAliveUs)
        lastReportUs = mainAliveUs
        lastStackUs = mainAliveUs - 2_000_000
        active = true
        event("diagnostics_start build=layout-diag-v1 session=$session")
    }

    fun beginCollection() {
        if (!active) return
        collectStartedUs = clockUs()
        collectorStage("screen")
        event("collect_begin")
    }

    fun collectorStage(name: String) {
        if (active) collector = Stage(name, clockUs())
    }

    fun endCollection() {
        if (!active) return
        event("collect_end elapsed_us=${clockUs() - collectStartedUs} last_stage=${collector.name}")
        collectorStage("idle")
    }

    fun socketStage(name: String) {
        if (active) socket = Stage(name, clockUs())
    }

    fun published(snapshot: LayoutSnapshot) {
        if (!active) return
        publishedUs = clockUs()
        publications.incrementAndGet()
        if (snapshot.flags and LayoutWire.INVALIDATE == 0) {
            event("snapshot seq=${snapshot.sequence} flags=${snapshot.flags} " +
                "nodes=${snapshot.nodes.size} media=${snapshot.nodes.count { it.kind != 0 }} " +
                "package=${snapshot.packageName} capture_us=${snapshot.completedAtUs - snapshot.sampledAtUs} " +
                "size=${snapshot.width}x${snapshot.height} rotation=${snapshot.rotation}")
        }
    }

    fun sent(sequence: Long, bytes: Int) {
        if (!active) return
        val now = clockUs()
        sentUs = now
        val count = sends.incrementAndGet()
        if (count == 1L || now - lastSendLogUs >= 1_000_000 || now - socket.sinceUs >= 50_000) {
            event("socket_sent seq=$sequence bytes=$bytes write_us=${now - socket.sinceUs}")
            lastSendLogUs = now
        }
    }

    fun event(message: String) {
        if (active) log("phone_us=${clockUs()} $message")
    }

    internal fun report() {
        if (!active) return
        val now = clockUs()
        val reading = collector
        val writing = socket
        if (now - lastReportUs >= 1_000_000) {
            event("health collect=${reading.name} collect_stage_us=${now - reading.sinceUs} " +
                "socket=${writing.name} socket_stage_us=${now - writing.sinceUs} " +
                "main_idle_us=${now - mainAliveUs} publications=${publications.get()} sends=${sends.get()} " +
                "publish_idle_us=${if (publishedUs == 0L) -1 else now - publishedUs} " +
                "send_idle_us=${if (sentUs == 0L) -1 else now - sentUs}")
            lastReportUs = now
        }
        if (now - mainAliveUs >= 500_000 && now - lastStackUs >= 2_000_000) {
            event("main_stalled collect=${reading.name} stage_us=${now - reading.sinceUs} stack=${mainStack()}")
            lastStackUs = now
        }
        if (heartbeatPending.compareAndSet(false, true)) {
            postHeartbeat {
                mainAliveUs = clockUs()
                heartbeatPending.set(false)
            }
        }
    }

    override fun close() {
        event("diagnostics_stop")
        active = false
        monitor?.interrupt()
    }
}
