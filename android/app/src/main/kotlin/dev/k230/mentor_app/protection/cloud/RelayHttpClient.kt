package dev.k230.mentor_app.protection.cloud

import com.google.gson.JsonObject
import dev.k230.mentor_app.protection.events.OutboxEntry
import dev.k230.mentor_app.protection.security.RetainedPairTranscript
import dev.k230.mentor_app.protection.security.StrictJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class RelayFailure(val fixedCode: String, val status: Int, val retryable: Boolean) : Exception(fixedCode)
data class PairRelayStatus(val active: Boolean, val transcriptSha256: String)
data class RelayAcknowledgment(val messageId: String, val status: String, val sha256: String)
data class RelayPage(val envelopes: List<ByteArray>, val nextCursor: String?)

class RelayHttpClient(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    fun register(credentials: RelayCredentials, transcript: RetainedPairTranscript): PairRelayStatus {
        val o = response(post("/v2/pairs", credentials, transcript.registrationBody()), 16384)
        StrictJson.exact(o, "v", "status", "transcript_sha256")
        val status = StrictJson.string(o, "status"); val digest = StrictJson.hex(StrictJson.string(o, "transcript_sha256"))
        requireV2(o); if (digest != transcript.sha256 || status !in setOf("pending", "active")) throw RelayFailure("invalid_envelope", 502, false)
        return PairRelayStatus(status == "active", digest)
    }

    fun accept(credentials: RelayCredentials, transcript: RetainedPairTranscript): PairRelayStatus =
        membership("accept", "active", credentials, transcript)

    fun revoke(credentials: RelayCredentials, transcript: RetainedPairTranscript): PairRelayStatus =
        membership("revoke", "revoked", credentials, transcript)

    private fun membership(action: String, expected: String, credentials: RelayCredentials,
                           transcript: RetainedPairTranscript): PairRelayStatus {
        val o = response(post("/v2/pairs/${transcript.pair.pairId}/$action", credentials, transcript.membershipBody()), 4096)
        StrictJson.exact(o, "v", "pair_id", "status"); requireV2(o)
        if (StrictJson.string(o, "pair_id") != transcript.pair.pairId || StrictJson.string(o, "status") != expected)
            throw RelayFailure("invalid_envelope", 502, false)
        return PairRelayStatus(expected == "active", transcript.sha256)
    }

    fun upload(credentials: RelayCredentials, entry: OutboxEntry): RelayAcknowledgment {
        StrictJson.ensure(entry.envelope.size in 1..24576 && StrictJson.sha(entry.envelope) == entry.sha256, "bounds")
        val o = response(post("/v2/envelopes", credentials, entry.envelope), 4096)
        StrictJson.exact(o, "v", "message_id", "status", "envelope_sha256", "received_at_ms"); requireV2(o)
        val status = StrictJson.string(o, "status")
        StrictJson.decimal(o, "received_at_ms", true)
        if (StrictJson.string(o, "message_id") != entry.messageId || status !in setOf("stored", "duplicate") ||
            StrictJson.hex(StrictJson.string(o, "envelope_sha256")) != entry.sha256) throw RelayFailure("event_conflict", 409, false)
        return RelayAcknowledgment(entry.messageId, status, entry.sha256)
    }

    fun fetch(credentials: RelayCredentials, pairId: String, cursor: String?): RelayPage {
        val builder = (baseUrl + "/v2/envelopes").toHttpUrl().newBuilder()
            .addQueryParameter("pair_id", StrictJson.uuid(pairId)).addQueryParameter("limit", "32")
        if (cursor != null) builder.addQueryParameter("after", cursor)
        val o = response(execute(Request.Builder().url(builder.build()).get().auth(credentials).build()), 1024 * 1024)
        StrictJson.exact(o, "v", "envelopes", "next_cursor"); requireV2(o)
        val array = o["envelopes"]?.takeIf { it.isJsonArray }?.asJsonArray ?: throw RelayFailure("invalid_envelope", 502, false)
        if (array.size() > 32) throw RelayFailure("bounds", 502, false)
        val envelopes = array.map {
            if (!it.isJsonObject) throw RelayFailure("invalid_envelope", 502, false)
            StrictJson.canonical(it.asJsonObject).also { bytes -> if (bytes.size > 24576) throw RelayFailure("bounds", 502, false) }
        }
        val next = o["next_cursor"].let { value ->
            if (value == null || value.isJsonNull) null else StrictJson.string(o, "next_cursor").also {
                if (!it.matches(Regex("[A-Za-z0-9_-]{1,512}"))) throw RelayFailure("bounds", 502, false)
            }
        }
        return RelayPage(envelopes, next)
    }

    fun delete(credentials: RelayCredentials, pairId: String, messageId: String) {
        val url = (baseUrl + "/v2/envelopes/${StrictJson.uuid(messageId)}").toHttpUrl().newBuilder()
            .addQueryParameter("pair_id", StrictJson.uuid(pairId)).build()
        val o = response(execute(Request.Builder().url(url).delete().auth(credentials).build()), 4096)
        StrictJson.exact(o, "v", "message_id", "status"); requireV2(o)
        if (StrictJson.string(o, "message_id") != messageId || StrictJson.string(o, "status") != "deleted")
            throw RelayFailure("invalid_envelope", 502, false)
    }

    fun registerPushToken(credentials: RelayCredentials, deviceId: String, token: String) {
        if (!token.matches(Regex("[\\x21-\\x7e]{1,4096}"))) throw RelayFailure("bounds", 400, false)
        val body = StrictJson.canonical(JsonObject().apply {
            addProperty("v", 2); addProperty("device_id", StrictJson.uuid(deviceId)); addProperty("token", token)
        })
        val o = response(post("/v2/push-token", credentials, body), 4096)
        StrictJson.exact(o, "v", "status"); requireV2(o)
        if (StrictJson.string(o, "status") != "registered") throw RelayFailure("invalid_envelope", 502, false)
    }

    private fun post(path: String, credentials: RelayCredentials, bytes: ByteArray) = execute(Request.Builder()
        .url((baseUrl + path).toHttpUrl()).post(bytes.toRequestBody(json)).auth(credentials).build())
    private fun Request.Builder.auth(credentials: RelayCredentials) = header("Authorization", "Bearer ${credentials.idToken}")
        .header("Accept", "application/json").header("Cache-Control", "no-store").apply {
            credentials.appCheckToken?.let { header("X-Firebase-AppCheck", it) }
        }

    private fun execute(request: Request): Response = try { client.newCall(request).execute() }
        catch (_: Exception) { throw RelayFailure("unavailable", 503, true) }

    private fun response(response: Response, maximum: Int): JsonObject = response.use {
        val bytes = readBounded(it, if (it.isSuccessful) maximum else 4096)
        val o = try { StrictJson.parse(bytes, if (it.isSuccessful) maximum else 4096) }
        catch (_: Exception) { throw RelayFailure("unavailable", it.code, it.code >= 500 || it.code == 408 || it.code == 429) }
        if (!it.isSuccessful) {
            StrictJson.exact(o, "v", "error"); requireV2(o)
            val code = StrictJson.string(o, "error")
            throw RelayFailure(code, it.code, it.code >= 500 || it.code == 408 || it.code == 429)
        }
        o
    }
    private fun readBounded(response: Response, maximum: Int): ByteArray {
        val body = response.body ?: throw RelayFailure("unavailable", response.code, true)
        if (body.contentLength() > maximum) throw RelayFailure("bounds", response.code, false)
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); val stream = body.byteStream()
        try {
            while (true) { val count = stream.read(buffer); if (count < 0) break; if (output.size() + count > maximum) throw RelayFailure("bounds", response.code, false); output.write(buffer, 0, count) }
        } catch (_: IOException) { throw RelayFailure("unavailable", 503, true) }
        return output.toByteArray()
    }
    private fun requireV2(o: JsonObject) { if (StrictJson.int(o, "v") != 2) throw RelayFailure("unsupported_version", 502, false) }
}
