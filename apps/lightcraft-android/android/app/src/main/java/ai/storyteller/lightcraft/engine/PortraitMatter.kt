package ai.storyteller.lightcraft.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline person matting (MODNet, Apache-2.0): a soft 0..1 alpha matte of the
 * people in a photograph at the model's input resolution, with hair-level edges.
 *
 * Input [1, 3, H, W] RGB normalised to -1..1, short side 512 and both sides a
 * multiple of 32; output [1, 1, H, W] alpha. Used for Subject/Background when the
 * scene model has found a person; everything else keeps the scene model.
 * Returns null when the model is not packaged, so selection never breaks.
 */
internal object PortraitMatter {
    private const val MODEL = "modnet_portrait.onnx"

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var cachedSession: OrtSession? = null
    private var unavailable = false

    class Matte(val width: Int, val height: Int, val alpha: FloatArray)

    @Synchronized
    private fun session(context: Context): OrtSession? {
        cachedSession?.let { return it }
        if (unavailable) return null
        return try {
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            require(bytes.size > 1_000_000) { "Portrait matting model is incomplete" }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            environment.createSession(bytes, opts).also { cachedSession = it }
        } catch (e: Exception) {
            unavailable = true
            null
        }
    }

    /** Run only from a background dispatcher. */
    @Synchronized
    fun matte(context: Context, image: Bitmap): Matte? {
        val s = session(context) ?: return null
        val (w, h) = SemanticMaskRefinement.mattingInputSize(image.width, image.height)
        val scaled = Bitmap.createScaledBitmap(image, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== image) scaled.recycle()
        val input = ByteBuffer.allocateDirect(3 * w * h * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (channel in 0..2) {
            val shift = when (channel) { 0 -> 16; 1 -> 8; else -> 0 }
            for (p in pixels) input.put(((p ushr shift) and 255) / 127.5f - 1f)
        }
        input.rewind()
        val rows: Array<FloatArray> =
            OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = result[0].value as Array<Array<Array<FloatArray>>>
                    output[0][0]
                }
            }
        val oh = rows.size
        val ow = rows.firstOrNull()?.size ?: 0
        if (oh < 8 || ow < 8) return null
        val alpha = FloatArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) alpha[y * ow + x] = rows[y][x].coerceIn(0f, 1f)
        return Matte(ow, oh, alpha)
    }
}
