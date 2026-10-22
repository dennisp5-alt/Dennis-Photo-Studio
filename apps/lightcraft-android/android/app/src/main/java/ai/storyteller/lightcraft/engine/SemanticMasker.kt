package ai.storyteller.lightcraft.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min

/**
 * Offline semantic selections for the photo editor.
 *
 * ADE20K SegFormer-B0: pixel_values [1, 3, 512, 512] (ImageNet normalisation),
 * logits [1, 150, 128, 128]. Class IDs 2=sky and 12=person (zero-based).
 *
 * Inference never sends images to a server, and the trained weights are packaged
 * into the APK at build time. The result is stored as the editor's existing
 * compressed SegMask data, so opening and exporting edited photographs needs
 * no model inference, network access or new persisted schema.
 */
internal object SemanticMasker {
    private const val INPUT = 512
    private const val SKY = 2
    private const val PERSON = 12
    private const val MODEL = "segformer_b0_ade.onnx"
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var cachedSession: OrtSession? = null

    @Synchronized
    private fun session(context: Context): OrtSession {
        cachedSession?.let { return it }
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        require(bytes.size > 1_000_000) { "Offline segmentation model is missing or incomplete" }
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        return environment.createSession(bytes, opts).also { cachedSession = it }
    }

    /** Run only from an IO/Default dispatcher; never on the Compose UI thread. */
    @Synchronized
    fun segment(context: Context, original: Bitmap, kind: String): JSONObject {
        require(kind in setOf("subject", "background", "sky")) { "Unknown semantic selection" }
        require(original.width > 0 && original.height > 0) { "Empty photograph" }
        val resized = Bitmap.createScaledBitmap(original, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        resized.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (resized !== original) resized.recycle()

        val input = ByteBuffer.allocateDirect(3 * INPUT * INPUT * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (channel in 0..2) {
            val shift = when (channel) { 0 -> 16; 1 -> 8; else -> 0 }
            for (pixel in pixels) {
                val c = (pixel ushr shift) and 255
                input.put((c / 255.0f - mean[channel]) / std[channel])
            }
        }
        input.rewind()

        val scores: Array<Array<FloatArray>>
        OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { tensor ->
            session(context).run(mapOf("pixel_values" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result[0].value as Array<Array<Array<FloatArray>>>
                scores = output[0]
            }
        }
        require(scores.size >= 150 && scores[0].isNotEmpty()) { "Unexpected semantic model output" }
        val h = scores[0].size
        val w = scores[0][0].size
        require(h == w && h in 16..1024) { "Unsupported mask resolution ${w}×${h}" }
        val side = h
        val bestClass = IntArray(side * side)
        val bestScore = FloatArray(side * side) { Float.NEGATIVE_INFINITY }
        val runnerUp = FloatArray(side * side) { Float.NEGATIVE_INFINITY }
        val counts = IntArray(scores.size)
        val centreCounts = IntArray(scores.size)
        for (label in scores.indices) {
            for (y in 0 until side) for (x in 0 until side) {
                val i = y * side + x
                val value = scores[label][y][x]
                if (value > bestScore[i]) {
                    runnerUp[i] = bestScore[i]
                    bestScore[i] = value
                    bestClass[i] = label
                } else if (value > runnerUp[i]) {
                    runnerUp[i] = value
                }
            }
        }
        for (i in bestClass.indices) {
            val label = bestClass[i]
            counts[label]++
            val x = i % side
            val y = i / side
            if (x in side / 4 until 3 * side / 4 && y in side / 5 until 4 * side / 5) centreCounts[label]++
        }
        val label = when (kind) {
            "sky" -> SKY
            else -> {
                if (counts[PERSON] >= side * side / 250) PERSON
                else {
                    // Non-people subjects: choose a salient central semantic category.
                    // Broad sky, floor, wall, road and architecture are background, not subjects.
                    val excluded = setOf(0, 1, 2, 3, 5, 6, 8, 9, 11, 13, 21, 26, 29, 46, 52, 54)
                    centreCounts.indices.filter { it !in excluded && counts[it] < side * side * 0.80 }
                        .maxByOrNull { centreCounts[it] }?.takeIf { centreCounts[it] > side * side / 180 }
                        ?: error("No clearly selectable subject was found")
                }
            }
        }
        require(counts[label] >= side * side / 500) {
            if (kind == "sky") "No identifiable sky was found" else "No identifiable subject was found"
        }

        // The model's per-class score margin is a signed logit. Preserving the
        // margin (not hard ArgMax edges) allows the Rust renderer to interpolate
        // a feathered boundary at original photo resolution.
        val mask = ByteArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            val i = y * side + x
            val chosen = scores[label][y][x]
            var other = Float.NEGATIVE_INFINITY
            for (c in scores.indices) if (c != label) other = max(other, scores[c][y][x])
            var logit = (chosen - other).coerceIn(-12.0f, 12.0f)
            if (kind == "background") logit = -logit
            // Rust SegMask decodes signed bytes as logits with an 8x scale.
            mask[i] = (logit * 8.0f).toInt().coerceIn(-127, 127).toByte()
        }
        val deflater = Deflater(8, true) // raw DEFLATE, matching miniz_oxide::compress_to_vec
        val packed = try {
            deflater.setInput(mask)
            deflater.finish()
            val buffer = ByteArray(mask.size * 2 + 64)
            var used = 0
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer, used, buffer.size - used)
                require(n > 0 && used + n <= buffer.size) { "Mask compression failed" }
                used += n
            }
            buffer.copyOf(used)
        } finally { deflater.end() }
        return JSONObject()
            .put("side", side)
            .put("data", Base64.encodeToString(packed, Base64.NO_WRAP))
    }
}
