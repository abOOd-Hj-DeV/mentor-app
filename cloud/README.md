# Mentor ciphertext relay (not deployed)

## Run and test

Use Node 22 and the committed lockfile: `cd cloud/functions && npm ci && npm run typecheck && npm run lint && npm test && npm run schemas:check`. No production credentials are needed for tests.

Start a Firestore emulator with `cloud/firestore.rules`, project **demo-mentor-relay**, on localhost:8080, then run `FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 npm run test:emulator`. This branch was tested with Java 17 and the official cloud-firestore-emulator 1.19.8 artifact. Alternatively run Firebase CLI `emulators:exec --only firestore --project demo-mentor-relay` from cloud with the test command. Newer emulators may require Java 21. Integration tests use actual Firestore transactions and unauthenticated/simulated-authenticated REST deny rules; token verification/KMS/Messaging are injected unit boundaries, not production service tests. Storage rules are supplied but not emulator-tested.

## Provision externally

Read Firebase Functions v2, Admin Auth, Firestore index and Cloud KMS docs before deploying. Configure your own Firebase project and Auth providers outside git. Directly signed QR transcripts pin guardian/child account UIDs and device/signing/HPKE identities. Use restrictive IAM for Functions, Firestore and a dedicated KMS key; apply the deny rules and provided indexes. Clients have no direct read/write path. Configure `PUSH_KMS_KEY` (full cryptoKey resource path), `ENVELOPE_RETENTION_DAYS` (1..30, default 30), and optional `REQUIRE_APP_CHECK`. Empty KMS key fails push registration closed; never use plaintext tokens. Do not weaken an already configured App Check deployment.

No deployment, project ID, ADC/service-account JSON, Firebase credentials or KMS secret is included. Configure monitoring/access logs to exclude bodies and sensitive headers; disable request-body capture. Monitor aggregate counts/fixed error codes only. Set operational abuse/cost limits and load-test global growth, pair registration and tombstone retention before release.

## HTTP

Functions export `mentorRelay`; routes are relative to its URL. Every request needs verified Firebase `Authorization: Bearer <ID token>`; revoked tokens are checked. POSTs require application/json (optional UTF-8 charset), no content-encoding, bounded rawBody and exact fields; duplicates/unknowns are rejected before framework parsed body. All responses use fixed v2 status/error fields.

* POST `/v2/pairs`: `{v,pair_id,guardian,child,offer,response,confirmation}`; guardian UID and direct signatures/hashes must agree, creates pending only.
* POST `/v2/pairs/{pair_id}/accept` or `/v2/pairs/{pair_id}/revoke`: `{v,pair_id,transcript_sha256}`; child consent required for active, guardian may revoke; never replace pinned keys.
* POST `/v2/envelopes`: exact protocol envelope. Sender UID, direction, pinned IDs and signature checked before transaction. Body <=24,576 bytes, decoded ciphertext 49..16,432; 60 new writes/pair/minute, pending <=10,000/32MiB. Canonically identical `(pair_id,message_id)` duplicates succeed; semantic conflicts return 409.
* GET `/v2/envelopes?pair_id=...&limit=1..50&after=...`: recipient only. Opaque cursor <=512 chars binds UID/pair and expires after 10 minutes. Continue non-null cursor even after an empty page.
* DELETE `/v2/envelopes/{message_id}?pair_id=...`: recipient only; remove envelope, retain hash tombstone, reconcile capacity. Re-upload cannot resurrect it; tombstones currently remain for pair lifetime.
* POST `/v2/push-token`: `{v,device_id,token}`; active membership required. KMS ciphertext is AAD-bound to UID/device and stored under UID/device hash; 30-day TTL checked before delivery. No token enters envelope/logs.

FCM is data only: `{v:"2",type:"inbox_changed"}`, normal priority, generic collapse key; no notification text/event IDs. Delivery is best effort **after** persistence, not proof of execution/receipt. Foreground/periodic sync must recover missing, delayed, coalesced and Doze-suppressed pushes. Decrypt locally.

## Privacy, retention, key loss

No server decryptor, media API, plaintext incident schema, classifier or private key exists. Relay still sees pair/device IDs, account/key fingerprints, kind/message ID, arrival times and envelope size: E2EE does not hide traffic metadata. A signed opaque envelope is not proof of a real intervention. Only encrypt typed metadata derived from authoritative native execution; never export screenshots/crops/images/audio/chat text/classifier tensors or log requests/tokens/signatures.

Scheduler removes expired ciphertext in batches <=500 every 30 minutes; fetch filters expiry even before sweep. Delete/sweep reclaim capacity. Tombstones/public transcript metadata persist. Account deletion/global tombstone cleanup require operational design; do not silently reopen replay windows by removing tombstones.

Keystore lock/loss/invalidation or storage corruption must be visible errors, never plaintext fallback. Old inbox/outbox/private material cannot be recovered by server; revoke and physically re-pair with new pair UUID/key epoch. Backups cannot recover Keystore keys. Real-device clocks, lock/lifecycle behavior, FCM/Doze and K230 latency remain integration/device tests.

## Dependencies

Tink remains exactly 1.16.0. Cloud candidate pins were upgraded to mature Admin 14.5.0, Functions 7.4.0, KMS 6.2.1/protobufjs 7.6.6 and compatible lint tooling to eliminate known advisories. Transitive uuid is overridden to 11.1.1 (same v4 API). Final lockfile audit: zero findings. Crypto suite/protocol unchanged. Production SDK compatibility requires integration review: tests exercise Admin Firestore, not deployed Functions/KMS/FCM.
