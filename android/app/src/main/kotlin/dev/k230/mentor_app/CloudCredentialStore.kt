package dev.k230.mentor_app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object CloudCredentialStore {
    private const val KEY_ALIAS = "mentor-cloud-device-v1"
    private const val PREFS_NAME = "mentor_cloud_credentials"
    private const val CREDENTIALS_KEY = "encrypted_credentials"

    fun save(context: Context, values: Map<*, *>) {
        val json = JSONObject()
            .put("serverUrl", values["serverUrl"] as? String ?: error("Missing server URL"))
            .put("deviceId", values["deviceId"] as? String ?: error("Missing device ID"))
            .put("deviceToken", values["deviceToken"] as? String ?: error("Missing device token"))
            .put("name", values["name"] as? String ?: error("Missing device name"))
            .put(
                "heartbeatIntervalSeconds",
                (values["heartbeatIntervalSeconds"] as? Number)?.toInt()
                    ?: error("Missing heartbeat interval"),
            )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        val payload = byteArrayOf(iv.size.toByte()) + iv + encrypted
        val stored = Base64.encodeToString(payload, Base64.NO_WRAP)
        check(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(CREDENTIALS_KEY, stored)
                .commit(),
        ) { "Could not persist device credentials" }
    }

    fun load(context: Context): Map<String, Any>? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(CREDENTIALS_KEY, null) ?: return null
        val payload = Base64.decode(stored, Base64.NO_WRAP)
        require(payload.isNotEmpty()) { "Stored device credentials are invalid" }
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize in 1..32 && payload.size > ivSize + 1) {
            "Stored device credentials are invalid"
        }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
        return mapOf(
            "serverUrl" to json.getString("serverUrl"),
            "deviceId" to json.getString("deviceId"),
            "deviceToken" to json.getString("deviceToken"),
            "name" to json.getString("name"),
            "heartbeatIntervalSeconds" to json.getInt("heartbeatIntervalSeconds"),
        )
    }

    fun clear(context: Context) {
        check(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(CREDENTIALS_KEY)
                .commit(),
        ) { "Could not clear device credentials" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}