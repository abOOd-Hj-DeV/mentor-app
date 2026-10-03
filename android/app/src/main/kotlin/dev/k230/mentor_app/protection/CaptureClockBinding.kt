package dev.k230.mentor_app.protection

internal data class ClockBindingReply(val status: String, val error: String? = null)

internal class CaptureClockBinding {
    private val regression = CaptureClockRegression()
    private var candidate: String? = null
    private var startedUs = 0L
    private var failed = false
    fun reset() { regression.reset(); candidate = null; startedUs = 0; failed = false }
    private fun reject(): ClockBindingReply {
        regression.reset(); failed = true
        return ClockBindingReply("rejected", "clock_unverified")
    }
    fun bind(command: BindCommand, receivedUs: Long): ClockBindingReply {
        if (failed) return ClockBindingReply("rejected", "clock_unverified")
        if (regression.verified) return ClockBindingReply("rejected", "busy")
        val pts = command.capturePtsUs
        if (pts == null) {
            if (candidate != null || receivedUs <= 0) return reject()
            candidate = command.streamId; startedUs = receivedUs
            return ClockBindingReply("pending")
        }
        return try {
            requireProtocol(command.streamId == candidate && receivedUs >= startedUs &&
                receivedUs - startedUs <= 3_000_000, "clock_unverified")
            ClockBindingReply(if (regression.sample(pts, receivedUs)) "accepted" else "pending")
        } catch (_: ProtocolFailure) {
            reject()
        }
    }
}
