package ai.storyteller.lightcraft.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
        // Subject and Background are one selection (Background is its exact
        // complement), analysed with the photograph's true proportions.
        // Sky keeps its approved square path.
        val subjectFit = if (kind != "sky")
            SubjectAspectMapper.fit(original.width, original.height, INPUT) else null
        val resized = if (subjectFit != null) {
            val square = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
            Canvas(square).apply {
                drawColor(0xFF777777.toInt())
                drawBitmap(original, null, Rect(subjectFit.left, subjectFit.top,
                    subjectFit.left + subjectFit.width,
                    subjectFit.top + subjectFit.height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            square
        } else Bitmap.createScaledBitmap(original, INPUT, INPUT, true)
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

        val scores: Array<Array<FloatArray>> =
            OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { tensor ->
                session(context).run(mapOf("pixel_values" to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = result[0].value as Array<Array<Array<FloatArray>>>
                    output[0]
                }
            }
        require(scores.size >= 150 && scores[0].isNotEmpty()) { "Unexpected semantic model output" }
        val h = scores[0].size
        val w = scores[0][0].size
        require(h == w && h in 16..1024) { "Unsupported mask resolution ${w}×${h}" }
        val side = h
        // Reproject the model's letterboxed Subject logits into the editor's
        // normalized 128x128 coordinates. This happens BEFORE class ranking,
        // so padding can never be mistaken for part of the photograph.
        val subjectMap = subjectFit?.let { SubjectAspectMapper.SampleMap(side, it, INPUT) }
        val bestClass = IntArray(side * side)
        val bestScore = FloatArray(side * side) { Float.NEGATIVE_INFINITY }
        val runnerUp = FloatArray(side * side) { Float.NEGATIVE_INFINITY }
        val counts = IntArray(scores.size)
        val centreCounts = IntArray(scores.size)
        for (label in scores.indices) {
            for (y in 0 until side) for (x in 0 until side) {
                val i = y * side + x
                val value = subjectMap?.at(scores[label], x, y) ?: scores[label][y][x]
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
        // Preserve the successful v0.6.18 background label decision verbatim.
        // Only the *subject* selector adds explicit animal/person prioritisation.
        // v0.6.32-beta.6: a subject with no scene class of its own (a protea, a fire)
        // is no longer an error here: the salient-object model may still find it.
        val label: Int? = when (kind) {
            "sky" -> SKY
            else -> {
                val legacy = if (counts[PERSON] >= side * side / 250) PERSON
                else {
                    // Non-people subjects: exclude expansive scene/background categories.
                    val excluded = setOf(0, 1, 2, 3, 5, 6, 8, 9, 11, 13, 21, 26, 29, 46, 52, 54)
                    centreCounts.indices.filter { it !in excluded && counts[it] < side * side * 0.80 }
                        .maxByOrNull { centreCounts[it] }?.takeIf { centreCounts[it] > side * side / 180 }
                }
                legacy?.let { SemanticMaskRefinement.selectSubject(counts, centreCounts, side, it) }
                    ?.takeIf { counts[it] >= side * side / 500 }
            }
        }
        if (kind == "sky") require(counts[SKY] >= side * side / 500) { "No identifiable sky was found" }

        // Keep the model's soft per-class score margin, not hard pixel labels.
        // The previous background path intentionally retains its 128px results.
        fun marginsOf(chosenLabel: Int): FloatArray {
            val out = FloatArray(side * side)
            for (y in 0 until side) for (x in 0 until side) {
                val i = y * side + x
                val chosen = subjectMap?.at(scores[chosenLabel], x, y) ?: scores[chosenLabel][y][x]
                var other = Float.NEGATIVE_INFINITY
                for (c in scores.indices) if (c != chosenLabel) {
                    other = max(other, subjectMap?.at(scores[c], x, y) ?: scores[c][y][x])
                }
                out[i] = (chosen - other).coerceIn(-12.0f, 12.0f)
            }
            return out
        }
        val margins: FloatArray? = label?.let { marginsOf(it) }
        // Subjects that are not people: the salient-object map, unless it is only a
        // small piece of an object the scene model outlined (daisy centres).
        val salient: FloatArray? = if (kind != "sky" && label != PERSON)
            SalientObject.saliency(context, original)?.takeIf {
                SemanticMaskRefinement.saliencyUsable(it, SalientObject.SIDE, margins, side)
            } else null
        if (label == null && salient == null) error("No clearly selectable subject was found")
        val outSide: Int
        val mask: ByteArray
        // People: a dedicated person matte (hair-level edges), kept to where the
        // scene model saw people. Background is the exact complement.
        val matte = (if (kind != "sky" && label == PERSON && margins != null)
            PortraitMatter.matte(context, original) else null)
            ?.takeIf { SemanticMaskRefinement.matteAgrees(it.alpha, it.width, it.height, margins!!, side) }
        // Upsampling guidance must use the original image coordinates, never the
        // letterboxed model input or its grey padding.
        fun subjectGuide(): IntArray {
            val guide = Bitmap.createScaledBitmap(original, INPUT, INPUT, true)
            val p = IntArray(INPUT * INPUT)
            guide.getPixels(p, 0, INPUT, 0, 0, INPUT, INPUT)
            if (guide !== original) guide.recycle()
            return p
        }
        if (matte != null) {
            outSide = SemanticMaskRefinement.MATTE_SIDE
            mask = SemanticMaskRefinement.matteToLogits(
                matte.alpha, matte.width, matte.height,
                SemanticMaskRefinement.personSupport(margins!!, side), side,
                outSide, invert = kind == "background")
        } else if (salient != null) {
            // The salient object's own edge, snapped to the photograph's colours.
            outSide = SemanticMaskRefinement.MATTE_SIDE
            val refined = SemanticMaskRefinement.refine(
                SemanticMaskRefinement.probabilityToMargins(salient), SalientObject.SIDE,
                subjectGuide(), INPUT, outSide, subjectBoundary = true)
            mask = if (kind == "background")
                ByteArray(refined.size) { i -> (-refined[i]).toByte() } else refined
        } else {
            outSide = SemanticMaskRefinement.REFINED_SIDE
            val evidence = if (kind == "sky")
                SemanticMaskRefinement.retainSkyRegions(margins!!, side)
            else margins!!
            val guidePixels = if (kind != "sky") subjectGuide() else pixels
            val refined = SemanticMaskRefinement.refine(
                evidence, side, guidePixels, INPUT, outSide,
                subjectBoundary = kind != "sky")
            // Background: the same refined edge, inverted (-128 cannot occur).
            mask = if (kind == "background")
                ByteArray(refined.size) { i -> (-refined[i]).toByte() } else refined
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
            .put("side", outSide)
            .put("data", Base64.encodeToString(packed, Base64.NO_WRAP))
    }
}
