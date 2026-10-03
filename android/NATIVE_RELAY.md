# Optional native E2EE relay

The supplemental `protection.cloud` package uses actual Firebase Auth, Firebase Messaging, OkHttp and WorkManager. It never invents an authenticated UID, decrypts in HTTP code, uploads media/plaintext, patches QR records or takes cloud keys as authority. It requires no Flutter/MainActivity changes. Application/service bootstrap and the concrete security/repository adapter belong to the Mentor integrator.

## Build/public configuration

`app/src/main/res/values/mentor_cloud.xml` has empty public identifiers and anonymous Auth disabled. No relay/Auth/FCM networking or work is started with those defaults; local enforcement and encrypted outbox continue independently. Partial/malformed configuration fails visibly. Configure in a trusted Android build resource overlay, not Dart, preferences, incoming FCM or remote config:

```xml
<resources>
    <string name="mentor_relay_url" translatable="false">https://REGION-PROJECT.cloudfunctions.net/mentorRelay</string>
    <string name="mentor_firebase_application_id" translatable="false">PUBLIC_ANDROID_APPLICATION_ID</string>
    <string name="mentor_firebase_api_key" translatable="false">PUBLIC_FIREBASE_API_KEY</string>
    <string name="mentor_firebase_project_id" translatable="false">PUBLIC_PROJECT_ID</string>
    <string name="mentor_firebase_sender_id" translatable="false">PUBLIC_SENDER_ID</string>
    <string name="mentor_relay_adapter_factory" translatable="false">your.native.AppRelayFactory</string>
    <bool name="mentor_firebase_anonymous_auth">false</bool>
</resources>
```

Use HTTPS without a trailing slash/query/userinfo. Identifiers are public Firebase Android configuration, not service-account/private credentials. No Google Services Gradle plugin or checked-in google-services.json is necessary: the default FirebaseApp is initialized from these resources and an existing default app must match all identifiers. FCM auto-init and Analytics collection are disabled in the manifest; FCM tokens are requested explicitly only during configured sync. Token rotation schedules generic recovery and periodic sync registers the current token again.

For a dedicated project/provider, enable anonymous Auth in Firebase and explicitly set `mentor_firebase_anonymous_auth=true`. Otherwise a real signed-in `FirebaseAuth.currentUser` is required. Acquire `FirebaseRelayIdentity(...).credentials(false).uid` **before** constructing cloud-capable public PeerKeys/QR descriptors. Do not use a local UUID or guess a Firebase UID. Never rewrite a signed offline descriptor to add an account. Changing/loss of Auth UID requires a new physical pairing; local enforcement must not depend on cloud availability.

## Exact integration interface

All types below are in `dev.k230.mentor_app.protection.cloud`, except existing security/events primitives:

```kotlin
data class RelayBinding(
    val pair: TrustedPair,
    val localDeviceId: String,
    val transcript: RetainedPairTranscript,
)
data class RelayAcknowledgment(val messageId: String, val status: String, val sha256: String)
enum class RelayPairPhase { UNREGISTERED, PENDING, ACTIVE, REVOKED }
enum class RelayState { UNCONFIGURED, AUTHENTICATING, PAIR_PENDING, ACTIVE,
    ACTIVE_WITHOUT_PUSH, BACKOFF, REVOKED, ERROR }

interface NativeRelayAdapter {
    fun binding(): RelayBinding?
    fun pending(nowMs: Long, limit: Int): List<OutboxEntry>
    fun acknowledge(ack: RelayAcknowledgment)
    fun failedAttempt(messageId: String, nowMs: Long): Long
    fun receiveEnvelope(canonicalEnvelope: ByteArray)
    fun cursor(): String?
    fun storeCursor(cursor: String?)
    fun pairPhase(): RelayPairPhase
    fun storePairPhase(phase: RelayPairPhase)
    fun appCheckToken(forceRefresh: Boolean): String? = null
    fun relayState(state: RelayState, fixedError: String? = null) {}
}
fun interface NativeRelayAdapterFactory {
    fun create(context: Context): NativeRelayAdapter
}
```

- `binding()` returns an authoritative paired binding or null while not paired. `PairingManager.retainedTranscript()` decrypts and revalidates original signed offer/response/confirmation and their digest; record getters return defensive copies. Existing old trusted-pair records without transcripts fail closed and require re-pairing. No fabricated/migrated signature is permitted.
- `pending` should call `EncryptedOutbox.readyEntries(nowMs, limit)`. Native outbox owns exact canonical bytes and persisted retry times. Uploads pass those bytes unchanged; ACK must match message ID, accepted status and SHA256 before local deletion. `acknowledge` maps to native repository/journal bookkeeping plus `EncryptedOutbox.acknowledge`, not just a UI success event.
- `receiveEnvelope` must verify/decrypt and **durably** merge incident revisions or replay-safely apply control/receipt before returning. Exceptions (lock, credential expiry, corruption, unavailable keys, storage failure) prevent remote DELETE. Durable duplicate/older revision handling may return normally. The HTTP worker has no plaintext/media API. Guardian decrypt/sign/control remain credential-gated; background Auth is not guardian device-credential authorization.
- Delegate cursor/phase methods to `EncryptedRelayState` using a separate pair-scoped, noBackupFilesDir `SealedStore` purpose such as `cloud_state`. Do not use plaintext SharedPreferences or reset failed reads to an empty state. Phase is relay bookkeeping, never key/enforcement authority.
- Optional `appCheckToken` supplies a **real** token from a separately configured native provider if the server already requires App Check. No token is invented and configured server controls are not weakened. App Check is not a new mandatory rollout gate; default server configuration does not require it.
- `relayState` should publish fixed native health to the existing authoritative event/state mechanism without logging bodies, tokens or decrypted data. Missing configured adapter/factory yields failed WorkInfo, not pretend success.

## Application/service lifecycle, not UI ownership

Implement a no-argument `AppRelayFactory : NativeRelayAdapterFactory` and name it in the public resource. It must rebuild binding/repository factories lazily from native encrypted stores after process death; it must not bypass guardian locks. Call `NativeRelayRuntime.start(applicationContext)` from Application/service bootstrap. The factory allows periodic/FCM WorkManager callbacks to reconstruct the backend without a running Activity. If a shrinker is enabled, retain the resource-named factory/no-argument constructor (`-keep class * implements dev.k230.mentor_app.protection.cloud.NativeRelayAdapterFactory { public <init>(); }`).

Alternatively `NativeRelayRuntime.install(context, adapter)` registers an existing native adapter and schedules work. Still supply the factory for process-death recovery; an in-memory install alone cannot reconstruct the backend after restart.

Call `NativeRelayRuntime.foreground(context)` on foreground recovery and `requestSync(context)` after native outbox changes. `requestRevoke(context)` queues the authenticated guardian relay revoke; only invoke it after the native guardian authorization/control flow permits it. A matched revoke response persists REVOKED and stops future relay sync; it does not replace local signed revocation/policy handling.

One-time work is unique/coalesced, periodic recovery has a 15-minute minimum and network constraints, and failures use WorkManager exponential backoff from 30 seconds. One-time, periodic and revoke jobs share a nonblocking single-flight lock; contending workers retry rather than waiting indefinitely. Stores are single-process. No WorkManager metadata contains event IDs or decrypted detail. Only exact data-only `{"v":"2","type":"inbox_changed"}` FCM triggers sync; notification payloads/additional fields are ignored. Push loss/Doze/FCM errors are recovered by foreground/periodic HTTP; FCM token failure produces ACTIVE_WITHOUT_PUSH without preventing ciphertext sync.

## Pairing validity and bounded recovery

Guardian registers the original transcript during the five-minute offer window; child accepts the exact retained digest. Registration phase is persisted, so normal later sync **does not re-register expired QR records**. Pending guardian registration becomes active by an authenticated inbox probe after child acceptance. If registration ACK was lost, an authenticated active-pair probe can recover without changing local trust. An offline pair that never registered during that window cannot be registered later with expired records: expose `pair_registration_expired` and perform fresh physical pairing (new pair UUID and real signed-in UIDs). This preserves the current frozen server validity rule.

HTTP requests have a 20-second absolute OkHttp call timeout including streamed response, plus connect/read/write timeouts, no redirects, no automatic replay and bounded bodies. Native envelopes remain <=24,576 bytes, responses <=1MiB, fetch pages <=32 envelopes (within server limit 50 and existing StrictJson array cap), at most eight pages per run, and a four-minute between-request sync budget. A request finishing across that budget is still bounded by its call timeout. Empty pages with continuation are followed and persisted; continuation exhaustion schedules backoff recovery. Expired recipient-bound cursors are cleared once and safely restarted. Receive/DELETE failures never advance past unprocessed entries.

## Validation and remaining integration gates

JVM tests use real local MockWebServer HTTP and public signed fixture bytes, not a production fake transport. They cover exact-body upload/ACK mismatch, UID mismatch before HTTP, empty-page cursor continuation, receipt persistence before delete, expired cursor restart, registration phase after QR expiry, child accept/guardian revoke, retry retention, public Unconfigured configuration, encrypted state restart/corruption, strict generic FCM filtering, trickling response and missing ACK deadlines, and oversized responses. Existing crypto/protocol assets and cloud routes/schemas are unchanged.

Actual Firebase Auth provider/device configuration, FCM delivery/token rotation, WorkManager process-death/Doze behavior, guardian Keystore lock/invalidation, API24/API35 and full signed control execution need emulator/real-device integration verification. This branch does not implement Application security/repository bootstrap, Flutter/MainActivity wiring, production deployment or credentials. No UI test or production Firebase deployment was performed.

Official APIs consulted: [Firebase Android initialization](https://firebase.google.com/docs/android/learn-more#multiple-projects), [anonymous Auth](https://firebase.google.com/docs/auth/android/anonymous-auth), [ID tokens](https://firebase.google.com/docs/auth/admin/verify-id-tokens#retrieve_id_tokens_on_clients), [FCM client](https://firebase.google.com/docs/cloud-messaging/android/client), [FCM reception](https://firebase.google.com/docs/cloud-messaging/android/receive), [WorkManager work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work), [Firebase releases](https://firebase.google.com/support/release-notes/android). Dependencies use Firebase BoM 33.16.0, WorkManager 2.10.1, OkHttp/MockWebServer 4.12.0 with locked versions and SHA256 verification; Tink suite/dependency is unchanged.
