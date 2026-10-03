package dev.k230.mentor_app.protection

import java.util.UUID

internal data class ScreenIdentity(val width: Int, val height: Int, val rotation: Int,
    val windowId: Int, val packageName: String)

/** A fresh token represents a stable, attributed content transition, never classifier Safe. */
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
    private var contentTransition = false

    fun contentEvent(packageName: String?, windowId: Int, applicationWindow: Boolean) {
        val underlying = identity ?: return
        if (packageName == ownPackage || !applicationWindow || packageName != underlying.packageName ||
            windowId != underlying.windowId) return
        invalidate(contentChanged = true)
    }

    fun invalidate(contentChanged: Boolean = false) {
        contentTransition = contentChanged
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
            val changedContent = contentTransition && previous == current
            identity = current
            val time = now()
            snapshot = ScreenSnapshot(UUID.randomUUID().toString(), epoch, current.width, current.height,
                current.rotation, current.windowId, current.packageName, time, time)
            pending = false
            contentTransition = false
            // Lock, geometry and service invalidations alone never authorize same-content release.
            if (changedApp || changedContent) onVerified(snapshot!!, launcher)
        } else snapshot = snapshot?.copy(sampledUs = now())
    }
}
