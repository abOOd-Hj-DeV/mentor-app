package dev.k230.mentor_app.protection.events

import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.security.IncidentCrypto
import dev.k230.mentor_app.protection.security.SealedStore
import dev.k230.mentor_app.protection.security.StrictJson
import dev.k230.mentor_app.protection.security.TrustedPair
import java.security.SecureRandom

data class OutboxEntry(val messageId: String, val envelope: ByteArray, val sha256: String)

class EncryptedOutbox(private val store: SealedStore, private val pair: TrustedPair,
                      private val crypto: IncidentCrypto, private val canUpload: () -> Boolean) {
    @Synchronized fun entries(limit: Int = 50): List<OutboxEntry> {
        StrictJson.ensure(limit in 1..50 && canUpload(), "pair_revoked")
        return names().take(limit).map { name ->
            val bytes = store.get(name) ?: throw dev.k230.mentor_app.protection.security.SecurityFailure("storage_failed")
            OutboxEntry(name.removePrefix("e_"), bytes, StrictJson.sha(bytes))
        }
    }
    @Synchronized fun readyEntries(nowMs: Long, limit: Int = 50): List<OutboxEntry> {
        StrictJson.ensure(nowMs >= 0 && limit in 1..50 && canUpload(), "pair_revoked")
        return names().filter { name ->
            val retry = store.get("r_${name.removePrefix("e_")}")?.let(StrictJson::parse)
            retry == null || StrictJson.decimal(retry, "next_ms") <= nowMs
        }.take(limit).map { name ->
            val bytes = store.get(name) ?: throw dev.k230.mentor_app.protection.security.SecurityFailure("storage_failed")
            OutboxEntry(name.removePrefix("e_"), bytes, StrictJson.sha(bytes))
        }
    }
    private fun names() = store.names().filter { it.startsWith("e_") }
    @Synchronized fun get(messageId: String): ByteArray? = store.get("e_${StrictJson.uuid(messageId)}")
    @Synchronized fun size(): Int = names().size
    @Synchronized fun enqueue(bytes: ByteArray): OutboxEntry {
        StrictJson.ensure(canUpload(), "pair_revoked")
        val parsed = crypto.validateEnvelope(bytes, pair)
        val canonical = StrictJson.canonical(parsed)
        val id = StrictJson.string(parsed, "message_id")
        val name = "e_$id"
        val existing = store.get(name)
        if (existing != null) {
            StrictJson.ensure(existing.contentEquals(canonical), "event_conflict")
            return OutboxEntry(id, existing, StrictJson.sha(existing))
        }
        val names = names()
        val total = names.sumOf { (store.get(it) ?: throw dev.k230.mentor_app.protection.security.SecurityFailure("storage_failed")).size.toLong() }
        StrictJson.ensure(names.size < 10000 && total + canonical.size <= 32L * 1024 * 1024, "storage_failed")
        store.put(name, canonical)
        return OutboxEntry(id, canonical, StrictJson.sha(canonical))
    }
    @Synchronized fun acknowledge(messageId: String, status: String, matchingSha256: String) {
        StrictJson.ensure(status in setOf("stored", "duplicate"), "invalid_envelope")
        val id = StrictJson.uuid(messageId)
        val bytes = get(id) ?: return
        StrictJson.ensure(StrictJson.sha(bytes) == StrictJson.hex(matchingSha256), "event_conflict")
        store.delete("e_$id")
        store.delete("r_$id")
    }
    @Synchronized fun failedAttempt(messageId: String, nowMs: Long): Long {
        val id = StrictJson.uuid(messageId)
        StrictJson.ensure(get(id) != null && nowMs >= 0, "bounds")
        val previous = store.get("r_$id")?.let { StrictJson.parse(it) }
        val attempts = ((previous?.let { StrictJson.int(it, "attempts") } ?: 0) + 1).coerceAtMost(20)
        val base = (30000L * (1L shl (attempts - 1).coerceAtMost(10))).coerceAtMost(21600000L)
        val jitter = SecureRandom().nextInt((base / 5).toInt() + 1).toLong()
        val next = Math.addExact(nowMs, (base + jitter).coerceAtMost(21600000L))
        val retry = JsonObject().apply { addProperty("attempts", attempts); addProperty("next_ms", next.toString()) }
        store.put("r_$id", StrictJson.canonical(retry))
        return next
    }
}
