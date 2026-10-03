package dev.k230.mentor_app.protection

import org.junit.Assert.*
import org.junit.Test

class ProtectionControllerTest {
    private class Journal : ExecutionJournal {
        private val digests = mutableMapOf<Pair<String,Long>, String>()
        private val results = mutableMapOf<Pair<String,Long>, ExecutionResult>()
        val episodes = mutableMapOf<String, Long>()
        var finishes = 0
        var broken = false
        var finishBroken = false
        override fun reserve(decision: DecisionCommand, semanticSha256: String): JournalReservation {
            if (broken) throw IllegalStateException()
            val key = decision.eventId to decision.revision
            val hash = digests[key]
            if (hash != null) {
                if (hash != semanticSha256) return JournalReservation.Conflict
                return results[key]?.let { JournalReservation.Cached(it) } ?: JournalReservation.Incomplete
            }
            digests[key] = semanticSha256
            return JournalReservation.New
        }
        override fun finish(decision: DecisionCommand, result: ExecutionResult) {
            if (finishBroken) throw IllegalStateException()
            finishes++
            results[decision.eventId to decision.revision] = result
            if (result.status == "executed" && result.stage in 1..2) {
                episodes.putIfAbsent(decision.eventId, result.executedUs!!)
            }
        }
        override fun executedEpisodes(packageName: String, policy: PolicyProfile, afterUs: Long, nowUs: Long) =
            episodes.filterValues { it > afterUs && it <= nowUs }.keys.toSet()
        override fun release(eventId: String, revision: Long, releasedUs: Long) = Unit
    }
    private class Actions : ProtectionActions {
        val calls = mutableListOf<String>()
        val coverRequests = mutableListOf<List<PixelRect>>()
        var attachedRects = emptyList<PixelRect>()
        var clearCount = 0
        var homeAccepted = true
        var homeVerified: Boolean? = true
        var overlayFailed = false
        var pendingHome: ((Boolean) -> Unit)? = null
        override fun cover(rects: List<PixelRect>, screen: ScreenSnapshot,
            done: (Result<List<PixelRect>>) -> Unit) {
            calls.add("cover")
            coverRequests.add(rects.toList())
            if (!overlayFailed) attachedRects = rects.toList()
            done(if (overlayFailed) Result.failure(ProtocolFailure("action_failed")) else Result.success(rects))
        }
        override fun shield(screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit) {
            calls.add("shield")
            if (!overlayFailed) attachedRects = listOf(PixelRect(0,0,screen.width,screen.height))
            done(if (overlayFailed) Result.failure(ProtocolFailure("action_failed")) else
                Result.success(listOf(PixelRect(0,0,screen.width,screen.height))))
        }
        override fun home(onVerified: (Boolean) -> Unit): Boolean {
            calls.add("home")
            if (homeAccepted) {
                homeVerified?.let { onVerified(it) } ?: run { pendingHome = onVerified }
            }
            return homeAccepted
        }
        override fun clear() { clearCount++; calls.add("clear"); attachedRects = emptyList() }
    }
    private class Harness {
        var now = 10_450_000L
        var screen = ProtocolFixtures.screen
        var profile = ProtocolFixtures.profile
        var locked = false
        var state = SecurityState(DeviceRole.CHILD, pairing="paired", encryption="ready")
        val journal = Journal()
        var journalAvailable = true
        val actions = Actions()
        var immediateWork = true
        val jobs = java.util.ArrayDeque<() -> Unit>()
        val security = object : GuardianSecurityDelegate {
            override fun state() = state
            override fun profile() = profile
            override fun journal() = if (journalAvailable) journal else null
            override fun invoke(request: SecurityRequest, reply: (SecurityReply) -> Unit) = Unit
            override fun onBackground() = Unit
            override fun recordExecution(decision: DecisionCommand, result: ExecutionResult) = Unit
            override fun recordRelease(eventId: String, revision: Long, reason: String) = Unit
        }
        val controller = ProtectionController({ now }, { screen }, { locked }, { security }, actions,
            { if (immediateWork) it() else jobs.add(it); true }, { it() }, {})
        val replies = mutableListOf<ExecutionResult>()
        init { controller.bind(ProtocolFixtures.SESSION,ProtocolFixtures.STREAM,true) }
        fun decide(d: DecisionCommand = ProtocolFixtures.parse(ProtocolFixtures.decision())) {
            controller.decide(d) { replies.add(it) }
        }
    }
    private val longChain = listOf(9_400_000L,9_650_000,9_900_000,10_150_000,10_400_000)

    @Test fun highPornReachesHomeAsFirstRequestedActionWithoutAnEarlierCover() {
        val h = Harness()
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,longChain))
        assertNull(h.controller.active)
        h.decide(d)
        assertEquals(listOf("shield","home"),h.actions.calls)
        assertEquals(listOf("pending","executed"),h.replies.map { it.status })
        assertEquals(3,h.replies.last().stage)
        assertEquals("home",h.replies.last().action)
        assertEquals(1,h.journal.finishes)
        assertTrue(h.journal.episodes.isEmpty())
    }
    @Test fun insufficientStage3ProofNeverRendersALowerUnprovenAction() {
        val h = Harness()
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0)))
        assertEquals("invalid_evidence",h.replies.single().error)
        assertTrue(h.actions.calls.isEmpty())
    }
    @Test fun hentaiWinnerDoesNotBlockIndependentPornHomeButHentaiCannotHomeByRepetitionOrRestart() {
        val h = Harness()
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,longChain))
        // The host transmits the decisive Porn-supported region, not the maximum-E Hentai crop.
        h.decide(d.copy(regions=d.regions.map { it.copy(trackId=2) }))
        assertEquals(3,h.replies.last().stage)
        repeat(4) {
            val fresh = Harness()
            fresh.profile = PolicyProfile(if (it % 2 == 0) 12 else 13, 1)
            fresh.journal.episodes["previous"] = fresh.now-1_000_000
            fresh.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(3,.05,.94,0.0,longChain,
                age=fresh.profile.age)))
            assertEquals("hentai_stage3_forbidden",fresh.replies.single().error)
            assertTrue(fresh.actions.calls.isEmpty())
        }
    }
    @Test fun coveredPixelsCannotEscalateButPreOverlayBufferCan() {
        val h = Harness()
        h.decide()
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,
            longChain.map { it+1_000_000 }, revision=2))
        h.now += 1_000_000; h.screen = h.screen.copy(sampledUs=h.now)
        h.decide(d)
        assertEquals("invalid_evidence",h.replies.last().error)
        assertEquals(listOf("cover"),h.actions.calls)
        val buffered = Harness()
        buffered.decide()
        buffered.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,longChain,revision=2)))
        assertEquals(3,buffered.replies.last().stage)
    }
    @Test fun shieldAndCoverPersistThroughDisconnectProfileChangesAndInvalidEvidence() {
        val h = Harness()
        h.decide()
        h.controller.disconnect()
        assertEquals(1,h.controller.active!!.stage)
        assertEquals(0,h.actions.clearCount)
        h.profile = PolicyProfile(13,2)
        h.controller.bind(ProtocolFixtures.SESSION,ProtocolFixtures.STREAM,true)
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(revision=2)))
        assertEquals("policy_mismatch",h.replies.last().error)
        assertEquals(1,h.controller.active!!.stage)
        assertEquals(0,h.actions.clearCount)
        h.controller.invalidateGeometry()
        assertEquals(2,h.controller.active!!.stage)
        assertEquals("shield",h.actions.calls.last())
    }
    @Test fun sameEventRetryDoesNotReexecuteAndConflictingSemanticIsRejected() {
        val h = Harness()
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision())
        h.decide(d)
        h.now += 10_000_000
        h.decide(d.copy(seq=99))
        assertEquals("duplicate",h.replies.last().status)
        assertEquals(1,h.actions.calls.size)
        val changed = ProtocolFixtures.parse(ProtocolFixtures.decision(p=.66))
        h.decide(changed)
        assertEquals("event_conflict",h.replies.last().error)
        assertEquals(1,h.actions.calls.size)
    }
    @Test fun homeRequestTrueWithoutLauncherIsFailedWithTruthfulShield() {
        val h = Harness()
        h.actions.homeVerified = false
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,longChain)))
        assertEquals("failed",h.replies.last().status)
        assertEquals("home_unverified",h.replies.last().error)
        assertEquals(2,h.replies.last().stage)
        assertEquals("calm_shield",h.replies.last().action)
        assertEquals(listOf(PixelRect(0,0,1080,2400)),h.replies.last().rects)
    }
    @Test fun homeInFlightPreventsSecondTransactionAndGuardianGrantMustMatchEpisode() {
        val h = Harness()
        h.actions.homeVerified = null
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,longChain)))
        h.decide()
        assertEquals("busy",h.replies.last().error)
        h.controller.guardianGrant(ProtocolFixtures.SCREEN)
        assertEquals(0,h.actions.clearCount)
        h.actions.pendingHome!!(false)
        h.controller.guardianGrant(ProtocolFixtures.SCREEN)
        assertEquals(0,h.actions.clearCount)
        h.controller.guardianGrant(ProtocolFixtures.EVENT)
        assertEquals(1,h.actions.clearCount)
        assertNull(h.controller.active)
    }
    @Test fun verifiedLauncherDuringHomeAndJournalWorkIsReleasedOnlyAfterFinalAck() {
        val h = Harness()
        h.actions.homeVerified = null
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,longChain)))
        h.screen = h.screen.copy(token=ProtocolFixtures.CONTINUITY,epoch=2,
            packageName="com.safe.launcher",windowId=9)
        h.controller.verifiedNavigation(h.screen,true)
        assertEquals(0,h.actions.clearCount)
        h.actions.pendingHome!!(true)
        assertEquals("home",h.replies.last().action)
        assertEquals(3,h.replies.last().stage)
        assertEquals(1,h.actions.clearCount)
        assertNull(h.controller.active)
    }
    @Test fun oneUseGuardianGrantDuringHomeIsNotSilentlyLost() {
        val h = Harness()
        h.actions.homeVerified = null
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,longChain)))
        h.controller.guardianGrant(ProtocolFixtures.EVENT)
        assertEquals(0,h.actions.clearCount)
        h.actions.pendingHome!!(false)
        assertEquals(1,h.actions.clearCount)
        assertNull(h.controller.active)
    }
    @Test fun confirmedGrantNeverReportsSuccessWhileBusyOrForOtherEvent() {
        val h = Harness()
        h.actions.homeVerified = null
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(3,.96,0.0,0.0,longChain)))
        assertFalse(h.controller.confirmedGuardianGrant(ProtocolFixtures.EVENT))
        assertEquals(0,h.actions.clearCount)
        h.actions.pendingHome!!(false)
        assertFalse(h.controller.confirmedGuardianGrant(ProtocolFixtures.SCREEN))
        assertTrue(h.controller.confirmedGuardianGrant(ProtocolFixtures.EVENT))
        assertFalse(h.controller.confirmedGuardianGrant(ProtocolFixtures.EVENT))
    }
    @Test fun failedPersistenceAcknowledgesActualOverlayButReportsStorageFailure() {
        val h = Harness()
        h.journal.finishBroken = true
        h.decide()
        assertEquals("executed",h.replies.single().status)
        assertEquals("storage_failed",h.replies.single().error)
        assertEquals(1,h.replies.single().stage)
        assertEquals(1,h.controller.active!!.stage)
    }
    @Test fun journalFailureUnpairedClockOrWrongSessionNeverApply() {
        for (variant in 0..7) {
            val h = Harness()
            when (variant) {
                0 -> h.journalAvailable=false
                1 -> h.journal.broken=true
                2 -> h.state=h.state.copy(pairing="unpaired")
                3 -> h.controller.bind(ProtocolFixtures.SESSION,ProtocolFixtures.STREAM,false)
                4 -> h.controller.disconnect()
                5 -> h.controller.bind(ProtocolFixtures.SESSION,ProtocolFixtures.SCREEN,true)
                6 -> h.state=h.state.copy(encryption="locked")
                7 -> h.state=h.state.copy(encryption="failed")
            }
            h.decide()
            assertTrue(h.actions.calls.isEmpty())
            assertEquals(0,h.replies.single().stage)
        }
    }
    @Test fun delayedJournalRevalidatesTtlScreenAndSessionImmediatelyBeforeRendering() {
        for (variant in 0..4) {
            val h = Harness()
            h.immediateWork=false
            h.decide()
            when(variant) {
                0 -> { h.now+=1_000_000; h.screen=h.screen.copy(sampledUs=h.now) }
                1 -> h.screen=h.screen.copy(token=ProtocolFixtures.EVENT)
                2 -> h.controller.disconnect()
                3 -> h.state=h.state.copy(pairing="revoked")
                4 -> h.state=h.state.copy(encryption="failed")
            }
            while(h.jobs.isNotEmpty()) h.jobs.removeFirst()()
            assertTrue(h.actions.calls.isEmpty())
            assertEquals("rejected",h.replies.single().status)
        }
    }
    @Test fun successfulDistinctEpisodesOnlyCountWithinStrictSixtySecondWindow() {
        val h = Harness()
        h.journal.episodes["expired"]=h.now-60_000_000
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(stage=2,reason="repetition")))
        assertEquals("invalid_evidence",h.replies.last().error)
        val fresh = Harness()
        fresh.journal.episodes["previous"]=fresh.now-59_999_999
        fresh.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(stage=2,reason="repetition")))
        assertEquals(2,fresh.replies.single().stage)
        val failed = Harness()
        failed.actions.overlayFailed=true
        failed.decide()
        assertTrue(failed.journal.episodes.isEmpty())
    }
    @Test fun coverSubcommandsAccumulateUnionAndAppliedActionsDoNotDowngrade() {
        val h = Harness()
        h.decide()
        h.now += 500_000
        h.screen = h.screen.copy(sampledUs=h.now)
        val pts = listOf(10_500_000L,10_700_000,10_900_000)
        val next = ProtocolFixtures.parse(ProtocolFixtures.decision(revision=2,pts=pts,
            regions=listOf(ProtocolFixtures.region(.65,.02,.1,pts,2,PixelRect(200,100,100,300)))))
        val a = h.actions.attachedRects.single()
        val aApplied = h.controller.active!!.masks.single().appliedUs
        h.decide(next)
        val complete = listOf(a,PixelRect(600,300,300,900))
        assertEquals(complete,h.actions.coverRequests.last())
        assertEquals(complete,h.actions.attachedRects)
        assertEquals(complete,h.controller.active!!.masks.map { it.rect })
        assertEquals(complete,h.replies.last().rects)
        assertEquals("executed",h.replies.last().status)
        assertEquals(2L,h.controller.active!!.revision)
        assertEquals(aApplied,h.controller.active!!.masks.first().appliedUs)
        assertEquals(h.now,h.controller.active!!.masks.last().appliedUs)
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(2,.85,.0,.0,revision=3)))
        assertEquals(2,h.controller.active!!.stage)
        assertEquals(listOf(PixelRect(0,0,h.screen.width,h.screen.height)),h.actions.attachedRects)
        assertEquals(h.actions.attachedRects,h.replies.last().rects)
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(revision=4)))
        assertEquals("event_conflict",h.replies.last().error)
        assertEquals(2,h.controller.active!!.stage)
    }
    @Test fun failedSecondRegionAttachmentPreservesFirstRegionAndActualAck() {
        val h = Harness()
        h.decide()
        val prior = h.controller.active!!
        val a = h.actions.attachedRects.single()
        h.actions.overlayFailed = true
        h.now += 500_000
        h.screen = h.screen.copy(sampledUs=h.now)
        val pts = listOf(10_500_000L,10_700_000,10_900_000)
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(revision=2,pts=pts,
            regions=listOf(ProtocolFixtures.region(.65,.02,.1,pts,2,PixelRect(200,100,100,300))))))
        assertEquals(listOf(a,PixelRect(600,300,300,900)),h.actions.coverRequests.last())
        assertEquals(listOf(a),h.actions.attachedRects)
        assertEquals(prior,h.controller.active)
        assertEquals("failed",h.replies.last().status)
        assertEquals("action_failed",h.replies.last().error)
        assertEquals(listOf(a),h.replies.last().rects)
        assertEquals(1,h.replies.last().stage)
        assertEquals(0,h.actions.clearCount)
    }
    @Test fun ninthDistinctCoverIsRejectedBeforeRenderingWithoutExposingTheFirstEight() {
        val h = Harness()
        val pts = listOf(10_000_000L,10_200_000,10_400_000)
        val firstEight = (1..8).map { i ->
            ProtocolFixtures.region(.65,.02,.1,pts,i.toLong(),PixelRect(i*20,100,10,10))
        }
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(regions=firstEight)))
        assertEquals("executed",h.replies.last().status)
        val installed = h.actions.attachedRects
        assertEquals(8,installed.size)
        h.now += 500_000
        h.screen = h.screen.copy(sampledUs=h.now)
        val addedPts = listOf(10_500_000L,10_700_000,10_900_000)
        h.decide(ProtocolFixtures.parse(ProtocolFixtures.decision(revision=2,pts=addedPts,
            regions=listOf(ProtocolFixtures.region(.65,.02,.1,addedPts,9,PixelRect(200,100,10,10))))))
        assertEquals("rejected",h.replies.last().status)
        assertEquals("bounds",h.replies.last().error)
        assertEquals(1,h.actions.coverRequests.size)
        assertEquals(installed,h.actions.attachedRects)
        assertEquals(installed,h.controller.active!!.masks.map { it.rect })
        assertEquals(installed,h.replies.last().rects)
        assertEquals(1L,h.controller.active!!.revision)
        assertEquals(installed,coverRectUnion(installed,installed))
    }
    @Test fun freshTokensAloneCannotReleaseTheProtectedUnderlyingScreen() {
        val h = Harness()
        h.decide()
        h.controller.verifiedNavigation(h.screen,false)
        assertEquals(0,h.actions.clearCount)
        h.controller.verifiedNavigation(h.screen.copy(token=ProtocolFixtures.CONTINUITY,epoch=2,
            status="invalid"),false)
        assertEquals(0,h.actions.clearCount)
        h.controller.verifiedNavigation(h.screen.copy(token=ProtocolFixtures.CONTINUITY,epoch=2),false)
        assertEquals(0,h.actions.clearCount)
        h.controller.verifiedNavigation(h.screen.copy(token=ProtocolFixtures.CONTINUITY,epoch=2,
            packageName="com.safe.other",windowId=9),false)
        assertEquals(1,h.actions.clearCount)
    }

    private class ScreenHarness {
        val h = Harness()
        val identity = ScreenIdentity(h.screen.width,h.screen.height,h.screen.rotation,
            h.screen.windowId,h.screen.packageName)
        lateinit var state: ForegroundScreenState
        init {
            state = ForegroundScreenState("dev.k230.mentor_app", { h.now }, {
                h.screen = state.snapshot!!
                h.controller.invalidateGeometry()
            }, { next, launcher ->
                h.screen = next
                h.controller.verifiedNavigation(next,launcher)
            })
            h.now = 9_000_000
            refresh()
            h.now += 100_000
            refresh()
            h.now = 10_450_000
            refresh()
        }
        fun refresh(current: ScreenIdentity = identity, launcher: Boolean = false) {
            state.refresh(current,false,launcher)
            h.screen = state.snapshot ?: h.screen
        }
        fun decide(stage: Int = 1) {
            val json = if (stage == 3) ProtocolFixtures.decision(3,.96,0.0,0.0,
                listOf(9_400_000L,9_650_000,9_900_000,10_150_000,10_400_000))
                else ProtocolFixtures.decision()
            h.decide(ProtocolFixtures.parse(json + ("screen_token" to h.screen.token)))
        }
    }

    @Test fun genericSameAppContentMutationKeepsShieldDespiteStableFreshToken() {
        val s = ScreenHarness()
        s.decide()
        val old = s.h.screen
        s.state.contentEvent(old.packageName,old.windowId,true)
        assertEquals("invalid",s.state.snapshot!!.status)
        assertEquals(2,s.h.controller.active!!.stage)
        assertEquals(listOf("cover","shield"),s.h.actions.calls)
        assertEquals(0,s.h.actions.clearCount)
        s.refresh()
        s.h.now += 99_999
        s.refresh()
        assertEquals("invalid",s.state.snapshot!!.status)
        assertEquals(0,s.h.actions.clearCount)
        s.h.now++
        s.refresh()
        assertEquals("verified",s.h.screen.status)
        assertNotEquals(old.token,s.h.screen.token)
        assertTrue(s.h.screen.epoch > old.epoch)
        assertEquals(old.packageName,s.h.screen.packageName)
        assertEquals(old.windowId,s.h.screen.windowId)
        assertEquals(0,s.h.actions.clearCount)
        assertEquals(2,s.h.controller.active!!.stage)
        assertEquals(listOf(PixelRect(0,0,old.width,old.height)),s.h.actions.attachedRects)
        s.h.controller.verifiedNavigation(s.h.screen,false)
        assertEquals(0,s.h.actions.clearCount)
        assertEquals(2,s.h.controller.active!!.stage)
    }

    @Test fun ownOverlayUnattributedAndWrongWindowEventsNeverInvalidateOrRelease() {
        val s = ScreenHarness()
        s.decide()
        val old = s.h.screen
        s.state.contentEvent("dev.k230.mentor_app",old.windowId,true)
        s.state.contentEvent(old.packageName,old.windowId,false)
        s.state.contentEvent(old.packageName,old.windowId+1,true)
        s.state.contentEvent(null,old.windowId,true)
        s.h.now += 200_000
        s.refresh()
        assertEquals(old.token,s.h.screen.token)
        assertEquals(old.epoch,s.h.screen.epoch)
        assertEquals(listOf("cover"),s.h.actions.calls)
        assertEquals(1,s.h.controller.active!!.stage)
        assertEquals(0,s.h.actions.clearCount)
    }

    @Test fun homeStageReleasesOnlyAfterVerifiedStableLauncherNotSameAppContent() {
        val s = ScreenHarness()
        s.decide(3)
        assertEquals(3,s.h.controller.active!!.stage)
        s.state.contentEvent(s.identity.packageName,s.identity.windowId,true)
        s.refresh()
        s.h.now += 100_000
        s.refresh()
        assertEquals(0,s.h.actions.clearCount)
        val launcher = s.identity.copy(packageName="com.safe.launcher",windowId=9)
        s.refresh(launcher,true)
        s.h.now += 99_999
        s.refresh(launcher,true)
        assertEquals(0,s.h.actions.clearCount)
        s.h.now++
        s.refresh(launcher,true)
        assertEquals(1,s.h.actions.clearCount)
        assertNull(s.h.controller.active)
    }

    @Test fun geometryOrServiceInvalidationDoesNotTreatTheSameContentAsNavigation() {
        for (geometry in listOf(false,true)) {
            val s = ScreenHarness()
            s.decide()
            s.state.invalidate()
            val current = if (geometry) s.identity.copy(rotation=90) else s.identity
            s.refresh(current)
            s.h.now += 100_000
            s.refresh(current)
            assertEquals("verified",s.h.screen.status)
            assertEquals(2,s.h.controller.active!!.stage)
            assertEquals(0,s.h.actions.clearCount)
        }
    }
}
