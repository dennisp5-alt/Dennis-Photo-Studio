package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.ImageHistogram
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** The rendered image's histogram: red, green and blue (adding up to white where they overlap) and luminance. */
@Composable
fun HistogramView(histogram: ImageHistogram, modifier: Modifier = Modifier) {
    val channels = remember(histogram) {
        val bins = listOf(histogram.red, histogram.green, histogram.blue, histogram.luma).map { c -> FloatArray(c.size) { c[it].toFloat() } }
        // scale to the tallest bin away from the ends (clipped pixels pile up there)
        val peak = bins.maxOf { b -> (1 until b.size - 1).maxOfOrNull { b[it] } ?: 0f }.coerceAtLeast(1f)
        bins.map { b -> FloatArray(b.size) { (b[it] / peak).coerceAtMost(1f) } }
    }
    Canvas(modifier.background(Color(0xA6000000), RoundedCornerShape(3.dp)).padding(5.dp)) {
        fun path(v: FloatArray, closed: Boolean) = Path().apply {
            val dx = if (v.size > 1) size.width / (v.size - 1) else 0f
            if (closed) moveTo(0f, size.height) else moveTo(0f, size.height * (1f - (v.firstOrNull() ?: 0f)))
            v.forEachIndexed { i, y -> lineTo(i * dx, size.height * (1f - y)) }
            if (closed) {
                lineTo(size.width, size.height)
                close()
            }
        }
        val rgb = listOf(Color(0xFFE5484D), Color(0xFF46A758), Color(0xFF3E63DD))
        rgb.forEachIndexed { i, color ->
            channels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { drawPath(path(it, true), color.copy(alpha = 0.6f), blendMode = BlendMode.Plus) }
        }
        channels.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { drawPath(path(it, false), Color.White.copy(alpha = 0.7f), style = Stroke(width = 1.dp.toPx())) }
    }
}
