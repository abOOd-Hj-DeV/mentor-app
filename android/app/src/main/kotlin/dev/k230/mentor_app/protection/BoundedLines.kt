package dev.k230.mentor_app.protection

internal class BoundedLines {
    private val buffer = ByteArray(CompanionProtocol.MAX_LINE_BYTES)
    private var size = 0
    private var startedUs: Long? = null
    fun checkDeadline(nowUs: Long) {
        requireProtocol(startedUs == null || nowUs - startedUs!! < 500_000, "stale")
    }
    fun append(bytes: ByteArray, count: Int, nowUs: Long): List<ByteArray> {
        checkDeadline(nowUs)
        val lines = mutableListOf<ByteArray>()
        for (i in 0 until count) {
            if (size == 0) startedUs = nowUs
            requireProtocol(size < buffer.size)
            buffer[size++] = bytes[i]
            if (bytes[i] == 10.toByte()) {
                requireProtocol(size > 1, "malformed_json")
                lines.add(buffer.copyOf(size))
                size = 0; startedUs = null
            } else requireProtocol(size < buffer.size)
        }
        return lines
    }
}
