package dev.k230.mentor_app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class MvpBreakActivity : Activity() {
    private var reason = "content"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        reason = intent.getStringExtra("reason") ?: "content"
        val blocked = intent.getStringExtra("blocked_package")
        val verified = intent.getBooleanExtra("force_stop_verified", false) && blocked != null &&
            runCatching {
                @Suppress("DEPRECATION")
                (packageManager.getApplicationInfo(blocked, 0).flags and
                    android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
            }.getOrDefault(false)
        if (reason == "content" && verified) MvpMetrics(this).intervention("content")
        setContentView(MvpInterventionView.create(this, reason) { finish() })
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        // Finishing before the first visible frame leaves `am start -W` waiting.
        if (!isFinishing && LayoutService.instance?.showMvpBreak(reason) == true) finish()
    }
}
