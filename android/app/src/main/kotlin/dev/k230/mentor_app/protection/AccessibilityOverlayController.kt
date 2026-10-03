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
import android.widget.ImageView
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat
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
    private var helpPanel: LinearLayout? = null
    private var helpScanner: DecoratedBarcodeView? = null

    fun cover(rects: List<PixelRect>, screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit) {
        val complete = try {
            requireProtocol(installed.none { it.shield }, "event_conflict")
            coverRectUnion(installed.map { it.rect }, rects)
        } catch (e: ProtocolFailure) { done(Result.failure(e)); return }
        attach(complete, screen, false, done)
    }
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
        addView(LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            helpPanel = this })
    }

    fun showHelp(reply: SecurityReply) {
        val panel = helpPanel?.takeIf { it.isAttachedToWindow && installed.any { w -> w.shield } } ?: return
        helpScanner?.pause(); helpScanner = null
        panel.removeAllViews()
        val payload = ((reply as? SecurityReply.Value)?.data as? Map<*, *>)?.get("qrPayload") as? String
        if (payload != null) {
            try {
                val size = (service.resources.displayMetrics.widthPixels * .65f).toInt().coerceIn(160, 512)
                panel.addView(ImageView(service).apply {
                    setImageBitmap(NativeQr.bitmap(payload, size)); contentDescription = "رمز طلب مساعدة ولي الأمر"
                }, LinearLayout.LayoutParams(size, size))
                panel.addView(TextView(service).apply {
                    text = "ليَمسح ولي الأمر هذا الرمز من جهازه المقترن. لا يُرسل الطلب سحابياً؛ تبقى الحماية فعّالة."
                    gravity = Gravity.CENTER; setTextColor(Color.rgb(18, 41, 57))
                })
                panel.addView(Button(service).apply {
                    text = "مسح رد ولي الأمر مع إبقاء الحاجب"
                    setOnClickListener { scanHelp(panel, size) }
                })
                return
            } catch (_: Exception) { panel.removeAllViews() }
        }
        panel.addView(TextView(service).apply {
            text = "طلب المساعدة غير متاح الآن. تحقق من اقتران الجهاز وفتحه؛ تبقى الحماية فعّالة."
            gravity = Gravity.CENTER; setTextColor(Color.rgb(18, 41, 57))
        })
    }

    private fun scanHelp(panel: LinearLayout, size: Int) {
        if (service.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            panel.addView(TextView(service).apply { text = "الكاميرا غير متاحة. امنحها للتطبيق أثناء الاقتران؛ لا تُرفع الحماية." })
            return
        }
        helpScanner?.pause()
        panel.removeAllViews()
        val status = TextView(service).apply { text = "امسح التحكم الموقّع من جهاز ولي الأمر. الحاجب باقٍ." }
        panel.addView(status)
        val scanner = DecoratedBarcodeView(service)
        helpScanner = scanner
        scanner.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        panel.addView(scanner, LinearLayout.LayoutParams(size, size))
        scanner.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                scanner.pause()
                val bytes = result.text.toByteArray(Charsets.UTF_8)
                val receive = ProtectionIntegration.receiveProtectedControl
                if (bytes.size !in 1..2953 || receive == null) { status.text = "تعذر التحقق من الرد. الحاجب باقٍ."; return }
                receive(bytes) { reply -> handler.post {
                    if (helpScanner === scanner && panel.isAttachedToWindow) {
                        status.text = if (reply is SecurityReply.Error) "رد غير صالح أو منتهي. الحاجب باقٍ."
                            else "تم التحقق من الرد؛ إزالة الحاجب تتطلب منحاً صالحاً لهذه الحادثة."
                    }
                } }
            }
        })
        scanner.resume()
    }

    fun suspendForKeyguard(locked: Boolean) {
        if (suspended == locked) return
        suspended = locked
        if (locked) helpScanner?.pause()
        installed.forEach { it.view.visibility = if (locked) View.GONE else View.VISIBLE }
    }
    fun clear() {
        transaction++
        installed.forEach { manager.removeViewImmediate(it.view) }
        installed.clear()
        helpScanner?.pause(); helpScanner = null
        helpPanel = null
    }
}
