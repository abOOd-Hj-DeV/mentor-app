package dev.k230.local_inspector

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

internal class ProtectionOverlay(private val service: AccessibilityService, private val home: () -> Unit) {
    private val manager = service.getSystemService(WindowManager::class.java)
    var stage = 0
        private set
    var covered = emptyList<Region>()
        private set
    private var view: View? = null

    fun show(decision: Decision): Boolean {
        if (decision.stage == 1) covered = (covered + decision.regions).distinct() else covered = emptyList()
        stage = maxOf(stage, decision.stage)
        val content = object : View(service) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val origin = IntArray(2)
            override fun onDraw(canvas: Canvas) {
                paint.color = Color.rgb(24, 37, 48)
                if (stage == 1) {
                    getLocationOnScreen(origin)
                    for (r in covered) canvas.drawRect((r.x - origin[0]).toFloat(), (r.y - origin[1]).toFloat(),
                        (r.x + r.width - origin[0]).toFloat(), (r.y + r.height - origin[1]).toFloat(), paint)
                } else {
                    canvas.drawColor(paint.color)
                    paint.color = Color.WHITE
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = 25 * resources.displayMetrics.scaledDensity
                    canvas.drawText("لنشاهد شيئاً آخر", width / 2f, height * .42f, paint)
                    paint.textSize = 17 * resources.displayMetrics.scaledDensity
                    canvas.drawText("اضغط للعودة إلى الشاشة الرئيسية", width / 2f, height * .53f, paint)
                }
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) performClick()
                return true
            }
            override fun performClick(): Boolean { super.performClick(); home(); return true }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                if (stage == 1) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        return try {
            view?.let { manager.removeView(it) }
            view = null
            manager.addView(content, params)
            view = content
            true
        } catch (_: RuntimeException) { stage = 0; covered = emptyList(); false }
    }
    fun clear() {
        view?.let { manager.removeView(it) }
        view = null; stage = 0; covered = emptyList()
    }
}
