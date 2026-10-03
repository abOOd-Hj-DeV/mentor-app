package dev.k230.mentor_app.protection

internal data class ClockBindingReply(val status: String, val error: String? = null)

internal class CaptureClockBinding {
    private val regression = CaptureClockRegression()
    private var candidate: String? = null
    private var startedUs = 0L
    fun reset() { regression.reset(); candidate = null; startedUs = 0 }
    fun bind(command: BindCommand, receivedUs: Long): ClockBindingReply {
        val pts = command.capturePtsUs
        if (pts == null) {
            reset(); candidate = command.streamId; startedUs = receivedUs
            return ClockBindingReply("pending")
        }
        return try {
            requireProtocol(command.streamId == candidate && receivedUs >= startedUs &&
                receivedUs - startedUs <= 3_000_000, "clock_unverified")
            ClockBindingReply(if (regression.sample(pts, receivedUs)) "accepted" else "pending")
        } catch (_: ProtocolFailure) {
            reset(); ClockBindingReply("rejected", "clock_unverified")
        }
    }
}
