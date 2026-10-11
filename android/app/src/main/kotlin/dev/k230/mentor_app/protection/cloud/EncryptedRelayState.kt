package dev.k230.mentor_app.protection.cloud

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.security.SealedStore
import dev.k230.mentor_app.protection.security.StrictJson

/** Supply a pair-scoped SealedStore with its own cloud-state purpose, never SharedPreferences. */
class EncryptedRelayState(private val store: SealedStore) {
    @Synchronized fun pairPhase(): RelayPairPhase {
        val o = read() ?: return RelayPairPhase.UNREGISTERED
        return RelayPairPhase.valueOf(StrictJson.string(o, "phase"))
    }
    @Synchronized fun cursor(): String? {
        val o = read() ?: return null
        return o["cursor"].takeUnless { it.isJsonNull }?.let { StrictJson.string(o, "cursor") }
    }
    @Synchronized fun storePairPhase(phase: RelayPairPhase) { write(phase, cursor()) }
    @Synchronized fun storeCursor(cursor: String?) { write(pairPhase(), cursor) }

    private fun read(): JsonObject? = store.get("relay_state")?.let { bytes ->
        StrictJson.parse(bytes, 4096).also {
            StrictJson.exact(it, "v", "phase", "cursor"); StrictJson.ensure(StrictJson.int(it, "v") == 2, "storage_failed")
            StrictJson.ensure(StrictJson.string(it, "phase") in RelayPairPhase.entries.map(RelayPairPhase::name), "storage_failed")
            if (!it["cursor"].isJsonNull) checkCursor(StrictJson.string(it, "cursor"))
        }
    }
    private fun write(phase: RelayPairPhase, cursor: String?) {
        cursor?.let(::checkCursor)
        store.put("relay_state", StrictJson.canonical(JsonObject().apply {
            addProperty("v", 2); addProperty("phase", phase.name)
            if (cursor == null) add("cursor", JsonNull.INSTANCE) else addProperty("cursor", cursor)
        }))
    }
    private fun checkCursor(cursor: String) { StrictJson.ensure(cursor.matches(Regex("[A-Za-z0-9_-]{1,512}")), "bounds") }
}
