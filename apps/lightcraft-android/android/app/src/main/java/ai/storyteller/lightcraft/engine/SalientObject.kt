package ai.storyteller.lightcraft.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline salient-object detection (U²-Netp, Apache-2.0, 4.6 MB): a soft 0..1 map of
 * "the thing this photograph is of" — a flower, a fire, a cup, an animal — for
 * Select Subject when no prominent person is found. The scene model (ADE20K) only
 * knows 150 scene categories at 128×128, so a protea or a candle often had no class
 * of its own and could not be selected at all.
 *
 * Input [1, 3, 320, 320] RGB with ImageNet normalisation over the whole photograph
 * stretched to a square; output [1, 1, 320, 320] (first output, the fused map).
 * Returns null when the model is not packaged or finds nothing, so selection never
 * breaks and the scene model is used instead.
 */
internal object SalientObject {
    private const val MODEL = "u2netp_salient.onnx"
    const val SIDE = 320
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var cachedSession: OrtSession? = null
    private var unavailable = false

    @Synchronized
    private fun session(context: Context): OrtSession? {
        cachedSession?.let { return it }
        if (unavailable) return null
        return try {
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            require(bytes.size > 1_000_000) { "Salient-object model is incomplete" }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            environment.createSession(bytes, opts).also { cachedSession = it }
        } catch (e: Exception) {
            unavailable = true
            null
        }
    }

    /** SIDE×SIDE saliency (0..1) over the photograph, or null. Background thread only. */
    @Synchronized
    fun saliency(context: Context, image: Bitmap): FloatArray? {
        val s = session(context) ?: return null
        val scaled = Bitmap.createScaledBitmap(image, SIDE, SIDE, true)
        val pixels = IntArray(SIDE * SIDE)
        scaled.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        if (scaled !== image) scaled.recycle()
        val input = ByteBuffer.allocateDirect(3 * SIDE * SIDE * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (channel in 0..2) {
            val shift = when (channel) { 0 -> 16; 1 -> 8; else -> 0 }
            for (p in pixels) input.put((((p ushr shift) and 255) / 255f - mean[channel]) / std[channel])
        }
        input.rewind()
        val rows: Array<FloatArray> =
            OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = result[0].value as Array<Array<Array<FloatArray>>>
                    output[0][0]
                }
            }
        if (rows.size != SIDE || rows[0].size != SIDE) return null
        val raw = FloatArray(SIDE * SIDE)
        for (y in 0 until SIDE) for (x in 0 until SIDE) raw[y * SIDE + x] = rows[y][x]
        return SemanticMaskRefinement.normaliseSaliency(raw)
    }
}
