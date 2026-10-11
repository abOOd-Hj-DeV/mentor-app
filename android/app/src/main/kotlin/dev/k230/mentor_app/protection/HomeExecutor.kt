package dev.k230.mentor_app.protection

import android.accessibilityservice.AccessibilityService
import android.os.Handler

internal class HomeExecutor(
    private val service: AccessibilityService, private val handler: Handler,
    private val tracker: ForegroundScreenTracker, private val now: () -> Long,
) {
    private var transaction = 0L
    private var inFlight = false
    fun request(done: (Boolean) -> Unit): Boolean {
        if (inFlight || tracker.locked) return false
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) return false
        inFlight = true
        val id = ++transaction
        val deadline = now() + 1_500_000
        var launcherSince: Long? = null
        val poll = object : Runnable {
            override fun run() {
                if (!inFlight || id != transaction) return
                tracker.refresh()
                if (!tracker.locked && tracker.isLauncher()) {
                    if (launcherSince == null) launcherSince = now()
                    if (now() - launcherSince!! >= 100_000) { inFlight = false; done(true); return }
                } else launcherSince = null
                if (now() >= deadline) { inFlight = false; done(false); return }
                handler.postDelayed(this, 50)
            }
        }
        handler.postDelayed(poll, 50)
        return true
    }
    fun close() { transaction++; inFlight = false }
}
