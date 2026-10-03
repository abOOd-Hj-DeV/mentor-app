package dev.k230.mentor_app.protection

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

internal class AccessibilityOverlayController(
    private val service: AccessibilityService, private val handler: Handler,
    private val mayAttach: (ScreenSnapshot) -> Boolean,
    private val younger: () -> Boolean, private val safeHome: () -> Unit, private val help: () -> Unit,
) {
    private data class Window(val view: View, val rect: PixelRect, val shield: Boolean)
    private val manager = service.getSystemService(WindowManager::class.java)
    private val installed = mutableListOf<Window>()
    private var transaction = 0L
    private var suspended = false

    fun cover(rects: List<PixelRect>, screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit) =
        attach(rects, screen, false, done)
    fun shield(screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit) =
        attach(listOf(PixelRect(0, 0, screen.width, screen.height)), screen, true, done)

    private fun attach(rects: List<PixelRect>, screen: ScreenSnapshot, shield: Boolean,
        done: (Result<List<PixelRect>>) -> Unit) {
        if (!mayAttach(screen)) { done(Result.failure(ProtocolFailure("wrong_screen"))); return }
        if (rects.size !in 1..8 || rects.any { !it.inside(screen.width, screen.height) }) {
            done(Result.failure(ProtocolFailure("invalid_transform"))); return
        }
        val id = ++transaction
        val retained = installed.filter { it.shield == shield && it.rect in rects }
        val newWindows = mutableListOf<Window>()
        val finished = AtomicBoolean(false)
        val listeners = mutableMapOf<View, ViewTreeObserver.OnGlobalLayoutListener>()
        fun finish(ok: Boolean, code: String = "action_failed") {
            if (!finished.compareAndSet(false, true)) return
            listeners.forEach { (view, listener) ->
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
            if (ok && id == transaction) {
                val removed = installed.filter { it !in retained }
                removed.forEach { manager.removeViewImmediate(it.view) }
                installed.clear(); installed.addAll(retained + newWindows)
                done(Result.success(rects))
            } else {
                newWindows.forEach { if (it.view.isAttachedToWindow) manager.removeViewImmediate(it.view) }
                done(Result.failure(ProtocolFailure(code)))
            }
        }
        fun check() {
            if (id != transaction || !mayAttach(screen)) { finish(false, "wrong_screen"); return }
            val windows = retained + newWindows
            if (windows.size != rects.size) return
            if (windows.any { !it.view.isAttachedToWindow || it.view.width == 0 }) return
            val exact = windows.all {
                val origin = IntArray(2)
                it.view.getLocationOnScreen(origin)
                it.view.isShown && origin[0] == it.rect.x && origin[1] == it.rect.y &&
                    it.view.width == it.rect.width && it.view.height == it.rect.height
            }
            finish(exact, "invalid_transform")
        }
        try {
            for (rect in rects.filter { r -> retained.none { it.rect == r } }) {
                val view = if (shield) shieldView() else View(service).apply {
                    setBackgroundColor(Color.rgb(18, 41, 57))
                    contentDescription = "مساحة محمية"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    isClickable = true
                }
                val params = WindowManager.LayoutParams(rect.width, rect.height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SECURE,
                    android.graphics.PixelFormat.OPAQUE).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    x = rect.x; y = rect.y
                    if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                }
                newWindows.add(Window(view, rect, shield))
                val listener = ViewTreeObserver.OnGlobalLayoutListener { check() }
                listeners[view] = listener
                view.viewTreeObserver.addOnGlobalLayoutListener(listener)
                // Full-display origin is explicitly measured above; never subtract insets twice.
                manager.addView(view, params)
            }
            check()
            handler.postDelayed({ finish(false) }, 400)
        } catch (_: Exception) { finish(false) }
    }

    private fun shieldView(): View = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(32, 48, 32, 48)
        setBackgroundColor(Color.rgb(247, 245, 236))
        isClickable = true
        addView(TextView(service).apply {
            text = "◇\nلحظة هدوء"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(18, 41, 57))
        })
        addView(TextView(service).apply {
            text = if (younger()) "لننتقل إلى مكان آمن. يمكنك طلب مساعدة وليّ أمرك."
                else "توقّف قصير لحمايتك. اختر العودة إلى شاشة آمنة أو التواصل مع وليّ أمرك."
            textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(18, 41, 57))
        })
        addView(Button(service).apply { text = "العودة إلى الأمان"; setOnClickListener { safeHome() } })
        addView(Button(service).apply { text = "طلب مساعدة وليّ الأمر"; setOnClickListener { help() } })
    }

    fun suspendForKeyguard(locked: Boolean) {
        if (suspended == locked) return
        suspended = locked
        installed.forEach { it.view.visibility = if (locked) View.GONE else View.VISIBLE }
    }
    fun clear() {
        transaction++
        installed.forEach { manager.removeViewImmediate(it.view) }
        installed.clear()
    }
}
