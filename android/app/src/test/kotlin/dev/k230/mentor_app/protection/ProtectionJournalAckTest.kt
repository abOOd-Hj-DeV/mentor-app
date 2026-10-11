package dev.k230.mentor_app.protection

import dev.k230.mentor_app.protection.security.BlobStore
import dev.k230.mentor_app.protection.security.SealedStore
import org.junit.Assert.*
import org.junit.Test

/** Real sealed journal + controller: durable retry authority and canonical ACK serialization. */
class ProtectionJournalAckTest {
    private class Disk : BlobStore {
        val bytes = mutableMapOf<String, ByteArray>()
        override fun read(name: String) = bytes[name]?.clone()
        override fun write(name: String, bytes: ByteArray) { this.bytes[name] = bytes.clone() }
        override fun delete(name: String) { bytes.remove(name) }
        override fun names() = bytes.keys.sorted()
    }
    private class Actions : ProtectionActions {
        var overlayFailed = false
        var homeVerified: Boolean? = true
        var attached = emptyList<PixelRect>()
        val calls = mutableListOf<String>()
        override fun cover(rects: List<PixelRect>, screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit) {
            calls.add("cover")
            if (overlayFailed) { done(Result.failure(ProtocolFailure("action_failed"))); return }
            attached = rects.toList(); done(Result.success(rects))
        }
        override fun shield(screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit) {
            calls.add("shield")
            if (overlayFailed) { done(Result.failure(ProtocolFailure("action_failed"))); return }
            attached = listOf(PixelRect(0, 0, screen.width, screen.height)); done(Result.success(attached))
        }
        override fun home(onVerified: (Boolean) -> Unit): Boolean {
            calls.add("home"); homeVerified?.let(onVerified); return true
        }
        override fun clear() { calls.add("clear"); attached = emptyList() }
    }
    private inner class Harness(val recordBroken: Boolean = false) {
        var now = 10_450_000L
        var screen = ProtocolFixtures.screen
        val disk = Disk()
        val sealed = SealedExecutionJournal(SealedStore(disk,
            com.google.crypto.tink.subtle.AesGcmJce(ByteArray(32) { 9 }), ProtocolFixtures.EVENT, "tests"),
            { now }, "tests")
        val actions = Actions()
        val order = mutableListOf<String>()
        val journal = object : ExecutionJournal by sealed {
            override fun finish(decision: DecisionCommand, result: ExecutionResult) {
                order.add("journal"); sealed.finish(decision, result)
            }
            override fun release(eventId: String, revision: Long, releasedUs: Long) {
                order.add("release_journal"); sealed.release(eventId, revision, releasedUs)
            }
        }
        val security = object : GuardianSecurityDelegate {
            override fun state() = SecurityState(DeviceRole.CHILD, pairing = "paired", encryption = "ready")
            override fun profile() = ProtocolFixtures.profile
            override fun journal(): ExecutionJournal = journal
            override fun invoke(request: SecurityRequest, reply: (SecurityReply) -> Unit) = Unit
            override fun onBackground() = Unit
            override fun recordExecution(decision: DecisionCommand, result: ExecutionResult) {
                order.add("incident")
                if (recordBroken) throw IllegalStateException()
            }
            override fun recordRelease(eventId: String, revision: Long, reason: String) { order.add("release_incident") }
        }
        val controller = ProtectionController({ now }, { screen }, { false }, { security }, actions,
            { it(); true }, { it() }, {})
        val replies = mutableListOf<ExecutionResult>()
        val acks = mutableListOf<Map<String, Any?>>()
        var seq = 0L
        init { controller.bind(ProtocolFixtures.SESSION, ProtocolFixtures.STREAM, true) }
        fun decide(map: Map<String, Any?> = ProtocolFixtures.decision()) {
            val d = ProtocolFixtures.parse(map)
            controller.decide(d) { result ->
                replies.add(result)
                val ack = ackWire(ProtocolFixtures.SESSION, (++seq).toString(), d, result)
                acks.add(ack)
                println("NATIVE_ACK=" + String(CompanionProtocol.encode(ack)).trim())
                validateAck(d, result)
            }
        }
        fun advance(us: Long) { now += us; screen = screen.copy(sampledUs = now) }
    }
    private val longChain = listOf(9_400_000L, 9_650_000, 9_900_000, 10_150_000, 10_400_000)
    private fun chain(shift: Long) = longChain.map { it + shift }

    /** Mirrors the canonical C++ companion ack requirements. */
    private fun validateAck(d: DecisionCommand, r: ExecutionResult) {
        assertTrue(r.status in setOf("executed", "duplicate", "rejected", "failed", "pending"))
        assertTrue(r.stage in 0..3 && r.stage <= d.stage)
        assertEquals(when (r.stage) {
            0 -> "none"
            1 -> "cover_region"
            2 -> "calm_shield"
            else -> "home"
        }, r.action)
        assertEquals(r.stage == 0, r.executedUs == null)
        assertEquals(r.stage == 1 || r.stage == 2, r.rects.isNotEmpty())
        if (r.status == "executed") { assertEquals(d.stage, r.stage); assertNull(r.error) }
        if (r.status == "pending") {
            assertEquals(3, d.stage); assertTrue(r.stage == 0 || r.stage == 2); assertNull(r.error)
        }
        if (r.status == "failed" || r.status == "rejected") assertNotNull(r.error)
    }

    @Test fun knownNonExecutionAllowsOneFreshMonotonicRetryOnTheRealJournal() {
        val h = Harness()
        h.actions.overlayFailed = true
        h.decide()
        assertEquals("failed", h.replies.last().status)
        assertEquals(0, h.replies.last().stage)
        assertTrue(h.replies.last().rects.isEmpty())
        h.actions.overlayFailed = false
        h.advance(1_000_000)
        h.decide(ProtocolFixtures.decision(revision = 2, pts = chain(1_000_000).takeLast(3)))
        assertEquals("executed", h.replies.last().status)
        assertEquals(1, h.replies.last().stage)
        h.advance(300_000)
        h.decide(ProtocolFixtures.decision(revision = 2, pts = chain(1_300_000).takeLast(3)))
        assertEquals("event_conflict", h.replies.last().error)
    }

    @Test fun executedHomeAndReleasedEventsAreNeverRetriedFromTheDurableJournal() {
        val h = Harness()
        h.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, longChain))
        assertEquals(listOf("pending", "executed"), h.replies.map { it.status })
        h.advance(500_000)
        h.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, chain(1_000_000), revision = 2))
        assertEquals("event_conflict", h.replies.last().error)
        val released = Harness()
        released.decide()
        released.controller.verifiedNavigation(released.screen.copy(token = ProtocolFixtures.CONTINUITY,
            epoch = 2, packageName = "com.safe.other", windowId = 9), false)
        assertNull(released.controller.active)
        released.advance(500_000)
        released.decide(ProtocolFixtures.decision(revision = 2, pts = chain(1_000_000).takeLast(3)))
        assertEquals("event_conflict", released.replies.last().error)
    }

    @Test fun verifiedHomeAckReportsStageThreeHomeWithoutShieldRectangles() {
        val h = Harness()
        h.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, longChain))
        val pending = h.replies.first()
        assertEquals(2, pending.stage)
        assertEquals("calm_shield", pending.action)
        assertEquals(listOf(PixelRect(0, 0, 1080, 2400)), pending.rects)
        val done = h.replies.last()
        assertEquals("executed", done.status)
        assertEquals(3, done.stage)
        assertEquals("home", done.action)
        assertTrue(done.rects.isEmpty())
        assertEquals(emptyList<Any?>(), h.acks.last()["display_rects"])
        assertEquals(listOf(PixelRect(0, 0, 1080, 2400)), h.actions.attached)
        assertEquals(3, h.controller.active!!.stage)
        h.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, longChain, revision = 2))
        assertEquals("event_conflict", h.replies.last().error)
        assertEquals(3, h.replies.last().stage)
        assertEquals("home", h.replies.last().action)
        assertTrue(h.replies.last().rects.isEmpty())
    }

    @Test fun durableIncidentPrecedesExecutionJournalAndStorageFailureIsProtocolValid() {
        val ordered = Harness()
        ordered.decide()
        assertEquals(listOf("incident", "journal"), ordered.order)
        val broken = Harness(recordBroken = true)
        broken.decide()
        val reply = broken.replies.single()
        assertEquals("failed", reply.status)
        assertEquals("storage_failed", reply.error)
        assertEquals(1, reply.stage)
        assertEquals("cover_region", reply.action)
        assertEquals(broken.actions.attached, reply.rects)
        assertEquals(listOf("incident"), broken.order)
    }

    @Test fun duplicateRetryOfAnExecutedRevisionReplaysTheStoredActualResult() {
        val h = Harness()
        h.decide()
        h.advance(100_000)
        h.decide()
        assertEquals("duplicate", h.replies.last().status)
        assertEquals(1, h.replies.last().stage)
        assertEquals("cover_region", h.replies.last().action)
        assertEquals(1, h.actions.calls.count { it == "cover" })
    }

    @Test fun incompleteCrashAndUnavailableIncidentNeverGrantNewRetryAuthority() {
        val crashed = Harness()
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision())
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(d.semanticJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(JournalReservation.New, crashed.sealed.reserve(d, digest))
        crashed.decide()
        assertEquals("home_unverified", crashed.replies.last().error)
        crashed.advance(1_000_000)
        crashed.decide(ProtocolFixtures.decision(revision = 2, pts = chain(1_000_000).takeLast(3)))
        assertEquals("event_conflict", crashed.replies.last().error)
        assertTrue(crashed.actions.calls.isEmpty())
        val broken = Harness(recordBroken = true)
        broken.decide()
        broken.advance(1_000_000)
        broken.decide(ProtocolFixtures.decision(revision = 2, pts = chain(1_000_000).takeLast(3)))
        assertEquals("event_conflict", broken.replies.last().error)
        assertEquals(1, broken.actions.calls.size)
    }

    @Test fun reusedEvidenceAndSecondNonexecutionRetryAreConservativelyRejected() {
        val stale = Harness()
        stale.actions.overlayFailed = true
        stale.decide()
        stale.decide(ProtocolFixtures.decision(revision = 2))
        assertEquals("event_conflict", stale.replies.last().error)
        assertEquals(1, stale.actions.calls.size)
        val exhausted = Harness()
        exhausted.actions.overlayFailed = true
        exhausted.decide()
        exhausted.advance(1_000_000)
        exhausted.decide(ProtocolFixtures.decision(revision = 2, pts = chain(1_000_000).takeLast(3)))
        assertEquals("failed", exhausted.replies.last().status)
        assertEquals(0, exhausted.replies.last().stage)
        exhausted.advance(1_000_000)
        exhausted.decide(ProtocolFixtures.decision(revision = 3, pts = chain(2_000_000).takeLast(3)))
        assertEquals("event_conflict", exhausted.replies.last().error)
        assertEquals(2, exhausted.actions.calls.size)
    }

    @Test fun rejectedNonexecutionCanRetryAndVerifiedHomeDuplicateKeepsEmptyRectangles() {
        val rejected = Harness()
        rejected.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0))
        assertEquals("rejected", rejected.replies.last().status)
        assertEquals("invalid_evidence", rejected.replies.last().error)
        assertEquals(0, rejected.replies.last().stage)
        rejected.advance(1_450_000)
        rejected.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, chain(1_450_000), revision = 2))
        assertEquals("executed", rejected.replies.last().status)
        assertEquals(3, rejected.replies.last().stage)
        rejected.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, chain(1_450_000), revision = 2))
        assertEquals("duplicate", rejected.replies.last().status)
        assertEquals("home", rejected.replies.last().action)
        assertTrue(rejected.replies.last().rects.isEmpty())
        assertEquals(1, rejected.actions.calls.count { it == "home" })
    }

    @Test fun failedHomeAndIncidentStorageFailureReportActualActionWithoutAppliedClaim() {
        val failed = Harness()
        failed.actions.homeVerified = false
        failed.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, longChain))
        assertEquals("failed", failed.replies.last().status)
        assertEquals(2, failed.replies.last().stage)
        assertEquals("calm_shield", failed.replies.last().action)
        assertEquals("home_unverified", failed.replies.last().error)
        failed.advance(1_000_000)
        failed.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, chain(1_000_000), revision = 2))
        assertEquals("event_conflict", failed.replies.last().error)
        assertEquals(1, failed.actions.calls.count { it == "home" })
        val broken = Harness(recordBroken = true)
        broken.decide(ProtocolFixtures.decision(3, .96, 0.0, 0.0, longChain))
        assertEquals("failed", broken.replies.last().status)
        assertEquals("storage_failed", broken.replies.last().error)
        assertEquals(3, broken.replies.last().stage)
        assertEquals("home", broken.replies.last().action)
        assertNotNull(broken.replies.last().executedUs)
        assertTrue(broken.replies.last().rects.isEmpty())
        assertEquals(listOf("incident"), broken.order)
        val released = Harness()
        released.decide()
        released.controller.guardianGrant(ProtocolFixtures.EVENT)
        assertEquals(listOf("incident", "journal", "release_incident", "release_journal"), released.order)
    }
}
