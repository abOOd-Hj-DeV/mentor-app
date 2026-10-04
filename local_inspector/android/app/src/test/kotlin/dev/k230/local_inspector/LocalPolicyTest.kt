package dev.k230.local_inspector

import org.junit.Assert.*
import org.junit.Test

class LocalPolicyTest {
    private val region = Region(100, 100, 200, 200)
    private fun observe(policy: LocalPolicy, time: Long, porn: Float, hentai: Float = 0f, r: Region = region): Decision =
        policy.evaluate(listOf(Observation(r, Scores(0f, hentai, 1 - porn - hentai, porn, 0f))), time, "test.app", 1080, 2400)

    @Test fun coverNeedsThreeFreshObservationsOfTheSameRegion() {
        val policy = LocalPolicy(10)
        assertEquals(0, observe(policy, 0, .65f).stage)
        assertEquals(0, observe(policy, 334, .65f).stage)
        assertEquals(1, observe(policy, 668, .65f).stage)
    }
    @Test fun ageChangesThresholds() {
        val policy = LocalPolicy(13)
        for (i in 0..6) assertEquals(0, observe(policy, i * 334L, .65f).stage)
    }
    @Test fun hentaiAtSixtyPercentNeverTriggersAlone() {
        val policy = LocalPolicy(10)
        for (i in 0..8) assertEquals(0, observe(policy, i * 334L, 0f, .60f).stage)
    }
    @Test fun hentaiRequiresFiveObservationsAndNeverExecutesHome() {
        val policy = LocalPolicy(10)
        for (i in 0..3) assertEquals(0, observe(policy, i * 334L, 0f, .99f).stage)
        for (i in 4..9) assertEquals(2, observe(policy, i * 334L, 0f, .99f).stage)
    }
    @Test fun homeRequiresPornInEveryObservationAndFiveSamples() {
        val policy = LocalPolicy(10)
        for (i in 0..3) assertEquals(0, observe(policy, i * 334L, .96f).stage)
        assertEquals(3, observe(policy, 1336, .96f).stage)
        val weakPorn = LocalPolicy(10)
        for (i in 0..8) assertTrue(observe(weakPorn, i * 334L, .55f, .41f).stage <= 2)
    }
    @Test fun gapsAndMovingRegionsResetEvidence() {
        val policy = LocalPolicy(10)
        observe(policy, 0, .7f); observe(policy, 334, .7f)
        assertEquals(0, observe(policy, 668, .7f, r = Region(500, 100, 200, 200)).stage)
        assertEquals(0, observe(policy, 1500, .7f).stage)
        assertEquals(0, observe(policy, 1500, .7f).stage)
    }
    @Test fun repeatedConfirmedEpisodesEscalateCoverButNotToHome() {
        val policy = LocalPolicy(10)
        policy.applied("test.app", 0)
        observe(policy, 0, .65f); observe(policy, 334, .65f)
        assertEquals(2, observe(policy, 668, .65f).stage)
    }
    @Test fun explicitFormulaIsMonotonicAndSexyIsSeparate() {
        assertEquals(.8f, Scores(0f, .3f, .2f, .5f, 0f).explicit, .00001f)
        assertTrue(Scores(0f, .3f, .1f, .6f, 0f).explicit >= Scores(0f, .3f, .2f, .5f, 0f).explicit)
        val policy = LocalPolicy()
        for (i in 0..4) assertEquals(0, policy.evaluate(
            listOf(Observation(region, Scores(0f, 0f, .1f, 0f, .9f))), i * 334L, "test.app", 1080, 2400).stage)
    }
    @Test fun changingRouteDoesNotReuseHentaiEvidenceForHome() {
        val policy = LocalPolicy(10)
        for (i in 0..3) observe(policy, i * 334L, 0f, .96f)
        assertEquals(0, observe(policy, 1336, .96f).stage)
    }
    @Test fun oneFrameEachSecondStillConfirmsCoverAndHome() {
        val policy = LocalPolicy(10)
        policy.captureIntervalMs = 1000
        assertEquals(0, observe(policy, 0, .65f).stage)
        assertEquals(1, observe(policy, 1000, .65f).stage)
        val explicit = LocalPolicy(10)
        explicit.captureIntervalMs = 1000
        assertEquals(0, observe(explicit, 0, .96f).stage)
        assertEquals(0, observe(explicit, 1000, .96f).stage)
        assertEquals(3, observe(explicit, 2000, .96f).stage)
    }
    @Test fun slowCaptureStillRequiresHentaiEvidenceAndBlocksHome() {
        val policy = LocalPolicy(10)
        policy.captureIntervalMs = 3000
        for (i in 0..1) assertEquals(0, observe(policy, i * 3000L, 0f, .99f).stage)
        for (i in 2..5) assertEquals(2, observe(policy, i * 3000L, 0f, .99f).stage)
    }
    @Test fun capturePausesLongerThanTheConfiguredGapResetEvidence() {
        val policy = LocalPolicy(10)
        policy.captureIntervalMs = 1000
        observe(policy, 0, .65f)
        assertEquals(0, observe(policy, 4000, .65f).stage)
        assertEquals(1, observe(policy, 5000, .65f).stage)
    }
}
