package ai.storyteller.lightcraft.engine

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure, testable mask-selection and image-guided upsampling routines.
 * Do not change the background selector here: its v0.6.18 result is the reference.
 */
internal object SemanticMaskRefinement {
    const val SKY = 2
    const val PERSON = 12
    const val ANIMAL = 126
    const val REFINED_SIDE = 384

    /**
     * A person in the distance should not replace a large central animal.
     * Prefer visibly present people/animals to unrelated central scene classes.
     * The legacy ranking is kept when neither class has meaningful evidence.
     */
    fun selectSubject(
        counts: IntArray,
        centreCounts: IntArray,
        side: Int,
        legacy: Int,
    ): Int {
        val total = side * side
        val minVisible = max(16, total / 450)
        val minCentral = max(6, total / 1600)
        val candidates = listOf(PERSON, ANIMAL).filter {
            it < counts.size && it < centreCounts.size &&
                counts[it] >= minVisible && centreCounts[it] >= minCentral
        }
        if (candidates.isEmpty()) return legacy
        if (candidates.size == 1) return candidates[0]
        val people = centreCounts[PERSON]
        val animals = centreCounts[ANIMAL]
        // A foreground animal must be substantially larger to override a person;
        // otherwise portrait groups continue to prefer people.
        return if (animals > people * 1.6f) ANIMAL else PERSON
    }

    /**
     * Remove stray isolated "sky" predictions without cutting out large separate
     * sky openings between trees. Work on the low-res model evidence; the following
     * joint upsampler refines only retained components.
     */
    fun retainSkyRegions(margins: FloatArray, side: Int): FloatArray {
        require(side > 0 && margins.size == side * side)
        val visited = BooleanArray(margins.size)
        val queue = IntArray(margins.size)
        val out = margins.copyOf()
        val minIsland = max(12, margins.size / 850)
        for (start in margins.indices) {
            if (visited[start] || margins[start] <= 0f) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var top = side
            while (head < tail) {
                val i = queue[head++]
                val x = i % side
                val y = i / side
                top = min(top, y)
                fun visit(j: Int) {
                    if (!visited[j] && margins[j] > 0f) {
                        visited[j] = true
                        queue[tail++] = j
                    }
                }
                if (x > 0) visit(i - 1)
                if (x + 1 < side) visit(i + 1)
                if (y > 0) visit(i - side)
                if (y + 1 < side) visit(i + side)
            }
            val plausible = tail >= minIsland &&
                (top < side * 0.45f || (tail >= margins.size / 55 && top < side * 0.70f))
            if (!plausible) for (k in 0 until tail) {
                out[queue[k]] = -4f
            }
        }
        return out
    }

    /**
     * Joint bilateral upsample: low-resolution semantic logits supply the region,
     * high-resolution photographed colours guide its boundary.
     *
     * Unlike sharpening a binary mask, this keeps soft model confidence and is
     * bounded by the actual object/image edges. The weight floor prevents small
     * colour texture/noise from creating holes within a selected subject.
     *
     * outputSide=384 fits existing SegMask limits and stays cheap on the S23U.
     */
    fun refine(
        margins: FloatArray,
        modelSide: Int,
        pixels: IntArray,
        pixelSide: Int = 512,
        outputSide: Int = REFINED_SIDE,
    ): ByteArray {
        require(modelSide > 1 && modelSide <= 1024 && margins.size == modelSide * modelSide)
        require(pixelSide > 0 && pixels.size == pixelSide * pixelSide)
        require(outputSide in 16..1024)
        val lowGuides = IntArray(modelSide * modelSide)
        for (y in 0 until modelSide) for (x in 0 until modelSide) {
            val sx = ((x + 0.5f) * pixelSide / modelSide).toInt().coerceIn(0, pixelSide - 1)
            val sy = ((y + 0.5f) * pixelSide / modelSide).toInt().coerceIn(0, pixelSide - 1)
            lowGuides[y * modelSide + x] = pixels[sy * pixelSide + sx]
        }
        val output = ByteArray(outputSide * outputSide)
        val colourSigma = 2f * 0.15f * 0.15f
        for (y in 0 until outputSide) for (x in 0 until outputSide) {
            val fx = (x + 0.5f) * modelSide / outputSide - 0.5f
            val fy = (y + 0.5f) * modelSide / outputSide - 0.5f
            val px = ((x + 0.5f) * pixelSide / outputSide).toInt().coerceIn(0, pixelSide - 1)
            val py = ((y + 0.5f) * pixelSide / outputSide).toInt().coerceIn(0, pixelSide - 1)
            val target = pixels[py * pixelSide + px]
            val tx = floor(fx).toInt()
            val ty = floor(fy).toInt()
            var sum = 0f
            var weights = 0f
            for (iy in max(0, ty - 1)..min(modelSide - 1, ty + 2)) {
                val dy = iy - fy
                for (ix in max(0, tx - 1)..min(modelSide - 1, tx + 2)) {
                    val dx = ix - fx
                    val guide = lowGuides[iy * modelSide + ix]
                    val dr = (((target ushr 16) and 255) - ((guide ushr 16) and 255)) / 255f
                    val dg = (((target ushr 8) and 255) - ((guide ushr 8) and 255)) / 255f
                    val db = ((target and 255) - (guide and 255)) / 255f
                    val dColour = dr * dr + dg * dg + db * db
                    val spatial = exp((-(dx * dx + dy * dy) / 2.8f).toDouble()).toFloat()
                    val similar = exp((-dColour / colourSigma).toDouble()).toFloat()
                    val weight = spatial * (0.10f + 0.90f * similar)
                    sum += weight * margins[iy * modelSide + ix]
                    weights += weight
                }
            }
            val chosen = if (weights > 0f) sum / weights else margins[
                fy.roundToInt().coerceIn(0, modelSide - 1) * modelSide +
                    fx.roundToInt().coerceIn(0, modelSide - 1)
            ]
            output[y * outputSide + x] =
                (chosen.coerceIn(-15.875f, 15.875f) * 8f).roundToInt()
                    .coerceIn(-127, 127).toByte()
        }
        return output
    }
}
