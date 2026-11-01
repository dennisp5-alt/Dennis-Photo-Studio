package ai.storyteller.lightcraft.engine

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure, testable mask-selection and image-guided upsampling routines.
 * Background is always the exact complement of the Subject selection.
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

    /** Stored person-matte mask resolution (SegMask allows up to 1024). */
    const val MATTE_SIDE = 512

    /**
     * Whether a person matte is trustworthy for this photograph. MODNet is a
     * portrait matter: excellent on one or two prominent people, unreliable on
     * small or crowded figures (it returns scattered fragments). Require a
     * prominent person in the scene model AND that the matte covers most of the
     * cells the scene model called "person". Otherwise keep the scene model.
     */
    fun matteAgrees(alpha: FloatArray, mw: Int, mh: Int, margins: FloatArray, side: Int): Boolean {
        require(alpha.size == mw * mh && margins.size == side * side)
        var person = 0
        var covered = 0
        for (y in 0 until side) for (x in 0 until side) {
            if (margins[y * side + x] <= 0f) continue
            person++
            val a = bilinear(alpha, mw, mh, (x + 0.5f) / side * mw - 0.5f, (y + 0.5f) / side * mh - 0.5f)
            if (a > 0.5f) covered++
        }
        return person >= side * side / 12 && covered >= person * 0.75f
    }

    /** MODNet input: short side 512, both sides a multiple of 32. */
    fun mattingInputSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = 512f / min(width, height)
        val w = max(32, ((width * scale).roundToInt() / 32) * 32)
        val h = max(32, ((height * scale).roundToInt() / 32) * 32)
        return w to h
    }

    /**
     * Where the scene model saw people, dilated by [radius] cells (a missed hair
     * edge or shoulder is still allowed) and softened: 1 near people, 0 far away.
     * Gates the person matte so a sign or poster far from anyone is never selected.
     */
    fun personSupport(margins: FloatArray, side: Int, radius: Int = 3): FloatArray {
        require(side > 0 && margins.size == side * side)
        val dilated = FloatArray(margins.size)
        for (y in 0 until side) for (x in 0 until side) {
            var hit = 0f
            loop@ for (yy in max(0, y - radius)..min(side - 1, y + radius))
                for (xx in max(0, x - radius)..min(side - 1, x + radius))
                    if (margins[yy * side + xx] > -2f) { hit = 1f; break@loop }
            dilated[y * side + x] = hit
        }
        var soft = dilated
        repeat(2) {
            val next = FloatArray(soft.size)
            for (y in 0 until side) for (x in 0 until side) {
                var sum = 0f
                var n = 0
                for (yy in max(0, y - 1)..min(side - 1, y + 1))
                    for (xx in max(0, x - 1)..min(side - 1, x + 1)) { sum += soft[yy * side + xx]; n++ }
                next[y * side + x] = sum / n
            }
            soft = next
        }
        return soft
    }

    /**
     * Saliency model output (sigmoid 0..1) stretched to 0..1 as the model's authors
     * do, or null when nothing in the photograph stands out (a raw peak below 0.5:
     * stretching that would only amplify noise into a fake selection).
     */
    fun normaliseSaliency(raw: FloatArray): FloatArray? {
        if (raw.isEmpty()) return null
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in raw) { if (!v.isFinite()) return null; lo = min(lo, v); hi = max(hi, v) }
        if (hi < 0.5f || hi - lo < 1e-4f) return null
        return FloatArray(raw.size) { ((raw[it] - lo) / (hi - lo)).coerceIn(0f, 1f) }
    }

    /**
     * Whether the salient-object map should define Select Subject.
     *
     * [sal] is [salSide]² over the photograph; [objectMargins] is the scene model's
     * chosen non-person object (score margin, > 0 inside) on its [side]² grid, or null
     * when the scene model found no selectable object.
     *
     * Not used when almost nothing stands out (under 0.4% of the frame), when nearly
     * everything does (over 85%: no single subject), or when the salient part is a
     * small piece of an object the scene model outlined (at least 60% of it lies inside
     * that object, which is over four times larger) — white daisies, for example, where
     * only the yellow centres stand out and the scene model's "flower" is the subject.
     */
    fun saliencyUsable(sal: FloatArray, salSide: Int, objectMargins: FloatArray?, side: Int): Boolean {
        require(salSide > 1 && sal.size == salSide * salSide)
        var salient = 0
        for (v in sal) if (v > 0.5f) salient++
        val area = salient.toFloat() / sal.size
        if (area < 0.004f || area > 0.85f) return false
        if (objectMargins == null) return true
        require(side > 1 && objectMargins.size == side * side)
        var objectCells = 0
        for (v in objectMargins) if (v > 0f) objectCells++
        val objectArea = objectCells.toFloat() / objectMargins.size
        if (objectArea <= 0f) return true
        var inside = 0
        for (y in 0 until salSide) for (x in 0 until salSide) {
            if (sal[y * salSide + x] <= 0.5f) continue
            val m = bilinear(objectMargins, side, side,
                (x + 0.5f) / salSide * side - 0.5f, (y + 0.5f) / salSide * side - 0.5f)
            if (m > 0f) inside++
        }
        val partOfObject = inside >= salient * 0.6f && area * 4f < objectArea
        return !partOfObject
    }

    /** A 0..1 map as clamped logits (±12), the evidence [refine] expects. */
    fun probabilityToMargins(p: FloatArray): FloatArray = FloatArray(p.size) {
        val q = p[it].coerceIn(1e-6f, 1f - 1e-6f)
        kotlin.math.ln(q / (1f - q)).coerceIn(-12f, 12f)
    }

    private fun bilinear(v: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (w - 1).toFloat())
        val y = fy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = x - x0
        val ty = y - y0
        val top = v[y0 * w + x0] * (1 - tx) + v[y0 * w + x1] * tx
        val bottom = v[y1 * w + x0] * (1 - tx) + v[y1 * w + x1] * tx
        return top * (1 - ty) + bottom * ty
    }

    /**
     * A 0..1 matte (on its own [mw]×[mh] grid over the photograph) as stored SegMask
     * logits on a square [outputSide] grid over the same photograph, optionally gated
     * by [support] (on a [supportSide] grid). [invert] gives the exact complement, so
     * Subject and Background always meet at the same edge.
     */
    fun matteToLogits(
        alpha: FloatArray, mw: Int, mh: Int,
        support: FloatArray?, supportSide: Int,
        outputSide: Int = MATTE_SIDE, invert: Boolean = false,
    ): ByteArray {
        require(mw > 1 && mh > 1 && alpha.size == mw * mh)
        require(support == null || (supportSide > 1 && support.size == supportSide * supportSide))
        require(outputSide in 16..1024)
        val out = ByteArray(outputSide * outputSide)
        for (y in 0 until outputSide) for (x in 0 until outputSide) {
            val u = (x + 0.5f) / outputSide
            val v = (y + 0.5f) / outputSide
            var a = bilinear(alpha, mw, mh, u * mw - 0.5f, v * mh - 0.5f)
            if (support != null) {
                a *= bilinear(support, supportSide, supportSide, u * supportSide - 0.5f, v * supportSide - 0.5f)
            }
            val p = a.coerceIn(1e-6f, 1f - 1e-6f)
            val logit = kotlin.math.ln(p / (1f - p))
            val stored = (logit.coerceIn(-15.875f, 15.875f) * 8f).roundToInt().coerceIn(-127, 127)
            // Negate the stored value (not the logit) so the complement is exact.
            out[y * outputSide + x] = (if (invert) -stored else stored).toByte()
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
        subjectBoundary: Boolean = false,
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
        // Tighten ONLY subject boundaries, keeping the original sky path byte-for-byte.
        // Across hair/sky and shoulder/grass, the old 10% weight floor mixed
        // dissimilar colours even when the photograph provided a strong edge.
        // A nonzero floor still protects soft hair against noisy colour gaps.
        val colourSigma = if (subjectBoundary) 2f * 0.12f * 0.12f
            else 2f * 0.15f * 0.15f
        val weightFloor = if (subjectBoundary) 0.035f else 0.10f
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
                    val weight = spatial * (weightFloor + (1f - weightFloor) * similar)
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
