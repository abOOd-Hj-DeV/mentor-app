package dev.k230.mentor_app.protection.events

import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.security.AndroidBase64
import dev.k230.mentor_app.protection.security.Base64Codec
import dev.k230.mentor_app.protection.security.StrictJson
import dev.k230.mentor_app.protection.security.bounded

enum class ControlOperation(val wire: String) { SET_PROFILE("set_profile"), UNLOCK("unlock"), REVOKE_PAIR("revoke_pair") }

/** Construct only from native state/scanned challenges, never a free-form UI map. */
data class GuardianControl(
    val commandId: String, val revision: Long, val operation: ControlOperation,
    val issuedAtMs: Long?, val expiresAtMs: Long?, val age: Int? = null, val eventId: String? = null,
    val nonce: String? = null, val challengeId: String? = null,
) {
    fun toJson(codec: Base64Codec = AndroidBase64): JsonObject {
        StrictJson.ensure(operation == ControlOperation.SET_PROFILE || age == null, "invalid_envelope")
        StrictJson.ensure(operation == ControlOperation.UNLOCK || eventId == null, "invalid_envelope")
        if (nonce != null) codec.bounded(nonce, 32, 32)
        StrictJson.ensure(operation == ControlOperation.UNLOCK || challengeId != null || nonce == null, "invalid_envelope")
        val o = JsonObject().apply {
            addProperty("v", 2); addProperty("type", "control"); addProperty("command_id", commandId)
            addProperty("control_revision", revision.toString()); addProperty("operation", operation.wire)
            addProperty("issued_at_ms", issuedAtMs?.toString()); addProperty("expires_at_ms", expiresAtMs?.toString())
            add("profile", if (operation == ControlOperation.SET_PROFILE) JsonObject().apply {
                addProperty("age", age); addProperty("policy_version", "age-10-15-v1")
            } else null)
            add("grant", if (operation == ControlOperation.UNLOCK) JsonObject().apply {
                addProperty("event_id", eventId); addProperty("nonce_b64", nonce)
            } else null)
            add("challenge", challengeId?.let { JsonObject().apply {
                addProperty("id", it); addProperty("nonce_b64", nonce)
            } })
        }
        PayloadValidation.control(o, codec)
        return o
    }
}

data class ControlReceipt(val commandId: String, val revision: Long, val applied: Boolean,
                          val error: String?, val policyRevision: Long) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("v", 2); addProperty("type", "control_receipt"); addProperty("command_id", commandId)
        addProperty("control_revision", revision.toString()); addProperty("status", if (applied) "applied" else "rejected")
        addProperty("error", error); addProperty("policy_revision", policyRevision.toString())
        PayloadValidation.receipt(this)
    }
}
