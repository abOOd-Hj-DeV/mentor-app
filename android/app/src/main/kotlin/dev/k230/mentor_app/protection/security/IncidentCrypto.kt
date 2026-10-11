package dev.k230.mentor_app.protection.security

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.util.UUID
import dev.k230.mentor_app.protection.events.PayloadValidation
import dev.k230.mentor_app.protection.events.IncidentMetadata
import dev.k230.mentor_app.protection.events.GuardianControl
import dev.k230.mentor_app.protection.events.ControlReceipt

data class TrustedPair(val pairId: String, val guardian: PeerKeys, val child: PeerKeys) {
    init {
        StrictJson.uuid(pairId)
        StrictJson.ensure(guardian.deviceId != child.deviceId && guardian.signingKid != child.signingKid &&
            guardian.hpkeKid != child.hpkeKid, "invalid_envelope")
    }
    fun sender(kind: String): PeerKeys = if (kind == "control") guardian else child
    fun recipient(kind: String): PeerKeys = if (kind == "control") child else guardian
}

class VerifiedPayload internal constructor(val kind: String, val messageId: String, val plaintext: JsonObject)

class IncidentCrypto(private val codec: Base64Codec = AndroidBase64) {
    fun sealIncident(metadata: IncidentMetadata, pair: TrustedPair, signer: EnvelopeSigner): ByteArray {
        StrictJson.ensure(metadata.childDeviceId == pair.child.deviceId, "pair_mismatch")
        return seal("incident", metadata.toJson(), pair, signer)
    }
    fun sealControl(control: GuardianControl, pair: TrustedPair, signer: EnvelopeSigner, auth: GuardianAuthorization): ByteArray {
        auth.requireGuardian()
        return seal("control", control.toJson(codec), pair, signer)
    }
    fun sealReceipt(receipt: ControlReceipt, pair: TrustedPair, signer: EnvelopeSigner): ByteArray =
        seal("control_receipt", receipt.toJson(), pair, signer)
    companion object {
        const val SUITE = "HPKE_X25519_HKDF_SHA256_AES256GCM_RAW+ECDSA_P256_SHA256_DER"
        val KINDS = setOf("incident", "control", "control_receipt")
        fun context(obj: JsonObject): ByteArray = (listOf("mentor.envelope.v2", "kind", "pair_id", "sender_id",
            "recipient_id", "message_id", "recipient_hpke_kid", "sender_signing_kid", "suite")
            .mapIndexed { i, key -> if (i == 0) key else StrictJson.string(obj, key) }.joinToString("\n") + "\n")
            .toByteArray(Charsets.US_ASCII)
        fun signatureInput(context: ByteArray, ciphertext: ByteArray): ByteArray =
            "mentor.signature.v2\n".toByteArray(Charsets.US_ASCII) +
                ByteBuffer.allocate(4).putInt(context.size).array() + context +
                ByteBuffer.allocate(4).putInt(ciphertext.size).array() + ciphertext
    }

    // Internal callers provide only validated typed incident/control metadata, never blobs from UI.
    internal fun seal(kind: String, plaintext: JsonObject, pair: TrustedPair, signer: EnvelopeSigner,
                      messageId: String = UUID.randomUUID().toString()): ByteArray {
        StrictJson.ensure(kind in KINDS, "invalid_envelope")
        PayloadValidation.validate(kind, plaintext, codec)
        val bytes = StrictJson.json(plaintext)
        StrictJson.ensure(bytes.size in 1..16384, "bounds")
        val sender = pair.sender(kind)
        val recipient = pair.recipient(kind)
        val envelope = JsonObject().apply {
            addProperty("v", 2); addProperty("kind", kind); addProperty("pair_id", pair.pairId)
            addProperty("sender_id", sender.deviceId); addProperty("recipient_id", recipient.deviceId)
            addProperty("message_id", StrictJson.uuid(messageId)); addProperty("recipient_hpke_kid", recipient.hpkeKid)
            addProperty("sender_signing_kid", sender.signingKid); addProperty("suite", SUITE)
        }
        val context = context(envelope)
        val ciphertext = recipient.hpke.getPrimitive(HybridEncrypt::class.java).encrypt(bytes, context)
        val signature = signer.sign(signatureInput(context, ciphertext))
        P256Signatures.verify(sender.signing, signatureInput(context, ciphertext), signature)
        envelope.addProperty("ciphertext_b64", codec.encode(ciphertext))
        envelope.addProperty("signature_b64", codec.encode(signature))
        return canonicalEnvelope(envelope, pair)
    }

    fun validateEnvelope(bytes: ByteArray, pair: TrustedPair): JsonObject {
        val obj = StrictJson.parse(bytes)
        canonicalEnvelope(obj, pair)
        return obj
    }

    private fun canonicalEnvelope(obj: JsonObject, pair: TrustedPair): ByteArray {
        StrictJson.exact(obj, "v", "kind", "pair_id", "sender_id", "recipient_id", "message_id",
            "recipient_hpke_kid", "sender_signing_kid", "suite", "ciphertext_b64", "signature_b64")
        val kind = StrictJson.string(obj, "kind")
        StrictJson.ensure(StrictJson.int(obj, "v") == 2 && kind in KINDS, "unsupported_version")
        val sender = pair.sender(kind)
        val recipient = pair.recipient(kind)
        StrictJson.uuid(StrictJson.string(obj, "message_id"))
        StrictJson.ensure(StrictJson.string(obj, "pair_id") == pair.pairId &&
            StrictJson.string(obj, "sender_id") == sender.deviceId &&
            StrictJson.string(obj, "recipient_id") == recipient.deviceId &&
            StrictJson.string(obj, "sender_signing_kid") == sender.signingKid &&
            StrictJson.string(obj, "recipient_hpke_kid") == recipient.hpkeKid &&
            StrictJson.string(obj, "suite") == SUITE, "pair_mismatch")
        codec.bounded(StrictJson.string(obj, "ciphertext_b64"), 49, 16432)
        P256Signatures.validateDer(codec.bounded(StrictJson.string(obj, "signature_b64"), 8, 72))
        val bytes = StrictJson.canonical(obj)
        StrictJson.ensure(bytes.size <= 24576, "bounds")
        return bytes
    }

    fun open(bytes: ByteArray, pair: TrustedPair, localDeviceId: String,
             decryptor: HybridDecrypt): VerifiedPayload = open(bytes, pair, localDeviceId) { decryptor }

    fun open(bytes: ByteArray, pair: TrustedPair, localDeviceId: String,
             decryptor: () -> HybridDecrypt): VerifiedPayload {
        val obj = validateEnvelope(bytes, pair)
        val kind = StrictJson.string(obj, "kind")
        StrictJson.ensure(localDeviceId == pair.recipient(kind).deviceId, "pair_mismatch")
        val context = context(obj)
        val ciphertext = codec.bounded(StrictJson.string(obj, "ciphertext_b64"), 49, 16432)
        val signature = codec.bounded(StrictJson.string(obj, "signature_b64"), 8, 72)
        // Authentication precedes private-key use, including on guardian devices.
        P256Signatures.verify(pair.sender(kind).signing, signatureInput(context, ciphertext), signature)
        val plaintext = decryptor().decrypt(ciphertext, context)
        val parsed = StrictJson.parse(plaintext, 16384)
        PayloadValidation.validate(kind, parsed, codec)
        if (kind == "incident") StrictJson.ensure(StrictJson.string(parsed, "child_device_id") == pair.child.deviceId, "pair_mismatch")
        return VerifiedPayload(kind, StrictJson.string(obj, "message_id"), parsed)
    }
}
