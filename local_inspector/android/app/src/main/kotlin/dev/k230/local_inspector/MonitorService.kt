package dev.k230.local_inspector

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

internal object MonitorState {
    var active = false
    var ready = false
    var error = ""
    var captured = 0L
    var analyzed = 0L
    var dropped = 0L
    var classifications = 0L
    var analysisMs = 0L
    var captureFps = 0.0
    var analysisFps = 0.0
    var lastCapture = 0L
    var lastAnalysis = 0L
    var age = 10
    var stage = 0
    var packageName = ""
    var scores: Scores? = null
    val events = ArrayDeque<JSONObject>()
    fun json(): String = JSONObject()
        .put("enabled", MonitorService.instance != null).put("active", active).put("ready", ready)
        .put("error", error).put("captured", captured).put("analyzed", analyzed).put("dropped", dropped)
        .put("classifications", classifications).put("analysisMs", analysisMs)
        .put("captureFps", if (active && SystemClock.uptimeMillis() - lastCapture < 1000) captureFps else 0.0)
        .put("analysisFps", if (active && SystemClock.uptimeMillis() - lastAnalysis < 1000) analysisFps else 0.0)
        .put("age", age).put("stage", stage)
        .put("package", packageName).put("events", JSONArray(events.toList()))
        .put("scores", scores?.let { JSONObject().put("Drawing", it.drawing).put("Hentai", it.hentai)
            .put("Neutral", it.neutral).put("Porn", it.porn).put("Sexy", it.sexy).put("explicit", it.explicit) } ?: JSONObject.NULL)
        .toString()
}

class MonitorService : AccessibilityService() {
    companion object {
        var instance: MonitorService? = null
            private set
        const val INTERVAL_MS = 334L
    }
    private data class Frame(val bitmap: Bitmap, val timestamp: Long, val generation: Long, val packageName: String)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pipeline: ModelPipeline? = null
    private var loading = false
    private val policy = LocalPolicy()
    private lateinit var overlay: ProtectionOverlay
    private var pending: Frame? = null
    private var busy = false
    private var capturePending = false
    private var generation = 0L
    private var foreground = ""
    private var destroyed = false
    private var episodeApplied = false
    private val captureTimes = ArrayDeque<Long>()
    private val analysisTimes = ArrayDeque<Long>()
    private val tick = object : Runnable {
        override fun run() {
            if (!MonitorState.active) return
            main.postDelayed(this, INTERVAL_MS)
            if (!MonitorState.ready || capturePending || overlay.stage >= 2) return
            val app = applicationPackage()
            if (app.isEmpty() || app == packageName || app == homePackage()) return
            if (app != foreground) changed(app)
            capturePending = true
            val epoch = generation
            val target = foreground
            try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    capturePending = false
                    val buffer = screenshot.hardwareBuffer
                    try {
                        if (!MonitorState.active || destroyed || epoch != generation) return
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?: throw IllegalStateException("screenshot_buffer")
                        val bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                        if (bitmap == null) throw IllegalStateException("screenshot_copy")
                        MonitorState.captured++
                        MonitorState.lastCapture = SystemClock.uptimeMillis()
                        rate(captureTimes, SystemClock.uptimeMillis())
                        MonitorState.captureFps = captureTimes.size / 3.0
                        if (pending != null) { pending?.bitmap?.recycle(); MonitorState.dropped++ }
                        pending = Frame(bitmap, screenshot.timestamp, epoch, target)
                        processLatest()
                    } catch (_: Exception) { MonitorState.error = "تعذر تجهيز لقطة الشاشة" }
                    finally { buffer.close() }
                }
                override fun onFailure(errorCode: Int) {
                    capturePending = false
                    if (!MonitorState.active || epoch != generation) return
                    MonitorState.error = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "رفض Android سرعة الالتقاط؛ ستتم المحاولة مجدداً"
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "فعّل خدمة إمكانية الوصول"
                        else -> "تعذر التقاط الشاشة ($errorCode)؛ الشاشات المحمية غير قابلة للتحليل"
                    }
                    policy.reset()
                }
            })
            } catch (_: RuntimeException) {
                capturePending = false
                MonitorState.error = "تعذر طلب لقطة الشاشة؛ تحقق من خدمة إمكانية الوصول"
                policy.reset()
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        overlay = ProtectionOverlay(this) { home(false) }
        MonitorState.age = getSharedPreferences("local", MODE_PRIVATE).getInt("age", 10)
    }
    internal fun start(age: Int) {
        require(age in 10..15)
        if (MonitorState.active) return
        policy.age = age
        MonitorState.age = age
        getSharedPreferences("local", MODE_PRIVATE).edit().putInt("age", age).apply()
        generation++; policy.reset()
        MonitorState.active = true; MonitorState.error = ""
        if (pipeline != null) { MonitorState.ready = true; main.post(tick); return }
        if (loading) return
        loading = true
        worker.execute {
            try {
                val loaded = ModelPipeline(this)
                pipeline = loaded
                main.post {
                    loading = false
                    if (!destroyed) {
                        MonitorState.ready = true
                        if (MonitorState.active) main.post(tick)
                    }
                }
            } catch (error: Exception) {
                modelLoadFailed(error)
            } catch (error: LinkageError) {
                modelLoadFailed(error)
            } catch (error: OutOfMemoryError) {
                modelLoadFailed(error)
            }
        }
    }
    private fun modelLoadFailed(error: Throwable) {
        Log.e("LocalInspector", "Local model initialization failed", error)
        main.post {
            loading = false
            if (!destroyed) {
                MonitorState.ready = false
                MonitorState.error = "فشل تحميل النموذجين المحليين (${error.javaClass.simpleName})؛ لم يبدأ التحليل"
                stop()
            }
        }
    }
    internal fun stop() {
        MonitorState.active = false; main.removeCallbacks(tick)
        generation++; policy.reset()
        pending?.bitmap?.recycle(); pending = null
        overlay.clear(); MonitorState.stage = 0; episodeApplied = false
    }
    private fun rate(times: ArrayDeque<Long>, now: Long) {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() >= 3000) times.removeFirst()
    }
    private fun processLatest() {
        if (busy || !MonitorState.active) return
        val frame = pending ?: return
        pending = null
        if (SystemClock.uptimeMillis() - frame.timestamp > 750) {
            frame.bitmap.recycle(); MonitorState.dropped++; return
        }
        busy = true
        worker.execute {
            val start = SystemClock.uptimeMillis()
            try {
                val observations = requireNotNull(pipeline).analyze(frame.bitmap)
                val width = frame.bitmap.width; val height = frame.bitmap.height
                val duration = SystemClock.uptimeMillis() - start
                main.post {
                    if (!destroyed && MonitorState.active && frame.generation == generation) {
                        MonitorState.analyzed++; MonitorState.classifications += observations.size
                        MonitorState.lastAnalysis = SystemClock.uptimeMillis()
                        MonitorState.analysisMs = duration
                        rate(analysisTimes, SystemClock.uptimeMillis())
                        MonitorState.analysisFps = analysisTimes.size / 3.0
                        MonitorState.scores = observations.maxByOrNull { it.scores.explicit }?.scores
                        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                        if (SystemClock.uptimeMillis() - frame.timestamp <= 750 && bounds.width() == width &&
                            bounds.height() == height && applicationPackage() == frame.packageName) {
                            val unmasked = observations.filter { o -> overlay.covered.none { it.overlap(o.region) > 0 } }
                            val decision = policy.evaluate(unmasked, frame.timestamp, frame.packageName, width, height)
                            apply(decision)
                            MonitorState.error = ""
                        } else {
                            policy.reset(); MonitorState.dropped++
                            MonitorState.error = "لم ينفذ قرار قديم: تأخر التحليل أو تغيرت الشاشة"
                        }
                    }
                    busy = false
                    processLatest()
                }
            } catch (error: Exception) {
                analysisFailed(frame, start, error)
            } catch (error: LinkageError) {
                analysisFailed(frame, start, error)
            } catch (error: OutOfMemoryError) {
                analysisFailed(frame, start, error)
            } finally { frame.bitmap.recycle() }
        }
    }
    private fun analysisFailed(frame: Frame, start: Long, error: Throwable) {
        val duration = SystemClock.uptimeMillis() - start
        Log.e("LocalInspector", "Screen analysis failed after ${duration}ms", error)
        main.post {
            busy = false
            if (!destroyed && frame.generation == generation) {
                val detail = error.message?.replace('\n', ' ')?.take(200) ?: "خطأ بدون تفاصيل"
                MonitorState.analysisMs = duration
                MonitorState.error = "فشل التحليل (${error.javaClass.simpleName}): $detail"
                stop()
            }
        }
    }
    private fun apply(decision: Decision) {
        if (decision.stage == 0) return
        if (decision.stage == 3) { home(true); return }
        if (decision.stage < overlay.stage) return
        if (overlay.show(decision)) {
            MonitorState.stage = overlay.stage
            if (!episodeApplied) {
                episodeApplied = true
                policy.applied(foreground, SystemClock.uptimeMillis())
                event(if (decision.stage == 1) "تغطية منطقة الصورة" else "حاجب كامل", decision.stage)
            }
        } else { MonitorState.error = "تعذر عرض الحاجب"; MonitorState.stage = 0 }
    }
    private fun home(automatic: Boolean) {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            event(if (automatic) "طلب HOME بعد تأكيد Porn" else "طلب HOME من زر الحاجب", 3)
            overlay.clear(); MonitorState.stage = 3
            generation++; policy.reset()
        } else {
            overlay.show(Decision(2))
            MonitorState.stage = 2; MonitorState.error = "تعذر HOME؛ الحاجب مستمر"
        }
    }
    private fun event(action: String, stage: Int) {
        MonitorState.events.addFirst(JSONObject().put("time", System.currentTimeMillis())
            .put("package", foreground).put("action", action).put("stage", stage))
        while (MonitorState.events.size > 50) MonitorState.events.removeLast()
    }
    private fun applicationPackage(): String {
        val root = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
            ?: return ""
        return try { root.packageName?.toString() ?: "" } finally { root.recycle() }
    }
    private fun homePackage(): String = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .resolveActivity(packageManager)?.packageName ?: ""
    private fun changed(app: String) {
        generation++; policy.reset()
        foreground = app; MonitorState.packageName = app
        pending?.bitmap?.recycle(); pending = null
        overlay.clear(); MonitorState.stage = 0; episodeApplied = false
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!MonitorState.active || event == null) return
        val app = applicationPackage()
        if (app.isNotEmpty() && app != foreground) changed(app)
        else if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && overlay.stage < 2) {
            generation++; policy.reset()
            overlay.clear(); MonitorState.stage = 0; episodeApplied = false
        }
    }
    override fun onInterrupt() { if (::overlay.isInitialized) stop() }
    override fun onDestroy() {
        if (::overlay.isInitialized) stop()
        destroyed = true; instance = null; MonitorState.ready = false
        worker.execute { pipeline?.close(); pipeline = null }
        worker.shutdown()
        super.onDestroy()
    }
}
