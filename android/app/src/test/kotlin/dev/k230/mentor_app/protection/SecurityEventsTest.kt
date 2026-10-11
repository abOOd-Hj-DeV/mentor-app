package dev.k230.mentor_app.protection

import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.security.*
import dev.k230.mentor_app.protection.events.*
import dev.k230.mentor_app.protection.events.ControlOperation
import dev.k230.mentor_app.protection.security.DeviceRole
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class SecurityEventsTest {
    private val codec = object : Base64Codec {
        override fun encode(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        override fun decode(text: String) = java.util.Base64.getUrlDecoder().decode(text)
    }
    private val pairId = "20000000-0000-4000-8000-000000000003"
    private val boot = "20000000-0000-4000-8000-000000000006"
    private class Disk : BlobStore {
        val values = mutableMapOf<String, ByteArray>()
        var failWritePrefix: String? = null
        var failDeletePrefix: String? = null
        override fun read(name: String) = values[name]?.clone()
        override fun write(name: String, bytes: ByteArray) {
            if (failWritePrefix?.let(name::startsWith) == true) {
                failWritePrefix = null; throw IllegalStateException("interrupted write")
            }
            values[name] = bytes.clone()
        }
        override fun delete(name: String) {
            if (failDeletePrefix?.let(name::startsWith) == true) {
                failDeletePrefix = null; throw IllegalStateException("interrupted delete")
            }
            values.remove(name)
        }
        override fun names() = values.keys.sorted()
    }
    private fun aead(): Aead {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key); c.updateAAD(associatedData)
                return c.iv + c.doFinal(plaintext)
            }
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12))); c.updateAAD(associatedData)
                return c.doFinal(ciphertext, 12, ciphertext.size - 12)
            }
        }
    }
    private fun signer(keys: KeyPair) = EnvelopeSigner {
        Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(it); sign() }
    }
    private data class Keys(val peer: PeerKeys, val hpke: com.google.crypto.tink.KeysetHandle, val ec: KeyPair)
    private fun keys(last: String): Keys {
        val hpke = CryptoKeyStore.newHpke()
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public = CryptoKeyStore.publicHpke(hpke)
        val obj = JsonObject().apply {
            addProperty("device_id", "20000000-0000-4000-8000-00000000000$last"); addProperty("auth_uid", "user_$last")
            addProperty("hpke_public_keyset_b64", codec.encode(public)); addProperty("hpke_kid", StrictJson.sha(public))
            addProperty("signing_public_spki_b64", codec.encode(ec.public.encoded)); addProperty("signing_kid", StrictJson.sha(ec.public.encoded))
        }
        return Keys(PeerKeys.parse(obj, codec), hpke, ec)
    }
    private fun metadata(child: String, revision: Long = 1) = IncidentMetadata(
        "10000000-0000-4000-8000-000000000005", revision, child, 1791043200000,
        "com.example.viewer", ProbabilityScores(.65, .02, .10), EvidenceSummary(EvidenceRoute.EXPLICIT, 3, 10000000, 10400000),
        ProtectionAction.COVER, ExecutionResult(ProtectionAction.COVER, ExecutionStatus.EXECUTED), 1)
    private fun rejected(block: () -> Unit) { assertThrows(Exception::class.java, block) }

    @Test fun strictParserRejectsDuplicateUnknownMalformedAndOversize() {
        for (text in listOf("{\"v\":2,\"v\":2}", "{\"v\":NaN}", "{\"v\":2}{}", "[]", "{\"v\":2,}", "{\"v\":1e999}")) {
            rejected { StrictJson.parse(text.toByteArray()) }
        }
        rejected { StrictJson.parse(byteArrayOf(123, 34, -64, 34, 58, 48, 125)) }
        rejected { StrictJson.parse(ByteArray(24577) { 32 }) }
        rejected { StrictJson.parse(("{\"x\":" + "[".repeat(12) + "0" + "]".repeat(12) + "}").toByteArray()) }
        rejected { StrictJson.int(StrictJson.parse("{\"v\":true}".toByteArray()), "v") }
        rejected { StrictJson.int(StrictJson.parse("{\"v\":2e0}".toByteArray()), "v") }
        rejected { StrictJson.int(StrictJson.parse("{\"v\":2.0}".toByteArray()), "v") }
        rejected { StrictJson.decimal(StrictJson.parse("{\"seq\":\"9223372036854775808\"}".toByteArray()), "seq") }
        assertEquals("{\"a\":null,\"v\":2}", StrictJson.canonical(StrictJson.parse("{\"v\":2,\"a\":null}".toByteArray())).toString(Charsets.UTF_8))
    }

    @Test fun pairingReplayPreservesDeadlineAndValidatedScanBootstrapsChildOnly() {
        val guardian = keys("1"); val child = keys("2"); var mono = 50000000L
        val g = PairingManager(guardian.peer, signer(guardian.ec), SealedStore(Disk(), aead(), pairId, "pairing"), boot, {mono}, {10000}, codec)
        val c = PairingManager(child.peer, signer(child.ec), SealedStore(Disk(), aead(), pairId, "pairing"), boot, {mono}, {10000}, codec)
        val prompt = object : CredentialPrompt { override fun launch(onResult: (Boolean) -> Unit) { error("child may not prompt") } }
        val auth = GuardianAuthorization(SealedStore(Disk(), aead(), pairId, "authority"), prompt, {10000})
        val delegate = SecurityPairingDelegate(auth, c); val offer = g.createOffer(pairId)
        rejected { delegate.scanPairOffer("invalid".toByteArray()) }; assertEquals(DeviceRole.UNCONFIGURED, auth.role)
        val response = delegate.scanPairOffer(offer); assertEquals(DeviceRole.CHILD, auth.role)
        mono += 200000000L
        assertArrayEquals(response, delegate.scanPairOffer(offer))
        mono += 100000001L
        rejected { delegate.scanPairOffer(offer) }
        rejected { auth.authenticate { _, _ -> error("must not establish guardian") } }
    }

    @Test fun nativeAndTypeScriptCanonicalSignatureBytesMatchPublishedPublicFixture() {
        val roots = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }.take(6)
        val file = roots.map { java.io.File(it, "protocol/v2/fixtures/signature-canonical.json") }.first { it.exists() }
        val f = StrictJson.parse(file.readBytes(), 24576)
        val e = f["envelope"].asJsonObject; val bytes = StrictJson.canonical(e); val crypto = IncidentCrypto(codec)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        val context = IncidentCrypto.context(e)
        val input = IncidentCrypto.signatureInput(context, codec.decode(e["ciphertext_b64"].asString))
        assertEquals(f["canonical_hex"].asString, hex(bytes))
        assertEquals(f["context_hex"].asString, hex(context))
        assertEquals(f["signature_input_hex"].asString, hex(input))
        assertEquals(f["envelope_sha256"].asString, StrictJson.sha(bytes))
        val r = f["request"].asJsonObject
        val pair = TrustedPair(e["pair_id"].asString, PeerKeys.parse(r["guardian"].asJsonObject, codec), PeerKeys.parse(r["child"].asJsonObject, codec))
        P256Signatures.verify(pair.child.signing, input, codec.decode(e["signature_b64"].asString))
        var privateKeyLoaded = false
        rejected { crypto.open(bytes, pair, pair.guardian.deviceId) { privateKeyLoaded = true; throw SecurityFailure("locked") } }
        assertTrue(privateKeyLoaded)
        e.addProperty("signature_b64", codec.encode(ByteArray(64)))
        privateKeyLoaded = false
        rejected { crypto.open(StrictJson.canonical(e), pair, pair.guardian.deviceId) { privateKeyLoaded = true; throw SecurityFailure("locked") } }
        assertFalse(privateKeyLoaded)
    }

    @Test fun hpkeAndSeparateSignatureBindEveryHeaderAndAuthenticateBeforeDecrypt() {
        val guardian = keys("1"); val child = keys("2")
        val pair = TrustedPair(pairId, guardian.peer, child.peer); val crypto = IncidentCrypto(codec)
        val envelope = crypto.sealIncident(metadata(child.peer.deviceId), pair, signer(child.ec))
        val decoded = crypto.open(envelope, pair, guardian.peer.deviceId, guardian.hpke.getPrimitive(HybridDecrypt::class.java))
        assertEquals("incident", decoded.kind)
        assertEquals(.67, decoded.plaintext["scores"].asJsonObject["explicit_score"].asDouble, 1e-12)
        for (field in listOf("pair_id", "sender_id", "recipient_id", "message_id", "recipient_hpke_kid", "sender_signing_kid", "suite", "ciphertext_b64", "signature_b64")) {
            val changed = StrictJson.parse(envelope)
            val old = changed[field].asString
            changed.addProperty(field, (if (old[0] == 'A') "B" else "A") + old.drop(1))
            var called = false
            rejected { crypto.open(StrictJson.json(changed), pair, guardian.peer.deviceId, HybridDecrypt { _, _ -> called = true; ByteArray(0) }) }
            assertFalse("must reject $field before private-key use", called)
        }
        val unknown = StrictJson.parse(envelope).apply { addProperty("app_package", "not allowed") }
        rejected { crypto.validateEnvelope(StrictJson.json(unknown), pair) }
        rejected { crypto.open(envelope, pair, child.peer.deviceId, child.hpke.getPrimitive(HybridDecrypt::class.java)) }
    }

    @Test fun keysetAndStoresFailClosedOnWrongKeysPurposePairOrCorruption() {
        val kek = aead(); val privateKey = CryptoKeyStore.newHpke()
        val bytes = CryptoKeyStore.wrapHpke(privateKey, kek, pairId)
        assertTrue(privateKey.equalsKeyset(CryptoKeyStore.readHpke(bytes, kek, pairId)))
        rejected { CryptoKeyStore.readHpke(bytes, aead(), pairId) }
        rejected { CryptoKeyStore.readHpke(bytes, kek, boot) }
        val disk = Disk(); val store = SealedStore(disk, kek, pairId, "outbox")
        store.put("sample", "private metadata".toByteArray())
        assertFalse(disk.values["sample"]!!.toString(Charsets.UTF_8).contains("private metadata"))
        rejected { SealedStore(disk, kek, pairId, "inbox").get("sample") }
        disk.values["sample"]!![5] = (disk.values["sample"]!![5].toInt() xor 1).toByte()
        rejected { store.get("sample") }
    }

    @Test fun threePhysicalScansBindExactKeysRejectSubstitutionExpiryAndReplay() {
        val guardian = keys("1"); val child = keys("2"); var now = 1000000L
        val gs = SealedStore(Disk(), aead(), pairId, "pairing"); val cs = SealedStore(Disk(), aead(), pairId, "pairing")
        val g = PairingManager(guardian.peer, signer(guardian.ec), gs, boot, { now }, { 10000 }, codec)
        val c = PairingManager(child.peer, signer(child.ec), cs, boot, { now }, { 10000 }, codec)
        val offer = g.createOffer(pairId)
        val response = c.scanOffer(offer)
        assertNull(c.trusted())
        val confirm = g.scanResponse(response)
        assertNull(g.trusted())
        val tampered = StrictJson.parse(confirm).apply { addProperty("response_sha256", "a".repeat(64)) }
        rejected { c.scanConfirmation(StrictJson.json(tampered)) }
        assertEquals(guardian.peer.signingKid, c.scanConfirmation(confirm).guardian.signingKid)
        assertEquals(child.peer.hpkeKid, g.scanConfirmation(confirm).child.hpkeKid)
        val transcript = c.retainedTranscript()!!
        assertEquals(pairId, transcript.pair.pairId)
        assertEquals(transcript.sha256, StrictJson.string(StrictJson.parse(transcript.membershipBody()), "transcript_sha256"))
        assertEquals(setOf("v", "pair_id", "guardian", "child", "offer", "response", "confirmation"),
            StrictJson.parse(transcript.registrationBody()).keySet())
        rejected { c.scanConfirmation(confirm) }
        rejected { c.scanOffer(offer) }
        now += 300000001
        val other = PairingManager(child.peer, signer(child.ec), cs, boot, { now }, { 400001 }, codec)
        rejected { other.scanOffer(offer) }
    }

    @Test fun authoritativeBootstrapRequiresActualCredentialAndNeverPromotesChild() {
        var callback: ((Boolean) -> Unit)? = null; var now = 0L
        val store = SealedStore(Disk(), aead(), pairId, "session")
        val auth = GuardianAuthorization(store, object : CredentialPrompt {
            override fun launch(onResult: (Boolean) -> Unit) { callback = onResult }
        }, { now })
        assertEquals(DeviceRole.UNCONFIGURED, auth.role)
        auth.authenticate { _, _ -> }; callback!!(false)
        assertEquals(DeviceRole.UNCONFIGURED, auth.role)
        rejected { auth.requireGuardian() }
        auth.authenticate { _, _ -> }; callback!!(true)
        assertEquals(DeviceRole.GUARDIAN, auth.role); assertTrue(auth.authenticated)
        now = 120000; assertFalse(auth.authenticated)
        auth.authenticate { _, _ -> }; auth.invalidate(); callback!!(true); assertFalse(auth.authenticated)
        val childAuth = GuardianAuthorization(SealedStore(Disk(), aead(), pairId, "session"), object : CredentialPrompt {
            override fun launch(onResult: (Boolean) -> Unit) { fail("child cannot launch credential prompt") }
        }, { now })
        childAuth.establishChildAfterVerifiedOffer()
        rejected { childAuth.authenticate { _, _ -> } }
        assertEquals(DeviceRole.CHILD, childAuth.role)
    }

    @Test fun outboxRetriesByteIdenticallyAndInboxCannotRollBackOrExposeWithoutAuth() {
        val guardian = keys("1"); val child = keys("2"); val pair = TrustedPair(pairId, guardian.peer, child.peer)
        val crypto = IncidentCrypto(codec); val disk = Disk(); val kek = aead(); var active = true
        val outbox = EncryptedOutbox(SealedStore(disk, kek, pairId, "outbox"), pair, crypto) { active }
        val journal = SealedStore(Disk(), aead(), pairId, "journal")
        val repo = IncidentRepository(journal, outbox, pair, crypto, signer(child.ec))
        val item = repo.record(metadata(child.peer.deviceId))
        assertArrayEquals(item.envelope, repo.record(metadata(child.peer.deviceId)).envelope)
        val restarted = EncryptedOutbox(SealedStore(disk, kek, pairId, "outbox"), pair, crypto) { active }
        assertArrayEquals(item.envelope, restarted.entries().single().envelope)
        val retryAt = restarted.failedAttempt(item.messageId, 10000)
        assertTrue(retryAt >= 40000)
        assertTrue(restarted.readyEntries(10000).isEmpty())
        assertEquals(item.messageId, restarted.readyEntries(retryAt).single().messageId)
        rejected { restarted.acknowledge(item.messageId, "stored", "a".repeat(64)) }
        assertEquals(1, restarted.size())
        var callback: ((Boolean) -> Unit)? = null
        val auth = GuardianAuthorization(SealedStore(Disk(), aead(), pairId, "session"), object : CredentialPrompt {
            override fun launch(onResult: (Boolean) -> Unit) { callback = onResult }
        }, { 0 })
        val inbox = EncryptedInbox(SealedStore(Disk(), aead(), pairId, "inbox"), pair, crypto, auth) { guardian.hpke.getPrimitive(HybridDecrypt::class.java) }
        rejected { inbox.receive(item.envelope) }
        auth.authenticate { _, _ -> }; callback!!(true)
        val second = repo.record(metadata(child.peer.deviceId, 2))
        assertTrue(inbox.receive(second.envelope)); assertFalse(inbox.receive(item.envelope)); assertFalse(inbox.receive(second.envelope))
        assertEquals("2", inbox.get(metadata(child.peer.deviceId).eventId)!!["incident_revision"].asString)
        auth.invalidate(); rejected { inbox.list() }
        active = false; rejected { outbox.entries() }
        active = true; repo.acknowledge(metadata(child.peer.deviceId).eventId, item.messageId, "duplicate", item.sha256)
        assertNull(outbox.get(item.messageId))
        val released = repo.recordRelease(metadata(child.peer.deviceId).eventId, 2)!!
        val payload = crypto.open(released.envelope, pair, guardian.peer.deviceId) { guardian.hpke.getPrimitive(HybridDecrypt::class.java) }.plaintext
        assertEquals("released", payload["executed"].asJsonObject["status"].asString)
        assertEquals("3", payload["incident_revision"].asString)
        assertNull(repo.recordRelease(metadata(child.peer.deviceId).eventId, 2))
        rejected { repo.acknowledgeMessage(released.messageId, "stored", "a".repeat(64)) }
        assertNotNull(outbox.get(released.messageId))
        repo.acknowledgeMessage(released.messageId, "stored", released.sha256)
        assertNull(outbox.get(released.messageId))
    }

    @Test fun encryptedRelayStateSurvivesRestartWithoutExposingCursorAndCorruptionFailsClosed() {
        val disk = Disk(); val key = aead()
        val state = dev.k230.mentor_app.protection.cloud.EncryptedRelayState(SealedStore(disk, key, pairId, "cloud_state"))
        assertNull(state.cursor()); state.storeCursor("opaque_cursor"); state.storePairPhase(dev.k230.mentor_app.protection.cloud.RelayPairPhase.ACTIVE)
        val restarted = dev.k230.mentor_app.protection.cloud.EncryptedRelayState(SealedStore(disk, key, pairId, "cloud_state"))
        assertEquals("opaque_cursor", restarted.cursor())
        assertEquals(dev.k230.mentor_app.protection.cloud.RelayPairPhase.ACTIVE, restarted.pairPhase())
        assertFalse(disk.values["relay_state"]!!.toString(Charsets.UTF_8).contains("opaque_cursor"))
        disk.values["relay_state"]!![4] = (disk.values["relay_state"]!![4].toInt() xor 1).toByte()
        rejected { restarted.pairPhase() }
    }

    @Test fun pendingIncidentsRecoverIntentAndSealedBytesBeforeEnqueue() {
        for (afterSealing in listOf(false, true)) {
            val guardian = keys("1"); val child = keys("2"); val pair = TrustedPair(pairId, guardian.peer, child.peer)
            val crypto = IncidentCrypto(codec); val key = aead()
            val journalDisk = Disk(); val outboxDisk = Disk()
            val journal = SealedStore(journalDisk, key, pairId, "journal")
            val outbox = EncryptedOutbox(SealedStore(outboxDisk, key, pairId, "outbox"), pair, crypto) { true }
            val repository = IncidentRepository(journal, outbox, pair, crypto, signer(child.ec))
            if (afterSealing) outboxDisk.failWritePrefix = "e_" else journalDisk.failWritePrefix = "c_"
            rejected { repository.record(metadata(child.peer.deviceId)) }
            assertEquals(0, outbox.size())
            val pending = StrictJson.parse(journal.get("i_${metadata(child.peer.deviceId).eventId}")!!)
            val id = StrictJson.string(pending, "message_id")
            val sealed = journal.get("c_$id")
            assertEquals(afterSealing, sealed != null)
            val restarted = IncidentRepository(SealedStore(journalDisk, key, pairId, "journal"), outbox,
                pair, crypto, signer(child.ec))
            assertEquals(1, restarted.recoverPending())
            val item = outbox.entries().single()
            assertEquals(id, item.messageId)
            if (sealed != null) assertArrayEquals(sealed, item.envelope)
            assertEquals(metadata(child.peer.deviceId).eventId, StrictJson.string(crypto.open(item.envelope,
                pair, guardian.peer.deviceId) { guardian.hpke.getPrimitive(HybridDecrypt::class.java) }.plaintext, "event_id"))
            restarted.recoverPending()
            assertArrayEquals(item.envelope, outbox.entries().single().envelope)
        }
    }

    @Test fun serverAckBeforeOutboxDeletionSurvivesRestartWithoutResealing() {
        val guardian = keys("1"); val child = keys("2"); val pair = TrustedPair(pairId, guardian.peer, child.peer)
        val crypto = IncidentCrypto(codec); val key = aead(); val journalDisk = Disk(); val outboxDisk = Disk()
        val outbox = EncryptedOutbox(SealedStore(outboxDisk, key, pairId, "outbox"), pair, crypto) { true }
        val repository = IncidentRepository(SealedStore(journalDisk, key, pairId, "journal"), outbox,
            pair, crypto, signer(child.ec))
        val incident = metadata(child.peer.deviceId); val item = repository.record(incident)
        outboxDisk.failDeletePrefix = "e_"
        rejected { repository.acknowledge(incident.eventId, item.messageId, "stored", item.sha256) }
        val restarted = IncidentRepository(SealedStore(journalDisk, key, pairId, "journal"), outbox,
            pair, crypto, signer(child.ec))
        assertEquals(0, restarted.recoverPending())
        assertArrayEquals(item.envelope, outbox.entries().single().envelope)
        restarted.acknowledge(incident.eventId, item.messageId, "duplicate", item.sha256)
        assertEquals(0, outbox.size())
    }

    @Test fun metadataHasNoMediaAndEnforcesHentaiCapScoresAndStrictAges() {
        val child = "20000000-0000-4000-8000-000000000002"
        assertEquals(.99, ProbabilityScores(0.0, 0.0, .99).sexy, 0.0)
        assertEquals(0.0, ProbabilityScores(0.0, 0.0, .99).explicitScore, 0.0)
        rejected { ProbabilityScores(Double.NaN, .1, .1).validate() }
        rejected { ProbabilityScores(.8, .2, .2).validate() }
        rejected { metadata(child).copy(scores = ProbabilityScores(.01, .98, 0.0),
            evidence = EvidenceSummary(EvidenceRoute.HENTAI, 5, 0, 1000000), requested = ProtectionAction.HOME).toJson() }
        rejected { metadata(child).copy(evidence = EvidenceSummary(EvidenceRoute.EXPLICIT, 3, 0, 399999)).toJson() }
        val obj = metadata(child).toJson().apply { addProperty("frame_b64", "disallowed") }
        rejected { PayloadValidation.incident(obj) }
    }

    @Test fun journalPrunesOnlyUploadedMetadataAndAcknowledgmentsRemainHashBound() {
        val guardian = keys("1"); val child = keys("2"); val pair = TrustedPair(pairId, guardian.peer, child.peer)
        val crypto = IncidentCrypto(codec); val outbox = EncryptedOutbox(SealedStore(Disk(), aead(), pairId, "outbox"), pair, crypto) {true}
        var now = 1000L
        val repo = IncidentRepository(SealedStore(Disk(), aead(), pairId, "journal"), outbox, pair, crypto, signer(child.ec)) {now}
        val incident = metadata(child.peer.deviceId); val item = repo.record(incident)
        rejected { repo.acknowledge(incident.eventId, item.messageId, "stored", "a".repeat(64)) }
        repo.acknowledge(incident.eventId, item.messageId, "stored", item.sha256)
        repo.acknowledge(incident.eventId, item.messageId, "duplicate", item.sha256)
        rejected { repo.acknowledge(incident.eventId, item.messageId, "duplicate", "a".repeat(64)) }
        now += 86399999; assertEquals(0, repo.pruneUploaded())
        now++; assertEquals(1, repo.pruneUploaded())
        repo.record(metadata(child.peer.deviceId, 2)); now += 172800000
        assertEquals(0, repo.pruneUploaded()); assertEquals(1, outbox.size())
    }

    @Test fun typedGuardianControlRequiresNativeAuthorityAndChildReceiptsRemainStrict() {
        val guardian = keys("1"); val child = keys("2"); val pair = TrustedPair(pairId, guardian.peer, child.peer)
        val auth = GuardianAuthorization(SealedStore(Disk(), aead(), pairId, "authority"), object : CredentialPrompt {
            override fun launch(onResult: (Boolean) -> Unit) { onResult(true) }
        }, {0})
        val crypto = IncidentCrypto(codec)
        val control = GuardianControl(boot, 1, ControlOperation.SET_PROFILE, 10000, 20000, age = 13)
        rejected { crypto.sealControl(control, pair, signer(guardian.ec), auth) }
        auth.authenticate { _, _ -> }
        val envelope = crypto.sealControl(control, pair, signer(guardian.ec), auth)
        assertEquals("control", crypto.open(envelope, pair, child.peer.deviceId, child.hpke.getPrimitive(HybridDecrypt::class.java)).kind)
        rejected { control.copy(age = 16).toJson(codec) }
        val receipt = ControlReceipt(boot, 1, false, "invalid_age", 0)
        val reply = crypto.sealReceipt(receipt, pair, signer(child.ec))
        assertEquals("control_receipt", crypto.open(reply, pair, guardian.peer.deviceId, guardian.hpke.getPrimitive(HybridDecrypt::class.java)).kind)
        auth.invalidate(); rejected { crypto.sealControl(control, pair, signer(guardian.ec), auth) }
    }
}
