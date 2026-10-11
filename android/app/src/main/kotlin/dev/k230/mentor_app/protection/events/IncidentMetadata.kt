package dev.k230.mentor_app.protection.events

import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.security.StrictJson
import dev.k230.mentor_app.protection.security.AndroidBase64
import dev.k230.mentor_app.protection.security.Base64Codec
import dev.k230.mentor_app.protection.security.bounded

enum class EvidenceRoute(val wire: String) { EXPLICIT("explicit"), HENTAI("hentai_dominant") }
enum class ExecutionStatus(val wire: String) {
    EXECUTED("executed"), FAILED("failed"), REJECTED("rejected"), UNKNOWN("unknown"), RELEASED("released")
}
enum class ProtectionAction(val wire: String, val stage: Int) {
    NONE("none", 0), COVER("cover_region", 1), SHIELD("calm_shield", 2), HOME("home", 3)
}
data class ProbabilityScores(val porn: Double, val hentai: Double, val sexy: Double) {
    val explicitScore: Double get() = (porn + hentai).coerceIn(0.0, 1.0)
    fun validate() {
        StrictJson.ensure(listOf(porn, hentai, sexy).all { it.isFinite() && it in 0.0..1.0 } &&
            porn + hentai + sexy <= 1.001, "invalid_scores")
    }
    fun json(): JsonObject {
        validate()
        return JsonObject().apply {
            addProperty("porn", porn); addProperty("hentai", hentai); addProperty("sexy", sexy)
            addProperty("explicit_score", explicitScore)
        }
    }
}
data class EvidenceSummary(val route: EvidenceRoute, val count: Int, val firstPtsUs: Long, val lastPtsUs: Long)
data class ExecutionResult(val action: ProtectionAction, val status: ExecutionStatus, val error: String? = null)

data class IncidentMetadata(
    val eventId: String, val revision: Long, val childDeviceId: String, val occurredAtMs: Long,
    val appPackage: String, val scores: ProbabilityScores, val evidence: EvidenceSummary,
    val requested: ProtectionAction, val executed: ExecutionResult, val policyRevision: Long,
    val appVersion: String = "1.0.0+1", val model: String = "nsfwjs-mobilenet-v2-onnx",
) {
    fun toJson(): JsonObject {
        val obj = JsonObject().apply {
            addProperty("v", 2); addProperty("type", "incident"); addProperty("event_id", eventId)
            addProperty("incident_revision", revision.toString()); addProperty("child_device_id", childDeviceId)
            addProperty("occurred_at_ms", occurredAtMs.toString()); addProperty("time_source", "device_wall_clock")
            addProperty("app_package", appPackage); addProperty("category", evidence.route.wire); add("scores", scores.json())
            add("evidence", JsonObject().apply {
                addProperty("route", evidence.route.wire); addProperty("frame_count", evidence.count)
                addProperty("span_us", Math.subtractExact(evidence.lastPtsUs, evidence.firstPtsUs).toString())
                addProperty("first_pts_us", evidence.firstPtsUs.toString()); addProperty("last_pts_us", evidence.lastPtsUs.toString())
                addProperty("complete", true)
            })
            addProperty("stage", requested.stage)
            add("requested", JsonObject().apply { addProperty("action", requested.wire); addProperty("stage", requested.stage) })
            add("executed", JsonObject().apply {
                addProperty("action", executed.action.wire); addProperty("stage", executed.action.stage)
                addProperty("status", executed.status.wire); addProperty("error", executed.error)
            })
            add("versions", JsonObject().apply {
                addProperty("contract", "mentor-parental-v2.0"); addProperty("policy", "age-10-15-v1")
                addProperty("policy_revision", policyRevision.toString()); addProperty("app", appVersion); addProperty("model", model)
            })
        }
        PayloadValidation.incident(obj)
        return obj
    }
}

object PayloadValidation {
    val errors = setOf("unsupported_version", "malformed_json", "bounds", "invalid_age", "policy_mismatch", "session_mismatch",
        "stream_mismatch", "stale", "future_pts", "clock_unverified", "wrong_screen", "invalid_transform", "invalid_scores",
        "invalid_evidence", "hentai_stage3_forbidden", "capability_missing", "permission_missing", "locked", "action_failed",
        "home_unverified", "event_conflict", "rate_limited", "busy", "storage_failed")
    private fun obj(parent: JsonObject, key: String): JsonObject {
        StrictJson.ensure(parent[key]?.isJsonObject == true, "invalid_envelope")
        return parent[key].asJsonObject
    }
    private fun action(parent: JsonObject, allowedNone: Boolean): Int {
        val stage = StrictJson.int(parent, "stage")
        val actions = listOf("none", "cover_region", "calm_shield", "home")
        StrictJson.ensure(stage in (if (allowedNone) 0 else 1)..3 &&
            StrictJson.string(parent, "action") == actions[stage], "invalid_envelope")
        return stage
    }
    private fun error(parent: JsonObject) {
        StrictJson.ensure(parent["error"].isJsonNull || StrictJson.string(parent, "error") in errors, "invalid_envelope")
    }
    fun validate(kind: String, payload: JsonObject, codec: Base64Codec = AndroidBase64) {
        when (kind) {
            "incident" -> incident(payload)
            "control" -> control(payload, codec)
            "control_receipt" -> receipt(payload)
            else -> StrictJson.ensure(false, "invalid_envelope")
        }
    }
    fun incident(o: JsonObject) {
        StrictJson.exact(o, "v", "type", "event_id", "incident_revision", "child_device_id", "occurred_at_ms", "time_source",
            "app_package", "category", "scores", "evidence", "stage", "requested", "executed", "versions")
        StrictJson.ensure(StrictJson.int(o, "v") == 2 && StrictJson.string(o, "type") == "incident", "unsupported_version")
        StrictJson.uuid(StrictJson.string(o, "event_id")); StrictJson.uuid(StrictJson.string(o, "child_device_id"))
        StrictJson.decimal(o, "incident_revision", true); StrictJson.decimal(o, "occurred_at_ms")
        StrictJson.ensure(StrictJson.string(o, "time_source") == "device_wall_clock" &&
            StrictJson.string(o, "app_package").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) &&
            StrictJson.string(o, "app_package").length <= 255, "bounds")
        val s = obj(o, "scores")
        StrictJson.exact(s, "porn", "hentai", "sexy", "explicit_score")
        fun number(key: String): Double {
            val value = s[key]
            StrictJson.ensure(value.isJsonPrimitive && value.asJsonPrimitive.isNumber, "invalid_scores")
            return value.asDouble
        }
        val scores = ProbabilityScores(number("porn"), number("hentai"), number("sexy"))
        scores.validate()
        val supplied = number("explicit_score")
        StrictJson.ensure(supplied.isFinite() && supplied in 0.0..1.0 && kotlin.math.abs(supplied - scores.explicitScore) <= 1e-6, "invalid_scores")
        val e = obj(o, "evidence")
        StrictJson.exact(e, "route", "frame_count", "span_us", "first_pts_us", "last_pts_us", "complete")
        val route = StrictJson.string(e, "route")
        StrictJson.ensure(route in setOf("explicit", "hentai_dominant") && route == StrictJson.string(o, "category"), "invalid_evidence")
        val first = StrictJson.decimal(e, "first_pts_us")
        val last = StrictJson.decimal(e, "last_pts_us")
        val span = StrictJson.decimal(e, "span_us")
        val requested = obj(o, "requested")
        StrictJson.exact(requested, "action", "stage")
        val stage = action(requested, false)
        StrictJson.ensure(StrictJson.int(o, "stage") == stage && last >= first && last - first == span &&
            e["complete"].isJsonPrimitive && e["complete"].asJsonPrimitive.isBoolean && e["complete"].asBoolean, "invalid_evidence")
        val hentai = scores.hentai > scores.porn && scores.porn < .60
        val count = StrictJson.int(e, "frame_count")
        StrictJson.ensure(count in (if (hentai || stage == 3) 5 else 3)..32 &&
            span >= (if (hentai || stage == 3) 1000000 else 400000), "invalid_evidence")
        StrictJson.ensure(hentai == (route == "hentai_dominant") && (!hentai || scores.hentai > .60), "invalid_evidence")
        StrictJson.ensure(stage != 3 || !hentai && scores.porn >= .60, "hentai_stage3_forbidden")
        val executed = obj(o, "executed")
        StrictJson.exact(executed, "action", "stage", "status", "error")
        val actual = action(executed, true)
        val status = StrictJson.string(executed, "status")
        error(executed)
        StrictJson.ensure(status in setOf("executed", "failed", "rejected", "unknown", "released"), "invalid_envelope")
        StrictJson.ensure(status != "executed" || actual == stage && executed["error"].isJsonNull, "invalid_envelope")
        StrictJson.ensure(status !in setOf("unknown", "released") || actual == 0 && executed["error"].isJsonNull, "invalid_envelope")
        StrictJson.ensure(actual <= stage && (!hentai || actual <= 2), "invalid_envelope")
        val versions = obj(o, "versions")
        StrictJson.exact(versions, "contract", "policy", "policy_revision", "app", "model")
        StrictJson.ensure(StrictJson.string(versions, "contract") == "mentor-parental-v2.0" &&
            StrictJson.string(versions, "policy") == "age-10-15-v1" &&
            StrictJson.string(versions, "app").matches(Regex("[ -~]{1,32}")) &&
            StrictJson.string(versions, "model") in setOf("nsfwjs-mobilenet-v2-onnx"), "invalid_envelope")
        StrictJson.decimal(versions, "policy_revision", true)
    }
    fun control(o: JsonObject, codec: Base64Codec = AndroidBase64) {
        StrictJson.exact(o, "v", "type", "command_id", "control_revision", "issued_at_ms", "expires_at_ms", "operation", "profile", "grant", "challenge")
        StrictJson.ensure(StrictJson.int(o, "v") == 2 && StrictJson.string(o, "type") == "control", "unsupported_version")
        StrictJson.uuid(StrictJson.string(o, "command_id")); StrictJson.decimal(o, "control_revision", true)
        val op = StrictJson.string(o, "operation")
        StrictJson.ensure(op in setOf("set_profile", "unlock", "revoke_pair"), "invalid_envelope")
        if (op == "set_profile") {
            val profile = obj(o, "profile"); StrictJson.exact(profile, "age", "policy_version")
            StrictJson.ensure(StrictJson.int(profile, "age") in 10..15, "invalid_age")
            StrictJson.ensure(StrictJson.string(profile, "policy_version") == "age-10-15-v1", "policy_mismatch")
        } else StrictJson.ensure(o["profile"].isJsonNull, "invalid_envelope")
        if (op == "unlock") {
            val grant = obj(o, "grant"); StrictJson.exact(grant, "event_id", "nonce_b64")
            StrictJson.uuid(StrictJson.string(grant, "event_id"))
            codec.bounded(StrictJson.string(grant, "nonce_b64"), 32, 32)
        } else StrictJson.ensure(o["grant"].isJsonNull, "invalid_envelope")
        if (o["challenge"].isJsonNull) {
            val issued = StrictJson.decimal(o, "issued_at_ms", true); val expires = StrictJson.decimal(o, "expires_at_ms", true)
            StrictJson.ensure(expires > issued && expires - issued <= 300000, "stale")
        } else {
            StrictJson.ensure(o["issued_at_ms"].isJsonNull && o["expires_at_ms"].isJsonNull, "invalid_envelope")
            val challenge = obj(o, "challenge"); StrictJson.exact(challenge, "id", "nonce_b64")
            StrictJson.uuid(StrictJson.string(challenge, "id"))
            codec.bounded(StrictJson.string(challenge, "nonce_b64"), 32, 32)
            if (op == "unlock") StrictJson.ensure(o["grant"].asJsonObject["nonce_b64"] == challenge["nonce_b64"], "invalid_envelope")
        }
    }
    fun receipt(o: JsonObject) {
        StrictJson.exact(o, "v", "type", "command_id", "control_revision", "status", "error", "policy_revision")
        StrictJson.ensure(StrictJson.int(o, "v") == 2 && StrictJson.string(o, "type") == "control_receipt", "unsupported_version")
        StrictJson.uuid(StrictJson.string(o, "command_id")); StrictJson.decimal(o, "control_revision", true)
        StrictJson.decimal(o, "policy_revision"); error(o)
        val status = StrictJson.string(o, "status")
        StrictJson.ensure(status in setOf("applied", "rejected") && (status != "applied" || o["error"].isJsonNull), "invalid_envelope")
    }
}
