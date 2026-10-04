package dev.k230.mentor_app.protection

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal class ProtocolFailure(val code: String) : Exception(code)
internal fun requireProtocol(ok: Boolean, code: String = "bounds") {
    if (!ok) throw ProtocolFailure(code)
}

internal data class PolicyProfile(val age: Int, val revision: Long) {
    init { requireProtocol(age in 10..15, "invalid_age"); requireProtocol(revision >= 1) }
    val profile get() = if (age <= 12) "10-12" else "13-15"
    val cover get() = if (age <= 12) .60 else .70
    val shield get() = if (age <= 12) .80 else .85
    val exit get() = if (age <= 12) .90 else .95
    val repetitions get() = if (age <= 12) 2 else 3
    fun wire() = mapOf("age" to age, "profile" to profile, "policy_version" to POLICY_VERSION,
        "policy_revision" to revision.toString())
    companion object { const val POLICY_VERSION = "age-10-15-v1" }
}

internal data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { requireProtocol(x >= 0 && y >= 0 && width > 0 && height > 0) }
    fun inside(w: Int, h: Int) = x.toLong() + width <= w && y.toLong() + height <= h
    fun overlaps(other: PixelRect) = x.toLong() < other.x.toLong() + other.width &&
        other.x.toLong() < x.toLong() + width && y.toLong() < other.y.toLong() + other.height &&
        other.y.toLong() < y.toLong() + height
    fun wire() = mapOf("x" to x, "y" to y, "width" to width, "height" to height)
}

internal data class ScreenSnapshot(
    val token: String, val epoch: Long, val width: Int, val height: Int, val rotation: Int,
    val windowId: Int, val packageName: String, val sampledUs: Long, val validFromUs: Long,
    val status: String = "verified",
) {
    fun wire() = mapOf("screen_token" to token, "content_epoch" to epoch.toString(), "display_id" to 0,
        "width" to width, "height" to height, "rotation_deg" to rotation, "window_id" to windowId,
        "package" to packageName, "sampled_at_us" to sampledUs.toString(),
        "valid_from_us" to validFromUs.toString(), "status" to status)
}

internal data class Scores(val porn: Double, val hentai: Double, val sexy: Double) {
    init {
        requireProtocol(listOf(porn, hentai, sexy).all { it.isFinite() && it in 0.0..1.0 } &&
            porn + hentai + sexy <= 1.001, "invalid_scores")
    }
    val explicit get() = (porn + hentai).coerceIn(0.0, 1.0)
    val hentaiDominant get() = hentai > porn && porn < .60
}
internal data class Observation(val ptsUs: Long, val scores: Scores)
internal data class RegionEvidence(
    val trackId: Long, val kind: String, val crop: PixelRect, val scores: Scores,
    val route: String, val observations: List<Observation>, val continuityId: String,
)
internal sealed interface CompanionCommand {
    val sessionId: String
    val seq: Long
    val streamId: String
}
internal data class BindCommand(
    override val sessionId: String, override val seq: Long, override val streamId: String,
    val capturePtsUs: Long? = null,
    val captureSource: String = "scrcpy-4.0-display",
) : CompanionCommand
internal data class DecisionCommand(
    override val sessionId: String, override val seq: Long, override val streamId: String,
    val eventId: String, val revision: Long, val ptsUs: Long, val expiresUs: Long,
    val screenToken: String, val epoch: Long, val packageName: String, val windowId: Int,
    val policy: PolicyProfile, val frameWidth: Int, val frameHeight: Int, val rotation: Int,
    val viewport: PixelRect, val stage: Int, val action: String, val reason: String,
    val regions: List<RegionEvidence>, val semanticJson: String,
) : CompanionCommand

internal object CompanionProtocol {
    const val MAX_LINE_BYTES = 16_384
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private data class NumberLexeme(val value: String)
    private val decimal = Regex("0|[1-9][0-9]{0,18}")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val pkg = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun encode(value: Map<String, Any?>): ByteArray {
        val bytes = (gson.toJson(value) + "\n").toByteArray(Charsets.UTF_8)
        requireProtocol(bytes.size <= MAX_LINE_BYTES)
        return bytes
    }

    fun parse(line: ByteArray): CompanionCommand {
        requireProtocol(line.size in 2..MAX_LINE_BYTES && line.last() == 10.toByte())
        requireProtocol(line.none { it == 0.toByte() || it == 13.toByte() }, "malformed_json")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(line, 0, line.size - 1)).toString()
        } catch (_: Exception) { throw ProtocolFailure("malformed_json") }
        requireProtocol(!text.startsWith('\uFEFF'), "malformed_json")
        val root = try {
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                reader.setNestingLimit(10)
                var tokens = 0
                fun read(depth: Int): Any? {
                    requireProtocol(depth <= 10 && ++tokens <= 4096)
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            val map = linkedMapOf<String, Any?>()
                            while (reader.hasNext()) {
                                requireProtocol(map.size < 32 && ++tokens <= 4096)
                                val key = reader.nextName()
                                requireProtocol(!map.containsKey(key), "malformed_json")
                                map[key] = read(depth + 1)
                            }
                            reader.endObject(); map
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            val values = mutableListOf<Any?>()
                            while (reader.hasNext()) {
                                requireProtocol(values.size < 32)
                                values.add(read(depth + 1))
                            }
                            reader.endArray(); values
                        }
                        JsonToken.STRING -> reader.nextString()
                        JsonToken.NUMBER -> NumberLexeme(reader.nextString())
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> { reader.nextNull(); null }
                        else -> throw ProtocolFailure("malformed_json")
                    }
                }
                val parsed = read(1)
                requireProtocol(reader.peek() == JsonToken.END_DOCUMENT, "malformed_json")
                Obj(parsed)
            }
        } catch (e: ProtocolFailure) { throw e }
        catch (_: Exception) { throw ProtocolFailure("malformed_json") }
        requireProtocol(root.int("v") == 2, "unsupported_version")
        return when (root.string("type")) {
            "bind" -> parseBind(root)
            "decision" -> parseDecision(root)
            else -> throw ProtocolFailure("unsupported_version")
        }
    }

    private fun parseBind(o: Obj): BindCommand {
        o.keys("v", "type", "session_id", "seq", "stream_id", "pts_clock", "capture", "capture_pts_us")
        requireProtocol(o.string("pts_clock") == "android_system_nano_time_us", "clock_unverified")
        o.obj("capture").apply {
            keys("source", "display_id", "mirror", "custom_crop", "custom_rotation")
            requireProtocol(string("source") in setOf("scrcpy-4.0-display", "android-mediaprojection-display") && int("display_id") == 0 &&
                !bool("mirror") && !bool("custom_crop") && !bool("custom_rotation"), "invalid_transform")
        }
        return BindCommand(o.id("session_id"), o.long("seq", 1), o.id("stream_id"),
            o.nullableLong("capture_pts_us"), o.obj("capture").string("source"))
    }

    private fun parseDecision(o: Obj): DecisionCommand {
        o.keys("v", "type", "session_id", "seq", "stream_id", "event_id", "action_revision",
            "pts_us", "expires_at_us", "screen_token", "content_epoch", "package", "window_id",
            "policy", "frame", "transform", "requested_stage", "requested_action", "reason", "regions")
        val policy = o.obj("policy").run {
            keys("age", "profile", "policy_version", "policy_revision")
            val p = PolicyProfile(int("age"), long("policy_revision", 1))
            requireProtocol(string("policy_version") == PolicyProfile.POLICY_VERSION &&
                string("profile") == p.profile, "policy_mismatch")
            p
        }
        val frame = o.obj("frame")
        frame.keys("width", "height", "display_rotation_deg")
        val fw = frame.int("width", 1..16384)
        val fh = frame.int("height", 1..16384)
        val rotation = frame.int("display_rotation_deg")
        requireProtocol(rotation in setOf(0, 90, 180, 270), "invalid_transform")
        val transform = o.obj("transform")
        transform.keys("rotation_cw_deg", "viewport_display_px")
        requireProtocol(transform.int("rotation_cw_deg") == 0, "invalid_transform")
        val stage = o.int("requested_stage", 1..3)
        val action = o.string("requested_action")
        requireProtocol(action == actionFor(stage), "invalid_evidence")
        val reason = o.string("reason")
        requireProtocol(reason == "threshold" || reason == "repetition" && stage == 2, "invalid_evidence")
        val regions = o.array("regions").map { raw ->
            val r = Obj(raw)
            r.keys("track_id", "kind", "crop_frame_px", "scores", "evidence")
            val kind = r.string("kind")
            requireProtocol(kind in setOf("Image", "BackgroundImage", "Video"), "invalid_evidence")
            val crop = rect(r.obj("crop_frame_px"))
            requireProtocol(crop.inside(fw, fh), "invalid_transform")
            val scores = scores(r.obj("scores"), true)
            val e = r.obj("evidence")
            e.keys("route", "observations", "analysis_continuity_id", "analysis_complete")
            requireProtocol(e.bool("analysis_complete"), "invalid_evidence")
            val route = e.string("route")
            requireProtocol(route in setOf("explicit", "hentai_dominant"), "invalid_evidence")
            val obs = e.array("observations").map {
                val sample = Obj(it)
                sample.keys("pts_us", "porn", "hentai", "sexy")
                Observation(sample.long("pts_us"), scores(sample, false))
            }
            requireProtocol(obs.size in 3..32, "invalid_evidence")
            RegionEvidence(r.long("track_id", 1), kind, crop, scores, route, obs, e.id("analysis_continuity_id"))
        }
        requireProtocol(regions.size in 1..8 && (stage == 1 || regions.size == 1), "invalid_evidence")
        requireProtocol(regions.map { it.trackId }.distinct().size == regions.size, "invalid_evidence")
        val packageName = o.string("package")
        requireProtocol(packageName.length in 1..255 && pkg.matches(packageName), "wrong_screen")
        // Session, sequence and expiry are transport fields, not idempotent action semantics.
        val semantic = o.values.filterKeys { it !in setOf("session_id", "seq", "expires_at_us") }
        fun canonical(value: Any?): Any? = when (value) {
            is NumberLexeme -> java.math.BigDecimal(value.value).stripTrailingZeros()
            is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
                .associate { it.key.toString() to canonical(it.value) }
            is List<*> -> value.map { canonical(it) }
            else -> value
        }
        return DecisionCommand(o.id("session_id"), o.long("seq", 1), o.id("stream_id"), o.id("event_id"),
            o.long("action_revision", 1), o.long("pts_us"), o.long("expires_at_us"), o.id("screen_token"),
            o.long("content_epoch", 1), packageName, o.int("window_id", 0..Int.MAX_VALUE), policy,
            fw, fh, rotation, rect(transform.obj("viewport_display_px")), stage, action, reason, regions,
            gson.toJson(canonical(semantic)))
    }

    fun actionFor(stage: Int) = when (stage) {
        1 -> "cover_region"; 2 -> "calm_shield"; 3 -> "home"; else -> "none"
    }
    private fun rect(o: Obj): PixelRect {
        o.keys("x", "y", "width", "height")
        return PixelRect(o.int("x", 0..16384), o.int("y", 0..16384),
            o.int("width", 1..16384), o.int("height", 1..16384))
    }
    private fun scores(o: Obj, withExplicit: Boolean): Scores {
        if (withExplicit) o.keys("porn", "hentai", "sexy", "explicit_score")
        val s = Scores(o.probability("porn"), o.probability("hentai"), o.probability("sexy"))
        if (withExplicit) requireProtocol(kotlin.math.abs(o.probability("explicit_score") - s.explicit) <= 1e-6,
            "invalid_scores")
        return s
    }
    private class Obj(raw: Any?) {
        @Suppress("UNCHECKED_CAST")
        val values: Map<String, Any?> = (raw as? Map<String, Any?>) ?: throw ProtocolFailure("malformed_json")
        fun keys(vararg names: String) = requireProtocol(values.keys == names.toSet(), "malformed_json")
        fun string(key: String) = values[key] as? String ?: throw ProtocolFailure("malformed_json")
        fun id(key: String) = string(key).also { requireProtocol(uuid.matches(it)) }
        fun bool(key: String) = values[key] as? Boolean ?: throw ProtocolFailure("malformed_json")
        fun obj(key: String) = Obj(values[key])
        fun array(key: String) = values[key] as? List<*> ?: throw ProtocolFailure("malformed_json")
        fun long(key: String, min: Long = 0): Long {
            val s = string(key)
            requireProtocol(decimal.matches(s))
            return (s.toLongOrNull() ?: throw ProtocolFailure("bounds")).also { requireProtocol(it >= min) }
        }
        fun nullableLong(key: String): Long? = if (values[key] == null) null else long(key, 1)
        fun int(key: String, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int {
            val n = values[key] as? NumberLexeme ?: throw ProtocolFailure("malformed_json")
            requireProtocol(Regex("-?(0|[1-9][0-9]*)").matches(n.value), "malformed_json")
            return (n.value.toIntOrNull() ?: throw ProtocolFailure("bounds")).also { requireProtocol(it in range) }
        }
        fun probability(key: String): Double {
            val n = values[key] as? NumberLexeme ?: throw ProtocolFailure("invalid_scores")
            return (n.value.toDoubleOrNull() ?: throw ProtocolFailure("invalid_scores"))
                .also { requireProtocol(it.isFinite() && it in 0.0..1.0, "invalid_scores") }
        }
    }
}
