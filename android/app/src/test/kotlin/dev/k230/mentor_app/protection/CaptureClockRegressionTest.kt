package dev.k230.mentor_app.protection

import org.junit.Assert.*
import org.junit.Test

class CaptureClockRegressionTest {
    @Test fun bindingNegotiatesSequentialSamplesRejectsStreamChangesAndExpires() {
        val gate = CaptureClockBinding()
        val cmd = BindCommand(ProtocolFixtures.SESSION, 1, ProtocolFixtures.STREAM)
        assertEquals("rejected", gate.bind(cmd.copy(capturePtsUs = 10_000_000), 10_100_000).status)
        assertEquals("pending", gate.bind(cmd, 10_000_000).status)
        assertEquals("pending", gate.bind(cmd.copy(capturePtsUs = 10_000_000), 10_100_000).status)
        assertEquals("pending", gate.bind(cmd.copy(capturePtsUs = 10_500_000), 10_600_000).status)
        assertEquals("accepted", gate.bind(cmd.copy(capturePtsUs = 11_000_000), 11_100_000).status)
        gate.reset()
        assertEquals("rejected", gate.bind(cmd.copy(capturePtsUs = 11_500_000), 11_600_000).status)
        gate.bind(cmd, 10_000_000)
        assertEquals("rejected", gate.bind(cmd.copy(capturePtsUs = 13_000_001), 13_000_001).status)
        gate.bind(cmd, 10_000_000)
        assertEquals("rejected", gate.bind(cmd.copy(streamId = ProtocolFixtures.EVENT, capturePtsUs = 10_100_000), 10_100_000).status)
    }
    @Test fun eightSampleLimitCannotBeBypassedByShortWindow() {
        val gate = CaptureClockBinding(); val cmd = BindCommand(ProtocolFixtures.SESSION, 1, ProtocolFixtures.STREAM)
        gate.bind(cmd, 10_000_000)
        repeat(8) { assertEquals("pending", gate.bind(cmd.copy(capturePtsUs = 10_000_000L + it * 50_000), 10_100_000L + it * 50_000).status) }
        assertEquals("rejected", gate.bind(cmd.copy(capturePtsUs = 10_400_000), 10_500_000).status)
    }
    @Test fun activeStateUsesOriginalOverlayTargetNotRotatedCurrentToken() {
        val inactive = protectionWire(null)
        assertEquals(0, inactive["stage"]); assertNull(inactive["event_id"])
        assertNull(inactive["action_revision"]); assertNull(inactive["target_screen_token"])
        val active = ActiveProtection(ProtocolFixtures.EVENT, 7, 2, 20_000_000, ProtocolFixtures.screen,
            listOf(AppliedMask(PixelRect(0, 0, 1080, 2400), 20_000_000)))
        val map = protectionWire(active)
        assertEquals("7", map["action_revision"])
        assertEquals(active.target.token, map["target_screen_token"])
        assertNotEquals(ProtocolFixtures.CONTINUITY, map["target_screen_token"])
    }
    @Test fun unmodifiedCorrectTimebaseRequiresIndependentOneSecondRegression() {
        val gate = CaptureClockRegression()
        assertFalse(gate.sample(10_000_000, 10_100_000))
        assertFalse(gate.sample(10_500_000, 10_600_000))
        assertTrue(gate.sample(11_000_000, 11_100_000))
        gate.reset(); assertFalse(gate.verified)
    }
    @Test fun wallBootArbitraryEpochConstantBackwardsFutureAndLagFailClosed() {
        for (bad in listOf(1_791_000_000_000_000L, 99_000_000, 42, 10_150_001, 9_349_999)) {
            val gate = CaptureClockRegression()
            assertThrows(ProtocolFailure::class.java) { gate.sample(bad, 10_100_000) }
            assertFalse(gate.verified)
        }
        for (bad in listOf(10_000_000L, 9_999_999)) {
            val gate = CaptureClockRegression(); gate.sample(10_000_000, 10_100_000)
            assertThrows(ProtocolFailure::class.java) { gate.sample(bad, 10_600_000) }
            assertFalse(gate.verified)
        }
    }
    @Test fun historyBatchReceiptRebaseTooFewTooShortAndStallsCannotApprove() {
        val gate = CaptureClockRegression()
        assertFalse(gate.sample(10_000_000, 10_100_000))
        assertFalse(gate.sample(10_200_000, 10_300_000))
        assertFalse(gate.sample(10_400_000, 10_500_000))
        assertFalse(gate.verified)
        assertThrows(ProtocolFailure::class.java) { gate.sample(13_100_000, 13_200_000) }
        assertFalse(gate.verified)
        gate.sample(20_000_000, 20_100_000)
        assertThrows(ProtocolFailure::class.java) { gate.sample(20_500_000, 20_100_000) }
        assertFalse(gate.verified)
    }
}
