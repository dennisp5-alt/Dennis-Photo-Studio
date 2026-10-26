package ai.storyteller.lightcraft.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Preserve the original subject's proportions before semantic inference.
 *
 * The previous 512x512 forced stretch made portrait heads, shoulders and
 * hair wider than photographed. Letterboxing is used ONLY for new Subject
 * selections; Sky and Background retain their established path.
 *
 * Reproject output logits to the editor's normalised square mask grid so
 * all saved masks, orientation, crops and image previews remain compatible.
 */
internal object SubjectAspectMapper {
    data class Fit(val left: Int, val top: Int, val width: Int, val height: Int)

    fun fit(width: Int, height: Int, side: Int): Fit {
        require(width > 0 && height > 0 && side > 1)
        val w: Int
        val h: Int
        if (width >= height) {
            w = side
            h = (height.toDouble() * side / width).roundToInt().coerceIn(1, side)
        } else {
            h = side
            w = (width.toDouble() * side / height).roundToInt().coerceIn(1, side)
        }
        return Fit((side - w) / 2, (side - h) / 2, w, h)
    }

    class SampleMap(private val side: Int, val fit: Fit, private val inputSide: Int) {
        private val x0 = IntArray(side)
        private val x1 = IntArray(side)
        private val tx = FloatArray(side)
        private val y0 = IntArray(side)
        private val y1 = IntArray(side)
        private val ty = FloatArray(side)

        init {
            require(side > 1 && inputSide > 1)
            for (i in 0 until side) {
                val fx = ((fit.left + (i + 0.5f) * fit.width / side) * side / inputSide - 0.5f)
                    .coerceIn(0f, side - 1f)
                val fy = ((fit.top + (i + 0.5f) * fit.height / side) * side / inputSide - 0.5f)
                    .coerceIn(0f, side - 1f)
                x0[i] = fx.toInt()
                x1[i] = min(side - 1, x0[i] + 1)
                tx[i] = fx - x0[i]
                y0[i] = fy.toInt()
                y1[i] = min(side - 1, y0[i] + 1)
                ty[i] = fy - y0[i]
            }
        }

        /** Interpolate a single model-class logit, without allocating a second
         * 150 x side x side tensor on a memory-limited phone. */
        fun at(plane: Array<FloatArray>, x: Int, y: Int): Float {
            val xx0 = x0[x]; val xx1 = x1[x]
            val yy0 = y0[y]; val yy1 = y1[y]
            val top = plane[yy0][xx0] * (1f - tx[x]) + plane[yy0][xx1] * tx[x]
            val bottom = plane[yy1][xx0] * (1f - tx[x]) + plane[yy1][xx1] * tx[x]
            return top * (1f - ty[y]) + bottom * ty[y]
        }
    }
}
