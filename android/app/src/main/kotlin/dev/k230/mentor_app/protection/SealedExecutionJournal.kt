package dev.k230.mentor_app.protection

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import dev.k230.mentor_app.protection.security.SealedStore
import dev.k230.mentor_app.protection.security.StrictJson

internal data object KnownNonexecutionRetry : JournalReservation

internal class SealedExecutionJournal(private val store: SealedStore, private val now: () -> Long,
    private val bootId: String) : ExecutionJournal {
    private val gson = GsonBuilder().serializeNulls().create()
    private val executed = mutableMapOf<String, Triple<String, PolicyProfile, Long>>()
    private var lastPolicy: PolicyProfile? = null
    private fun key(d: DecisionCommand) = "j_${d.eventId}"
    @Synchronized override fun reserve(decision: DecisionCommand, semanticSha256: String): JournalReservation {
        val key = key(decision)
        val previous = store.get(key)?.let(StrictJson::parse)
        var nonexecutionRetry = false
        if (previous != null) {
            val rev = StrictJson.decimal(previous, "revision", true)
            val cached = previous["actions"].asJsonArray.firstOrNull {
                StrictJson.decimal(it.asJsonObject, "revision", true) == decision.revision
            }?.asJsonObject
            if (cached != null) {
                if (StrictJson.string(cached, "digest") != semanticSha256) return JournalReservation.Conflict
                val result = cached["result"]
                return if (result.isJsonNull) JournalReservation.Incomplete
                    else JournalReservation.Cached(gson.fromJson(result, ExecutionResult::class.java))
            }
            val result = previous["result"]
            if (decision.revision != rev + 1 || decision.stage < StrictJson.int(previous, "stage") ||
                result.isJsonNull || previous["released"].asBoolean) return JournalReservation.Conflict
            val status = StrictJson.string(result.asJsonObject, "status")
            val executedStage = StrictJson.int(result.asJsonObject, "stage")
            if (executedStage >= 3) return JournalReservation.Conflict
            if (executedStage == 0) {
                if (status !in setOf("failed", "rejected") || rev != 1L ||
                    !previous.has("evidence_end_us") || !result.asJsonObject["executedUs"].isJsonNull ||
                    result.asJsonObject["rects"].asJsonArray.size() != 0 ||
                    result.asJsonObject["error"].isJsonNull) return JournalReservation.Conflict
                val endUs = StrictJson.decimal(previous, "evidence_end_us")
                if (decision.ptsUs <= endUs ||
                    decision.regions.any { r -> r.observations.any { it.ptsUs <= endUs } })
                    return JournalReservation.Conflict
                nonexecutionRetry = true
            } else if (status !in setOf("executed", "failed", "rejected")) return JournalReservation.Conflict
        } else if (decision.revision != 1L) return JournalReservation.Conflict
        prune()
        requireProtocol(previous != null || store.names().size < 4096, "busy")
        val actions = previous?.get("actions")?.asJsonArray ?: JsonArray()
        requireProtocol(actions.size() < 32, "busy")
        actions.add(JsonObject().apply {
            addProperty("revision", decision.revision.toString()); addProperty("digest", semanticSha256); add("result", null)
        })
        store.put(key, StrictJson.json(JsonObject().apply {
            addProperty("revision", decision.revision.toString()); addProperty("digest", semanticSha256)
            addProperty("stage", decision.stage); addProperty("boot_id", bootId)
            addProperty("evidence_end_us", decision.ptsUs.toString())
            addProperty("created_us", now().toString()); addProperty("released", false); add("result", null)
            add("actions", actions)
        }))
        return if (nonexecutionRetry) KnownNonexecutionRetry else JournalReservation.New
    }
    @Synchronized override fun finish(decision: DecisionCommand, result: ExecutionResult) {
        val key = key(decision)
        val record = StrictJson.parse(store.get(key) ?: throw ProtocolFailure("storage_failed"))
        requireProtocol(StrictJson.decimal(record, "revision", true) == decision.revision, "event_conflict")
        record.add("result", gson.toJsonTree(result))
        record["actions"].asJsonArray.last().asJsonObject.add("result", gson.toJsonTree(result))
        // A failed or rejected stage-0 attempt installed nothing; only an executed clearance releases.
        if (result.stage == 0 && result.status == "executed") record.addProperty("released", true)
        store.put(key, StrictJson.json(record))
        if (lastPolicy != decision.policy) { executed.clear(); lastPolicy = decision.policy }
        if (result.status == "executed" && result.stage in 1..2 && result.executedUs != null)
            executed.putIfAbsent(decision.eventId, Triple(decision.packageName, decision.policy, result.executedUs))
    }
    @Synchronized override fun executedEpisodes(packageName: String, policy: PolicyProfile,
        afterUs: Long, nowUs: Long): Set<String> {
        if (lastPolicy != policy) { executed.clear(); lastPolicy = policy }
        executed.entries.removeAll { it.value.third <= afterUs }
        return executed.filterValues { it.first == packageName && it.second == policy &&
            it.third > afterUs && it.third <= nowUs }.keys.toSet()
    }
    @Synchronized override fun release(eventId: String, revision: Long, releasedUs: Long) {
        val key = "j_${StrictJson.uuid(eventId)}"
        val record = store.get(key)?.let(StrictJson::parse) ?: return
        requireProtocol(StrictJson.decimal(record, "revision", true) == revision, "event_conflict")
        record.addProperty("released", true); store.put(key, StrictJson.json(record))
    }
    private fun prune() {
        store.names().forEach { key ->
            val r = store.get(key)?.let(StrictJson::parse) ?: return@forEach
            if (r["released"].asBoolean && StrictJson.string(r, "boot_id") == bootId &&
                now() - StrictJson.decimal(r, "created_us") >= 86_400_000_000L) store.delete(key)
        }
    }
}
