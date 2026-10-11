package dev.k230.mentor_app

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

internal class MvpMetrics(context: Context) {
    private val preferences = context.getSharedPreferences("mentor_mvp", Context.MODE_PRIVATE)
    private var previousPackage: String? = null
    private var previousElapsed = 0L
    private var previousWall = 0L

    fun role(): String? = preferences.getString("role", null)

    fun setRole(role: String) {
        require(role == "child" || role == "guardian")
        preferences.edit().putString("role", role).apply()
    }

    fun intervention(reason: String) {
        val key = if (reason == "usage") "usage_breaks" else "blocked_threats"
        preferences.edit().putInt(key, preferences.getInt(key, 0) + 1).apply()
    }

    fun observeUsage(packageName: String?, elapsedMs: Long, wallMs: Long) {
        val delta = elapsedMs - previousElapsed
        if (delta in 1..15_000 && previousElapsed > 0 && wallMs >= previousWall) {
            val day = dayStart(wallMs)
            val beforeMidnight = (day - previousWall).coerceIn(0, delta)
            if (beforeMidnight > 0) addDuration("coverage", dayStart(previousWall), beforeMidnight)
            addDuration("coverage", day, delta - beforeMidnight)
            if (previousPackage != null) {
                if (beforeMidnight > 0) addDuration("usage", dayStart(previousWall), beforeMidnight)
                addDuration("usage", day, delta - beforeMidnight)
            }
        }
        if (!preferences.contains("tracked_since") && packageName != null)
            preferences.edit().putLong("tracked_since", wallMs).apply()
        previousPackage = packageName
        previousElapsed = elapsedMs
        previousWall = wallMs
    }

    private fun addDuration(prefix: String, day: Long, duration: Long) {
        val key = "${prefix}_$day"
        preferences.edit().putLong(key, preferences.getLong(key, 0) + duration).apply()
        val calendar = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DATE, -1) }
        val oldest = calendar.timeInMillis
        val edit = preferences.edit()
        preferences.all.keys.filter { (it.startsWith("usage_") && it != "usage_breaks") || it.startsWith("coverage_") }
            .filter { (it.substringAfter("_").toLongOrNull() ?: oldest) < oldest }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    fun childSnapshot(now: Long = System.currentTimeMillis()): JSONObject {
        val day = dayStart(now)
        val yesterday = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DATE, -1) }.timeInMillis
        var source = preferences.getString("source", null)
        if (source == null) {
            source = UUID.randomUUID().toString()
            preferences.edit().putString("source", source).apply()
        }
        return JSONObject().put("schema", 1).put("source", source).put("sampledAtMs", now)
            .put("day", day).put("blockedThreats", preferences.getInt("blocked_threats", 0))
            .put("usageBreaks", preferences.getInt("usage_breaks", 0))
            .put("todayUsageMs", preferences.getLong("usage_$day", 0))
            .put("yesterdayUsageMs", preferences.getLong("usage_$yesterday", 0))
            .put("comparisonReady", preferences.getLong("tracked_since", Long.MAX_VALUE) <= yesterday &&
                preferences.getLong("coverage_$yesterday", 0) >= day - yesterday - 60_000)
    }

    fun importSnapshot(value: JSONObject): Boolean {
        if (role() != "guardian" || value.optInt("schema") != 1) return false
        val source = value.optString("source")
        if (!source.matches(Regex("[a-f0-9-]{36}"))) return false
        if (runCatching { UUID.fromString(source).toString() }.getOrNull() != source) return false
        val linked = preferences.getString("linked_source", null)
        if (linked != null && linked != source) return false
        val now = System.currentTimeMillis()
        val sampled = value.optLong("sampledAtMs", -1)
        if (sampled < now - 86_400_000 || sampled > now + 300_000) return false
        val day = value.optLong("day", -1)
        if (day <= 0 || day > sampled || sampled - day > 27L * 3_600_000) return false
        for (key in listOf("blockedThreats", "usageBreaks"))
            if (value.optLong(key, -1) !in 0..10_000_000) return false
        for (key in listOf("todayUsageMs", "yesterdayUsageMs"))
            if (value.optLong(key, -1) !in 0..90_000_000) return false
        val previous = preferences.getString("guardian_snapshot", null)?.let { JSONObject(it) }
        if (previous != null && (sampled < previous.getLong("sampledAtMs") ||
            value.getLong("blockedThreats") < previous.getLong("blockedThreats") ||
            value.getLong("usageBreaks") < previous.getLong("usageBreaks") ||
            (day == previous.getLong("day") &&
                value.getLong("todayUsageMs") < previous.getLong("todayUsageMs")))) return false
        preferences.edit().putString("linked_source", source)
            .putString("guardian_snapshot", value.toString()).putLong("received_at", now).apply()
        return true
    }

    fun summary(): Map<String, Any?> {
        val value = if (role() == "guardian") preferences.getString("guardian_snapshot", null)?.let { JSONObject(it) }
            else childSnapshot()
        val result = mutableMapOf<String, Any?>("role" to role(), "linked" to (value != null))
        if (value != null) value.keys().forEach { key -> result[key] = value.get(key) }
        result["receivedAtMs"] = preferences.getLong("received_at", 0)
        return result
    }

    private fun dayStart(value: Long): Long = Calendar.getInstance().apply {
        timeInMillis = value
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
