package dev.k230.mentor_app

import io.flutter.embedding.android.FlutterActivity
import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
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
}
