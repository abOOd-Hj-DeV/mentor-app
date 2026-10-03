package dev.k230.mentor_app.protection

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class ProtectionRuntime(private val service: AccessibilityService) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val storage = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32)) {
        Thread(it, "protection-journal").apply { isDaemon = true }
    }
    private var sessionId: String? = null
    private var streamId: String? = null
    private var outgoingSeq = 0L
    private var bound = false
    private val captureClock = CaptureClockBinding()
    private var closed = false
    private var lastStateUs = 0L
    private val bootId = bootScopedId()
    private val tracker = ForegroundScreenTracker(service, ::nowUs,
        onInvalidate = { if (::controller.isInitialized) controller.invalidateGeometry() },
        onVerified = { screen, launcher ->
            if (::controller.isInitialized) controller.verifiedNavigation(screen, launcher)
        })
    private val home = HomeExecutor(service, handler, tracker, ::nowUs)
    private val overlay: AccessibilityOverlayController = AccessibilityOverlayController(service, handler,
        mayAttach = { screen ->
            !closed && !tracker.locked && tracker.snapshot?.let {
                it.token == screen.token && it.width == screen.width && it.height == screen.height &&
                    it.rotation == screen.rotation
            } == true
        },
        younger = { ProtectionIntegration.security.profile()?.age?.let { it <= 12 } ?: true },
        safeHome = { navigateHome { } },
        help = {
            controller.active?.let { a ->
                ProtectionIntegration.security.invoke(
                    SecurityRequest.CreateChallenge(ControlOperation.UNLOCK, a.eventId)) { reply ->
                    handler.post { if (!closed && controller.active?.eventId == a.eventId) overlay.showHelp(reply) }
                }
            }
        })
    private lateinit var controller: ProtectionController
    private val socket = CompanionSocket(
        main = { task -> handler.post { if (!closed) task() } },
        hello = ::hello,
        onConnect = { id -> sessionId = id; streamId = null; outgoingSeq = 0; bound = false; captureClock.reset(); changed() },
        onDisconnect = { id ->
            if (sessionId == id) { sessionId = null; streamId = null; bound = false; captureClock.reset(); controller.disconnect(); changed() }
        },
        onCommand = ::command,
    )
    var onStateChanged: (() -> Unit)? = null

    init {
        ProtectionIntegration.activeEvent = { controller.active?.eventId }
        controller = ProtectionController(::nowUs, { tracker.snapshot }, { tracker.locked },
            { ProtectionIntegration.security }, object : ProtectionActions {
                override fun cover(rects: List<PixelRect>, screen: ScreenSnapshot,
                    done: (Result<List<PixelRect>>) -> Unit) = overlay.cover(rects, screen, done)
                override fun shield(screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit) =
                    overlay.shield(screen, done)
                override fun home(onVerified: (Boolean) -> Unit) = home.request(onVerified)
                override fun clear() = overlay.clear()
            }, ::background, { handler.post { if (!closed) it() } }, ::changed)
        controller.onReleased = { active, reason ->
            val id = sessionId; val stream = streamId
            if (id != null && stream != null && bound) socket.send(id, mapOf(
                "v" to 2, "type" to "released", "session_id" to id, "seq" to nextSeq(),
                "stream_id" to stream, "event_id" to active.eventId,
                "action_revision" to active.revision.toString(), "reason" to reason,
                "released_at_us" to nowUs().toString(), "previous_screen_token" to active.target.token,
                "new_screen_token" to tracker.snapshot?.takeIf {
                    it.status == "verified" && it.token != active.target.token }?.token))
        }
        ProtectionIntegration.guardianRelease = { eventId, deadlineUs, done ->
            handler.post { done(!closed && nowUs() <= deadlineUs && controller.confirmedGuardianGrant(eventId)) }
        }
    }

    private fun background(task: () -> Unit): Boolean = try {
        storage.execute(task); true
    } catch (_: java.util.concurrent.RejectedExecutionException) { false }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            val before = tracker.snapshot
            tracker.refresh()
            val after = tracker.snapshot
            if (after?.status == "verified" && before?.status != "verified") {
                controller.active?.let { active ->
                    if (active.stage == 1 || active.masks.map { it.rect } !=
                        listOf(PixelRect(0, 0, after.width, after.height))) controller.invalidateGeometry()
                }
            }
            overlay.suspendForKeyguard(tracker.locked)
            if (nowUs() - lastStateUs >= 1_000_000) publishState()
            handler.postDelayed(this, 100)
        }
    }
    fun start() { socket.start(); handler.post(heartbeat) }
    fun event(event: AccessibilityEvent) {
        val before = tracker.snapshot
        tracker.event(event)
        if (before?.token != tracker.snapshot?.token || before?.status != tracker.snapshot?.status) changed()
    }
    fun interrupt() { tracker.invalidate(); changed() }
    fun refreshTrustedState() { tracker.invalidate(); changed() }

    private fun command(id: String, cmd: CompanionCommand) {
        if (id != sessionId) return
        when (cmd) {
            is BindCommand -> {
                val receiveUs = nowUs()
                val result = if (bound) ClockBindingReply("rejected", "busy") else captureClock.bind(cmd, receiveUs)
                socket.send(id, mapOf("v" to 2, "type" to "bound", "session_id" to id,
                    "seq" to nextSeq(), "request_seq" to cmd.seq.toString(), "stream_id" to cmd.streamId,
                    "status" to result.status, "error" to result.error,
                    "phone_time_us" to receiveUs.toString()))
                if (result.status == "accepted") {
                    bound = true; streamId = cmd.streamId
                    controller.bind(id, cmd.streamId, true)
                    publishState()
                }
            }
            is DecisionCommand -> {
                if (!bound) ack(id, cmd, ExecutionResult("rejected", 0, "none", null, emptyList(), "clock_unverified"))
                else controller.decide(cmd) { result -> ack(id, cmd, result) }
            }
        }
    }
    private fun ack(id: String, d: DecisionCommand, r: ExecutionResult) {
        if (sessionId != id) return
        socket.send(id, mapOf("v" to 2, "type" to "ack", "session_id" to id, "seq" to nextSeq(),
            "stream_id" to d.streamId, "request_seq" to d.seq.toString(), "event_id" to d.eventId,
            "action_revision" to d.revision.toString(), "status" to r.status, "requested_stage" to d.stage,
            "executed_stage" to r.stage, "executed_action" to r.action,
            "executed_at_us" to r.executedUs?.toString(), "screen_token" to d.screenToken,
            "display_rects" to r.rects.map { it.wire() }, "error" to r.error))
    }
    private fun nextSeq() = (++outgoingSeq).toString()
    private fun hello(id: String) = mapOf("v" to 2, "type" to "hello", "session_id" to id,
        "phone_boot_id" to bootId, "versions" to listOf(2), "max_line_bytes" to CompanionProtocol.MAX_LINE_BYTES,
        "phone_time_us" to nowUs().toString(), "clock" to "android_system_nano_time_us",
        "capabilities" to listOf("cover_region", "calm_shield", "home"),
        "policy" to ProtectionIntegration.security.profile()?.wire(), "screen" to tracker.snapshot?.wire())

    private fun changed() {
        onStateChanged?.invoke(); ProtectionIntegration.onDisplayStateChanged?.invoke(); publishState()
    }
    private fun publishState() {
        lastStateUs = nowUs()
        val id = sessionId ?: return
        val stream = streamId ?: return
        if (!bound) return
        val security = ProtectionIntegration.security
        val state = security.state()
        val active = controller.active
        socket.send(id, mapOf("v" to 2, "type" to "state", "session_id" to id,
            "seq" to nextSeq(), "stream_id" to stream, "phone_time_us" to nowUs().toString(),
            "policy" to security.profile()?.wire(), "screen" to tracker.snapshot?.wire(),
            "protection" to protectionWire(active),
            "health" to mapOf("accessibility" to true, "keystore" to state.encryption,
                "pairing" to state.pairing, "cloud" to state.cloud, "outbox_count" to state.outboxCount)))
    }

    fun displayState(): Map<String, Any?> {
        val security = ProtectionIntegration.security
        val state = security.state()
        val profile = security.profile()
        val active = controller.active
        return mapOf("role" to state.role.wire, "guardianAuthenticated" to state.guardianAuthenticated,
            "profile" to profile?.let { mapOf("age" to it.age, "profile" to it.profile,
                "policyVersion" to PolicyProfile.POLICY_VERSION, "policyRevision" to it.revision.toString()) },
            "pairing" to state.pairing,
            "health" to mapOf("accessibility" to true,
                "companion" to if (socket.connected) "connected" else "disconnected",
                "analysis" to if (profile == null) "unconfigured" else "unknown",
                "execution" to if (tracker.locked || state.encryption == "locked") "locked" else if (bound &&
                    state.role == DeviceRole.CHILD && state.pairing == "paired" && state.encryption == "ready" &&
                    controller.error == null && profile != null && security.journal() != null &&
                    tracker.snapshot?.status == "verified") "ready" else "degraded",
                "encryption" to state.encryption, "cloud" to state.cloud, "outboxCount" to state.outboxCount),
            "activeProtection" to active?.let { mapOf("eventId" to it.eventId, "stage" to it.stage,
                "actionRevision" to it.revision.toString(), "targetScreenToken" to it.target.token,
                "explanationKey" to if ((profile?.age ?: 10) <= 12) "calm_younger" else "calm_older",
                "canNavigateHome" to !tracker.locked) },
            "error" to (controller.error ?: socket.error ?: if (socket.connected && !bound) "clock_unverified" else null))
    }

    fun navigateHome(reply: (Map<String, Any?>) -> Unit) {
        if (!home.request { verified ->
                reply(mapOf("status" to if (verified) "executed" else "failed",
                    "stage" to if (verified) 3 else 0, "action" to if (verified) "home" else "none",
                    "error" to if (verified) null else "home_unverified"))
            }) reply(mapOf("status" to "failed", "stage" to 0, "action" to "none",
                "error" to if (tracker.locked) "locked" else "action_failed"))
    }
    override fun close() {
        closed = true
        ProtectionIntegration.guardianRelease = null
        ProtectionIntegration.activeEvent = { null }
        handler.removeCallbacksAndMessages(null)
        socket.close(); home.close(); controller.disconnect()
        // Service destruction necessarily removes its accessibility windows; never claim a release.
        overlay.clear()
        storage.shutdownNow()
    }

    private fun bootScopedId(): String {
        val boot = Settings.Global.getInt(service.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = service.getSharedPreferences("companion_boot_public", android.content.Context.MODE_PRIVATE)
        val old = prefs.getString("id", null)
        if (boot >= 0 && old != null && prefs.getInt("boot", -2) == boot) return old
        return UUID.randomUUID().toString().also {
            if (boot >= 0) prefs.edit().putInt("boot", boot).putString("id", it).apply()
        }
    }
    companion object { private fun nowUs() = System.nanoTime() / 1000 }
}
