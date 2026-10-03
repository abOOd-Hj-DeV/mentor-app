package dev.k230.mentor_app.protection.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.events.OutboxEntry
import dev.k230.mentor_app.protection.security.PairRole
import dev.k230.mentor_app.protection.security.PeerKeys
import dev.k230.mentor_app.protection.security.RetainedPairTranscript
import dev.k230.mentor_app.protection.security.StrictJson
import dev.k230.mentor_app.protection.security.TrustedPair
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RelayTransportTest {
    private val codec = object : dev.k230.mentor_app.protection.security.Base64Codec {
        override fun encode(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        override fun decode(text: String) = java.util.Base64.getUrlDecoder().decode(text)
    }
    private lateinit var server: MockWebServer
    private lateinit var fixture: JsonObject
    private lateinit var envelope: JsonObject
    private lateinit var transcript: RetainedPairTranscript
    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.take(6)
            .map { File(it, "protocol/v2/fixtures/signature-canonical.json") }.first { it.exists() }
        fixture = StrictJson.parse(root.readBytes()); val request = fixture["request"].asJsonObject
        val pair = TrustedPair(request["pair_id"].asString, PeerKeys.parse(request["guardian"].asJsonObject, codec),
            PeerKeys.parse(request["child"].asJsonObject, codec))
        val offer = request["offer"].asJsonObject; val response = request["response"].asJsonObject; val confirmation = request["confirmation"].asJsonObject
        val digest = StrictJson.sha(StrictJson.canonical(offer) + StrictJson.canonical(response) + StrictJson.canonical(confirmation))
        transcript = RetainedPairTranscript(pair, offer, response, confirmation, digest)
        envelope = fixture["envelope"].asJsonObject
    }
    @After fun tearDown() { server.shutdown() }
    private fun response(o: JsonObject, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(StrictJson.canonical(o).toString(Charsets.UTF_8))
    private fun status(vararg pairs: Pair<String, String>) = JsonObject().apply {
        addProperty("v", 2); pairs.forEach { addProperty(it.first, it.second) }
    }

    @Test fun exactEnvelopeUploadRequiresHashMatchingAcknowledgment() {
        val bytes = StrictJson.canonical(envelope); val entry = OutboxEntry(envelope["message_id"].asString, bytes, StrictJson.sha(bytes))
        server.enqueue(response(status("message_id" to entry.messageId, "status" to "stored",
            "envelope_sha256" to entry.sha256, "received_at_ms" to "1"), 201))
        val client = RelayHttpClient(server.url("/relay").toString().removeSuffix("/"))
        assertEquals(entry.sha256, client.upload(RelayCredentials("child", "real-token"), entry).sha256)
        val request = server.takeRequest()
        assertEquals("Bearer real-token", request.getHeader("Authorization")); assertArrayEquals(bytes, request.body.readByteArray())

        server.enqueue(response(status("message_id" to entry.messageId, "status" to "stored",
            "envelope_sha256" to "a".repeat(64), "received_at_ms" to "1"), 201))
        assertThrows(RelayFailure::class.java) { client.upload(RelayCredentials("child", "real-token"), entry) }
    }

    @Test fun guardianSyncContinuesEmptyCursorAndDeletesOnlyAfterDurableReceive() {
        server.enqueue(response(status("status" to "active", "transcript_sha256" to transcript.sha256)))
        server.enqueue(response(status("status" to "registered")))
        server.enqueue(response(JsonObject().apply {
            addProperty("v", 2); add("envelopes", JsonArray()); addProperty("next_cursor", "next_page")
        }))
        server.enqueue(response(JsonObject().apply {
            addProperty("v", 2); add("envelopes", JsonArray().apply { add(envelope.deepCopy()) }); add("next_cursor", JsonNull.INSTANCE)
        }))
        server.enqueue(response(status("message_id" to envelope["message_id"].asString, "status" to "deleted")))
        val adapter = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.guardian.deviceId, transcript))
        val sync = NativeRelaySync(RelayIdentity { RelayCredentials("guardian", "id-token") }, RelayPushToken { "push-token" },
            RelayHttpClient(server.url("/relay").toString().removeSuffix("/")), adapter, {1}, {1})
        assertTrue(sync.synchronize(10000)); assertEquals(1, adapter.received.size); assertNull(adapter.savedCursor)
        assertEquals(RelayState.ACTIVE, adapter.state)
        val requests = (1..5).map { server.takeRequest() }
        assertArrayEquals(transcript.registrationBody(), requests[0].body.readByteArray())
        assertEquals("next_page", requests[3].requestUrl!!.queryParameter("after"))
        assertEquals("DELETE", requests[4].method)
    }

    @Test fun failedDurableReceiveNeverDeletesServerEnvelope() {
        server.enqueue(response(status("status" to "active", "transcript_sha256" to transcript.sha256)))
        server.enqueue(response(status("status" to "registered")))
        server.enqueue(response(JsonObject().apply {
            addProperty("v", 2); add("envelopes", JsonArray().apply { add(envelope.deepCopy()) }); add("next_cursor", JsonNull.INSTANCE)
        }))
        val adapter = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.guardian.deviceId, transcript), failReceive = true)
        val sync = NativeRelaySync(RelayIdentity { RelayCredentials("guardian", "id-token") }, RelayPushToken { "push-token" },
            RelayHttpClient(server.url("/relay").toString().removeSuffix("/")), adapter, {1}, {1})
        assertThrows(IllegalStateException::class.java) { sync.synchronize(10000) }
        assertEquals(3, server.requestCount)
    }

    @Test fun unconfiguredBindingNeverObtainsIdentityOrUsesNetwork() {
        val adapter = MemoryAdapter(null)
        val sync = NativeRelaySync(RelayIdentity { error("must not authenticate") }, RelayPushToken { error("must not request token") },
            RelayHttpClient(server.url("/relay").toString()), adapter)
        assertTrue(sync.synchronize()); assertEquals(RelayState.UNCONFIGURED, adapter.state); assertEquals(0, server.requestCount)
    }

    @Test fun mismatchedRealUidIsRejectedBeforeAnyHttpRequest() {
        val adapter = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.guardian.deviceId, transcript))
        val sync = NativeRelaySync(RelayIdentity { RelayCredentials("wrong-uid", "id-token") }, RelayPushToken { "push-token" },
            RelayHttpClient(server.url("/relay").toString()), adapter)
        assertThrows(dev.k230.mentor_app.protection.security.SecurityFailure::class.java) { sync.synchronize() }
        assertEquals(0, server.requestCount)
    }

    @Test fun committedRegistrationIsNotRepostedAfterQrExpiry() {
        server.enqueue(response(status("status" to "registered")))
        server.enqueue(response(JsonObject().apply { addProperty("v", 2); add("envelopes", JsonArray()); add("next_cursor", JsonNull.INSTANCE) }))
        val adapter = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.guardian.deviceId, transcript))
        adapter.storePairPhase(RelayPairPhase.ACTIVE)
        val sync = NativeRelaySync(RelayIdentity { RelayCredentials("guardian", "id-token") }, RelayPushToken { "push-token" },
            RelayHttpClient(server.url("/relay").toString()), adapter, { Long.MAX_VALUE / 2 }, {1})
        assertTrue(sync.synchronize()); assertEquals("/relay/v2/push-token", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun childAcceptUsesOnlyRetainedTranscriptDigest() {
        server.enqueue(response(status("pair_id" to transcript.pair.pairId, "status" to "active")))
        server.enqueue(response(status("status" to "registered")))
        server.enqueue(response(JsonObject().apply { addProperty("v", 2); add("envelopes", JsonArray()); add("next_cursor", JsonNull.INSTANCE) }))
        val adapter = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.child.deviceId, transcript))
        val sync = NativeRelaySync(RelayIdentity { RelayCredentials("child", "id-token") }, RelayPushToken { "push-token" },
            RelayHttpClient(server.url("/relay").toString()), adapter)
        assertTrue(sync.synchronize())
        val accept = server.takeRequest(); assertTrue(accept.requestUrl!!.encodedPath.endsWith("/accept"))
        assertArrayEquals(transcript.membershipBody(), accept.body.readByteArray())
    }

    @Test fun retryableUploadFailurePersistsBackoffAndNeverAcknowledges() {
        server.enqueue(response(status("status" to "registered")))
        server.enqueue(response(status("error" to "unavailable"), 503))
        val adapter = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.child.deviceId, transcript))
        adapter.storePairPhase(RelayPairPhase.ACTIVE)
        val bytes = StrictJson.canonical(envelope)
        adapter.outgoing += OutboxEntry(envelope["message_id"].asString, bytes, StrictJson.sha(bytes))
        val sync = NativeRelaySync(RelayIdentity { RelayCredentials("child", "id-token") }, RelayPushToken { "push-token" },
            RelayHttpClient(server.url("/relay").toString()), adapter, {1}, {1}, dev.k230.mentor_app.protection.security.IncidentCrypto(codec))
        assertThrows(RelayFailure::class.java) { sync.synchronize() }
        assertEquals(1, adapter.failed); assertEquals(1, adapter.outgoing.size); assertTrue(adapter.acknowledged.isEmpty())
    }

    @Test fun staleCursorIsClearedAndFetchRestartsWithoutSkippingInbox() {
        server.enqueue(response(status("status" to "registered")))
        server.enqueue(response(status("error" to "forbidden"), 403))
        server.enqueue(response(JsonObject().apply { addProperty("v", 2); add("envelopes", JsonArray()); add("next_cursor", JsonNull.INSTANCE) }))
        val adapter = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.guardian.deviceId, transcript))
        adapter.storePairPhase(RelayPairPhase.ACTIVE); adapter.storeCursor("stale_cursor")
        val sync = NativeRelaySync(RelayIdentity { RelayCredentials("guardian", "id-token") }, RelayPushToken { "push-token" },
            RelayHttpClient(server.url("/relay").toString()), adapter)
        assertTrue(sync.synchronize()); server.takeRequest()
        assertEquals("stale_cursor", server.takeRequest().requestUrl!!.queryParameter("after"))
        assertNull(server.takeRequest().requestUrl!!.queryParameter("after"))
    }

    @Test fun revokeIsGuardianOnlyAndUsesExactRetainedMembershipBody() {
        server.enqueue(response(status("pair_id" to transcript.pair.pairId, "status" to "revoked")))
        val adapter = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.guardian.deviceId, transcript))
        val sync = NativeRelaySync(RelayIdentity { RelayCredentials("guardian", "id-token") }, RelayPushToken { "push-token" },
            RelayHttpClient(server.url("/relay").toString()), adapter)
        sync.revoke(); assertEquals(RelayState.REVOKED, adapter.state)
        val revoke = server.takeRequest(); assertTrue(revoke.requestUrl!!.encodedPath.endsWith("/revoke"))
        assertArrayEquals(transcript.membershipBody(), revoke.body.readByteArray())
        val child = MemoryAdapter(RelayBinding(transcript.pair, transcript.pair.child.deviceId, transcript))
        assertThrows(dev.k230.mentor_app.protection.security.SecurityFailure::class.java) {
            NativeRelaySync(RelayIdentity { RelayCredentials("child", "id-token") }, RelayPushToken { "push-token" },
                RelayHttpClient(server.url("/relay").toString()), child).revoke()
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun absoluteCallDeadlineStopsTricklingResponse() {
        val bytes = StrictJson.canonical(envelope); val entry = OutboxEntry(envelope["message_id"].asString, bytes, StrictJson.sha(bytes))
        server.enqueue(response(status("message_id" to entry.messageId, "status" to "stored",
            "envelope_sha256" to entry.sha256, "received_at_ms" to "1")).throttleBody(1, 200, TimeUnit.MILLISECONDS))
        val client = RelayHttpClient(server.url("/relay").toString(), OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS).build())
        val start = System.nanoTime()
        val failure = assertThrows(RelayFailure::class.java) { client.upload(RelayCredentials("child", "id-token"), entry) }
        assertTrue(failure.retryable); assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test fun noAckAndOversizeResponsesRemainBounded() {
        val bytes = StrictJson.canonical(envelope); val entry = OutboxEntry(envelope["message_id"].asString, bytes, StrictJson.sha(bytes))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val client = RelayHttpClient(server.url("/relay").toString(), OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS).build())
        val start = System.nanoTime()
        assertTrue(assertThrows(RelayFailure::class.java) { client.upload(RelayCredentials("child", "id-token"), entry) }.retryable)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
        server.enqueue(MockResponse().setBody(" ".repeat(5000)))
        assertEquals("bounds", assertThrows(RelayFailure::class.java) { client.upload(RelayCredentials("child", "id-token"), entry) }.fixedCode)
    }

    @Test fun publicConfigurationDefaultsAndFcmWakeUpsFailClosed() {
        assertNull(RelayConfigurationLoader.fromPublicValues(List(5) { "" }, false))
        assertThrows(dev.k230.mentor_app.protection.security.SecurityFailure::class.java) {
            RelayConfigurationLoader.fromPublicValues(listOf("https://relay.example", "", "", "", ""), false)
        }
        assertThrows(dev.k230.mentor_app.protection.security.SecurityFailure::class.java) {
            RelayConfigurationLoader.fromPublicValues(listOf("http://relay.example", "app", "key", "project", "sender"), false)
        }
        assertFalse(RelayConfigurationLoader.fromPublicValues(listOf("https://relay.example", "app", "key", "project", "sender"), false)!!.allowAnonymousAuth)
        val data = mapOf("v" to "2", "type" to "inbox_changed")
        assertTrue(isGenericRelayWakeUp(data, false)); assertFalse(isGenericRelayWakeUp(data, true))
        assertFalse(isGenericRelayWakeUp(data + ("message_id" to "must-not-be-sent"), false))
    }

    private class MemoryAdapter(private val value: RelayBinding?, private val failReceive: Boolean = false) : NativeRelayAdapter {
        val received = mutableListOf<ByteArray>(); var savedCursor: String? = null; var state: RelayState? = null
        val outgoing = mutableListOf<OutboxEntry>(); val acknowledged = mutableListOf<RelayAcknowledgment>(); var failed = 0
        override fun binding() = value
        override fun pending(nowMs: Long, limit: Int) = outgoing.take(limit)
        override fun acknowledge(ack: RelayAcknowledgment) { acknowledged += ack; outgoing.removeAll { it.messageId == ack.messageId } }
        override fun failedAttempt(messageId: String, nowMs: Long): Long { failed++; return nowMs + 30000 }
        override fun receiveEnvelope(canonicalEnvelope: ByteArray) { if (failReceive) error("storage unavailable"); received += canonicalEnvelope }
        override fun cursor() = savedCursor
        override fun storeCursor(cursor: String?) { savedCursor = cursor }
        private var phase = RelayPairPhase.UNREGISTERED
        override fun pairPhase() = phase
        override fun storePairPhase(phase: RelayPairPhase) { this.phase = phase }
        override fun relayState(state: RelayState, fixedError: String?) { this.state = state }
    }
}
