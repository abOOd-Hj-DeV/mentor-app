package dev.k230.mentor_app.protection

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.CaptureActivity

class SecureCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}

internal class NativeQr(private val activity: Activity) {
    private var callback: ((ByteArray?) -> Unit)? = null
    fun scan(result: (ByteArray?) -> Unit) {
        requireProtocol(callback == null, "busy")
        callback = result
        try {
            IntentIntegrator(activity).setCaptureActivity(SecureCaptureActivity::class.java)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("امسح الرمز من الجهاز المقترن مباشرةً")
                .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false)
                .initiateScan()
        } catch (e: Exception) { callback = null; throw e }
    }
    fun result(request: Int, code: Int, data: Intent?): Boolean {
        val scan = IntentIntegrator.parseActivityResult(request, code, data) ?: return false
        val pending = callback ?: return true
        callback = null
        pending(scan.contents?.toByteArray(Charsets.UTF_8)?.takeIf { it.size in 1..2953 })
        return true
    }
    fun cancel() { val pending = callback; callback = null; pending?.invoke(null) }
    companion object {
        fun bitmap(payload: String, size: Int): Bitmap {
            requireProtocol(payload.toByteArray(Charsets.UTF_8).size in 1..2953)
            val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
            return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                for (y in 0 until size) for (x in 0 until size)
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}
