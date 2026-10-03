package dev.k230.mentor_app.protection

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.google.crypto.tink.HybridDecrypt
import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.events.*
import dev.k230.mentor_app.protection.cloud.*
import dev.k230.mentor_app.protection.security.*
import dev.k230.mentor_app.protection.security.DeviceRole as StoredRole
import dev.k230.mentor_app.protection.events.ControlOperation as SignedOperation
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object NativeSecurityProvider {
    private var instance: NativeSecurityDelegate? = null
    @Synchronized fun install(context: Context): NativeSecurityDelegate = instance ?: NativeSecurityDelegate(
        context.applicationContext).also {
            instance = it; ProtectionIntegration.security = it
            ProtectionIntegration.receiveProtectedControl = it::protectedControl
            it.initialize()
            NativeRelayRuntime.install(context.applicationContext, it.relayAdapter)
        }
}

internal class NativeSecurityDelegate(private val context: Context) : GuardianSecurityDelegate {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private val prefs = context.getSharedPreferences("mentor_install_public", Context.MODE_PRIVATE)
    private val installId = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
        requireProtocol(prefs.edit().putString("id", it).commit(), "storage_failed")
    }
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val boot = if (bootCount >= 0 && prefs.getInt("boot", -2) == bootCount)
        prefs.getString("boot_id", null) ?: UUID.randomUUID().toString()
        else UUID.randomUUID().toString().also {
            if (bootCount >= 0) requireProtocol(prefs.edit().putInt("boot", bootCount).putString("boot_id", it).commit(), "storage_failed")
        }
    private val deviceKeys = CryptoKeyStore(context, CryptoKeyStore.Role.DEVICE)
    private var authority: SealedStore? = null
    private var auth: GuardianAuthorization? = null
    private var activity: Activity? = null
    private var credential: AndroidCredentialPrompt? = null
    private var scanner: NativeQr? = null
    private var pairing: PairingManager? = null
    private var pairStore: SealedStore? = null
    private var roleKeys: CryptoKeyStore? = null
    private var local: PeerKeys? = null
    private var controls: DirectControls? = null
    private var outbox: EncryptedOutbox? = null
    private var incidents: IncidentRepository? = null
    private var inbox: EncryptedInbox? = null
    private var relayStore: EncryptedRelayState? = null
    @Volatile private var cloudHealth = "unconfigured"
    @Volatile private var current = SecurityState()
    @Volatile private var currentProfile: PolicyProfile? = null
    @Volatile private var executionJournal: ExecutionJournal? = null
    @Volatile private var authDeadline = 0L
    @Volatile private var storageError: String? = null
    private fun nowUs() = System.nanoTime() / 1000
    private fun <T> relayCall(action: () -> T): T {
        requireProtocol(Looper.myLooper() != Looper.getMainLooper(), "busy")
        val task = java.util.concurrent.FutureTask<T> {
            bootstrap()
            if (auth!!.role == StoredRole.GUARDIAN) auth!!.requireGuardian()
            action().also { refresh() }
        }
        try {
            worker.execute(task)
            return task.get(35, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception ?: SecurityFailure("storage_failed"))
        } catch (_: java.util.concurrent.TimeoutException) {
            task.cancel(false); throw SecurityFailure("busy")
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            throw SecurityFailure("busy")
        } finally { main.post { ProtectionIntegration.onDisplayStateChanged?.invoke() } }
    }
    val relayAdapter: NativeRelayAdapter = object : NativeRelayAdapter {
        override fun binding(): RelayBinding? = relayCall {
            val transcript = pairing?.retainedTranscript() ?: return@relayCall null
            if (controls?.revoked() == true || transcript.pair.guardian.authUid == null || transcript.pair.child.authUid == null)
                return@relayCall null
            val peer = local ?: throw SecurityFailure("unpaired")
            val pinned = if (auth!!.role == StoredRole.GUARDIAN) transcript.pair.guardian else transcript.pair.child
            requireProtocol(StrictJson.canonical(peer.descriptor).contentEquals(StrictJson.canonical(pinned.descriptor)), "pair_mismatch")
            RelayBinding(transcript.pair, peer.deviceId, transcript)
        }
        override fun pending(nowMs: Long, limit: Int) = relayCall { outbox!!.readyEntries(nowMs, limit) }
        override fun acknowledge(ack: RelayAcknowledgment) = relayCall {
            if (current.role == DeviceRole.CHILD) incidents!!.acknowledgeMessage(ack.messageId, ack.status, ack.sha256)
            else outbox!!.acknowledge(ack.messageId, ack.status, ack.sha256)
        }
        override fun failedAttempt(messageId: String, nowMs: Long) = relayCall { outbox!!.failedAttempt(messageId, nowMs) }
        override fun receiveEnvelope(canonicalEnvelope: ByteArray) = relayCall {
            val pair = pairing!!.trusted() ?: throw SecurityFailure("unpaired")
            val envelope = IncidentCrypto().validateEnvelope(canonicalEnvelope, pair)
            when (StrictJson.string(envelope, "kind")) {
                "incident" -> { requireProtocol(current.role == DeviceRole.GUARDIAN, "guardian_only"); inbox!!.receive(canonicalEnvelope); Unit }
                "control" -> receiveControl(canonicalEnvelope)
                "control_receipt" -> receiveReceipt(canonicalEnvelope)
                else -> throw SecurityFailure("invalid_envelope")
            }
        }
        override fun cursor() = relayCall { relayStore!!.cursor() }
        override fun storeCursor(cursor: String?) = relayCall { relayStore!!.storeCursor(cursor) }
        override fun pairPhase() = relayCall { relayStore!!.pairPhase() }
        override fun storePairPhase(phase: RelayPairPhase) = relayCall { relayStore!!.storePairPhase(phase) }
        override fun relayState(state: RelayState, fixedError: String?) {
            cloudHealth = when (state) {
                RelayState.UNCONFIGURED, RelayState.REVOKED -> "unconfigured"
                RelayState.ACTIVE -> "online"
                RelayState.ACTIVE_WITHOUT_PUSH, RelayState.AUTHENTICATING, RelayState.PAIR_PENDING, RelayState.BACKOFF -> "offline"
                RelayState.ERROR -> if (fixedError in setOf("identity_mismatch", "unauthenticated", "relay_unconfigured")) "auth_error" else "offline"
            }
            main.post { ProtectionIntegration.onDisplayStateChanged?.invoke() }
        }
    }
    private fun store(namespace: String, id: String, keys: CryptoKeyStore) = SealedStore(
        PrivateAtomicStore(context, namespace), keys.wrappingAead(), id, namespace)
    private fun submit(reply: ((SecurityReply) -> Unit)? = null, action: () -> Any?) {
        try { worker.execute {
            val result = try { val value = action(); refresh(); SecurityReply.Value(value) }
                catch (e: Exception) {
                    if (code(e) in setOf("storage_failed", "key_lost", "locked", "secure_lock_required")) storageError = code(e)
                    SecurityReply.Error(code(e))
                }
            main.post { ProtectionIntegration.onDisplayStateChanged?.invoke(); reply?.invoke(result) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { reply?.invoke(SecurityReply.Error("busy")) }
    }
    fun initialize() = submit { bootstrap(); null }
    private fun bootstrap() {
        if (authority == null) {
            val disk = PrivateAtomicStore(context, "authority")
            if (!deviceKeys.exists()) {
                requireProtocol(disk.names().isEmpty(), "key_lost"); deviceKeys.provision()
            }
            authority = SealedStore(disk, deviceKeys.wrappingAead(), installId, "authority")
            auth = GuardianAuthorization(authority!!, object : CredentialPrompt {
                override fun launch(onResult: (Boolean) -> Unit) {
                    val prompt = credential ?: throw SecurityFailure("authentication_unavailable")
                    main.post { try { prompt.launch(onResult) } catch (_: Exception) { onResult(false) } }
                }
            })
        }
        if (pairing == null && (auth!!.role != StoredRole.GUARDIAN || auth!!.authenticated)) authority!!.get("local_pair")?.let {
            val identity = StrictJson.parse(it)
            loadPair(StrictJson.string(identity, "pair_id"), StrictJson.string(identity, "device_id"), false)
        }
        refresh()
    }
    private fun loadPair(id: String, deviceId: String, provision: Boolean) {
        val role = auth!!.role
        val provisionUid = if (provision) RelayConfigurationLoader.load(context)?.let {
            FirebaseRelayIdentity(context, it).credentials(false).uid
        } else null
        val keys = CryptoKeyStore(context, if (role == StoredRole.GUARDIAN) CryptoKeyStore.Role.GUARDIAN else CryptoKeyStore.Role.CHILD)
        if (provision && !keys.exists()) keys.provision()
        requireProtocol(keys.exists(), "key_lost")
        val ps = store("pair", id, keys)
        val wrapped = ps.get("hpke")
        val hpke = if (wrapped == null && provision) CryptoKeyStore.newHpke().also {
            ps.put("hpke", CryptoKeyStore.wrapHpke(it, keys.wrappingAead(), id))
        } else CryptoKeyStore.readHpke(wrapped ?: throw SecurityFailure("key_lost"), keys.wrappingAead(), id)
        val publicHpke = CryptoKeyStore.publicHpke(hpke); val signing = keys.signingSpki()
        val persistedPeer = ps.get("local_peer")?.let { PeerKeys.parse(StrictJson.parse(it)) }
        val uid = persistedPeer?.authUid ?: if (persistedPeer == null) provisionUid else null
        val peer = PeerKeys.parse(JsonObject().apply {
            addProperty("device_id", deviceId); addProperty("auth_uid", uid)
            addProperty("hpke_public_keyset_b64", AndroidBase64.encode(publicHpke)); addProperty("hpke_kid", StrictJson.sha(publicHpke))
            addProperty("signing_public_spki_b64", AndroidBase64.encode(signing)); addProperty("signing_kid", StrictJson.sha(signing))
        })
        if (persistedPeer != null) requireProtocol(StrictJson.canonical(peer.descriptor)
            .contentEquals(StrictJson.canonical(persistedPeer.descriptor)), "key_lost")
        else ps.put("local_peer", StrictJson.canonical(peer.descriptor))
        roleKeys = keys; pairStore = ps; local = peer
        pairing = PairingManager(peer, keys.signer(), ps, boot, ::nowUs, System::currentTimeMillis)
        authority!!.put("local_pair", StrictJson.json(JsonObject().apply {
            addProperty("pair_id", id); addProperty("device_id", deviceId)
        }))
        composeTrusted()
    }
    private fun decryptor(): HybridDecrypt {
        val ps = pairStore ?: throw SecurityFailure("unpaired")
        return CryptoKeyStore.readHpke(ps.get("hpke") ?: throw SecurityFailure("key_lost"),
            roleKeys!!.wrappingAead(), ps.pairId).getPrimitive(HybridDecrypt::class.java)
    }
    private fun composeTrusted() {
        val pair = pairing?.trusted() ?: return
        val keys = roleKeys!!; val crypto = IncidentCrypto()
        val controlStore = store("controls", pair.pairId, keys)
        controls = DirectControls(controlStore, pair, keys.signer(), boot, ::nowUs,
            { ProtectionIntegration.activeEvent() }, grant = ::grantProtection)
        outbox = EncryptedOutbox(store("outbox", pair.pairId, keys), pair, crypto) { controls?.revoked() == false }
        relayStore = EncryptedRelayState(store("cloud_state", pair.pairId, keys))
        if (auth!!.role == StoredRole.CHILD) {
            executionJournal = SealedExecutionJournal(store("execution", pair.pairId, keys), ::nowUs, boot)
            incidents = IncidentRepository(store("incidents", pair.pairId, keys), outbox!!, pair, crypto, keys.signer())
        } else inbox = EncryptedInbox(store("inbox", pair.pairId, keys), pair, crypto, auth!!, ::decryptor)
        refresh()
        NativeRelayRuntime.requestSync(context)
    }
    private fun refresh() {
        val role = auth?.role ?: StoredRole.UNCONFIGURED
        val trusted = pairing?.trusted()
        currentProfile = if (role == StoredRole.CHILD) controls?.profile() else pairStore?.get("confirmed_profile")?.let {
            val o = StrictJson.parse(it); PolicyProfile(StrictJson.int(o, "age"), StrictJson.decimal(o, "revision", true))
        }
        current = SecurityState(DeviceRole.valueOf(role.name), role == StoredRole.GUARDIAN && auth!!.authenticated,
            pairing = if (controls?.revoked() == true) "revoked" else if (trusted != null) "paired"
                else if (pairing != null) "pending" else "unpaired", encryption = "ready", cloud = cloudHealth,
            outboxCount = outbox?.size() ?: 0)
        storageError = null
    }
    override fun state(): SecurityState {
        val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        return current.copy(guardianAuthenticated = current.role == DeviceRole.GUARDIAN &&
            !locked && android.os.SystemClock.elapsedRealtime() < authDeadline,
            cloud = cloudHealth,
            encryption = if (locked) "locked" else if (storageError != null) "failed" else current.encryption)
    }
    override fun profile() = currentProfile
    override fun journal() = executionJournal
    fun attach(host: Activity) {
        activity = host; credential = AndroidCredentialPrompt(host); scanner = NativeQr(host); foreground()
    }
    fun foreground() { initialize(); NativeRelayRuntime.foreground(context) }
    fun detach(host: Activity) {
        if (activity !== host) return
        onBackground(); scanner?.cancel(); activity = null; credential = null; scanner = null
    }
    fun activityResult(request: Int, result: Int, data: Intent?): Boolean =
        credential?.onActivityResult(request, result, data) == true || scanner?.result(request, result, data) == true
    private fun authenticate(reply: (SecurityReply) -> Unit, continuation: (() -> Any?)? = null) {
        submit(reply = { initialized ->
            if (initialized is SecurityReply.Error) reply(initialized)
            else try { auth!!.authenticate { ok, expires ->
                authDeadline = expires ?: 0
                if (!ok) reply(SecurityReply.Error("guardian_auth_required"))
                else submit(reply) {
                    bootstrap()
                    NativeRelayRuntime.requestSync(context)
                    if (continuation != null) continuation() else mapOf("authenticated" to true,
                        "expiresAtMs" to (System.currentTimeMillis() + maxOf(0, expires!! - android.os.SystemClock.elapsedRealtime())).toString())
                }
            } } catch (e: Exception) { reply(SecurityReply.Error(code(e))) }
        }) { bootstrap(); null }
    }
    override fun invoke(request: SecurityRequest, reply: (SecurityReply) -> Unit) {
        if (request == SecurityRequest.Authenticate) { authenticate(reply); return }
        if (request is SecurityRequest.GetPairQr && request.step == QrStep.CONFIRMATION && current.role == DeviceRole.GUARDIAN) {
            submit(reply = { value ->
                if (value is SecurityReply.Error) reply(value)
                else {
                    val host = activity
                    if (host == null) reply(SecurityReply.Error("unavailable"))
                    else {
                        val data = (value as SecurityReply.Value).data as Map<*, *>
                        val payload = data["qrPayload"] as String
                        val view = android.widget.LinearLayout(host).apply {
                            orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
                            addView(android.widget.ImageView(host).apply { setImageBitmap(NativeQr.bitmap(payload, 512)) })
                            addView(android.widget.TextView(host).apply {
                                text = "قارن البصمات على الجهازين ثم سلّم هذا الرمز للطفل ليَمسحه:\n" +
                                    (data["fingerprints"] as Map<*, *>).values.joinToString("\n")
                            })
                        }
                        android.app.AlertDialog.Builder(host).setTitle("تأكيد الاقتران المباشر").setView(view)
                            .setNegativeButton("إلغاء") { _, _ -> reply(SecurityReply.Error("scan_cancelled")) }
                            .setOnCancelListener { reply(SecurityReply.Error("scan_cancelled")) }
                            .setPositiveButton("قارنت البصمات وسلّمت الرمز") { _, _ -> submit(reply) {
                                auth!!.requireGuardian(); pairing!!.scanConfirmation(payload.toByteArray())
                                composeTrusted(); qr(payload.toByteArray())
                            } }.show()
                    }
                }
            }) {
                bootstrap(); auth!!.requireGuardian()
                qr(pairStore!!.get("qr_confirmation") ?: throw SecurityFailure("unavailable"))
            }
            return
        }
        if (request is SecurityRequest.ScanPairQr) {
            main.post {
                try { (scanner ?: throw SecurityFailure("camera_unavailable")).scan { bytes ->
                    if (bytes == null) reply(SecurityReply.Error("scan_cancelled"))
                    else if (current.role == DeviceRole.GUARDIAN) authenticate(reply) { scan(request.step, bytes) }
                    else submit(reply) { bootstrap(); scan(request.step, bytes) }
                } } catch (e: Exception) { reply(SecurityReply.Error(code(e))) }
            }
            return
        }
        submit(reply) {
            bootstrap()
            if (request !is SecurityRequest.CreateChallenge && request != SecurityRequest.SyncInbox &&
                !(request is SecurityRequest.GetPairQr && current.role == DeviceRole.CHILD)) auth!!.requireGuardian()
            when (request) {
                SecurityRequest.CreatePairOffer -> {
                    if (pairing == null) loadPair(UUID.randomUUID().toString(), UUID.randomUUID().toString(), true)
                    val bytes = pairing!!.createOffer(pairStore!!.pairId)
                    pairStore!!.put("qr_offer", bytes); qr(bytes)
                }
                is SecurityRequest.GetPairQr -> {
                    val bytes = pairStore!!.get("qr_${request.step.wire}") ?: throw SecurityFailure("unavailable")
                    qr(bytes)
                }
                is SecurityRequest.CreateChallenge -> {
                    requireProtocol(current.role == DeviceRole.CHILD, "permission_missing")
                    val bytes = (controls ?: throw SecurityFailure("unpaired")).challenge(request.operation, request.eventId)
                    pairStore!!.put("qr_challenge", bytes); qr(bytes)
                }
                is SecurityRequest.SetChildAge -> createControl(ControlOperation.SET_PROFILE, request.age)
                SecurityRequest.RevokePair -> { createControl(ControlOperation.REVOKE_PAIR, null); null }
                is SecurityRequest.ListIncidents -> {
                    requireProtocol(request.cursor == null, "bounds")
                    mapOf("items" to inbox?.list(request.limit)?.map { native(it) }.orEmpty(), "nextCursor" to null)
                }
                is SecurityRequest.GetIncident -> native(inbox?.get(request.eventId) ?: throw SecurityFailure("unavailable"))
                SecurityRequest.SyncInbox -> {
                    auth!!.requireGuardian()
                    requireProtocol(RelayConfigurationLoader.load(context) != null, "cloud_unconfigured")
                    NativeRelayRuntime.requestSync(context); mapOf("scheduled" to true)
                }
                else -> throw SecurityFailure("unavailable")
            }
        }
    }
    private fun qr(bytes: ByteArray): Map<String, Any?> {
        requireProtocol(bytes.size in 1..2953)
        return mapOf("qrPayload" to bytes.toString(Charsets.UTF_8), "fingerprints" to pairing!!.fingerprints())
    }
    private fun scan(step: QrStep, bytes: ByteArray): Any? {
        when (step) {
            QrStep.OFFER -> {
                requireProtocol(auth!!.role != StoredRole.GUARDIAN, "permission_missing")
                val offer = StrictJson.parse(bytes, 2953)
                StrictJson.exact(offer, "v", "type", "pair_id", "nonce_b64", "expires_at_ms", "guardian", "signature_b64")
                requireProtocol(StrictJson.int(offer, "v") == 2 && StrictJson.string(offer, "type") == "pair_offer")
                val guardian = PeerKeys.parse(offer["guardian"].asJsonObject)
                val unsigned = offer.deepCopy().apply { remove("signature_b64") }
                P256Signatures.verify(guardian.signing, "mentor.pair.v2\n".toByteArray() + StrictJson.canonical(unsigned),
                    AndroidBase64.bounded(StrictJson.string(offer, "signature_b64"), 8, 72))
                val pairId = StrictJson.uuid(StrictJson.string(offer, "pair_id"))
                if (pairing == null) loadPair(pairId, UUID.randomUUID().toString(), true)
                val response = SecurityPairingDelegate(auth!!, pairing!!).scanPairOffer(bytes)
                pairStore!!.put("qr_offer", bytes); pairStore!!.put("qr_response", response)
            }
            QrStep.RESPONSE -> {
                val confirmation = SecurityPairingDelegate(auth!!, pairing!!).scanPairResponse(bytes)
                pairStore!!.put("qr_response", bytes); pairStore!!.put("qr_confirmation", confirmation)
            }
            QrStep.CONFIRMATION -> {
                SecurityPairingDelegate(auth!!, pairing!!).scanPairConfirmation(bytes)
                pairStore!!.put("qr_confirmation", bytes); composeTrusted()
            }
            QrStep.CHALLENGE -> {
                auth!!.requireGuardian()
                val challenge = DirectControls.verifyChallenge(bytes, pairing!!.trusted() ?: throw SecurityFailure("unpaired"))
                pairStore!!.put("scanned_challenge", StrictJson.json(challenge))
                if (StrictJson.string(challenge, "operation") == "unlock") createControl(ControlOperation.UNLOCK, null)
            }
            QrStep.CONTROL -> receiveControl(bytes)
            QrStep.RECEIPT -> receiveReceipt(bytes)
        }
        return null
    }
    private fun receiveReceipt(bytes: ByteArray) {
        auth!!.requireGuardian()
        val pair = pairing!!.trusted() ?: throw SecurityFailure("unpaired")
        val payload = IncidentCrypto().open(bytes, pair, pair.guardian.deviceId, ::decryptor)
        requireProtocol(payload.kind == "control_receipt")
        val r = payload.plaintext
        val key = "receipt_${StrictJson.uuid(StrictJson.string(r, "command_id"))}"
        val stored = pairStore!!.get(key)?.let(StrictJson::parse)
        if (stored != null) requireProtocol(stored == r, "event_conflict")
        val pending = pairStore!!.get("pending_control")?.let(StrictJson::parse)
        if (pending == null || r["command_id"] != pending["command_id"] || r["control_revision"] != pending["control_revision"]) {
            requireProtocol(stored != null, "stale"); return
        }
        pairStore!!.put(key, StrictJson.canonical(r))
        if (StrictJson.string(r, "status") == "applied") {
            if (pending["operation"].asString == "set_profile") pairStore!!.put("confirmed_profile", StrictJson.json(JsonObject().apply {
                add("age", pending["profile"].asJsonObject["age"]); add("revision", r["policy_revision"])
            }))
            if (pending["operation"].asString == "revoke_pair") NativeRelayRuntime.requestRevoke(context)
            pairStore!!.delete("pending_control")
        }
    }
    private fun createControl(operation: ControlOperation, age: Int?): Any? {
        auth!!.requireGuardian()
        val ps = pairStore ?: throw SecurityFailure("unpaired")
        val pair = pairing!!.trusted() ?: throw SecurityFailure("unpaired")
        val challenge = ps.get("scanned_challenge")?.let(StrictJson::parse) ?: throw SecurityFailure("challenge_required")
        requireProtocol(StrictJson.string(challenge, "operation") == operation.wire, "challenge_required")
        val pending = ps.get("pending_control")?.let(StrictJson::parse)
        val same = pending != null && pending["operation"].asString == operation.wire &&
            pending["challenge"].asJsonObject["id"] == challenge["challenge_id"] &&
            (age == null || pending["profile"].asJsonObject["age"].asInt == age)
        val revision = if (same) StrictJson.decimal(pending!!, "control_revision", true)
            else Math.addExact(ps.get("control_revision")?.toString(Charsets.US_ASCII)?.toLong() ?: 0, 1)
        val control = if (same) pending!! else GuardianControl(UUID.randomUUID().toString(), revision,
            SignedOperation.valueOf(operation.name), null, null, age,
            if (operation == ControlOperation.UNLOCK) challenge["event_id"].asString else null,
            challenge["nonce_b64"].asString, challenge["challenge_id"].asString).toJson()
        if (!same) {
            ps.put("control_revision", revision.toString().toByteArray())
            ps.put("pending_control", StrictJson.json(control))
            val sealed = IncidentCrypto().seal("control", control, pair, roleKeys!!.signer())
            requireProtocol(sealed.size <= 2953)
            ps.put("qr_control", sealed); outbox!!.enqueue(sealed)
            NativeRelayRuntime.requestSync(context)
        }
        if (operation != ControlOperation.SET_PROFILE) return qr(ps.get("qr_control")!!)
        val policy = PolicyProfile(age!!, (currentProfile?.revision ?: 0) + 1)
        return mapOf("status" to "pending", "commandId" to control["command_id"].asString,
            "profile" to profileWire(policy), "qrPayload" to ps.get("qr_control")!!.toString(Charsets.UTF_8))
    }
    internal fun receiveControl(bytes: ByteArray) {
        requireProtocol(auth!!.role == StoredRole.CHILD, "permission_missing")
        val pair = pairing!!.trusted() ?: throw SecurityFailure("unpaired")
        val payload = IncidentCrypto().open(bytes, pair, pair.child.deviceId, ::decryptor)
        requireProtocol(payload.kind == "control")
        val receipt = controls!!.apply(payload.plaintext)
        val bundle = pairStore!!.get("receipt_bundle")?.let(StrictJson::parse)
        val envelope = if (bundle?.get("command_id")?.asString == receipt.commandId)
            AndroidBase64.decode(bundle["envelope_b64"].asString) else
            IncidentCrypto().seal("control_receipt", receipt.toJson(), pair, roleKeys!!.signer(), receipt.commandId).also {
                pairStore!!.put("receipt_bundle", StrictJson.json(JsonObject().apply {
                    addProperty("command_id", receipt.commandId); addProperty("envelope_b64", AndroidBase64.encode(it))
                }))
            }
        pairStore!!.put("qr_receipt", envelope)
        if (!controls!!.revoked()) outbox!!.enqueue(envelope)
        NativeRelayRuntime.requestSync(context)
        main.post { dev.k230.mentor_app.LayoutService.instance?.protection?.refreshTrustedState() }
    }
    fun protectedControl(bytes: ByteArray, reply: (SecurityReply) -> Unit) = submit(reply) {
        bootstrap(); requireProtocol(bytes.size in 1..2953); receiveControl(bytes); null
    }
    private fun grantProtection(eventId: String): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val receive = ProtectionIntegration.guardianRelease ?: return false
        receive(eventId, nowUs() + 4_500_000) { released.set(it); done.countDown() }
        return done.await(5, TimeUnit.SECONDS) && released.get()
    }
    override fun onBackground() {
        authDeadline = 0
        if (credential?.pending == true) auth?.credentialActivityBackgrounded() else auth?.invalidate()
    }
    override fun recordExecution(decision: DecisionCommand, result: ExecutionResult) {
        submit {
            val repository = incidents ?: return@submit null
            val pair = pairing?.trusted() ?: return@submit null
            if (result.status !in setOf("executed", "failed", "rejected") || controls!!.revoked()) return@submit null
            val region = decision.regions.maxBy { it.scores.explicit }
            val metadata = IncidentMetadata(decision.eventId, decision.revision, pair.child.deviceId,
                System.currentTimeMillis(), decision.packageName,
                ProbabilityScores(region.scores.porn, region.scores.hentai, region.scores.sexy),
                EvidenceSummary(if (region.route == "explicit") EvidenceRoute.EXPLICIT else EvidenceRoute.HENTAI,
                    region.observations.size, region.observations.first().ptsUs, region.observations.last().ptsUs),
                ProtectionAction.entries.first { it.stage == decision.stage },
                dev.k230.mentor_app.protection.events.ExecutionResult(ProtectionAction.entries.first { it.stage == result.stage },
                    ExecutionStatus.entries.first { it.wire == result.status }, result.error), decision.policy.revision)
            repository.record(metadata); NativeRelayRuntime.requestSync(context); null
        }
    }
    override fun recordRelease(eventId: String, revision: Long, reason: String) {
        submit {
            if (controls?.revoked() == false) incidents?.recordRelease(eventId, revision)
            NativeRelayRuntime.requestSync(context); null
        }
    }
    private fun profileWire(p: PolicyProfile) = mapOf("age" to p.age, "profile" to p.profile,
        "policyVersion" to PolicyProfile.POLICY_VERSION, "policyRevision" to p.revision.toString())
    private fun native(o: com.google.gson.JsonElement): Any? = when {
        o.isJsonNull -> null
        o.isJsonObject -> o.asJsonObject.entrySet().associate { it.key to native(it.value) }
        o.isJsonArray -> o.asJsonArray.map(::native)
        o.asJsonPrimitive.isBoolean -> o.asBoolean
        o.asJsonPrimitive.isString -> o.asString
        o.toString().matches(Regex("-?[0-9]+")) -> o.asInt
        else -> o.asDouble
    }
    private fun code(e: Exception): String = when (e) {
        is SecurityFailure -> e.code; is ProtocolFailure -> e.code
        is android.security.keystore.UserNotAuthenticatedException -> "guardian_auth_required"
        is android.security.keystore.KeyPermanentlyInvalidatedException -> "key_lost"
        else -> "storage_failed"
    }
}
