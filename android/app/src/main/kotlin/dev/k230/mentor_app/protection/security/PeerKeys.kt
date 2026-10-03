package dev.k230.mentor_app.protection.security

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkeKdf
import com.google.crypto.tink.proto.HpkeKem
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.gson.JsonObject
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.math.BigInteger

class PeerKeys private constructor(
    val deviceId: String,
    val authUid: String?,
    val hpkeKid: String,
    val signingKid: String,
    val hpke: KeysetHandle,
    val signing: PublicKey,
    val descriptor: JsonObject,
) {
    companion object {
        fun parse(obj: JsonObject, codec: Base64Codec = AndroidBase64): PeerKeys {
            StrictJson.exact(obj, "device_id", "auth_uid", "hpke_public_keyset_b64", "hpke_kid", "signing_public_spki_b64", "signing_kid")
            val id = StrictJson.uuid(StrictJson.string(obj, "device_id"))
            val uid = if (obj["auth_uid"].isJsonNull) null else StrictJson.string(obj, "auth_uid")
            StrictJson.ensure(uid == null || uid.matches(Regex("[A-Za-z0-9:_-]{1,128}")), "bounds")
            val hpkeBytes = codec.bounded(StrictJson.string(obj, "hpke_public_keyset_b64"), 1, 1024)
            val spki = codec.bounded(StrictJson.string(obj, "signing_public_spki_b64"), 1, 128)
            val hpkeKid = StrictJson.hex(StrictJson.string(obj, "hpke_kid"))
            val signingKid = StrictJson.hex(StrictJson.string(obj, "signing_kid"))
            StrictJson.ensure(StrictJson.sha(hpkeBytes) == hpkeKid && StrictJson.sha(spki) == signingKid, "invalid_envelope")
            val proto = Keyset.parseFrom(hpkeBytes)
            StrictJson.ensure(proto.keyCount == 1, "invalid_envelope")
            val key = proto.getKey(0)
            StrictJson.ensure(key.keyId == proto.primaryKeyId && key.status == KeyStatusType.ENABLED &&
                key.outputPrefixType == OutputPrefixType.RAW &&
                key.keyData.keyMaterialType == KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC &&
                key.keyData.typeUrl == "type.googleapis.com/google.crypto.tink.HpkePublicKey", "invalid_envelope")
            val public = HpkePublicKey.parseFrom(key.keyData.value)
            StrictJson.ensure(public.version == 0 && public.publicKey.size() == 32 &&
                public.params.kem == HpkeKem.DHKEM_X25519_HKDF_SHA256 &&
                public.params.kdf == HpkeKdf.HKDF_SHA256 && public.params.aead == HpkeAead.AES_256_GCM, "invalid_envelope")
            HybridConfig.register()
            val hpke = KeysetHandle.readNoSecret(BinaryKeysetReader.withBytes(hpkeBytes))
            val signing = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as? ECPublicKey
                ?: throw SecurityFailure("invalid_envelope")
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            StrictJson.ensure(signing.params.order == params.order && signing.params.cofactor == params.cofactor &&
                signing.params.curve == params.curve && signing.params.generator == params.generator &&
                signing.encoded.contentEquals(spki), "invalid_envelope")
            return PeerKeys(id, uid, hpkeKid, signingKid, hpke, signing, obj.deepCopy())
        }
    }
}

object P256Signatures {
    private val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    fun validateDer(bytes: ByteArray) {
        StrictJson.ensure(bytes.size in 8..72 && bytes[0].toInt() == 0x30 && bytes[1].toInt() == bytes.size - 2, "invalid_envelope")
        var offset = 2
        repeat(2) {
            StrictJson.ensure(offset + 2 <= bytes.size && bytes[offset++].toInt() == 2, "invalid_envelope")
            val length = bytes[offset++].toInt() and 255
            StrictJson.ensure(length in 1..33 && offset + length <= bytes.size, "invalid_envelope")
            val integer = bytes.copyOfRange(offset, offset + length)
            StrictJson.ensure(integer[0].toInt() >= 0 &&
                (length == 1 || integer[0].toInt() != 0 || integer[1].toInt() < 0), "invalid_envelope")
            val value = BigInteger(integer)
            StrictJson.ensure(value.signum() > 0 && value < order, "invalid_envelope")
            offset += length
        }
        StrictJson.ensure(offset == bytes.size, "invalid_envelope")
    }

    fun verify(key: PublicKey, data: ByteArray, signature: ByteArray) {
        validateDer(signature)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(data)
        StrictJson.ensure(verifier.verify(signature), "invalid_signature")
    }
}

fun interface EnvelopeSigner { fun sign(bytes: ByteArray): ByteArray }
