package dev.k230.local_inspector

import org.junit.Assert.*
import org.junit.Test

class ImageInputTest {
    @Test fun nsfwPreservesRgbAndCornerAlignedResize() {
        val input = FloatArray(224 * 224 * 3)
        ImageInput.nsfw(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), 2, 1, Region(0, 0, 2, 1), input)
        assertEquals(1f, input[0], 0f); assertEquals(0f, input[1], 0f); assertEquals(0f, input[2], 0f)
        assertEquals(0f, input[223 * 3], 0f); assertEquals(1f, input[223 * 3 + 1], 0f)
        assertEquals(100f / 223, input[100 * 3 + 1], .00001f)
    }
    @Test fun yoloUsesNchwPaddingAndOriginalAspectRatio() {
        val input = FloatArray(3 * 640 * 640)
        ImageInput.yolo(intArrayOf(0xffff0000.toInt(), 0xffff0000.toInt()), 2, 1, input)
        assertEquals(114f / 255, input[0], 0f)
        assertEquals(1f, input[320 * 640 + 320], 0f)
        assertEquals(0f, input[640 * 640 + 320 * 640 + 320], 0f)
        assertEquals(160, ImageInput.letterbox(2, 1).top)
    }
    @Test fun regionsUndoLetterboxAndSuppressOverlappingMedia() {
        val output = FloatArray(25 * 8400)
        for (i in 0..1) {
            output[i] = 310f; output[8400 + i] = 300f
            output[16800 + i] = 200f; output[25200 + i] = 160f
            output[(4 + if (i == 0) 9 else 0) * 8400 + i] = if (i == 0) .9f else .8f
        }
        val found = ImageInput.regions(output, 320, 640)
        assertEquals(listOf(Region(50, 220, 200, 160)), found)
    }
    @Test fun invalidModelScoresFailRatherThanProduceSafeResults() {
        val output = FloatArray(25 * 8400); output[4 * 8400] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) { ImageInput.regions(output, 640, 640) }
    }
}
