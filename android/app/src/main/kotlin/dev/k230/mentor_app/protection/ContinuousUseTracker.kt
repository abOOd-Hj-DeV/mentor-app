package dev.k230.mentor_app.protection

internal class ContinuousUseTracker(private val limitMs: Long = 20 * 60 * 1000L) {
    private var packageName: String? = null
    private var startedMs = 0L

    fun observe(candidate: String?, nowMs: Long): String? {
        if (!eligible(candidate)) {
            packageName = null
            startedMs = 0
            return null
        }
        if (candidate != packageName || nowMs < startedMs) {
            packageName = candidate
            startedMs = nowMs
            return null
        }
        if (nowMs - startedMs < limitMs) return null
        startedMs = nowMs
        return candidate
    }

    companion object {
    fun eligible(value: String?): Boolean {
        if (value == null || !value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) return false
        return value != "dev.k230.mentor_app" && value != "com.android.systemui" &&
            value != "com.android.settings" && value != "com.bbk.launcher2" &&
            value != "com.google.android.apps.nexuslauncher" &&
            !value.startsWith("com.android.launcher")
    }
    }
}
