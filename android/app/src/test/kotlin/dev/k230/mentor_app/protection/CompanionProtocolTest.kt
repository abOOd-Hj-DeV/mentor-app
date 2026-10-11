package dev.k230.mentor_app.protection

import org.junit.Assert.*
import org.junit.Test

class CompanionProtocolTest {
    private fun fails(code: String? = null, block: () -> Unit) {
        try { block(); fail("must reject") }
        catch (e: ProtocolFailure) { if (code != null) assertEquals(code, e.code) }
    }
    @Test fun exactBindAndDecisionAndRectScaling() {
        val bind = CompanionProtocol.parse(CompanionProtocol.encode(ProtocolFixtures.bind())) as BindCommand
        assertEquals(1, bind.seq)
        val d = ProtocolFixtures.parse(ProtocolFixtures.decision())
        assertEquals(listOf(PixelRect(60, 300, 480, 900)), ProtocolFixtures.valid(d))
    }
    @Test fun invalidAgesNeverReplacePreviousProfile() {
        val valid = PolicyProfile(13, 42)
        for (age in listOf(9, 16, 0, -1, Int.MAX_VALUE)) fails("invalid_age") { PolicyProfile(age, 43) }
        assertEquals("13-15", valid.profile)
        assertEquals(42L, valid.revision)
        for (age in listOf(10, 11, 12)) assertEquals("10-12", PolicyProfile(age, 1).profile)
        for (age in listOf(13, 14, 15)) assertEquals("13-15", PolicyProfile(age, 1).profile)
        val text = String(CompanionProtocol.encode(ProtocolFixtures.decision()))
        for (value in listOf("12.5", "null", "\"12\"", "true")) {
            fails { CompanionProtocol.parse(text.replace("\"age\":12", "\"age\":$value").toByteArray()) }
        }
    }
    @Test fun explicitScoreAlwaysExcludesSexyAndAllowsTinySumDrift() {
        assertEquals(.3, Scores(.1, .2, .7).explicit, 1e-12)
        assertEquals(0.0, Scores(0.0, 0.0, 1.0).explicit, 0.0)
        assertEquals(1.0, Scores(.6, .4005, 0.0).explicit, 0.0)
        for (p in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.001, 1.01)) fails("invalid_scores") { Scores(p, 0.0, 0.0) }
        fails("invalid_scores") { Scores(.6, .402, 0.0) }
        fails("invalid_evidence") { ProtocolFixtures.valid(ProtocolFixtures.parse(ProtocolFixtures.decision(p=0.0, h=0.0, s=1.0))) }
    }
    @Test fun parserRejectsDuplicateKeysUnknownFieldsAndNonJsonSyntax() {
        val text = String(CompanionProtocol.encode(ProtocolFixtures.bind()))
        val invalid = listOf(
            text.replace("\"v\":2", "\"v\":2,\"v\":2"),
            text.replace("\"mirror\":false", "\"mirror\":false,\"mirror\":false"),
            text.replace("\"v\":2", "\"v\":2,\"extra\":0"),
            text.replace("\"mirror\":false", "\"mirror\":false,\"extra\":0"),
            text.replace("\"v\":2", "\"v\":true"), text.replace("\"v\":2", "\"v\":2.0"),
            text.replace("\"v\":2", "\"v\":NaN"), text.replace("\"seq\":\"1\"", "\"seq\":1"),
            text.replace("\"seq\":\"1\"", "\"seq\":\"01\""),
            text.replace("\"seq\":\"1\"", "\"seq\":\"9223372036854775808\""),
            text.replace("\"stream_id\":", "\"stream_id\":/*x*/"),
            text.replace("\n", "\r\n"), "\uFEFF$text", "[]\n", "null\n", "\n",
            text.trimEnd() + "{}\n", text.replace("\"v\"", "'v'"), text.dropLast(3) + "\n",
        )
        invalid.forEach { fails { CompanionProtocol.parse(it.toByteArray()) } }
        fails("unsupported_version") { CompanionProtocol.parse(text.replace("\"v\":2", "\"v\":1").toByteArray()) }
        fails { CompanionProtocol.parse(byteArrayOf(123, 34, -61, 40, 34, 58, 48, 125, 10)) }
        fails { CompanionProtocol.parse(text.replace("\"v\"", "\"v\u0000\"").toByteArray()) }
    }
    @Test fun parserIsBoundedByDepthMembersArraysAndLineSize() {
        for (json in listOf(
                "[".repeat(11) + "0" + "]".repeat(11),
                (0..32).joinToString(",", "{", "}") { "\"k$it\":0" },
                (0..32).joinToString(",", "[", "]") { "0" },
            )) fails { CompanionProtocol.parse((json + "\n").toByteArray()) }
        fails { CompanionProtocol.parse(ByteArray(16_385) { if (it == 16_384) 10 else 32 }) }
    }
    @Test fun invalidScoreAndTransformAndUnsupportedKindsRejected() {
        val text = String(CompanionProtocol.encode(ProtocolFixtures.decision()))
        fails("invalid_scores") { CompanionProtocol.parse(text.replace("\"explicit_score\":0.67", "\"explicit_score\":0.70").toByteArray()) }
        fails("invalid_evidence") { CompanionProtocol.parse(text.replace("\"Image\"", "\"WebView\"").toByteArray()) }
        fails("invalid_evidence") { CompanionProtocol.parse(text.replace("\"analysis_complete\":true", "\"analysis_complete\":false").toByteArray()) }
        fails("invalid_transform") { CompanionProtocol.parse(text.replace("\"rotation_cw_deg\":0", "\"rotation_cw_deg\":90").toByteArray()) }
        fails { CompanionProtocol.parse(text.replace("\"x\":20", "\"x\":2147483647").toByteArray()) }
        val bind = String(CompanionProtocol.encode(ProtocolFixtures.bind()))
        fails("invalid_transform") { CompanionProtocol.parse(bind.replace("\"custom_crop\":false", "\"custom_crop\":true").toByteArray()) }
    }
    @Test fun idempotencyDigestIgnoresWireOrderSeqExpiryAndSession() {
        val map = ProtocolFixtures.decision()
        val a = ProtocolFixtures.parse(map)
        val b = ProtocolFixtures.parse(map.entries.reversed().associate { it.toPair() } +
            mapOf("seq" to "99", "session_id" to ProtocolFixtures.CONTINUITY, "expires_at_us" to "123"))
        assertEquals(a.semanticJson, b.semanticJson)
        assertNotEquals(a.semanticJson, ProtocolFixtures.parse(map + mapOf("requested_stage" to 2,
            "requested_action" to "calm_shield")).semanticJson)
    }
    @Test fun slowlorisDeadlineNotResetByTrickleOrIdle() {
        val f = BoundedLines()
        assertTrue(f.append(byteArrayOf(123), 1, 1_000_000).isEmpty())
        assertTrue(f.append(byteArrayOf(32), 1, 1_499_999).isEmpty())
        fails("stale") { f.checkDeadline(1_500_000) }
        val idle = BoundedLines()
        idle.checkDeadline(999_999_999)
        assertEquals(1, idle.append("{}\n".toByteArray(), 3, 999_999_999).size)
        idle.checkDeadline(Long.MAX_VALUE)
        fails { BoundedLines().append(ByteArray(16_384) { 32 }, 16_384, 0) }
        fails("malformed_json") { BoundedLines().append(byteArrayOf(10), 1, 0) }
    }
}
