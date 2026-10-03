package dev.k230.mentor_app

import io.flutter.embedding.android.FlutterActivity
import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.os.Bundle
import android.view.WindowManager
import dev.k230.mentor_app.protection.ProtectionChannel
import dev.k230.mentor_app.protection.NativeSecurityProvider

class MainActivity : FlutterActivity() {
    private var protectionChannel: ProtectionChannel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        NativeSecurityProvider.install(this).attach(this)
    }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        protectionChannel = ProtectionChannel(this, flutterEngine.dartExecutor.binaryMessenger)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.k230.mentor/layout")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(null)
                    }
                    "status" -> result.success(
                        LayoutService.instance?.status() ?: mapOf("running" to false, "connected" to false),
                    )
                    else -> result.notImplemented()
                }
            }
    }
    override fun onPause() {
        protectionChannel?.backgrounded()
        super.onPause()
    }
    override fun onDestroy() {
        NativeSecurityProvider.install(this).detach(this)
        protectionChannel?.close()
        protectionChannel = null
        super.onDestroy()
    }
    @Deprecated("Activity result bridge for native credentials and QR")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!NativeSecurityProvider.install(this).activityResult(requestCode, resultCode, data))
            super.onActivityResult(requestCode, resultCode, data)
    }
}
