package dev.k230.mentor_app.protection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import dev.k230.mentor_app.LayoutService
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class RemoteCaptureService : Service() {
    private val worker = HandlerThread("remote-capture")
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var connection: RemoteConnection? = null
    private var width = 0
    private var height = 0
    private var lastFrameUs = 0L
    private var networkStarted = false
    @Volatile private var geometryVerified = Build.VERSION.SDK_INT < 34
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); worker.start(); handler = Handler(worker.looper)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        try {
            val request = intent ?: throw ProtocolFailure("bounds")
            val config = RemoteConnectionConfig.parse(request.getStringExtra("endpoint") ?: "",
                request.getStringExtra("token") ?: "", request.getStringExtra("pin") ?: "")
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26)
                manager.createNotificationChannel(NotificationChannel("remote_capture", "تحليل الشاشة عن بعد", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, RemoteCaptureService::class.java).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "remote_capture") else Notification.Builder(this)
            val notification = builder.setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("تحليل الشاشة عن بعد")
                .setContentText("تُرسل صور الشاشة مشفّرة إلى السيرفر دون حفظها. لا يوجد التقاط صوت.")
                .setOngoing(true).addAction(Notification.Action.Builder(null, "إيقاف المشاركة", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(4103, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(4103, notification)
            val security = ProtectionIntegration.security.state()
            requireProtocol(security.role == DeviceRole.CHILD && security.pairing == "paired", "unpaired")
            requireProtocol(LayoutService.instance?.protection != null, "permission_missing")
            val wm = getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = wm.maximumWindowMetrics.bounds; width = bounds.width(); height = bounds.height()
            } else {
                val size = android.graphics.Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(size)
                width = size.x; height = size.y
            }
            requireProtocol(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8 * 1024 * 1024)
            @Suppress("DEPRECATION")
            val consent = request.getParcelableExtra<Intent>("projection") ?: throw ProtocolFailure("bounds")
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(request.getIntExtra("resultCode", 0), consent)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                    if (newWidth != width || newHeight != height) { state = "geometry_changed"; stopSelf() }
                    else geometryVerified = true
                }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    if (!isVisible) { state = "geometry_changed"; stopSelf() }
                }
            }, handler)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            RemoteCaptureProof.active = true
            state = "awaiting_screen"
            connection = RemoteConnection(config) {
                state = it
                if (it == "disconnected") stopSelf()
            }
            reader!!.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val pts = image.timestamp / 1000
                    val now = System.nanoTime() / 1000
                    if (!geometryVerified || pts <= 0 || now - lastFrameUs < 250_000 || pts > now || now - pts > 250_000) return@setOnImageAvailableListener
                    val current = ProtectionIntegration.security.state()
                    if (current.role != DeviceRole.CHILD || current.pairing != "paired" || current.encryption != "ready") {
                        stopSelf(); return@setOnImageAvailableListener
                    }
                    val screen = LayoutService.instance?.protection?.captureScreen() ?: return@setOnImageAvailableListener
                    if (screen.width != width || screen.height != height) { state = "geometry_changed"; stopSelf(); return@setOnImageAvailableListener }
                    if (pts < screen.validFromUs) return@setOnImageAvailableListener
                    if (!networkStarted) {
                        networkStarted = true; state = "connecting"; connection?.start()
                    }
                    lastFrameUs = now
                    val plane = image.planes[0]
                    requireProtocol(plane.pixelStride == 4 && plane.rowStride >= width * 4)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        val packed = ByteBuffer.allocate(width * height * 4)
                        val sourcePixels = plane.buffer.duplicate()
                        val row = ByteArray(width * 4)
                        repeat(height) { y ->
                            sourcePixels.position(y * plane.rowStride)
                            sourcePixels.get(row); packed.put(row)
                        }
                        packed.flip(); bitmap.copyPixelsFromBuffer(packed)
                        val output = ByteArrayOutputStream()
                        requireProtocol(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        connection?.frame(pts, screen, output.toByteArray())
                    } finally {
                        bitmap.recycle()
                    }
                } catch (_: Exception) { state = "capture_failed"; stopSelf() }
                finally { image.close() }
            }, handler)
            display = projection!!.createVirtualDisplay("mentor-remote", width, height,
                resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler)
        } catch (_: Exception) { state = "capture_failed"; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        RemoteCaptureProof.clear()
        connection?.close(); connection = null
        display?.release(); reader?.close(); projection?.stop()
        worker.quitSafely()
        if (state !in setOf("geometry_changed", "capture_failed", "disconnected")) state = "stopped"
        super.onDestroy()
    }
    companion object {
        @Volatile private var state = "stopped"
        fun status(): Map<String, Any?> = mapOf("status" to state, "mediaStored" to false, "audio" to false)
    }
}
