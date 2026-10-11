package dev.k230.local_inspector

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object ImageInput {
    data class Letterbox(val width: Int, val height: Int, val left: Int, val top: Int, val scale: Double)
    fun letterbox(width: Int, height: Int): Letterbox {
        val scale = min(640.0 / width, 640.0 / height)
        val w = (width * scale).roundToInt().coerceIn(1, 640)
        val h = (height * scale).roundToInt().coerceIn(1, 640)
        return Letterbox(w, h, (640 - w) / 2, (640 - h) / 2, scale)
    }
    private fun sample(pixels: IntArray, width: Int, height: Int, x: Double, y: Double, channel: Int): Float {
        val sx = x.coerceIn(0.0, width - 1.0)
        val sy = y.coerceIn(0.0, height - 1.0)
        val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val shift = 16 - channel * 8
        fun color(px: Int, py: Int) = (pixels[py * width + px] ushr shift) and 255
        val top = color(x0, y0) + (color(x1, y0) - color(x0, y0)) * (sx - x0)
        val bottom = color(x0, y1) + (color(x1, y1) - color(x0, y1)) * (sx - x0)
        return (top + (bottom - top) * (sy - y0)).toFloat()
    }
    fun yolo(pixels: IntArray, width: Int, height: Int, target: FloatArray) {
        val box = letterbox(width, height)
        target.fill(114f / 255)
        for (y in 0 until box.height) for (x in 0 until box.width) {
            val sy = (y + .5) * height / box.height - .5
            val sx = (x + .5) * width / box.width - .5
            for (channel in 0..2) target[channel * 640 * 640 + (y + box.top) * 640 + x + box.left] =
                sample(pixels, width, height, sx, sy, channel).roundToInt() / 255f
        }
    }
    fun nsfw(pixels: IntArray, width: Int, height: Int, region: Region, target: FloatArray) {
        for (y in 0 until 224) for (x in 0 until 224) for (channel in 0..2) {
            val sx = region.x + x * (region.width - 1.0) / 223
            val sy = region.y + y * (region.height - 1.0) / 223
            target[(y * 224 + x) * 3 + channel] = sample(pixels, width, height, sx, sy, channel) / 255f
        }
    }
    fun regions(output: FloatArray, width: Int, height: Int): List<Region> {
        require(output.size == 25 * 8400)
        val box = letterbox(width, height)
        val candidates = mutableListOf<Pair<Region, Float>>()
        for (i in 0 until 8400) {
            var confidence = 0f; var label = -1
            for (c in 0 until 21) {
                val score = output[(4 + c) * 8400 + i]
                require(score.isFinite() && score in 0f..1f) { "invalid_ui_scores" }
                if (score > confidence) { confidence = score; label = c }
            }
            if (confidence < .25f || (label != 0 && label != 9)) continue
            val cx = output[i]; val cy = output[8400 + i]
            val w = output[16800 + i]; val h = output[25200 + i]
            require(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite() && w > 0 && h > 0)
            val left = ((cx - w / 2 - box.left) / box.scale).coerceIn(0.0, width.toDouble())
            val top = ((cy - h / 2 - box.top) / box.scale).coerceIn(0.0, height.toDouble())
            val right = ((cx + w / 2 - box.left) / box.scale).coerceIn(0.0, width.toDouble())
            val bottom = ((cy + h / 2 - box.top) / box.scale).coerceIn(0.0, height.toDouble())
            if (right - left < 64 || bottom - top < 64 || (right - left) * (bottom - top) < .01 * width * height) continue
            val x = floor(left).toInt(); val y = floor(top).toInt()
            candidates.add(Region(x, y, ceil(right).toInt() - x, ceil(bottom).toInt() - y, label) to confidence)
        }
        val kept = mutableListOf<Region>()
        for ((region, _) in candidates.sortedByDescending { it.second }.take(300)) {
            if (kept.none { it.overlap(region) > .70 }) kept.add(region)
            if (kept.size == 8) break
        }
        return kept
    }
}

internal class ModelPipeline(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val ui: OrtSession
    private val nsfw: OrtSession
    private val uiPixels = FloatArray(3 * 640 * 640)
    private val nsfwPixels = FloatArray(224 * 224 * 3)
    private val uiOutput = FloatArray(25 * 8400)
    private val nsfwOutput = FloatArray(5)
    init {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            ui = environment.createSession(model(context, "android-ui-yolov8n.onnx",
                "1d0b1bff254b62377b0f534332c456854d6a769465ffcf6906b75d35c99b6652"), options)
            try {
                nsfw = environment.createSession(model(context, "nsfwjs-mobilenet-v2.onnx",
                    "63b0624e6ade8cf8e80e7533798572973c7c98603912f4961dfa91d44dadbfa5"), options)
                try {
                    contract(ui, longArrayOf(1, 3, 640, 640), longArrayOf(1, 25, 8400))
                    contract(nsfw, longArrayOf(1, 224, 224, 3), longArrayOf(1, 5))
                    require(ui.metadata.customMetadata["k230.ui.media_classes"] == "0:BackgroundImage,9:Image")
                    require(ui.metadata.customMetadata["k230.ui.input"] == "rgb_nchw_float32_0_1_letterbox_114_half_pixel")
                    require(nsfw.metadata.customMetadata["nsfwjs.classes"] == "Drawing,Hentai,Neutral,Porn,Sexy")
                    require(nsfw.metadata.customMetadata["nsfwjs.input"] == "rgb_nhwc_float32_0_1_align_corners")
                } catch (error: Exception) { nsfw.close(); throw error }
            } catch (error: Exception) { ui.close(); throw error }
        }
    }
    private fun model(context: Context, name: String, hash: String): ByteArray {
        val bytes = context.assets.open("models/$name").use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual == hash) { "model_hash_mismatch" }
        return bytes
    }
    private fun contract(session: OrtSession, input: LongArray, output: LongArray) {
        require(session.inputInfo.size == 1 && session.outputInfo.size == 1)
        val i = session.inputInfo.values.single().info as TensorInfo
        val o = session.outputInfo.values.single().info as TensorInfo
        require(i.type == OnnxJavaType.FLOAT && o.type == OnnxJavaType.FLOAT &&
            i.shape.contentEquals(input) && o.shape.contentEquals(output)) { "model_contract_mismatch" }
    }
    private fun run(session: OrtSession, values: FloatArray, shape: LongArray, target: FloatArray) {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                require(output.info.type == OnnxJavaType.FLOAT && output.info.numElements == target.size.toLong())
                requireNotNull(output.floatBuffer).get(target)
            }
        }
    }
    fun analyze(bitmap: Bitmap): List<Observation> {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        ImageInput.yolo(pixels, width, height, uiPixels)
        run(ui, uiPixels, longArrayOf(1, 3, 640, 640), uiOutput)
        return ImageInput.regions(uiOutput, width, height).map { region ->
            Observation(region, classify(pixels, width, height, region))
        }
    }
    fun classify(pixels: IntArray, width: Int, height: Int, region: Region): Scores {
        require(width > 0 && height > 0 && pixels.size == width * height && region.x >= 0 && region.y >= 0 &&
            region.width > 0 && region.height > 0 && region.x + region.width <= width && region.y + region.height <= height)
        ImageInput.nsfw(pixels, width, height, region, nsfwPixels)
        run(nsfw, nsfwPixels, longArrayOf(1, 224, 224, 3), nsfwOutput)
        require(nsfwOutput.all { it.isFinite() && it in 0f..1f } && abs(nsfwOutput.sum() - 1f) <= .001f)
        return Scores(nsfwOutput[0], nsfwOutput[1], nsfwOutput[2], nsfwOutput[3], nsfwOutput[4])
    }
    override fun close() { nsfw.close(); ui.close() }
}
