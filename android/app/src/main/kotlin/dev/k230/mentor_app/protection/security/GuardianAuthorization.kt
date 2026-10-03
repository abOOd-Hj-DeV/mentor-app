package dev.k230.mentor_app.protection.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock

enum class DeviceRole { UNCONFIGURED, GUARDIAN, CHILD }

interface CredentialPrompt {
    fun launch(onResult: (Boolean) -> Unit)
}

/** Forward the hosting Activity's result here. No MethodChannel accepts an auth result. */
class AndroidCredentialPrompt(private val activity: Activity) : CredentialPrompt {
    private var callback: ((Boolean) -> Unit)? = null
    val pending: Boolean get() = callback != null
    override fun launch(onResult: (Boolean) -> Unit) {
        StrictJson.ensure(callback == null, "busy")
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        StrictJson.ensure(keyguard.isDeviceSecure, "secure_lock_required")
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent("تأكيد ولي الأمر", "استخدم قفل جهاز ولي الأمر")
            ?: throw SecurityFailure("authentication_unavailable")
        callback = onResult
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) { callback = null; throw e }
    }
    fun onActivityResult(requestCode: Int, resultCode: Int, @Suppress("UNUSED_PARAMETER") data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val pending = callback ?: return true
        callback = null
        pending(resultCode == Activity.RESULT_OK)
        return true
    }
    companion object { const val REQUEST_CODE = 23172 }
}

class GuardianAuthorization(
    private val store: SealedStore,
    private val prompt: CredentialPrompt,
    private val monotonicMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var expiresAtMs: Long? = null
    private var lifecycleGeneration = 0L
    val role: DeviceRole get() = store.get("device_role")?.toString(Charsets.US_ASCII)?.let {
        DeviceRole.valueOf(it)
    } ?: DeviceRole.UNCONFIGURED
    val authenticated: Boolean get() = role == DeviceRole.GUARDIAN && expiresAtMs?.let { monotonicMs() < it } == true

    fun authenticate(result: (Boolean, Long?) -> Unit) {
        StrictJson.ensure(role != DeviceRole.CHILD, "guardian_only")
        val generation = lifecycleGeneration
        prompt.launch { success ->
            if (!success || generation != lifecycleGeneration || role == DeviceRole.CHILD) {
                expiresAtMs = null
                result(false, null)
            } else {
                if (role == DeviceRole.UNCONFIGURED) store.put("device_role", DeviceRole.GUARDIAN.name.toByteArray())
                expiresAtMs = Math.addExact(monotonicMs(), 120000L)
                result(true, expiresAtMs)
            }
        }
    }

    fun requireGuardian() = StrictJson.ensure(authenticated, "guardian_auth_required")
    /** Only invoke after PairingManager has fully validated a scanned offer. */
    internal fun establishChildAfterVerifiedOffer() {
        StrictJson.ensure(role == DeviceRole.UNCONFIGURED || role == DeviceRole.CHILD, "role_conflict")
        store.put("device_role", DeviceRole.CHILD.name.toByteArray())
        invalidate()
    }
    fun invalidate() { lifecycleGeneration++; expiresAtMs = null }
    fun credentialActivityBackgrounded() { expiresAtMs = null }
}

/** Typed integration surface; scans never return camera bytes to Dart. */
class SecurityPairingDelegate(private val auth: GuardianAuthorization, private val pairing: PairingManager) {
    fun createPairOffer(): ByteArray { auth.requireGuardian(); return pairing.createOffer() }
    fun scanPairOffer(scannedQr: ByteArray): ByteArray {
        StrictJson.ensure(auth.role != DeviceRole.GUARDIAN, "role_conflict")
        val response = pairing.scanOffer(scannedQr)
        auth.establishChildAfterVerifiedOffer()
        return response
    }
    fun scanPairResponse(scannedQr: ByteArray): ByteArray {
        auth.requireGuardian()
        return pairing.scanResponse(scannedQr)
    }
    fun scanPairConfirmation(scannedQr: ByteArray): TrustedPair {
        if (auth.role == DeviceRole.GUARDIAN) auth.requireGuardian()
        else StrictJson.ensure(auth.role == DeviceRole.CHILD, "role_conflict")
        return pairing.scanConfirmation(scannedQr)
    }
}
