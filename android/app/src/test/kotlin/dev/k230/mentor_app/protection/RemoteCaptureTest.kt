package dev.k230.mentor_app.protection

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RemoteCaptureTest {
    @After fun cleanup() { RemoteCaptureProof.clear() }

    @Test fun configurationRejectsCleartextAndEmbeddedCredentials() {
        for (endpoint in listOf("http://server:8443", "tls://user:pass@server:8443", "tls://server:0", "tls://server:8443/path")) {
            try {
                RemoteConnectionConfig.parse(endpoint, "a".repeat(32), "")
                fail("invalid endpoint accepted")
            } catch (_: ProtocolFailure) { }
        }
        assertEquals(8443, RemoteConnectionConfig.parse("tls://server.example:8443", "a".repeat(32), "ab".repeat(32)).port)
    }

    @Test fun projectionBindingRequiresLocallyObservedPts() {
        val command = BindCommand("10000000-0000-4000-8000-000000000001", 1,
            "10000000-0000-4000-8000-000000000002", captureSource = "android-mediaprojection-display")
        assertEquals("rejected", CaptureClockBinding().bind(command, 1_000_000).status)
        RemoteCaptureProof.active = true
        val binding = CaptureClockBinding()
        assertEquals("pending", binding.bind(command, 1_000_000).status)
        assertEquals("rejected", binding.bind(command.copy(capturePtsUs = 1_100_000), 1_100_000).status)
    }

    @Test fun verifiedProjectionKeepsTheExistingClockGate() {
        RemoteCaptureProof.active = true
        val binding = CaptureClockBinding()
        val command = BindCommand("10000000-0000-4000-8000-000000000001", 1,
            "10000000-0000-4000-8000-000000000002", captureSource = "android-mediaprojection-display")
        assertEquals("pending", binding.bind(command, 1_000_000).status)
        for (pts in listOf(1_100_000L, 1_600_000L, 2_100_000L)) {
            RemoteCaptureProof.record(pts)
            assertEquals(if (pts == 2_100_000L) "accepted" else "pending",
                binding.bind(command.copy(capturePtsUs = pts), pts + 20_000).status)
        }
        RemoteCaptureProof.clear()
        assertFalse(RemoteCaptureProof.contains(2_100_000))
    }
}
