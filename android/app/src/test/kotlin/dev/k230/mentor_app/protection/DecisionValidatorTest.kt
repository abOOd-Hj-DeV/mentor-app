package dev.k230.mentor_app.protection

import org.junit.Assert.*
import org.junit.Test

class DecisionValidatorTest {
    private fun fails(code: String, block: () -> Unit) {
        try { block(); fail("must reject") } catch (e: ProtocolFailure) { assertEquals(code, e.code) }
    }
    private val longChain = listOf(9_400_000L, 9_650_000, 9_900_000, 10_150_000, 10_400_000)
    @Test fun ageThresholdBoundariesAndPornExitPredicate() {
        for (age in listOf(10, 12, 13, 15)) {
            val p = PolicyProfile(age, 1)
            for (stage in 1..3) {
                val threshold = when (stage) { 1 -> p.cover; 2 -> p.shield; else -> p.exit }
                val pts = if (stage == 3) longChain else listOf(10_000_000L, 10_200_000, 10_400_000)
                ProtocolFixtures.valid(ProtocolFixtures.parse(ProtocolFixtures.decision(stage, threshold, 0.0, 0.0, pts, age)))
                fails("invalid_evidence") { ProtocolFixtures.valid(ProtocolFixtures.parse(
                    ProtocolFixtures.decision(stage, threshold - .000001, 0.0, 0.0, pts, age))) }
            }
        }
        ProtocolFixtures.valid(ProtocolFixtures.parse(ProtocolFixtures.decision(3, .60, .35, 0.0, longChain)))
        fails("invalid_evidence") { ProtocolFixtures.valid(ProtocolFixtures.parse(
            ProtocolFixtures.decision(3, .599999, .350001, 0.0, longChain))) }
    }
    @Test fun hentaiStrictPredicateLongChainAndPermanentStage3Cap() {
        for (stage in listOf(1, 2)) {
            fails("invalid_evidence") { ProtocolFixtures.valid(ProtocolFixtures.parse(
                ProtocolFixtures.decision(stage, .3, .60, 0.0, longChain))) }
            ProtocolFixtures.valid(ProtocolFixtures.parse(
                ProtocolFixtures.decision(stage, .3, .600001, 0.0, longChain)))
        }
        fails("hentai_stage3_forbidden") { ProtocolFixtures.valid(ProtocolFixtures.parse(
            ProtocolFixtures.decision(3, .05, .94, 0.0, longChain))) }
        fails("invalid_evidence") { ProtocolFixtures.valid(ProtocolFixtures.parse(
            ProtocolFixtures.decision(1, .05, .94, 0.0))) }
    }
    @Test fun countAndFullSpanBothRequiredAndHighCadenceNeedsFullChain() {
        fails("invalid_evidence") { ProtocolFixtures.valid(ProtocolFixtures.parse(ProtocolFixtures.decision(
            pts=listOf(10_000_000, 10_100_000, 10_200_000)))) }
        fails("invalid_evidence") { ProtocolFixtures.valid(ProtocolFixtures.parse(ProtocolFixtures.decision(
            3, .96, 0.0, 0.0, listOf(10_000_000, 10_100_000, 10_200_000, 10_300_000, 10_400_000)))) }
        val pts = (0..10).map { 9_400_000L + it * 100_000 }
        ProtocolFixtures.valid(ProtocolFixtures.parse(ProtocolFixtures.decision(3, .96, 0.0, 0.0, pts)))
    }
    @Test fun duplicatePtsGapMixedRoutesAndLowInterveningObservationNeverQualify() {
        for (pts in listOf(listOf(10_000_000L, 10_000_000, 10_400_000),
                listOf(10_200_000L, 10_000_000, 10_400_000),
                listOf(9_700_000L, 10_300_000, 10_400_000))) {
            fails("invalid_evidence") { ProtocolFixtures.valid(ProtocolFixtures.parse(ProtocolFixtures.decision(pts=pts))) }
        }
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision(3, .96, 0.0, 0.0, longChain))
        for (scores in listOf(Scores(.2, .0, .0), Scores(.05, .94, .0))) {
            val r = d.regions.single()
            val obs = r.observations.toMutableList().apply { this[2] = this[2].copy(scores=scores) }
            fails("invalid_evidence") { ProtocolFixtures.valid(d.copy(regions=listOf(r.copy(observations=obs)))) }
        }
    }
    @Test fun screenPolicyClockAndTtlCheckedAtExecutionWithExactEdges() {
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision())
        fun validate(now: Long, s: ScreenSnapshot = ProtocolFixtures.screen.copy(sampledUs=now),
            policy: PolicyProfile = d.policy, clock: Boolean = true, locked: Boolean = false) =
            DecisionValidator.validate(d, s, policy, now, clock, locked, emptyList(), false)
        validate(d.ptsUs + 750_000)
        fails("stale") { validate(d.ptsUs + 750_001) }
        validate(d.ptsUs - 50_000)
        fails("future_pts") { validate(d.ptsUs - 50_001) }
        fails("clock_unverified") { validate(d.ptsUs, clock=false) }
        fails("locked") { validate(d.ptsUs, locked=true) }
        fails("policy_mismatch") { validate(d.ptsUs, policy=PolicyProfile(13, 2)) }
        for (s in listOf(ProtocolFixtures.screen.copy(token=ProtocolFixtures.EVENT),
                ProtocolFixtures.screen.copy(windowId=8), ProtocolFixtures.screen.copy(epoch=2),
                ProtocolFixtures.screen.copy(packageName="com.other.viewer"), ProtocolFixtures.screen.copy(rotation=90),
                ProtocolFixtures.screen.copy(status="invalid"))) {
            fails("wrong_screen") { validate(d.ptsUs, s.copy(sampledUs=d.ptsUs)) }
        }
        fails("stale") { validate(d.ptsUs, ProtocolFixtures.screen.copy(sampledUs=d.ptsUs-500_001)) }
        fails("invalid_evidence") { validate(d.ptsUs, ProtocolFixtures.screen.copy(sampledUs=d.ptsUs, validFromUs=d.ptsUs)) }
    }
    @Test fun multipleSameFrameRegionsRetainIndependentEvidenceAndCoveredPixelsCannotEscalate() {
        val pts = listOf(10_000_000L, 10_200_000, 10_400_000)
        val regions = listOf(ProtocolFixtures.region(.65, .02, .1, pts),
            ProtocolFixtures.region(.66, .02, .1, pts, 2, PixelRect(200, 100, 100, 300)))
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision(regions=regions))
        assertEquals(2, ProtocolFixtures.valid(d).size)
        fails("invalid_evidence") { ProtocolFixtures.valid(d, masks=listOf(
            AppliedMask(PixelRect(60, 300, 480, 900), 10_300_000))) }
        assertEquals(2, ProtocolFixtures.valid(d, masks=listOf(
            AppliedMask(PixelRect(60, 300, 480, 900), 10_450_000))).size)
        val stage3 = ProtocolFixtures.parse(ProtocolFixtures.decision(3, .96, 0.0, 0.0, longChain,
            regions=listOf(ProtocolFixtures.region(.96, 0.0, 0.0, longChain, 2))))
        ProtocolFixtures.valid(stage3)
    }
    @Test fun repetitionNeverAuthorizesHomeAndRequiresIndependentAuthority() {
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision(stage=2, reason="repetition"))
        fails("invalid_evidence") { ProtocolFixtures.valid(d) }
        ProtocolFixtures.valid(d, repetition=true)
        val map = ProtocolFixtures.decision(stage=3, reason="repetition")
        fails("invalid_evidence") { ProtocolFixtures.parse(map) }
    }
    @Test fun rotationsOddSizesUniformScaleAndViewportMustBeExplicit() {
        assertEquals(600 to 20, DecisionValidator.rotateEdge(20, 200, 360, 800, 90))
        assertEquals(340 to 600, DecisionValidator.rotateEdge(20, 200, 360, 800, 180))
        assertEquals(200 to 340, DecisionValidator.rotateEdge(20, 200, 360, 800, 270))
        assertEquals(PixelRect(2, 2, 5, 5), DecisionValidator.scale(PixelRect(1,1,2,2), 3,3,7,7))
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision())
        fails("invalid_transform") { ProtocolFixtures.valid(d.copy(frameWidth=400)) }
        fails("invalid_transform") { ProtocolFixtures.valid(d.copy(viewport=PixelRect(0, 24,1080,2376))) }
        val landscape = d.copy(frameWidth=800, frameHeight=360, rotation=90,
            viewport=PixelRect(0,0,2400,1080), regions=d.regions.map { it.copy(crop=PixelRect(20,20,100,100)) })
        DecisionValidator.validate(landscape, ProtocolFixtures.screen.copy(width=2400,height=1080,rotation=90),
            d.policy, 10_450_000, true,false,emptyList(),false)
    }
}
