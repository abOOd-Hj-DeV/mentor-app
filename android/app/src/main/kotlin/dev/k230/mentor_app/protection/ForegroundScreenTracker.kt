package dev.k230.mentor_app.protection

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.UUID

internal data class ScreenIdentity(val width: Int, val height: Int, val rotation: Int,
    val windowId: Int, val packageName: String)

/** No text, screenshot or own-overlay event is used as underlying-content proof. */
internal class ForegroundScreenTracker(
    private val service: AccessibilityService,
    private val now: () -> Long,
    private val onInvalidate: () -> Unit,
    private val onVerified: (ScreenSnapshot, Boolean) -> Unit,
) {
    var snapshot: ScreenSnapshot? = null
        private set
    private var identity: ScreenIdentity? = null
    private var candidate: ScreenIdentity? = null
    private var candidateSince = 0L
    private var epoch = 1L
    private var pending = true
    private val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val locked get() = keyguard.isKeyguardLocked
    private val launchers: Set<String>
        get() = service.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()

    fun event(event: AccessibilityEvent) {
        if (event.packageName?.toString() == service.packageName) return
        val windows = service.windows
        val window = windows.firstOrNull { it.id == event.windowId }
        val applicationEvent = window?.type == AccessibilityWindowInfo.TYPE_APPLICATION
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 33) windows.forEach { it.recycle() }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refresh()
        } else if (applicationEvent && event.packageName?.toString() == identity?.packageName &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            // Content changes invalidate evidence, but NEVER prove safe content/release.
            invalidate()
        }
    }

    fun invalidate() {
        if (!pending) {
            epoch++
            val s = snapshot
            snapshot = s?.copy(token = UUID.randomUUID().toString(), epoch = epoch, status = "invalid",
                windowId = -1, packageName = "", sampledUs = now(), validFromUs = now())
            pending = true
            candidate = null
            onInvalidate()
        }
    }

    fun refresh() {
        val current = if (!locked) readIdentity() else null
        if (current == null) {
            invalidate()
            snapshot = snapshot?.copy(status = if (locked) "locked" else "unsupported")
            return
        }
        if (current != identity && !pending) invalidate()
        if (pending) {
            if (candidate != current) { candidate = current; candidateSince = now(); return }
            if (now() - candidateSince < 100_000) return
            val previous = identity
            identity = current
            snapshot = ScreenSnapshot(UUID.randomUUID().toString(), epoch, current.width, current.height,
                current.rotation, current.windowId, current.packageName, now(), now())
            pending = false
            if (previous != null && (previous.packageName != current.packageName ||
                    previous.windowId != current.windowId)) onVerified(snapshot!!, current.packageName in launchers)
        } else snapshot = snapshot?.copy(sampledUs = now())
    }

    fun isLauncher() = snapshot?.let { it.status == "verified" && it.packageName in launchers } == true

    @Suppress("DEPRECATION")
    private fun readIdentity(): ScreenIdentity? {
        val display = (service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val size = Point().also { display.getRealSize(it) }
        if (size.x !in 1..16384 || size.y !in 1..16384 || service.magnificationController.scale != 1f) return null
        val windows = service.windows
        try {
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (apps.size != 1) return null
            val w = apps.single()
            if (!w.isActive || Build.VERSION.SDK_INT >= 30 && w.displayId != 0) return null
            val root = w.root ?: return null
            try {
                val pkg = root.packageName?.toString().orEmpty()
                if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(pkg)) return null
                return ScreenIdentity(size.x, size.y, display.rotation * 90, w.id, pkg)
            } finally { if (Build.VERSION.SDK_INT < 33) root.recycle() }
        } finally { if (Build.VERSION.SDK_INT < 33) windows.forEach { it.recycle() } }
    }
}
