# Native security/events handoff

`NativeSecurityProvider` composes `protection.security`/`protection.events` from `MentorApplication` and `LayoutService`, independent of Flutter. MainActivity attaches only credential/camera UI and results. Typed Flutter channels cannot set roles, mutate keys or release a shield. Device secure lock, Keystore, allowBackup=false, dependency verification/locking remain mandatory. This is not a claim of measured hardware or production cloud readiness.
Optional `protection.cloud` uses real Firebase Auth/FCM, bounded exact-byte HTTP and WorkManager through the native provider adapter. Defaults start no relay networking. See [native relay integration](NATIVE_RELAY.md).

## Compose authority, never trust Flutter role choice

Use a DEVICE CryptoKeyStore for encrypted bootstrap authority, a stable installation UUID namespace and noBackupFilesDir. Secure unlocked device is mandatory. Only successful AndroidCredentialPrompt device-credential result may persist GUARDIAN. CHILD is established only after SecurityPairingDelegate.scanPairOffer verifies a direct offer; child cannot authenticate as guardian. No fake-success MethodChannel handlers exist: missing integration must fail visibly. Map typed delegation to the fixed `mentor/protection` method/event names without adding role-toggle authority.

After real guardian authentication provision GUARDIAN Keystore material (P-256 signing, AES wrapping with <=120s credential authentication); provision CHILD following validated child bootstrap. Use pair-scoped stores and matching pair UUID, protected local PeerKeys, a native authoritative boot-scoped UUID and monotonic clock. Never take boot/role/key authority from Dart or cloud. PairingManager deadlines are bound to boot UUID and do not extend on identical offers; restart with a fresh offer after expiry/boot.

Guardian scans response, checks fingerprints physically and passes confirmation directly; invoke scanPairConfirmation only after acknowledgment of physical handoff. Child scans the signed confirmation. No server key directory is a trust root. Re-pair requires a new pair UUID; existing pair keys cannot be patched. Keep a single native serialized authority/worker per store.

GuardianAuthorization owns role/auth expiry; on background/lock call `invalidate()` and discard loaded private primitives. Keystore checks defend against lock/key loss but do not replace lifecycle/channel authorization. Do not retain/display decrypted incident details in Flutter/logs while guardian locked.

## Storage and crypto

Private Tink HPKE keysets use **encrypted associated-data** serialization only; no AndroidKeysetManager plaintext fallback. Android Keystore AES-GCM wrapping, Keystore P-256 SHA256withECDSA DER signatures, HPKE RAW X25519/HKDF-SHA256/AES256-GCM. Verify pair/direction/context/signature before loading private HPKE material or decrypting. Production uses AndroidBase64; JVM codec/prompt/in-memory stores are test injection only.

PrivateAtomicStore uses atomic files in noBackupFilesDir. SealedStore binds purpose/pair/record in AEAD, bounds encrypted records to 64KiB and each store to 10,000/32MiB. Corrupt oversized file reads remain bounded. Separate journal/outbox/inbox purposes; no multiprocess writers or plaintext fallback. Surface fixed storage/key errors rather than treating corrupt state as empty/unconfigured. Backup is disabled.

Construct only typed IncidentMetadata from independently validated **actual** execution: clamp(P+H,0..1), Sexy diagnostic only, Hentai stage3 forbidden. Repository journals before sealing; outbox retains canonical retry bytes, validates acknowledgment hash and bounds retries/capacity. Call `pruneUploaded()` for >=24h uploaded journal cleanup; unuploaded items are not silently dropped. Guardian inbox verifies/decrypts before merge, retains greatest revision, rejects rollback/same-revision conflicts. Storage is bounded; age-based inbox cleanup/indexes remain integration work.

Typed sealControl requires guardian authentication; sealReceipt uses typed ControlReceipt. `DirectControls` requires signed pair-pinned, boot-scoped one-use challenge QR, persisted monotone control revisions, and the actual active event for unlock. Wall-clock commands remain rejected without a verified wall-clock source. SET_PROFILE persists a native profile; REVOKE_PAIR does not synthesize an unlock. Receipts are persisted before enqueue and retried byte-exactly. No public free-form/media seal API is exposed.

All synchronous disk/crypto primitives run on bounded single-writer queues, not UI/analysis/enforcement hot paths. Upload/storage failure must never stop local enforcement. Native QR capture is non-exported/FLAG_SECURE; a QR scanner in the opaque help panel can receive the guardian response without exposing protected content. Relay health remains `unconfigured` until actual binding/Auth/configuration. No cloud service is deployed.

## Validation limits

JVM tests exercise real Tink HPKE and JCE signatures, with injected storage/credential prompt: strict metadata/parser, signature-before-private-key-load/decrypt, encrypted-at-rest/AAD/corruption, child/guardian bootstrap, transcript substitution/replay/deadline, byte-identical outbox retries and inbox ordering. Node/JVM consume the same public canonical/signature vector. This does not establish hardware Keystore or real device prompt handling.

API24/API35 instrumented Keystore/QR/lock/key-invalidation, real-device clock/FCM/Doze, HOME/overlay execution, absolute IPC deadline/backpressure/join and first-action HOME deferral belong to native enforcement/integration. Reconcile companion schemas/thresholds/model fixtures across both repos before release.

The C++ branch `devin/1791054238-age-policy-protocol-v2` owns companion.schema.json, seven synthetic wire fixtures, policy traces and a manifest with a `sha256` member. This Mentor branch uses a `files` manifest for E2EE schemas/fixtures. Keep the canonical manifest byte-exact as `companion.manifest.json`, with the E2EE `files` manifest indexing all assets. Canonical copy/parity is pending until the new source commit arrives. The frozen 92-case planning seed, wire fixtures, policy traces and structural/signature expectations are distinct coverage sets.

`CaptureClockBinding`/`CaptureClockRegression` now verify sequential current raw scrcpy PTS against phone System.nanoTime()/1000, not a string or operator flag. Calibration is reset on disconnect/config rejection: ≥3 increasing samples, ≥1s on both clocks, ≤8 samples/3s, lag≤750ms/future≤50ms. Pinned official scrcpy display0, no mirror/crop/custom rotation and trusted shell/root are prerequisites. This is alignment regression, not cryptographic provenance against malicious root. A real phone run with the pinned producer, reconnect/suspend and bounded lag remains a release gate.
