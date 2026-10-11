package dev.k230.mentor_app.protection.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Provision explicitly, never replace lost/inaccessible keys during reads. */
class CryptoKeyStore(private val context: Context, private val role: Role) {
    enum class Role { DEVICE, CHILD, GUARDIAN }
    private val prefix = "mentor.v2.${role.name.lowercase()}"
    private val store: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean = store.containsAlias("$prefix.wrap") && store.containsAlias("$prefix.sign")

    private fun requireUnlocked() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        StrictJson.ensure(keyguard.isDeviceSecure && !keyguard.isDeviceLocked, "locked")
    }

    private fun builder(alias: String, purposes: Int): KeyGenParameterSpec.Builder {
        val builder = KeyGenParameterSpec.Builder(alias, purposes)
        if (role == Role.GUARDIAN) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(120,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(120)
            }
        } else if (Build.VERSION.SDK_INT >= 28) builder.setUnlockedDeviceRequired(true)
        return builder
    }

    fun provision() {
        requireUnlocked()
        StrictJson.ensure(context.getSystemService(KeyguardManager::class.java).isDeviceSecure, "secure_lock_required")
        StrictJson.ensure(!store.containsAlias("$prefix.wrap") && !store.containsAlias("$prefix.sign"), "key_conflict")
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(builder("$prefix.wrap", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(builder("$prefix.sign", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generateKeyPair()
        }
    }

    fun wrappingAead(): Aead {
        val key = store.getKey("$prefix.wrap", null) as? SecretKey ?: throw SecurityFailure("key_lost")
        return object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                requireUnlocked()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                StrictJson.ensure(cipher.iv.size == 12, "storage_failed")
                cipher.updateAAD(associatedData)
                return cipher.iv + cipher.doFinal(plaintext)
            }
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
                requireUnlocked()
                StrictJson.ensure(ciphertext.size >= 28, "storage_failed")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
                cipher.updateAAD(associatedData)
                return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
            }
        }
    }

    fun signer(): EnvelopeSigner = EnvelopeSigner { bytes ->
        requireUnlocked()
        val key = store.getKey("$prefix.sign", null) as? PrivateKey ?: throw SecurityFailure("key_lost")
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes); sign() }
    }
    fun signingSpki(): ByteArray = store.getCertificate("$prefix.sign")?.publicKey?.encoded
        ?: throw SecurityFailure("key_lost")

    fun backing(): String {
        val key = store.getKey("$prefix.wrap", null) as? SecretKey ?: throw SecurityFailure("key_lost")
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        @Suppress("DEPRECATION")
        return if (info.isInsideSecureHardware) "hardware" else "software"
    }

    companion object {
        fun newHpke(): KeysetHandle {
            HybridConfig.register()
            return KeysetHandle.generateNew(HpkeParameters.builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .setVariant(HpkeParameters.Variant.NO_PREFIX).build())
        }
        fun wrapHpke(handle: KeysetHandle, aead: Aead, pairId: String): ByteArray {
            val out = ByteArrayOutputStream()
            handle.writeWithAssociatedData(BinaryKeysetWriter.withOutputStream(out), aead, keysetAad(pairId))
            return out.toByteArray()
        }
        fun readHpke(bytes: ByteArray, aead: Aead, pairId: String): KeysetHandle {
            StrictJson.ensure(bytes.size in 1..8192, "bounds")
            HybridConfig.register()
            return KeysetHandle.readWithAssociatedData(BinaryKeysetReader.withBytes(bytes), aead, keysetAad(pairId))
        }
        fun publicHpke(handle: KeysetHandle): ByteArray = ByteArrayOutputStream().run {
            handle.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(this))
            toByteArray()
        }
        private fun keysetAad(pairId: String) = "mentor.storage.v2\nhpke\n${StrictJson.uuid(pairId)}\n".toByteArray()
    }
}
