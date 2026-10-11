package dev.k230.mentor_app.protection

import kotlin.math.ceil
import kotlin.math.floor

internal data class AppliedMask(val rect: PixelRect, val appliedUs: Long)

internal object DecisionValidator {
    fun validate(
        d: DecisionCommand, screen: ScreenSnapshot?, profile: PolicyProfile?, nowUs: Long,
        clockVerified: Boolean, locked: Boolean, masks: List<AppliedMask>, repetitionAuthorized: Boolean,
    ): List<PixelRect> {
        requireProtocol(!locked, "locked")
        requireProtocol(clockVerified, "clock_unverified")
        requireProtocol(profile != null && d.policy == profile, "policy_mismatch")
        requireProtocol(screen != null && screen.status == "verified", "wrong_screen")
        val s = screen!!
        requireProtocol(d.screenToken == s.token && d.epoch == s.epoch && d.packageName == s.packageName &&
            d.windowId == s.windowId && d.rotation == s.rotation, "wrong_screen")
        requireProtocol(nowUs >= s.sampledUs && nowUs - s.sampledUs <= 500_000, "stale")
        requireProtocol(d.ptsUs <= Long.MAX_VALUE - 750_000 && d.expiresUs == d.ptsUs + 750_000, "stale")
        requireProtocol(d.ptsUs - nowUs <= 50_000, "future_pts")
        requireProtocol(nowUs - d.ptsUs <= 750_000 && nowUs <= d.expiresUs, "stale")
        requireProtocol(d.viewport == PixelRect(0, 0, s.width, s.height), "invalid_transform")
        val sx = s.width.toDouble() / d.frameWidth
        val sy = s.height.toDouble() / d.frameHeight
        requireProtocol(kotlin.math.abs(sx - sy) / maxOf(sx, sy) <= .015, "invalid_transform")
        if (d.reason == "repetition") requireProtocol(repetitionAuthorized, "invalid_evidence")
        return d.regions.map { r ->
            validateEvidence(d, r, s)
            val rect = scale(r.crop, d.frameWidth, d.frameHeight, s.width, s.height)
            requireProtocol(masks.none { mask -> mask.rect.overlaps(rect) &&
                r.observations.any { it.ptsUs >= mask.appliedUs } }, "invalid_evidence")
            rect
        }
    }

    private fun validateEvidence(d: DecisionCommand, r: RegionEvidence, s: ScreenSnapshot) {
        val hentai = r.route == "hentai_dominant"
        requireProtocol(!(hentai && d.stage == 3), "hentai_stage3_forbidden")
        val n = if (hentai || d.stage == 3) 5 else 3
        val span = if (hentai || d.stage == 3) 1_000_000L else 400_000L
        val obs = r.observations
        requireProtocol(obs.size >= n && obs.last().ptsUs == d.ptsUs &&
            obs.last().scores == r.scores && obs.first().ptsUs >= s.validFromUs &&
            obs.last().ptsUs - obs.first().ptsUs >= span, "invalid_evidence")
        requireProtocol(obs.zipWithNext().all { (a, b) ->
            b.ptsUs > a.ptsUs && b.ptsUs - a.ptsUs <= 500_000
        }, "invalid_evidence")
        val threshold = when {
            d.stage == 3 -> d.policy.exit
            d.stage == 2 && d.reason != "repetition" -> d.policy.shield
            else -> d.policy.cover
        }
        requireProtocol(obs.all {
            it.scores.hentaiDominant == hentai && (!hentai || it.scores.hentai > .60) &&
                it.scores.explicit >= threshold && (d.stage != 3 || it.scores.porn >= .60)
        }, "invalid_evidence")
    }

    fun scale(r: PixelRect, fw: Int, fh: Int, dw: Int, dh: Int): PixelRect {
        val x = floor(r.x.toDouble() * dw / fw).toInt()
        val y = floor(r.y.toDouble() * dh / fh).toInt()
        val right = ceil((r.x.toLong() + r.width).toDouble() * dw / fw).toInt()
        val bottom = ceil((r.y.toLong() + r.height).toDouble() * dh / fh).toInt()
        return PixelRect(x, y, right - x, bottom - y).also {
            requireProtocol(it.inside(dw, dh), "invalid_transform")
        }
    }

    fun rotateEdge(x: Int, y: Int, w: Int, h: Int, degrees: Int): Pair<Int, Int> = when (degrees) {
        0 -> x to y; 90 -> h - y to x; 180 -> w - x to h - y; 270 -> y to w - x
        else -> throw ProtocolFailure("invalid_transform")
    }
}
