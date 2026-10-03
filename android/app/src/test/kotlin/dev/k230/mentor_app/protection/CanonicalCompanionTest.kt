package dev.k230.mentor_app.protection

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CanonicalCompanionTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "protocol/v2") }.first { File(it, "companion.manifest.json").isFile }
    private fun fixture(name: String) = JsonParser.parseString(File(root, "fixtures/$name.json").readText()).asJsonObject
    @Test fun exactCanonicalManifestAssetsAndNativeParser() {
        val manifest = JsonParser.parseString(File(root, "companion.manifest.json").readText()).asJsonObject
        assertEquals("mentor-parental-v2.0", manifest["contract"].asString)
        assertEquals(9, manifest["sha256"].asJsonObject.size())
        manifest["sha256"].asJsonObject.entrySet().forEach { (name, hash) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(File(root, name).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(name, hash.asString, digest)
        }
        val bind = CompanionProtocol.parse((fixture("bind").toString() + "\n").toByteArray()) as BindCommand
        assertNull(bind.capturePtsUs)
        val decision = CompanionProtocol.parse((fixture("decision").toString() + "\n").toByteArray()) as DecisionCommand
        assertEquals(listOf(PixelRect(60, 300, 480, 900)), ProtocolFixtures.valid(decision))
        val inactive = fixture("state")["protection"].asJsonObject
        protectionWire(null).forEach { (name, value) ->
            if (value == null) assertTrue(inactive[name].isJsonNull)
            else assertEquals(name, JsonParser.parseString(com.google.gson.Gson().toJson(value)), inactive[name])
        }
    }
    @Test fun fortyCanonicalPolicyTracesValidateNativeProofThresholds() {
        val cases = fixture("policy-traces")["cases"].asJsonArray
        assertEquals(40, cases.size())
        cases.forEach { entry ->
            val c = entry.asJsonObject
            val name = c["name"].asString
            val expected = c["expected_stage"].asInt
            val pts = List(c["count"].asInt) { 10_000_000L + it * c["spacing_us"].asLong }
            fun accepts(stage: Int): Boolean = try {
                val d = ProtocolFixtures.parse(ProtocolFixtures.decision(stage, c["porn"].asDouble,
                    c["hentai"].asDouble, c["sexy"].asDouble, pts, c["age"].asInt))
                ProtocolFixtures.valid(d); true
            } catch (_: ProtocolFailure) { false }
            if (expected > 0) assertTrue(name, accepts(expected))
            // Too-short HOME traces describe the exit chain, not lower-stage evidence.
            val rejectedStage = if (name.endsWith("exit_five_too_short")) 3 else maxOf(expected + 1, 1)
            if (rejectedStage <= 3) assertFalse(name, accepts(rejectedStage))
        }
    }
}
