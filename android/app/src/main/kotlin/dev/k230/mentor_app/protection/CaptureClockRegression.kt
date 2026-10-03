package dev.k230.mentor_app.protection

/** Regress unmodified capture PTS against independently sampled phone monotonic time.
 * Evidence is session-local; a bind string or operator assertion never approves a clock. */
internal class CaptureClockRegression {
    private var firstPts = 0L
    private var firstReceive = 0L
    private var lastPts = 0L
    private var lastReceive = 0L
    private var count = 0
    var verified = false
        private set
    fun reset() {
        firstPts = 0; firstReceive = 0; lastPts = 0; lastReceive = 0; count = 0; verified = false
    }
    fun sample(capturePtsUs: Long, receivedUs: Long): Boolean {
        val valid = capturePtsUs > 0 && receivedUs > 0 &&
            (if (capturePtsUs > receivedUs) capturePtsUs - receivedUs <= 50_000
                else receivedUs - capturePtsUs <= 750_000) &&
            (count == 0 || capturePtsUs > lastPts && receivedUs > lastReceive &&
                receivedUs - firstReceive <= 3_000_000) && count < 8
        if (!valid) { reset(); throw ProtocolFailure("clock_unverified") }
        if (count == 0) { firstPts = capturePtsUs; firstReceive = receivedUs }
        count++; lastPts = capturePtsUs; lastReceive = receivedUs
        verified = count >= 3 && capturePtsUs - firstPts >= 1_000_000 && receivedUs - firstReceive >= 1_000_000
        return verified
    }
}
