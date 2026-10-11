package dev.k230.mentor_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONObject

class MvpReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val metrics = MvpMetrics(context)
            when (intent.action) {
                "dev.k230.mentor.MVP_EXPORT" -> {
                    if (metrics.role() == "guardian") { resultCode = 1; return }
                    resultData = "REPORT:" + Base64.encodeToString(
                        metrics.childSnapshot().toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    resultCode = 0
                }
                "dev.k230.mentor.MVP_IMPORT" -> {
                    val encoded = intent.getStringExtra("snapshot") ?: return
                    if (encoded.length > 8192) { resultCode = 1; return }
                    val value = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
                    resultCode = if (metrics.importSnapshot(value)) 0 else 1
                }
            }
        } catch (_: Exception) {
            resultCode = 1
        }
    }
}
