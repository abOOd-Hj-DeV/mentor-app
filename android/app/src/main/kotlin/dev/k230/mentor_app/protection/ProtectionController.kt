package dev.k230.mentor_app.protection

import java.security.MessageDigest

internal data class ActiveProtection(
    val eventId: String, val revision: Long, val stage: Int, val appliedUs: Long,
    val target: ScreenSnapshot, val masks: List<AppliedMask>,
)

internal fun protectionWire(active: ActiveProtection?) = mapOf(
    "stage" to (active?.stage ?: 0), "event_id" to active?.eventId,
    "action_revision" to active?.revision?.toString(), "target_screen_token" to active?.target?.token,
    "applied_at_us" to active?.appliedUs?.toString(),
    "covered_rects" to active?.masks.orEmpty().map { it.rect.wire() }, "release_pending" to false,
)

/** Canonical companion ACK shape: stage 0 carries no time or rectangles, only stage 1/2 report
 * actual overlay rectangles, and a verified HOME reports stage 3 even while a shield persists. */
internal fun ackWire(session: String, seq: String, d: DecisionCommand, r: ExecutionResult) = mapOf(
    "v" to 2, "type" to "ack", "session_id" to session, "seq" to seq, "stream_id" to d.streamId,
    "request_seq" to d.seq.toString(), "event_id" to d.eventId, "action_revision" to d.revision.toString(),
    "status" to r.status, "requested_stage" to d.stage, "executed_stage" to r.stage,
    "executed_action" to r.action, "executed_at_us" to r.executedUs?.toString(),
    "screen_token" to d.screenToken, "display_rects" to r.rects.map { it.wire() }, "error" to r.error,
)

/** Main-thread state machine. Journal work is delegated off the main/socket reader threads. */
internal class ProtectionController(
    private val now: () -> Long,
    private val screen: () -> ScreenSnapshot?,
    private val locked: () -> Boolean,
    private val security: () -> GuardianSecurityDelegate,
    private val actions: ProtectionActions,
    private val background: (() -> Unit) -> Boolean,
    private val main: (() -> Unit) -> Unit,
    private val onChange: () -> Unit,
) {
    var active: ActiveProtection? = null
        private set
    var error: String? = null
        private set
    private var sessionId: String? = null
    private var streamId: String? = null
    private var clockVerified = false
    private var busy = false
    private var generation = 0L
    private var pendingNavigation: Pair<ScreenSnapshot, Boolean>? = null
    private var pendingGrant: String? = null

    fun bind(session: String, stream: String, verifiedClock: Boolean) {
        sessionId = session; streamId = stream; clockVerified = verifiedClock; generation++
    }
    fun disconnect() {
        sessionId = null; streamId = null; clockVerified = false; generation++
    }

    fun decide(d: DecisionCommand, reply: (ExecutionResult) -> Unit) {
        if (d.sessionId != sessionId) { reply(rejected("session_mismatch")); return }
        if (d.streamId != streamId) { reply(rejected("stream_mismatch")); return }
        if (busy) { reply(rejected("busy")); return }
        val port = security()
        val journal = port.journal()
        if (port.state().role != DeviceRole.CHILD || port.state().pairing != "paired") {
            reply(rejected("policy_mismatch")); return
        }
        if (journal == null) { reply(rejected("storage_failed")); return }
        if (port.state().encryption != "ready") {
            reply(rejected(if (port.state().encryption == "locked") "locked" else "storage_failed")); return
        }
        val connectionGeneration = generation
        busy = true
        val queued = background {
            val reservation = try {
                val digest = MessageDigest.getInstance("SHA-256").digest(d.semanticJson.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                journal.reserve(d, digest)
            } catch (_: Exception) { null }
            val repeated = try {
                val p = port.profile()
                p != null && (journal.executedEpisodes(d.packageName, p, now() - 60_000_000, now()) +
                    d.eventId).size >= p.repetitions
            } catch (_: Exception) { false }
            main {
                when (reservation) {
                    is JournalReservation.Cached -> {
                        reply(reservation.result.copy(status = "duplicate"))
                        settled()
                    }
                    JournalReservation.Conflict -> { reply(rejected("event_conflict")); settled() }
                    JournalReservation.Incomplete -> { reply(rejected("home_unverified")); settled() }
                    null -> { reply(rejected("storage_failed")); settled() }
                    JournalReservation.New, KnownNonexecutionRetry -> execute(d, port, journal,
                        connectionGeneration, repeated, reservation == KnownNonexecutionRetry, reply)
                }
            }
        }
        if (!queued) { reply(rejected("busy")); settled() }
    }

    private fun execute(
        d: DecisionCommand, port: GuardianSecurityDelegate, journal: ExecutionJournal,
        connectionGeneration: Long, repeated: Boolean, nonexecutionRetry: Boolean,
        reply: (ExecutionResult) -> Unit,
    ) {
        fun complete(result: ExecutionResult) {
            error = result.error
            onChange()
            val queued = background {
                var saved = true
                // Durable incident metadata must precede the execution journal so a crash in between
                // cannot leave an executed action without recoverable evidence.
                try { port.recordExecution(d, result); journal.finish(d, result) }
                catch (_: Exception) { saved = false }
                main {
                    if (!saved) { error = "storage_failed"; onChange() }
                    reply(if (saved) result else result.copy(status = "failed", error = "storage_failed"))
                    settled()
                }
            }
            if (!queued) {
                error = "storage_failed"; onChange()
                reply(result.copy(status = "failed", error = "storage_failed")); settled()
            }
        }
        try {
            requireProtocol(connectionGeneration == generation && d.sessionId == sessionId, "session_mismatch")
            requireProtocol(port === security(), "policy_mismatch")
            requireProtocol(port.state().role == DeviceRole.CHILD && port.state().pairing == "paired",
                "policy_mismatch")
            requireProtocol(port.state().encryption == "ready", "storage_failed")
            val mapped = DecisionValidator.validate(d, screen(), port.profile(), now(), clockVerified,
                locked(), active?.masks.orEmpty(), repeated)
            val old = active
            requireProtocol(old == null || old.eventId == d.eventId, "busy")
            requireProtocol(old == null || d.revision == old.revision + 1 && d.stage >= old.stage, "event_conflict")
            requireProtocol(old != null || d.revision == 1L || nonexecutionRetry, "event_conflict")
            val s = screen()!!
            val union = if (d.stage == 1) {
                coverRectUnion(old?.masks.orEmpty().map { it.rect }, mapped)
            } else emptyList()
            val installed: (Result<List<PixelRect>>) -> Unit = { result ->
                result.fold({ rects ->
                    val applied = now()
                    val inherited = old?.masks.orEmpty()
                    val masks = rects.map { rect ->
                        inherited.firstOrNull { it.rect == rect } ?: AppliedMask(rect, applied)
                    }
                    active = ActiveProtection(d.eventId, d.revision, minOf(d.stage, 2), applied, s, masks)
                    onChange()
                    if (d.stage != 3) complete(actual("executed"))
                    else {
                        // Recheck immediately before HOME after asynchronous overlay attachment.
                        try {
                            requireProtocol(connectionGeneration == generation, "session_mismatch")
                            requireProtocol(port === security() && port.state().role == DeviceRole.CHILD &&
                                port.state().pairing == "paired", "policy_mismatch")
                            requireProtocol(port.state().encryption == "ready", "storage_failed")
                            DecisionValidator.validate(d, screen(), port.profile(), now(), clockVerified,
                                locked(), old?.masks.orEmpty(), repeated)
                            reply(actual("pending"))
                            if (!actions.home { verified ->
                                if (verified) {
                                    active = active?.copy(stage = 3, appliedUs = now())
                                    complete(actual("executed", home = true))
                                } else complete(actual("failed", "home_unverified"))
                            }) complete(actual("failed", "action_failed"))
                        } catch (e: ProtocolFailure) { complete(actual("failed", e.code)) }
                    }
                }, { complete(actual("failed", (it as? ProtocolFailure)?.code ?: "action_failed")) })
            }
            if (d.stage == 1) actions.cover(union, s, installed) else actions.shield(s, installed)
        } catch (e: ProtocolFailure) { complete(rejected(e.code)) }
        catch (_: Exception) { complete(actual("failed", "action_failed")) }
    }

    fun invalidateGeometry() {
        val a = active ?: return
        val s = screen() ?: a.target
        actions.shield(s) { result ->
            result.fold({ rects ->
                if (active?.eventId == a.eventId) active = a.copy(stage = maxOf(2, a.stage),
                    masks = rects.map { AppliedMask(it, now()) })
            }, { error = "action_failed" })
            onChange()
        }
        onChange()
    }

    fun verifiedNavigation(next: ScreenSnapshot, launcher: Boolean) {
        if (next.status != "verified") return
        if (busy) { pendingNavigation = next to launcher; return }
        val a = active ?: return
        if (a.stage == 3 && !launcher) return
        if (next.token == a.target.token || next.epoch <= a.target.epoch) return
        if (next.packageName == a.target.packageName && next.windowId == a.target.windowId) return
        release("verified_navigation")
    }

    /** Security delegate calls this only after consuming an authenticated, one-use guardian grant. */
    fun guardianGrant(eventId: String) {
        if (active?.eventId != eventId) return
        if (busy) pendingGrant = eventId else release("guardian_grant")
    }
    fun confirmedGuardianGrant(eventId: String): Boolean {
        if (busy || active?.eventId != eventId) return false
        release("guardian_grant")
        return active == null
    }

    private fun settled() {
        busy = false
        val grant = pendingGrant
        pendingGrant = null
        if (grant != null) guardianGrant(grant)
        val navigation = pendingNavigation
        pendingNavigation = null
        navigation?.let { (next, launcher) ->
            if (!locked() && screen()?.let { it.status == "verified" && it.token == next.token } == true) {
                verifiedNavigation(next, launcher)
            }
        }
    }

    var onReleased: ((ActiveProtection, String) -> Unit)? = null
    private fun release(reason: String) {
        val a = active ?: return
        try { actions.clear() } catch (_: Exception) { error = "action_failed"; onChange(); return }
        active = null
        val port = security()
        if (!background {
            try {
                port.recordRelease(a.eventId, a.revision, reason)
                port.journal()?.release(a.eventId, a.revision, now())
            } catch (_: Exception) { main { error = "storage_failed"; onChange() } }
        }) error = "storage_failed"
        onReleased?.invoke(a, reason)
        onChange()
    }

    private fun rejected(code: String) = actual("rejected", code)
    private fun actual(status: String, code: String? = null, home: Boolean = false): ExecutionResult {
        val a = active
        val stage = if (home) 3 else a?.stage ?: 0
        return ExecutionResult(status, stage,
            if (stage == 3) "home" else CompanionProtocol.actionFor(stage), a?.appliedUs,
            if (stage == 1 || stage == 2) a?.masks.orEmpty().map { it.rect } else emptyList(), code)
    }
}
