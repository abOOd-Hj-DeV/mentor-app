package dev.k230.mentor_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutDiagnosticsTest {
    @Test fun stalledChildReadReportsMainStackWithoutQueuingMoreHeartbeats() {
        var now = 10_000_000L
        val lines = mutableListOf<String>()
        val heartbeats = mutableListOf<() -> Unit>()
        val diagnostics = LayoutDiagnostics(
            log = { lines.add(it) }, postHeartbeat = { heartbeats.add(it) },
            mainStack = { "AccessibilityInteractionClient.waitForResultTimedLocked" }, clockUs = { now },
        )
        diagnostics.activate(7)
        diagnostics.beginCollection()
        diagnostics.collectorStage("getChild:12:3")
        now += 250_000
        diagnostics.report()
        now += 250_000
        diagnostics.report()
        assertEquals(1, heartbeats.size)
        assertTrue(lines.any { it.contains("main_stalled collect=getChild:12:3 stage_us=500000") })
        assertTrue(lines.any { it.contains("stack=AccessibilityInteractionClient.waitForResultTimedLocked") })
        now += 250_000
        diagnostics.report()
        assertEquals(1, lines.count { it.contains("main_stalled") })
        heartbeats.removeAt(0).invoke()
        diagnostics.endCollection()
        now += 250_000
        diagnostics.report()
        assertEquals(1, heartbeats.size)
        assertTrue(lines.any { it.contains("health collect=idle") && it.contains("main_idle_us=250000") })
        diagnostics.close()
    }

    @Test fun blockedWriteIsVisibleWhileCollectorAndMainThreadKeepRunning() {
        var now = 10_000_000L
        val lines = mutableListOf<String>()
        val diagnostics = LayoutDiagnostics(
            log = { lines.add(it) }, postHeartbeat = { it() }, mainStack = { "unused" }, clockUs = { now },
        )
        diagnostics.activate(7)
        diagnostics.socketStage("write")
        repeat(4) { index ->
            now += 250_000
            diagnostics.beginCollection()
            diagnostics.published(LayoutSnapshot(
                0, 7, index + 1L, now, now, now, 1080, 2400, 0, 0, 42, "dev.example", emptyList(),
            ))
            diagnostics.endCollection()
            diagnostics.report()
        }
        assertTrue(lines.any {
            it.contains("socket=write socket_stage_us=1000000") && it.contains("publications=4 sends=0")
        })
        assertFalse(lines.any { it.contains("main_stalled") })
        diagnostics.sent(4, 80)
        diagnostics.socketStage("idle")
        now += 1_000_000
        diagnostics.report()
        assertTrue(lines.any { it.contains("socket=idle") && it.contains("publications=4 sends=1") })
        diagnostics.close()
    }

    @Test fun inactiveDiagnosticsDoNotLogOrScheduleWork() {
        val diagnostics = LayoutDiagnostics(
            log = { throw AssertionError("inactive logger called") },
            postHeartbeat = { throw AssertionError("inactive heartbeat queued") },
            mainStack = { throw AssertionError("inactive stack captured") },
        )
        diagnostics.beginCollection()
        diagnostics.collectorStage("root")
        diagnostics.endCollection()
        diagnostics.socketStage("write")
        diagnostics.sent(1, 80)
        diagnostics.report()
        diagnostics.close()
    }
}
