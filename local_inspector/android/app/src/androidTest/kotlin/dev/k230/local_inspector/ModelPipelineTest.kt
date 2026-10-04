package dev.k230.local_inspector

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPipelineTest {
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
