package dev.k230.local_inspector

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPipelineTest {
    @Test fun portraitRgbFramesRunBothModelsWithFiniteScores() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 360; val height = 800
        val pixels = IntArray(width * height) { i ->
            val x = i % width; val y = i / width
            val r = x * 255 / (width - 1)
            val g = y * 255 / (height - 1)
            val b = if ((x / 40 + y / 40) % 2 == 0) 32 else 224
            0xff000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            ModelPipeline(context).use { pipeline ->
                val score = pipeline.classify(pixels, width, height, Region(0, 0, width, height))
                assertTrue(score.explicit.isFinite())
                val observations = pipeline.analyze(bitmap)
                assertTrue(observations.size <= 8)
                for (observation in observations) {
                    assertTrue(observation.scores.explicit.isFinite())
                    assertTrue(observation.region.x + observation.region.width <= width)
                    assertTrue(observation.region.y + observation.region.height <= height)
                }
            }
        } finally { bitmap.recycle() }
    }
    @Test fun bundledModelsRunOfflineAndMatchTheCppBlackFrameReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ModelPipeline(context).use { pipeline ->
            val score = pipeline.classify(IntArray(3 * 5) { 0xff000000.toInt() }, 3, 5, Region(0, 0, 3, 5))
            assertEquals(.788308978f, score.drawing, .0001f)
            assertEquals(.026247092f, score.hentai, .0001f)
            assertEquals(.173371479f, score.neutral, .0001f)
            assertEquals(.010009553f, score.porn, .0001f)
            assertEquals(.002062896f, score.sexy, .0001f)
            val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            try {
                val regions = pipeline.analyze(bitmap)
                assertTrue(regions.size <= 8)
            } finally { bitmap.recycle() }
        }
    }
}
