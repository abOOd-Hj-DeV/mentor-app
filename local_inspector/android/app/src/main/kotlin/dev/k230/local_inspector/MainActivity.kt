package dev.k230.local_inspector

import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "local_inspector/control").setMethodCallHandler { call, result ->
            when (call.method) {
                "status" -> result.success(MonitorState.json())
                "settings" -> { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); result.success(null) }
                "start" -> {
                    val service = MonitorService.instance
                    val age = call.argument<Int>("age")
                    if (service == null) result.error("accessibility", "فعّل خدمة حماية محلية أولاً", null)
                    else if (age == null || age !in 10..15) result.error("age", "العمر المدعوم من 10 إلى 15", null)
                    else { service.start(age); result.success(null) }
                }
                "stop" -> { MonitorService.instance?.stop(); result.success(null) }
                "clearEvents" -> { MonitorState.events.clear(); result.success(null) }
                else -> result.notImplemented()
            }
        }
    }
}
