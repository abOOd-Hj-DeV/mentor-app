package dev.k230.mentor_app.protection

import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.events.ControlReceipt
import dev.k230.mentor_app.protection.events.PayloadValidation
import dev.k230.mentor_app.protection.security.AndroidBase64
import dev.k230.mentor_app.protection.security.EnvelopeSigner
import dev.k230.mentor_app.protection.security.P256Signatures
import dev.k230.mentor_app.protection.security.SealedStore
import dev.k230.mentor_app.protection.security.StrictJson
import dev.k230.mentor_app.protection.security.TrustedPair
import dev.k230.mentor_app.protection.security.bounded
import dev.k230.mentor_app.protection.security.Base64Codec
import java.security.SecureRandom
import java.util.UUID

internal class DirectControls(private val store: SealedStore, private val pair: TrustedPair,
    private val signer: EnvelopeSigner, private val boot: String, private val now: () -> Long,
    private val activeEvent: () -> String?, private val codec: Base64Codec = AndroidBase64,
    private val grant: (String) -> Boolean = { false }) {
    private fun state() = store.get("control_state")?.let(StrictJson::parse) ?: JsonObject().apply {
        addProperty("revision", "0"); add("profile", null); add("challenge", null)
        add("last_control", null); add("receipt", null); addProperty("revoked", false)
    }
    fun profile(): PolicyProfile? = state()["profile"].takeIf { !it.isJsonNull }?.asJsonObject?.let {
        PolicyProfile(StrictJson.int(it, "age"), StrictJson.decimal(it, "policy_revision", true))
    }
    fun revoked(): Boolean = state()["revoked"].asBoolean
    @Synchronized fun challenge(operation: ControlOperation, eventId: String?): ByteArray {
        requireProtocol(!revoked(), "unpaired")
        requireProtocol(operation != ControlOperation.UNLOCK || eventId != null && eventId == activeEvent(), "wrong_screen")
        requireProtocol(operation == ControlOperation.UNLOCK || eventId == null)
        val issued = now()
        val obj = JsonObject().apply {
            addProperty("v", 2); addProperty("type", "control_challenge"); addProperty("pair_id", pair.pairId)
            addProperty("child_device_id", pair.child.deviceId); addProperty("challenge_id", UUID.randomUUID().toString())
            addProperty("nonce_b64", codec.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            addProperty("operation", operation.wire); addProperty("event_id", eventId)
            addProperty("phone_boot_id", boot); addProperty("issued_at_us", issued.toString())
            addProperty("expires_at_us", Math.addExact(issued, 300_000_000L).toString())
        }
        val input = "mentor.control_challenge.v2\n".toByteArray() + StrictJson.canonical(obj)
        val signature = signer.sign(input)
        P256Signatures.verify(pair.child.signing, input, signature)
        obj.addProperty("signature_b64", codec.encode(signature))
        val bytes = StrictJson.canonical(obj)
        requireProtocol(bytes.size <= 2953)
        val next = state(); next.add("challenge", obj); store.put("control_state", StrictJson.json(next))
        return bytes
    }
    @Synchronized fun apply(control: JsonObject): ControlReceipt {
        PayloadValidation.control(control, codec)
        val next = state()
        val id = StrictJson.string(control, "command_id")
        val revision = StrictJson.decimal(control, "control_revision", true)
        if (revision == StrictJson.decimal(next, "revision") && next["last_control"] == control) {
            val r = next["receipt"].asJsonObject
            return ControlReceipt(id, revision, StrictJson.string(r, "status") == "applied",
                r["error"].takeIf { !it.isJsonNull }?.asString, StrictJson.decimal(r, "policy_revision"))
        }
        requireProtocol(!next["revoked"].asBoolean, "unpaired")
        requireProtocol(revision > StrictJson.decimal(next, "revision"), "stale")
        // A wall clock cannot authorize an offline control. There is no cloud-clock fallback.
        requireProtocol(!control["challenge"].isJsonNull, "clock_unverified")
        val challenge = next["challenge"].takeIf { it.isJsonObject }?.asJsonObject ?: throw ProtocolFailure("stale")
        val binding = control["challenge"].asJsonObject
        requireProtocol(StrictJson.string(challenge, "phone_boot_id") == boot &&
            now() <= StrictJson.decimal(challenge, "expires_at_us") &&
            now() >= StrictJson.decimal(challenge, "issued_at_us") &&
            binding["id"] == challenge["challenge_id"] && binding["nonce_b64"] == challenge["nonce_b64"] &&
            control["operation"] == challenge["operation"], "stale")
        val operation = StrictJson.string(control, "operation")
        if (operation == "unlock") requireProtocol(control["grant"].asJsonObject["event_id"] == challenge["event_id"] &&
            challenge["event_id"].asString == activeEvent(), "wrong_screen")
        var policyRevision = profile()?.revision ?: 0
        if (operation == "set_profile") {
            val age = StrictJson.int(control["profile"].asJsonObject, "age")
            val profile = PolicyProfile(age, Math.addExact(policyRevision, 1))
            policyRevision = profile.revision
            next.add("profile", JsonObject().apply {
                addProperty("age", age); addProperty("policy_revision", policyRevision.toString())
            })
        }
        if (operation == "revoke_pair") next.addProperty("revoked", true)
        val receipt = ControlReceipt(id, revision, operation != "unlock",
            if (operation == "unlock") "action_failed" else null, policyRevision)
        next.addProperty("revision", revision.toString()); next.add("challenge", null)
        next.add("last_control", control); next.add("receipt", receipt.toJson())
        store.put("control_state", StrictJson.json(next))
        if (operation == "unlock" && grant(StrictJson.string(control["grant"].asJsonObject, "event_id"))) {
            val confirmed = receipt.copy(applied = true, error = null)
            next.add("receipt", confirmed.toJson()); store.put("control_state", StrictJson.json(next))
            return confirmed
        }
        return receipt
    }
    companion object {
        fun verifyChallenge(bytes: ByteArray, pair: TrustedPair, codec: Base64Codec = AndroidBase64): JsonObject {
            val o = StrictJson.parse(bytes, 2953)
            StrictJson.exact(o, "v", "type", "pair_id", "child_device_id", "challenge_id", "nonce_b64", "operation",
                "event_id", "phone_boot_id", "issued_at_us", "expires_at_us", "signature_b64")
            requireProtocol(StrictJson.int(o, "v") == 2 && StrictJson.string(o, "type") == "control_challenge")
            requireProtocol(StrictJson.string(o, "pair_id") == pair.pairId &&
                StrictJson.string(o, "child_device_id") == pair.child.deviceId, "invalid_pairing")
            StrictJson.uuid(StrictJson.string(o, "challenge_id")); StrictJson.uuid(StrictJson.string(o, "phone_boot_id"))
            codec.bounded(StrictJson.string(o, "nonce_b64"), 32, 32)
            val op = StrictJson.string(o, "operation")
            requireProtocol(op in setOf("set_profile", "unlock", "revoke_pair"))
            if (op == "unlock") StrictJson.uuid(StrictJson.string(o, "event_id")) else requireProtocol(o["event_id"].isJsonNull)
            requireProtocol(StrictJson.decimal(o, "expires_at_us") - StrictJson.decimal(o, "issued_at_us") == 300_000_000L)
            val unsigned = o.deepCopy().apply { remove("signature_b64") }
            P256Signatures.verify(pair.child.signing, "mentor.control_challenge.v2\n".toByteArray() + StrictJson.canonical(unsigned),
                codec.bounded(StrictJson.string(o, "signature_b64"), 8, 72))
            return o
        }
    }
}
