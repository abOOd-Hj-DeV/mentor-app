package dev.k230.mentor_app.protection

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

class RemoteCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState != null) { finish(); return }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(request, 4103)
    }
    @Deprecated("Projection permission bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4103 && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, RemoteCaptureService::class.java)
                .putExtra("projection", data).putExtra("resultCode", resultCode)
                .putExtra("endpoint", intent.getStringExtra("endpoint"))
                .putExtra("token", intent.getStringExtra("token"))
                .putExtra("pin", intent.getStringExtra("pin"))
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        }
        finish()
    }
}
