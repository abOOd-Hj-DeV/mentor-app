package dev.k230.mentor_app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.security.SecureRandom
import java.util.ArrayDeque
import dev.k230.mentor_app.protection.ProtectionRuntime

class LayoutService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val diagnostics = LayoutDiagnostics(
        log = { Log.i("MentorLayout", it) },
        postHeartbeat = { beat -> handler.post { beat() } },
        mainStack = {
            handler.looper.thread.stackTrace.take(14).joinToString(" <- ") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
        },
    )
    private val socket = LayoutSocket(diagnostics)
    internal var protection: ProtectionRuntime? = null
        private set
    private val session = SecureRandom().nextLong().ushr(1).coerceAtLeast(1)
    private var sequence = 0L
    private var validFromUs = 0L
    @Volatile private var latest: LayoutSnapshot? = null
    private val refresh = object : Runnable {
        override fun run() {
            collect()
            handler.postDelayed(this, 200)
        }
    }
    private val collectAfterEvent = Runnable { collect() }

    override fun onServiceConnected() {
        dev.k230.mentor_app.protection.NativeSecurityProvider.install(this).initialize()
        instance = this
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) diagnostics.start(session)
        validFromUs = nowUs()
        socket.start {
            handler.post {
                validFromUs = nowUs()
                invalidate()
                collect()
            }
        }
        protection = ProtectionRuntime(this).also { it.start() }
        handler.post(refresh)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        protection?.event(event)
        validFromUs = maxOf(validFromUs, minOf(nowUs(), event.eventTime * 1000))
        invalidate()
        handler.removeCallbacks(collectAfterEvent)
        handler.postDelayed(collectAfterEvent, 35)
    }

    override fun onInterrupt() {
        protection?.interrupt()
        validFromUs = nowUs()
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun screen(): Triple<Int, Int, Int>? {
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val size = Point()
        display.getRealSize(size)
        if (size.x <= 0 || size.y <= 0) return null
        return Triple(size.x, size.y, display.rotation)
    }

    private fun invalidate() {
        val dimensions = screen() ?: return
        val time = nowUs()
        val snapshot = LayoutSnapshot(
            LayoutWire.INVALIDATE, session, ++sequence, time, time,
            minOf(validFromUs, time), dimensions.first, dimensions.second, dimensions.third,
            0, -1, "", emptyList(),
        )
        latest = snapshot
        socket.publish(snapshot)
    }

    private fun collect() {
        diagnostics.beginCollection()
        try {
            collectSnapshot()
        } finally {
            diagnostics.endCollection()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectSnapshot() {
        val dimensions = screen() ?: return
        val start = nowUs()
        diagnostics.collectorStage("root")
        val root = rootInActiveWindow
        val nodes = mutableListOf<LayoutNode>()
        var flags = 0
        var packageName = ""
        var windowId = -1
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        if (root == null) {
            validFromUs = start
            flags = LayoutWire.PARTIAL or LayoutWire.INVALIDATE
        } else {
            packageName = root.packageName?.toString().orEmpty()
            windowId = root.windowId
            queue.add(root)
        }
        diagnostics.collectorStage("windows")
        val interactiveWindows = windows
        if (interactiveWindows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } > 1) {
            flags = flags or LayoutWire.PARTIAL
        }
        val wrongDisplay = Build.VERSION.SDK_INT >= 30 &&
            interactiveWindows.any { it.id == windowId && it.displayId != Display.DEFAULT_DISPLAY }
        if (Build.VERSION.SDK_INT < 33) interactiveWindows.forEach { it.recycle() }
        var visited = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            visited++
            diagnostics.collectorStage("node:$visited")
            val stop = nodes.size >= LayoutWire.MAX_NODES || visited >= 1024 || nowUs() - start > 50_000
            if (stop) flags = flags or LayoutWire.PARTIAL
            if (!stop && node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val className = node.className?.toString().orEmpty()
                val kind = when {
                    className.endsWith("ImageView") -> 1
                    className.endsWith("VideoView") || className.endsWith("SurfaceView") ||
                        className.endsWith("TextureView") -> 2
                    className.endsWith("WebView") -> 3
                    else -> 0
                }
                if (kind == 2 || kind == 3) flags = flags or LayoutWire.PARTIAL
                if (bounds.intersect(0, 0, dimensions.first, dimensions.second)) {
                    nodes.add(LayoutNode(bounds.left, bounds.top, bounds.right, bounds.bottom, kind, visited))
                }
                val available = (1024 - visited - queue.size).coerceAtLeast(0)
                if (node.childCount > available) flags = flags or LayoutWire.PARTIAL
                for (index in 0 until minOf(node.childCount, available)) {
                    diagnostics.collectorStage("getChild:$visited:$index")
                    val child = node.getChild(index)
                    if (child == null) flags = flags or LayoutWire.PARTIAL
                    else queue.add(child)
                }
            }
            if (Build.VERSION.SDK_INT < 33) node.recycle()
            if (stop) {
                while (queue.isNotEmpty()) {
                    val remaining = queue.removeFirst()
                    if (Build.VERSION.SDK_INT < 33) remaining.recycle()
                }
                break
            }
        }
        diagnostics.collectorStage("screen_end")
        val endDimensions = screen()
        diagnostics.collectorStage("validate_display_magnification")
        if (wrongDisplay || endDimensions != dimensions || magnificationController.scale != 1f) {
            validFromUs = start
            flags = LayoutWire.PARTIAL or LayoutWire.INVALIDATE
            nodes.clear()
        }
        val end = nowUs()
        val snapshot = LayoutSnapshot(
            flags, session, ++sequence, start, end, minOf(validFromUs, start),
            dimensions.first, dimensions.second, dimensions.third, 0, windowId, packageName, nodes,
        )
        latest = snapshot
        diagnostics.collectorStage("publish")
        socket.publish(snapshot)
    }

    fun status() = mapOf(
        "running" to true, "connected" to socket.connected, "error" to socket.error,
        "sequence" to latest?.sequence, "nodes" to latest?.nodes?.size,
        "flags" to latest?.flags, "package" to latest?.packageName,
        "captureUs" to latest?.let { it.completedAtUs - it.sampledAtUs },
    )

    override fun onDestroy() {
        diagnostics.event("service_destroy")
        handler.removeCallbacksAndMessages(null)
        socket.close()
        protection?.close()
        protection = null
        diagnostics.close()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: LayoutService? = null
            private set
        private fun nowUs() = System.nanoTime() / 1000
    }
}
