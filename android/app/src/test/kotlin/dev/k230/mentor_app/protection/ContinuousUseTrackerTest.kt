package dev.k230.mentor_app.protection

import org.junit.Assert.*
import org.junit.Test

class ContinuousUseTrackerTest {
    @Test fun showsBreakAfterTwentyContinuousMinutes() {
        val tracker = ContinuousUseTracker()
        assertNull(tracker.observe("com.example.video", 0))
        assertNull(tracker.observe("com.example.video", 1_199_999))
        assertEquals("com.example.video", tracker.observe("com.example.video", 1_200_000))
        assertNull(tracker.observe("com.example.video", 1_200_001))
        assertEquals("com.example.video", tracker.observe("com.example.video", 2_400_000))
    }

    @Test fun switchingApplicationsRestartsTheInterval() {
        val tracker = ContinuousUseTracker()
        tracker.observe("com.example.video", 0)
        tracker.observe("com.example.browser", 900_000)
        assertNull(tracker.observe("com.example.browser", 1_200_000))
        assertEquals("com.example.browser", tracker.observe("com.example.browser", 2_100_000))
    }

    @Test fun launcherAndMentorDoNotCountAsContinuousConsumption() {
        val tracker = ContinuousUseTracker(10)
        tracker.observe("com.example.video", 0)
        tracker.observe("dev.k230.mentor_app", 9)
        assertNull(tracker.observe("com.example.video", 10))
        assertNull(tracker.observe("com.android.launcher3", 30))
    }

    @Test fun screenOffOrMissingForegroundRestartsTheInterval() {
        val tracker = ContinuousUseTracker()
        tracker.observe("com.example.video", 0)
        tracker.observe(null, 1_199_999)
        assertNull(tracker.observe("com.example.video", 1_200_000))
        assertEquals("com.example.video", tracker.observe("com.example.video", 2_400_000))
    }

    @Test fun backwardClockAndProtectedVendorLaunchersCannotTriggerBreaks() {
        val tracker = ContinuousUseTracker(10)
        tracker.observe("com.example.video", 100)
        assertNull(tracker.observe("com.example.video", 50))
        assertEquals("com.example.video", tracker.observe("com.example.video", 60))
        assertFalse(ContinuousUseTracker.eligible("com.bbk.launcher2"))
        assertFalse(ContinuousUseTracker.eligible("com.google.android.apps.nexuslauncher"))
    }
}
