package dev.k230.mentor_app.protection

import com.google.crypto.tink.Aead
import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.security.*
import dev.k230.mentor_app.protection.events.GuardianControl
import dev.k230.mentor_app.protection.events.ControlOperation as SignedOperation
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class NativeCompositionTest {
    private val codec = object : Base64Codec {
        override fun encode(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        override fun decode(text: String) = java.util.Base64.getUrlDecoder().decode(text)
    }
    private class Disk : BlobStore {
        val bytes = mutableMapOf<String, ByteArray>()
        override fun read(name: String) = bytes[name]?.clone()
        override fun write(name: String, bytes: ByteArray) { this.bytes[name] = bytes.clone() }
        override fun delete(name: String) { bytes.remove(name) }
        override fun names() = bytes.keys.sorted()
    }
    private fun store() = SealedStore(Disk(), com.google.crypto.tink.subtle.AesGcmJce(ByteArray(32) { 7 }),
        ProtocolFixtures.EVENT, "tests")
    private fun peer(id: String): Pair<PeerKeys, EnvelopeSigner> {
        val hpke = CryptoKeyStore.publicHpke(CryptoKeyStore.newHpke())
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val descriptor = JsonObject().apply {
            addProperty("device_id", id); add("auth_uid", null)
            addProperty("hpke_public_keyset_b64", codec.encode(hpke)); addProperty("hpke_kid", StrictJson.sha(hpke))
            addProperty("signing_public_spki_b64", codec.encode(ec.public.encoded)); addProperty("signing_kid", StrictJson.sha(ec.public.encoded))
        }
        return PeerKeys.parse(descriptor, codec) to EnvelopeSigner { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(ec.private); update(bytes); sign() }
        }
    }
    private class Fixture(val codec: Base64Codec, val store: SealedStore, val pair: TrustedPair,
        val signer: EnvelopeSigner) {
        var now = 10_000_000L
        var active: String? = ProtocolFixtures.EVENT
        val receiver = DirectControls(store, pair, signer, ProtocolFixtures.CONTINUITY, { now }, { active }, codec)
        fun control(op: ControlOperation, rev: Long, age: Int? = null): JsonObject {
            val bytes = receiver.challenge(op, if (op == ControlOperation.UNLOCK) active else null)
            val c = DirectControls.verifyChallenge(bytes, pair, codec)
            return GuardianControl(java.util.UUID.randomUUID().toString(), rev, SignedOperation.valueOf(op.name),
                null, null, age, if (op == ControlOperation.UNLOCK) active else null,
                c["nonce_b64"].asString, c["challenge_id"].asString).toJson(codec)
        }
    }
    private fun fixture(): Fixture {
        val guardian = peer(ProtocolFixtures.SESSION); val child = peer(ProtocolFixtures.STREAM)
        return Fixture(codec, store(), TrustedPair(ProtocolFixtures.SCREEN, guardian.first, child.first), child.second)
    }
    @Test fun oneUseProfilePersistsWithoutAnActivityAndDuplicatesDoNotAdvanceRevision() {
        val f = fixture(); val c = f.control(ControlOperation.SET_PROFILE, 1, 13)
        assertTrue(f.receiver.apply(c).applied)
        assertEquals(PolicyProfile(13, 1), f.receiver.profile())
        assertEquals(f.receiver.apply(c), f.receiver.apply(c))
        val restored = DirectControls(f.store, f.pair, f.signer, ProtocolFixtures.CONTINUITY, { f.now }, { f.active }, codec)
        assertEquals(PolicyProfile(13, 1), restored.profile())
        val changed = c.deepCopy().apply { addProperty("control_revision", "2") }
        assertThrows(Exception::class.java) { restored.apply(changed) }
        assertEquals(PolicyProfile(13, 1), restored.profile())
    }
    @Test fun unlockRejectsWrongEventStaleChallengeBootAndReplayMutation() {
        val f = fixture(); val control = f.control(ControlOperation.UNLOCK, 1)
        f.active = ProtocolFixtures.STREAM
        assertThrows(Exception::class.java) { f.receiver.apply(control) }
        f.active = ProtocolFixtures.EVENT
        f.now += 300_000_001
        assertThrows(Exception::class.java) { f.receiver.apply(control) }
        f.now = 10_000_000
        val wrongBoot = DirectControls(f.store, f.pair, f.signer, ProtocolFixtures.SCREEN, { f.now }, { f.active }, codec)
        assertThrows(Exception::class.java) { wrongBoot.apply(control) }
        assertTrue(f.receiver.apply(control).applied)
        assertThrows(Exception::class.java) { f.receiver.apply(control.deepCopy().apply {
            addProperty("command_id", ProtocolFixtures.SESSION)
        }) }
    }
    @Test fun invalidAgeAndWallClockCannotReplaceProfileOrAuthorizeUnlock() {
        val f = fixture(); assertTrue(f.receiver.apply(f.control(ControlOperation.SET_PROFILE, 1, 12)).applied)
        val c = f.control(ControlOperation.SET_PROFILE, 2, 15)
        c["profile"].asJsonObject.addProperty("age", 16)
        assertThrows(Exception::class.java) { f.receiver.apply(c) }
        val wall = GuardianControl(ProtocolFixtures.STREAM, 3, SignedOperation.SET_PROFILE, 1000, 2000, age = 13).toJson(codec)
        assertThrows(Exception::class.java) { f.receiver.apply(wall) }
        assertEquals(PolicyProfile(12, 1), f.receiver.profile())
    }
    @Test fun challengeSignatureAndPairPinsCannotBeSubstituted() {
        val f = fixture(); val bytes = f.receiver.challenge(ControlOperation.SET_PROFILE, null)
        assertEquals("set_profile", DirectControls.verifyChallenge(bytes, f.pair, codec)["operation"].asString)
        val modified = StrictJson.parse(bytes).apply { addProperty("operation", "revoke_pair") }
        assertThrows(Exception::class.java) { DirectControls.verifyChallenge(StrictJson.json(modified), f.pair, codec) }
        val alien = fixture()
        assertThrows(Exception::class.java) { DirectControls.verifyChallenge(bytes, alien.pair, codec) }
    }
    @Test fun revokeIsDurableAndNeverSynthesizesAnUnlock() {
        val f = fixture(); val c = f.control(ControlOperation.REVOKE_PAIR, 1)
        assertTrue(f.receiver.apply(c).applied); assertTrue(f.receiver.revoked())
        assertEquals(ProtocolFixtures.EVENT, f.active)
        assertEquals(f.receiver.apply(c), f.receiver.apply(c))
        assertThrows(Exception::class.java) { f.receiver.challenge(ControlOperation.UNLOCK, f.active) }
    }
    @Test fun journalCachesAllRevisionsAndResultsButNeverRestoresRepetitionAuthority() {
        var now = 10_500_000L; val store = store()
        val journal = SealedExecutionJournal(store, { now }, ProtocolFixtures.CONTINUITY)
        val one = ProtocolFixtures.parse(ProtocolFixtures.decision())
        val result = ExecutionResult("executed", 1, "cover_region", now, listOf(PixelRect(60, 300, 480, 900)))
        assertEquals(JournalReservation.New, journal.reserve(one, "a"))
        assertEquals(JournalReservation.Incomplete, journal.reserve(one, "a"))
        journal.finish(one, result)
        assertEquals(setOf(one.eventId), journal.executedEpisodes(one.packageName, one.policy, now - 60_000_000, now))
        val two = ProtocolFixtures.parse(ProtocolFixtures.decision(revision = 2, stage = 2))
        assertEquals(JournalReservation.New, journal.reserve(two, "b")); journal.finish(two, result.copy(stage = 2, action = "calm_shield"))
        assertEquals(JournalReservation.Cached(result), journal.reserve(one, "a"))
        assertEquals(JournalReservation.Conflict, journal.reserve(one, "mutation"))
        val restarted = SealedExecutionJournal(store, { now }, ProtocolFixtures.CONTINUITY)
        assertEquals(JournalReservation.Cached(result), restarted.reserve(one, "a"))
        assertTrue(restarted.executedEpisodes(one.packageName, one.policy, now - 60_000_000, now).isEmpty())
        journal.release(two.eventId, 2, now)
        assertEquals(JournalReservation.Conflict, journal.reserve(ProtocolFixtures.parse(ProtocolFixtures.decision(revision = 3)), "c"))
        assertTrue(journal.executedEpisodes(one.packageName, PolicyProfile(13, 2), now - 60_000_000, now).isEmpty())
    }
}
