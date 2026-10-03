package dev.k230.mentor_app.protection.events

import com.google.crypto.tink.HybridDecrypt
import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.security.EnvelopeSigner
import dev.k230.mentor_app.protection.security.GuardianAuthorization
import dev.k230.mentor_app.protection.security.IncidentCrypto
import dev.k230.mentor_app.protection.security.SealedStore
import dev.k230.mentor_app.protection.security.StrictJson
import dev.k230.mentor_app.protection.security.TrustedPair
import java.util.UUID

class IncidentRepository(private val journal: SealedStore, private val outbox: EncryptedOutbox,
                         private val pair: TrustedPair, private val crypto: IncidentCrypto,
                         private val signer: EnvelopeSigner, private val wallMs: () -> Long = System::currentTimeMillis) {
    /** Run off the enforcement/UI threads. Persist before crypto/network, retry recovery is idempotent. */
    @Synchronized fun record(metadata: IncidentMetadata): OutboxEntry {
        return recordJson(metadata.toJson())
    }
    private fun recordJson(plaintext: JsonObject): OutboxEntry {
        PayloadValidation.incident(plaintext)
        StrictJson.ensure(StrictJson.string(plaintext, "child_device_id") == pair.child.deviceId, "pair_mismatch")
        val revision = StrictJson.decimal(plaintext, "incident_revision", true)
        val key = "i_${StrictJson.uuid(StrictJson.string(plaintext, "event_id"))}"
        val existing = journal.get(key)?.let { StrictJson.parse(it) }
        val messageId: String
        if (existing != null) {
            val old = existing["metadata"].asJsonObject
            val oldRevision = StrictJson.decimal(old, "incident_revision", true)
            StrictJson.ensure(revision >= oldRevision, "stale")
            if (revision == oldRevision) {
                StrictJson.ensure(old == plaintext, "event_conflict")
                messageId = StrictJson.string(existing, "message_id")
                val persisted = outbox.get(messageId)
                if (persisted != null) return OutboxEntry(messageId, persisted, StrictJson.sha(persisted))
                StrictJson.ensure(!existing["uploaded"].asBoolean, "already_uploaded")
            } else messageId = UUID.randomUUID().toString()
        } else {
            StrictJson.ensure(journal.names().size < 4096, "busy")
            messageId = UUID.randomUUID().toString()
        }
        val pending = JsonObject().apply {
            add("metadata", plaintext); addProperty("message_id", messageId); addProperty("uploaded", false)
            addProperty("recorded_at_ms", wallMs().toString())
        }
        journal.put(key, StrictJson.json(pending))
        val bytes = crypto.seal("incident", plaintext, pair, signer, messageId)
        return outbox.enqueue(bytes)
    }
    @Synchronized fun recordRelease(eventId: String, actionRevision: Long): OutboxEntry? {
        val old = journal.get("i_${StrictJson.uuid(eventId)}")?.let(StrictJson::parse) ?: return null
        val payload = old["metadata"].asJsonObject.deepCopy()
        if (payload["executed"].asJsonObject["status"].asString == "released") return null
        payload.addProperty("incident_revision", Math.addExact(maxOf(actionRevision,
            StrictJson.decimal(payload, "incident_revision", true)), 1).toString())
        payload.addProperty("occurred_at_ms", wallMs().toString())
        payload.add("executed", JsonObject().apply {
            addProperty("action", "none"); addProperty("stage", 0); addProperty("status", "released"); add("error", null)
        })
        return recordJson(payload)
    }
    @Synchronized fun acknowledgeMessage(messageId: String, status: String, sha256: String) {
        StrictJson.uuid(messageId)
        val event = journal.names().filter { it.startsWith("i_") }.firstOrNull {
            val record = StrictJson.parse(journal.get(it) ?: throw dev.k230.mentor_app.protection.security.SecurityFailure("storage_failed"))
            StrictJson.string(record, "message_id") == messageId
        }
        if (event == null) outbox.acknowledge(messageId, status, sha256)
        else acknowledge(event.removePrefix("i_"), messageId, status, sha256)
        pruneUploaded()
    }

    @Synchronized fun acknowledge(eventId: String, messageId: String, status: String, sha256: String) {
        val key = "i_${StrictJson.uuid(eventId)}"
        val obj = journal.get(key)?.let { StrictJson.parse(it) }
        val bytes = outbox.get(messageId)
        StrictJson.hex(sha256)
        StrictJson.ensure(status in setOf("stored", "duplicate"), "event_conflict")
        if (bytes == null) {
            StrictJson.ensure(obj != null && StrictJson.string(obj, "message_id") == messageId &&
                obj["uploaded"].asBoolean && obj["envelope_sha256"]?.asString == sha256, "event_conflict")
            return
        }
        StrictJson.ensure(StrictJson.sha(bytes) == sha256, "event_conflict")
        if (obj != null && StrictJson.string(obj, "message_id") == messageId) {
            obj.addProperty("uploaded", true)
            obj.addProperty("envelope_sha256", sha256)
            journal.put(key, StrictJson.json(obj))
        }
        outbox.acknowledge(messageId, status, sha256)
    }

    @Synchronized fun pruneUploaded(): Int {
        val expired = journal.names().filter { it.startsWith("i_") }.filter {
            val o = StrictJson.parse(journal.get(it) ?: throw dev.k230.mentor_app.protection.security.SecurityFailure("storage_failed"))
            o["uploaded"].asBoolean && wallMs() - StrictJson.decimal(o, "recorded_at_ms") >= 86400000L
        }
        expired.forEach(journal::delete)
        return expired.size
    }
}

/** Guardian-only verified local metadata cache. Keystore key must itself be auth-gated. */
class EncryptedInbox(private val store: SealedStore, private val pair: TrustedPair,
                     private val crypto: IncidentCrypto, private val auth: GuardianAuthorization,
                     private val decryptor: () -> HybridDecrypt) {
    @Synchronized fun receive(envelope: ByteArray): Boolean {
        auth.requireGuardian()
        val verified = crypto.open(envelope, pair, pair.guardian.deviceId, decryptor)
        StrictJson.ensure(verified.kind == "incident", "invalid_envelope")
        val obj = verified.plaintext
        val eventId = StrictJson.string(obj, "event_id")
        val revision = StrictJson.decimal(obj, "incident_revision", true)
        val key = "i_$eventId"
        val old = store.get(key)?.let { StrictJson.parse(it) }
        if (old != null) {
            val oldRevision = StrictJson.decimal(old, "incident_revision", true)
            if (revision < oldRevision) return false
            if (revision == oldRevision) {
                StrictJson.ensure(old == obj, "event_conflict")
                return false
            }
        } else StrictJson.ensure(store.names().size < 10000, "storage_failed")
        store.put(key, StrictJson.json(obj))
        return true
    }
    @Synchronized fun get(eventId: String): JsonObject? {
        auth.requireGuardian()
        return store.get("i_${StrictJson.uuid(eventId)}")?.let { StrictJson.parse(it) }
    }
    @Synchronized fun list(limit: Int = 50): List<JsonObject> {
        auth.requireGuardian(); StrictJson.ensure(limit in 1..50, "bounds")
        return store.names().filter { it.startsWith("i_") }.take(limit).mapNotNull { store.get(it)?.let(StrictJson::parse) }
    }
}
