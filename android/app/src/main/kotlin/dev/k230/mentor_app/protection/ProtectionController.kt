package dev.k230.mentor_app.protection

import java.security.MessageDigest

internal data class ActiveProtection(
    val eventId: String, val revision: Long, val stage: Int, val appliedUs: Long,
    val target: ScreenSnapshot, val masks: List<AppliedMask>,
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
                    JournalReservation.New -> execute(d, port, journal, connectionGeneration, repeated, reply)
                }
            }
        }
        if (!queued) { reply(rejected("busy")); settled() }
    }

    private fun execute(
        d: DecisionCommand, port: GuardianSecurityDelegate, journal: ExecutionJournal,
        connectionGeneration: Long, repeated: Boolean, reply: (ExecutionResult) -> Unit,
    ) {
        fun complete(result: ExecutionResult) {
            error = result.error
            onChange()
            val queued = background {
                var saved = true
                try { journal.finish(d, result); port.recordExecution(d, result) }
                catch (_: Exception) { saved = false }
                main {
                    if (!saved) { error = "storage_failed"; onChange() }
                    reply(if (saved) result else result.copy(error = "storage_failed"))
                    settled()
                }
            }
            if (!queued) {
                error = "storage_failed"; onChange()
                reply(result.copy(error = "storage_failed")); settled()
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
            requireProtocol(old != null || d.revision == 1L, "event_conflict")
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
                port.journal()?.release(a.eventId, a.revision, now())
                port.recordRelease(a.eventId, a.revision, reason)
            } catch (_: Exception) { main { error = "storage_failed"; onChange() } }
        }) error = "storage_failed"
        onReleased?.invoke(a, reason)
        onChange()
    }

    private fun rejected(code: String) = actual("rejected", code)
    private fun actual(status: String, code: String? = null, home: Boolean = false): ExecutionResult {
        val a = active
        return ExecutionResult(status, a?.stage ?: 0,
            if (home) "home" else CompanionProtocol.actionFor(minOf(a?.stage ?: 0, 2)),
            a?.appliedUs, a?.masks.orEmpty().map { it.rect }, code)
    }
}
