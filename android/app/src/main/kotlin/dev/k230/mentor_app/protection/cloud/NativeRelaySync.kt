package dev.k230.mentor_app.protection.cloud

import android.content.Context
import dev.k230.mentor_app.protection.events.OutboxEntry
import dev.k230.mentor_app.protection.security.PairRole
import dev.k230.mentor_app.protection.security.IncidentCrypto
import dev.k230.mentor_app.protection.security.RetainedPairTranscript
import dev.k230.mentor_app.protection.security.SecurityFailure
import dev.k230.mentor_app.protection.security.StrictJson
import dev.k230.mentor_app.protection.security.TrustedPair

data class RelayBinding(
    val pair: TrustedPair,
    val localDeviceId: String,
    val transcript: RetainedPairTranscript,
)

enum class RelayState { UNCONFIGURED, AUTHENTICATING, PAIR_PENDING, ACTIVE, ACTIVE_WITHOUT_PUSH, BACKOFF, REVOKED, ERROR }
enum class RelayPairPhase { UNREGISTERED, PENDING, ACTIVE, REVOKED }

/**
 * Integration boundary. Implementations must use pair-scoped encrypted stores. receiveEnvelope must
 * verify/decrypt and durably merge/apply replay-safely before returning; throwing prevents server deletion.
 * Cursor writes must also be durable so expired/empty pages cannot starve recovery after process death.
 */
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

fun interface NativeRelayAdapterFactory { fun create(context: Context): NativeRelayAdapter }

class NativeRelaySync(
    private val identity: RelayIdentity,
    private val pushToken: RelayPushToken,
    private val http: RelayHttpClient,
    private val adapter: NativeRelayAdapter,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val crypto: IncidentCrypto = IncidentCrypto(),
) {
    fun synchronize(maximumMs: Long = 240000): Boolean {
        val binding = adapter.binding() ?: run { adapter.relayState(RelayState.UNCONFIGURED); return true }
        if (adapter.pairPhase() == RelayPairPhase.REVOKED) { adapter.relayState(RelayState.REVOKED); return true }
        val deadline = Math.addExact(monotonicMs(), maximumMs.coerceIn(1000, 240000))
        adapter.relayState(RelayState.AUTHENTICATING)
        return try { synchronize(binding, identity.credentials(false), deadline) }
        catch (failure: RelayFailure) {
            if (failure.fixedCode == "unauthenticated") synchronize(binding, identity.credentials(true), deadline) else throw failure
        }
    }

    private fun synchronize(binding: RelayBinding, credentials: RelayCredentials, deadline: Long): Boolean {
        ensureSamePair(binding)
        val role = binding.transcript.role(binding.localDeviceId)
        val peerUid = if (role == PairRole.GUARDIAN) binding.pair.guardian.authUid else binding.pair.child.authUid
        if (peerUid == null || peerUid != credentials.uid) throw SecurityFailure("identity_mismatch")
        checkDeadline(deadline)
        var firstPage: RelayPage? = null
        var phase = adapter.pairPhase()
        if (role == PairRole.GUARDIAN && phase == RelayPairPhase.UNREGISTERED &&
            StrictJson.decimal(binding.transcript.offer, "expires_at_ms") >= nowMs()) {
            val status = http.register(credentials, binding.transcript)
            phase = if (status.active) RelayPairPhase.ACTIVE else RelayPairPhase.PENDING
            adapter.storePairPhase(phase)
        }
        if (role == PairRole.CHILD && phase != RelayPairPhase.ACTIVE) {
            try { http.accept(credentials, binding.transcript) }
            catch (failure: RelayFailure) {
                if (failure.fixedCode == "forbidden") { adapter.relayState(RelayState.PAIR_PENDING); return false }
                throw failure
            }
            phase = RelayPairPhase.ACTIVE; adapter.storePairPhase(phase)
        }
        if (role == PairRole.GUARDIAN && phase != RelayPairPhase.ACTIVE) {
            firstPage = try { http.fetch(credentials, binding.pair.pairId, null) }
            catch (failure: RelayFailure) {
                if (failure.fixedCode == "forbidden") {
                    adapter.relayState(RelayState.PAIR_PENDING,
                        if (phase == RelayPairPhase.UNREGISTERED) "pair_registration_expired" else null)
                    return false
                }
                throw failure
            }
            adapter.storeCursor(null); adapter.storePairPhase(RelayPairPhase.ACTIVE)
        }

        var pushReady = true
        try { checkDeadline(deadline); http.registerPushToken(credentials, binding.localDeviceId, pushToken.token()) }
        catch (_: Exception) { pushReady = false }

        val pending = adapter.pending(nowMs(), 32)
        for (entry in pending) {
            checkDeadline(deadline)
            val parsed = crypto.validateEnvelope(entry.envelope, binding.pair)
            StrictJson.ensure(StrictJson.string(parsed, "sender_id") == binding.localDeviceId &&
                StrictJson.string(parsed, "message_id") == entry.messageId && StrictJson.sha(entry.envelope) == entry.sha256,
                "pair_mismatch")
            try { adapter.acknowledge(http.upload(credentials, entry)) }
            catch (failure: RelayFailure) {
                if (failure.retryable) adapter.failedAttempt(entry.messageId, nowMs())
                throw failure
            }
        }

        var cursor = adapter.cursor(); var pages = 0; var restartedCursor = false
        while (pages++ < 8) {
            checkDeadline(deadline)
            val page = try { firstPage?.also { firstPage = null } ?: http.fetch(credentials, binding.pair.pairId, cursor) }
            catch (failure: RelayFailure) {
                if (cursor != null && failure.fixedCode == "forbidden" && !restartedCursor) {
                    adapter.storeCursor(null); cursor = null; restartedCursor = true; continue
                }
                throw failure
            }
            for (envelope in page.envelopes) {
                checkDeadline(deadline)
                adapter.receiveEnvelope(envelope)
                val parsed = StrictJson.parse(envelope)
                http.delete(credentials, binding.pair.pairId, StrictJson.string(parsed, "message_id"))
            }
            adapter.storeCursor(page.nextCursor); cursor = page.nextCursor
            if (cursor == null) break
        }
        adapter.relayState(if (pushReady) RelayState.ACTIVE else RelayState.ACTIVE_WITHOUT_PUSH)
        return pending.size < 32 && cursor == null
    }

    fun revoke() {
        val binding = adapter.binding() ?: throw SecurityFailure("pair_missing")
        ensureSamePair(binding)
        if (binding.transcript.role(binding.localDeviceId) != PairRole.GUARDIAN) throw SecurityFailure("guardian_only")
        val credentials = identity.credentials(false)
        if (binding.pair.guardian.authUid != credentials.uid) throw SecurityFailure("guardian_only")
        http.revoke(credentials, binding.transcript)
        adapter.storePairPhase(RelayPairPhase.REVOKED)
        adapter.relayState(RelayState.REVOKED)
    }

    private fun ensureSamePair(binding: RelayBinding) {
        val directPair = binding.transcript.pair
        StrictJson.ensure(binding.pair.pairId == directPair.pairId &&
            StrictJson.canonical(binding.pair.guardian.descriptor).contentEquals(StrictJson.canonical(directPair.guardian.descriptor)) &&
            StrictJson.canonical(binding.pair.child.descriptor).contentEquals(StrictJson.canonical(directPair.child.descriptor)), "pair_mismatch")
    }

    private fun checkDeadline(deadline: Long) {
        if (monotonicMs() >= deadline) throw RelayFailure("unavailable", 503, true)
    }
}
