package dev.k230.mentor_app

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LayoutNode(
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val kind: Int, val id: Int,
)

data class LayoutSnapshot(
    val flags: Int,
    val session: Long,
    val sequence: Long,
    val sampledAtUs: Long,
    val completedAtUs: Long,
    val validFromUs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val displayId: Int,
    val windowId: Int,
    val packageName: String,
    val nodes: List<LayoutNode>,
)

object LayoutWire {
    const val PARTIAL = 1
    const val INVALIDATE = 2
    const val MAX_NODES = 256
    const val HEADER_BYTES = 76
    const val NODE_BYTES = 24
    const val MAX_PAYLOAD = HEADER_BYTES + 256 + MAX_NODES * NODE_BYTES
    const val SOCKET_NAME = "k230_layout"

    fun encode(snapshot: LayoutSnapshot): ByteArray {
        val name = snapshot.packageName.toByteArray(Charsets.US_ASCII)
        require(name.size <= 256 && snapshot.packageName.all { it.code in 33..126 })
        require(snapshot.flags in 0..3)
        require(snapshot.session > 0 && snapshot.sequence > 0)
        require(snapshot.sampledAtUs >= 0 && snapshot.completedAtUs >= snapshot.sampledAtUs)
        require(snapshot.validFromUs in 0..snapshot.sampledAtUs)
        require(snapshot.width in 1..16384 && snapshot.height in 1..16384)
        require(snapshot.rotation in 0..3 && snapshot.displayId == 0)
        require(snapshot.nodes.size <= MAX_NODES)
        require(snapshot.flags and INVALIDATE == 0 || snapshot.nodes.isEmpty())
        val length = HEADER_BYTES + name.size + snapshot.nodes.size * NODE_BYTES
        val out = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(length).putInt(0x59414c4b).putShort(1).putShort(snapshot.flags.toShort())
        out.putLong(snapshot.session).putLong(snapshot.sequence)
        out.putLong(snapshot.sampledAtUs).putLong(snapshot.completedAtUs).putLong(snapshot.validFromUs)
        out.putInt(snapshot.width).putInt(snapshot.height).putInt(snapshot.rotation)
        out.putInt(snapshot.displayId).putInt(snapshot.windowId)
        out.putInt(snapshot.nodes.size).putInt(name.size).put(name)
        snapshot.nodes.forEach {
            require(it.left >= 0 && it.top >= 0 && it.right <= snapshot.width && it.bottom <= snapshot.height)
            require(it.left < it.right && it.top < it.bottom && it.kind in 0..3 && it.id > 0)
            out.putInt(it.left).putInt(it.top).putInt(it.right).putInt(it.bottom)
            out.putInt(it.kind).putInt(it.id)
        }
        return out.array()
    }
}
