package dev.k230.mentor_app.protection

import java.util.UUID

internal data class ScreenIdentity(val width: Int, val height: Int, val rotation: Int,
    val windowId: Int, val packageName: String)

/** Same-window media identity proof is unavailable: generic mutations rotate scope, not release authority. */
internal class ForegroundScreenState(
    private val ownPackage: String,
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

    fun contentEvent(packageName: String?, windowId: Int, applicationWindow: Boolean) {
        val underlying = identity ?: return
        if (packageName == ownPackage || !applicationWindow || packageName != underlying.packageName ||
            windowId != underlying.windowId) return
        invalidate()
    }

    fun invalidate() {
        candidate = null
        if (!pending) {
            epoch++
            val time = now()
            snapshot = snapshot?.copy(token = UUID.randomUUID().toString(), epoch = epoch, status = "invalid",
                windowId = -1, packageName = "", sampledUs = time, validFromUs = time)
            pending = true
            onInvalidate()
        }
    }

    fun refresh(current: ScreenIdentity?, locked: Boolean, launcher: Boolean) {
        if (current == null || locked) {
            invalidate()
            snapshot = snapshot?.copy(status = if (locked) "locked" else "unsupported")
            return
        }
        if (current != identity && !pending) invalidate()
        if (pending) {
            if (candidate != current) { candidate = current; candidateSince = now(); return }
            if (now() - candidateSince < 100_000) return
            val previous = identity
            val changedApp = previous != null && (previous.packageName != current.packageName ||
                previous.windowId != current.windowId)
            identity = current
            val time = now()
            snapshot = ScreenSnapshot(UUID.randomUUID().toString(), epoch, current.width, current.height,
                current.rotation, current.windowId, current.packageName, time, time)
            pending = false
            // Stable package/window plus token rotation is not verified changed-media proof.
            if (changedApp) onVerified(snapshot!!, launcher)
        } else snapshot = snapshot?.copy(sampledUs = now())
    }
}
