package dev.k230.local_inspector

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class Region(val x: Int, val y: Int, val width: Int, val height: Int, val kind: Int = 9) {
    fun overlap(other: Region): Double {
        val intersection = max(0, min(x + width, other.x + other.width) - max(x, other.x)).toDouble() *
            max(0, min(y + height, other.y + other.height) - max(y, other.y))
        return intersection / (width.toDouble() * height + other.width.toDouble() * other.height - intersection)
    }
}

internal data class Scores(val drawing: Float, val hentai: Float, val neutral: Float, val porn: Float, val sexy: Float) {
    val explicit: Float get() = (porn + hentai).coerceIn(0f, 1f)
    val hentaiDominant: Boolean get() = hentai > porn && porn < .60f
}

internal data class Observation(val region: Region, val scores: Scores)
internal data class Decision(val stage: Int = 0, val regions: List<Region> = emptyList())

internal class LocalPolicy(var age: Int = 10) {
    var captureIntervalMs = 334L
        set(value) { field = value.coerceAtLeast(100L) }
    private val gap get() = captureIntervalMs * 2 + 400
    private val slow get() = captureIntervalMs >= 750
    private class Chain {
        var count = 0
        var first = -1L
        fun add(qualifies: Boolean, time: Long) {
            if (!qualifies) { count = 0; first = -1; return }
            if (first < 0) first = time
            count++
        }
        fun ready(number: Int, span: Long, time: Long) = count >= number && time - first >= span
    }
    private class Track(val region: Region, val hentai: Boolean) {
        var cover = Chain()
        var shield = Chain()
        var home = Chain()
    }
    private var tracks = emptyList<Track>()
    private var lastTime = -1L
    private val actions = mutableListOf<Pair<String, Long>>()
    val coverThreshold get() = if (age <= 12) .60f else .70f
    val shieldThreshold get() = if (age <= 12) .80f else .85f
    val homeThreshold get() = if (age <= 12) .90f else .95f

    fun reset() { tracks = emptyList(); lastTime = -1 }
    fun applied(packageName: String, time: Long) {
        actions.removeAll { time - it.second >= 60_000 }
        actions.add(packageName to time)
    }
    fun evaluate(observations: List<Observation>, time: Long, packageName: String, width: Int, height: Int): Decision {
        require(age in 10..15)
        if (time <= lastTime) return Decision()
        if (lastTime >= 0 && time - lastTime > gap) tracks = emptyList()
        lastTime = time
        val used = mutableSetOf<Track>()
        val next = mutableListOf<Track>()
        val covers = mutableListOf<Region>()
        var stage = 0
        var deferred = false
        val repeated = actions.count { it.first == packageName && time - it.second in 0..59_999 } + 1 >=
            if (age <= 12) 2 else 3
        for (observation in observations) {
            val r = observation.region
            val score = observation.scores
            val candidates = tracks.filter {
                it !in used && it.hentai == score.hentaiDominant && it.region.kind == r.kind &&
                    it.region.overlap(r) >= .70 &&
                    hypot((r.x + r.width * .5) - (it.region.x + it.region.width * .5),
                        (r.y + r.height * .5) - (it.region.y + it.region.height * .5)) <= .05 * hypot(width.toDouble(), height.toDouble()) &&
                    abs(r.width - it.region.width) <= .10 * it.region.width &&
                    abs(r.height - it.region.height) <= .10 * it.region.height
            }.sortedByDescending { it.region.overlap(r) }
            val previous = candidates.firstOrNull()?.takeIf {
                candidates.size == 1 || it.region.overlap(r) - candidates[1].region.overlap(r) > .05
            }
            val track = Track(r, score.hentaiDominant)
            if (previous != null) {
                used.add(previous)
                track.cover = previous.cover; track.shield = previous.shield; track.home = previous.home
            }
            val eligible = !score.hentaiDominant || score.hentai > .60f
            track.cover.add(eligible && score.explicit >= coverThreshold, time)
            track.shield.add(eligible && score.explicit >= shieldThreshold, time)
            track.home.add(!score.hentaiDominant && score.explicit >= homeThreshold && score.porn >= .60f, time)
            val count = if (score.hentaiDominant) { if (slow) 3 else 5 } else { if (slow) 2 else 3 }
            val span = if (score.hentaiDominant) 1000L else 400L
            val homeCount = if (slow) 3 else 5
            var level = 0
            if (track.cover.ready(count, span, time)) level = 1
            if (track.shield.ready(count, span, time) || (level == 1 && repeated)) level = 2
            if (track.home.ready(homeCount, 1000, time)) level = 3
            if (level < 3 && track.home.count > 0 && track.home.count < homeCount &&
                time - track.home.first < gap * homeCount) deferred = true
            if (level > 0) { covers.add(r); stage = max(stage, level) }
            next.add(track)
        }
        tracks = next
        return if (deferred && stage < 3) Decision() else Decision(stage, covers)
    }
}
