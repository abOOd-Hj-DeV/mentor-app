package dev.k230.mentor_app.protection.security

import com.google.gson.JsonObject
import java.security.SecureRandom
import java.util.UUID

enum class PairRole { GUARDIAN, CHILD }

class RetainedPairTranscript internal constructor(
    val pair: TrustedPair,
    offer: JsonObject,
    response: JsonObject,
    confirmation: JsonObject,
    val sha256: String,
) {
    private val signedOffer = offer.deepCopy()
    private val signedResponse = response.deepCopy()
    private val signedConfirmation = confirmation.deepCopy()
    val offer: JsonObject get() = signedOffer.deepCopy()
    val response: JsonObject get() = signedResponse.deepCopy()
    val confirmation: JsonObject get() = signedConfirmation.deepCopy()
    fun role(deviceId: String): PairRole = when (StrictJson.uuid(deviceId)) {
        pair.guardian.deviceId -> PairRole.GUARDIAN
        pair.child.deviceId -> PairRole.CHILD
        else -> throw SecurityFailure("pair_mismatch")
    }
    fun registrationBody(): ByteArray = StrictJson.canonical(JsonObject().apply {
        addProperty("v", 2); addProperty("pair_id", pair.pairId)
        add("guardian", pair.guardian.descriptor); add("child", pair.child.descriptor)
        add("offer", offer.deepCopy()); add("response", response.deepCopy()); add("confirmation", confirmation.deepCopy())
    })
    fun membershipBody(): ByteArray = StrictJson.canonical(JsonObject().apply {
        addProperty("v", 2); addProperty("pair_id", pair.pairId); addProperty("transcript_sha256", sha256)
    })
}

/** Direct scans only. No API accepts a server key directory as a trust source. */
class PairingManager(
    private val local: PeerKeys,
    private val signer: EnvelopeSigner,
    private val store: SealedStore,
    private val bootId: String,
    private val monotonicUs: () -> Long,
    private val wallMs: () -> Long,
    private val codec: Base64Codec = AndroidBase64,
) {
    private fun sign(record: JsonObject): ByteArray {
        val signature = signer.sign("mentor.pair.v2\n".toByteArray() + StrictJson.canonical(record))
        P256Signatures.verify(local.signing, "mentor.pair.v2\n".toByteArray() + StrictJson.canonical(record), signature)
        record.addProperty("signature_b64", codec.encode(signature))
        val bytes = StrictJson.canonical(record)
        StrictJson.ensure(bytes.size <= 2953, "bounds")
        return bytes
    }
    private fun verify(record: JsonObject, peer: PeerKeys) {
        val signature = codec.bounded(StrictJson.string(record, "signature_b64"), 8, 72)
        val unsigned = record.deepCopy().apply { remove("signature_b64") }
        P256Signatures.verify(peer.signing, "mentor.pair.v2\n".toByteArray() + StrictJson.canonical(unsigned), signature)
        StrictJson.ensure(StrictJson.int(record, "v") == 2, "unsupported_version")
    }
    private fun pending(record: JsonObject, offer: JsonObject, response: JsonObject? = null) {
        val prior = store.get("pair_pending")?.let { StrictJson.parse(it) }
        val sameOffer = prior != null && StrictJson.canonical(prior["offer"].asJsonObject)
            .contentEquals(StrictJson.canonical(offer))
        val state = JsonObject().apply {
            add("record", record); add("offer", offer); add("response", response)
            addProperty("boot_id", StrictJson.uuid(bootId))
            addProperty("deadline_us", if (sameOffer) StrictJson.string(prior!!, "deadline_us")
                else Math.addExact(monotonicUs(), 300000000L).toString())
        }
        store.put("pair_pending", StrictJson.canonical(state))
    }
    private fun loadPending(): JsonObject {
        val state = StrictJson.parse(store.get("pair_pending") ?: throw SecurityFailure("pair_missing"))
        StrictJson.ensure(StrictJson.string(state, "boot_id") == bootId &&
            monotonicUs() <= StrictJson.decimal(state, "deadline_us"), "stale")
        return state
    }

    @Synchronized fun createOffer(pairId: String = UUID.randomUUID().toString()): ByteArray {
        StrictJson.ensure(store.get("trusted_pair") == null && pairId == store.pairId, "pair_conflict")
        val offer = JsonObject().apply {
            addProperty("v", 2); addProperty("type", "pair_offer"); addProperty("pair_id", StrictJson.uuid(pairId))
            addProperty("nonce_b64", codec.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            addProperty("expires_at_ms", Math.addExact(wallMs(), 300000L).toString())
            add("guardian", local.descriptor)
        }
        val bytes = sign(offer)
        pending(offer, offer)
        return bytes
    }

    @Synchronized fun scanOffer(bytes: ByteArray): ByteArray {
        StrictJson.ensure(store.get("trusted_pair") == null, "pair_conflict")
        val offer = StrictJson.parse(bytes, 2953)
        StrictJson.exact(offer, "v", "type", "pair_id", "nonce_b64", "expires_at_ms", "guardian", "signature_b64")
        StrictJson.ensure(StrictJson.string(offer, "type") == "pair_offer" &&
            StrictJson.uuid(StrictJson.string(offer, "pair_id")) == store.pairId, "pair_mismatch")
        codec.bounded(StrictJson.string(offer, "nonce_b64"), 32, 32)
        val expiry = StrictJson.decimal(offer, "expires_at_ms", true)
        StrictJson.ensure(expiry >= wallMs() && expiry - wallMs() <= 300000, "stale")
        val guardian = PeerKeys.parse(offer["guardian"].asJsonObject, codec)
        TrustedPair(store.pairId, guardian, local)
        verify(offer, guardian)
        val previous = store.get("pair_pending")?.let { StrictJson.parse(it) }
        if (previous != null && StrictJson.canonical(previous["offer"].asJsonObject)
                .contentEquals(StrictJson.canonical(offer))) {
            loadPending()
            StrictJson.ensure(previous["response"]?.isJsonObject == true, "pair_conflict")
            return StrictJson.canonical(previous["response"].asJsonObject)
        }
        val response = JsonObject().apply {
            addProperty("v", 2); addProperty("type", "pair_response"); addProperty("pair_id", store.pairId)
            addProperty("offer_sha256", StrictJson.sha(StrictJson.canonical(offer))); add("child", local.descriptor)
        }
        val output = sign(response)
        pending(response, offer, response)
        return output
    }

    @Synchronized fun scanResponse(bytes: ByteArray): ByteArray {
        val pending = loadPending()
        val offer = pending["offer"].asJsonObject
        StrictJson.ensure(PeerKeys.parse(offer["guardian"].asJsonObject, codec).signingKid == local.signingKid, "pair_mismatch")
        val response = StrictJson.parse(bytes, 2953)
        StrictJson.exact(response, "v", "type", "pair_id", "offer_sha256", "child", "signature_b64")
        StrictJson.ensure(StrictJson.string(response, "type") == "pair_response" &&
            StrictJson.string(response, "pair_id") == store.pairId &&
            StrictJson.string(response, "offer_sha256") == StrictJson.sha(StrictJson.canonical(offer)), "pair_mismatch")
        val child = PeerKeys.parse(response["child"].asJsonObject, codec)
        verify(response, child)
        TrustedPair(store.pairId, local, child)
        val confirmation = JsonObject().apply {
            addProperty("v", 2); addProperty("type", "pair_confirm"); addProperty("pair_id", store.pairId)
            addProperty("offer_sha256", StrictJson.sha(StrictJson.canonical(offer)))
            addProperty("response_sha256", StrictJson.sha(StrictJson.canonical(response)))
        }
        val output = sign(confirmation)
        pending(confirmation, offer, response)
        // Guardian retains pending until the physical confirmation handoff is acknowledged locally.
        return output
    }

    @Synchronized fun scanConfirmation(bytes: ByteArray): TrustedPair {
        val pending = loadPending()
        val offer = pending["offer"].asJsonObject
        val response = pending["response"].asJsonObject
        val confirmation = StrictJson.parse(bytes, 2953)
        StrictJson.exact(confirmation, "v", "type", "pair_id", "offer_sha256", "response_sha256", "signature_b64")
        StrictJson.ensure(StrictJson.string(confirmation, "type") == "pair_confirm" &&
            StrictJson.string(confirmation, "pair_id") == store.pairId &&
            StrictJson.string(confirmation, "offer_sha256") == StrictJson.sha(StrictJson.canonical(offer)) &&
            StrictJson.string(confirmation, "response_sha256") == StrictJson.sha(StrictJson.canonical(response)), "pair_mismatch")
        val guardian = PeerKeys.parse(offer["guardian"].asJsonObject, codec)
        val child = PeerKeys.parse(response["child"].asJsonObject, codec)
        verify(confirmation, guardian)
        StrictJson.ensure(local.signingKid == child.signingKid || local.signingKid == guardian.signingKid, "pair_mismatch")
        val trust = JsonObject().apply {
            addProperty("pair_id", store.pairId); add("guardian", guardian.descriptor); add("child", child.descriptor)
            addProperty("transcript_sha256", StrictJson.sha(StrictJson.canonical(offer) +
                StrictJson.canonical(response) + StrictJson.canonical(confirmation)))
            add("offer", offer.deepCopy()); add("response", response.deepCopy()); add("confirmation", confirmation.deepCopy())
        }
        val pair = TrustedPair(store.pairId, guardian, child)
        store.put("trusted_pair", StrictJson.canonical(trust))
        store.delete("pair_pending")
        return pair
    }

    fun trusted(): TrustedPair? {
        return retainedTranscript()?.pair
    }

    /** Revalidates the exact physically exchanged records every time before cloud registration. */
    fun retainedTranscript(): RetainedPairTranscript? {
        val obj = StrictJson.parse(store.get("trusted_pair") ?: return null)
        StrictJson.exact(obj, "pair_id", "guardian", "child", "transcript_sha256", "offer", "response", "confirmation")
        val pairId = StrictJson.uuid(StrictJson.string(obj, "pair_id")); StrictJson.ensure(pairId == store.pairId, "pair_mismatch")
        val guardian = PeerKeys.parse(obj["guardian"].asJsonObject, codec); val child = PeerKeys.parse(obj["child"].asJsonObject, codec)
        val offer = obj["offer"].asJsonObject; val response = obj["response"].asJsonObject; val confirmation = obj["confirmation"].asJsonObject
        StrictJson.exact(offer, "v", "type", "pair_id", "nonce_b64", "expires_at_ms", "guardian", "signature_b64")
        StrictJson.exact(response, "v", "type", "pair_id", "offer_sha256", "child", "signature_b64")
        StrictJson.exact(confirmation, "v", "type", "pair_id", "offer_sha256", "response_sha256", "signature_b64")
        StrictJson.ensure(StrictJson.string(offer, "type") == "pair_offer" && StrictJson.string(response, "type") == "pair_response" &&
            StrictJson.string(confirmation, "type") == "pair_confirm" && listOf(offer, response, confirmation).all {
                StrictJson.int(it, "v") == 2 && StrictJson.string(it, "pair_id") == pairId
            }, "pair_mismatch")
        codec.bounded(StrictJson.string(offer, "nonce_b64"), 32, 32); StrictJson.decimal(offer, "expires_at_ms", true)
        StrictJson.ensure(StrictJson.canonical(PeerKeys.parse(offer["guardian"].asJsonObject, codec).descriptor)
            .contentEquals(StrictJson.canonical(guardian.descriptor)) &&
            StrictJson.canonical(PeerKeys.parse(response["child"].asJsonObject, codec).descriptor)
                .contentEquals(StrictJson.canonical(child.descriptor)), "pair_mismatch")
        verify(offer, guardian); verify(response, child); verify(confirmation, guardian)
        val offerHash = StrictJson.sha(StrictJson.canonical(offer)); val responseHash = StrictJson.sha(StrictJson.canonical(response))
        StrictJson.ensure(StrictJson.string(response, "offer_sha256") == offerHash &&
            StrictJson.string(confirmation, "offer_sha256") == offerHash &&
            StrictJson.string(confirmation, "response_sha256") == responseHash, "pair_mismatch")
        val digest = StrictJson.sha(StrictJson.canonical(offer) + StrictJson.canonical(response) + StrictJson.canonical(confirmation))
        StrictJson.ensure(StrictJson.hex(StrictJson.string(obj, "transcript_sha256")) == digest, "invalid_signature")
        return RetainedPairTranscript(TrustedPair(pairId, guardian, child), offer.deepCopy(), response.deepCopy(), confirmation.deepCopy(), digest)
    }

    fun fingerprints(): Map<String, String> {
        val trusted = trusted()
        if (trusted != null) return mapOf("guardian" to trusted.guardian.signingKid, "child" to trusted.child.signingKid)
        val state = loadPending()
        val guardian = PeerKeys.parse(state["offer"].asJsonObject["guardian"].asJsonObject, codec)
        val child = state["response"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("child")
            ?.asJsonObject?.let { PeerKeys.parse(it, codec) }
        return mapOf("guardian" to guardian.signingKid) + (child?.let { mapOf("child" to it.signingKid) } ?: emptyMap())
    }
}
