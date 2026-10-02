package dev.k230.mentor_app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LayoutWireTest {
    private fun snapshot() = LayoutSnapshot(
        0, 7, 9, 1_000_000, 1_005_000, 990_000,
        1080, 2400, 0, 0, 42, "dev.example",
        listOf(LayoutNode(100, 200, 900, 1000, 1, 1)),
    )

    @Test fun writesLittleEndianFramingAndExportsCppFixture() {
        val bytes = LayoutWire.encode(snapshot())
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(111, input.int)
        assertEquals(0x59414c4b, input.int)
        assertEquals(1, input.short.toInt())
        assertEquals(0, input.short.toInt())
        assertEquals(7, input.long)
        assertEquals(9, input.long)
        assertEquals(115, bytes.size)
        File("build/layout-golden.bin").also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
    }

    @Test fun refusesInvalidBoundsAndOversizedSnapshots() {
        assertThrows(IllegalArgumentException::class.java) {
            LayoutWire.encode(snapshot().copy(nodes = listOf(LayoutNode(-1, 0, 100, 100, 1, 1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutWire.encode(snapshot().copy(nodes = List(257) { LayoutNode(0, 0, 100, 100, 1, it + 1) }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutWire.encode(snapshot().copy(flags = LayoutWire.INVALIDATE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayoutWire.encode(snapshot().copy(completedAtUs = 0))
        }
    }
}
