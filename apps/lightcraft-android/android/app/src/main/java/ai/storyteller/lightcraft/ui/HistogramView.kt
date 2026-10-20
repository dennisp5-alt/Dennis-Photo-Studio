package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.ImageHistogram
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * RGB + display-encoded luma histogram of the *rendered preview*.
 *
 * Channel endpoint percentages are calculated from the same engine bins
 * as the plotted histogram, not sampled from a screenshot. They are the
 * largest clipped-channel percentages (conservative lower bounds on
 * pixels with any channel at an endpoint), NOT RAW sensor clipping or
 * a claim that information in the full-resolution export is lost.
 */
@Composable
fun HistogramView(histogram: ImageHistogram, modifier: Modifier = Modifier) {
    val data = remember(histogram) {
        val rgb = listOf(histogram.red, histogram.green, histogram.blue)
        val bins = (rgb + listOf(histogram.luma)).map { c ->
            FloatArray(c.size) { c[it].toFloat() }
        }
        // Display shape relative to non-endpoint peak. Endpoint counts can
        // be huge, so indicate true endpoint mass separately rather than
        // letting it flatten the entire useful graph.
        val peak = bins.maxOf { b ->
            (1 until (b.size - 1).coerceAtLeast(1)).maxOfOrNull { b[it] } ?: 0f
        }.coerceAtLeast(1f)
        val normalized = bins.map { b -> FloatArray(b.size) { (b[it] / peak).coerceAtMost(1f) } }
        val count = rgb.firstOrNull()?.sumOf { it.toDouble() }?.coerceAtLeast(1.0) ?: 1.0
        val dark = (rgb.maxOfOrNull { it.firstOrNull()?.toDouble() ?: 0.0 } ?: 0.0) * 100.0 / count
        val bright = (rgb.maxOfOrNull { it.lastOrNull()?.toDouble() ?: 0.0 } ?: 0.0) * 100.0 / count
        Triple(normalized, dark, bright)
    }
    val (channels, darkPct, brightPct) = data
    val accessibility = "Rendered preview RGB histogram. Channel black endpoint " +
        "%.1f".format(darkPct) + " percent; white endpoint " +
        "%.1f".format(brightPct) + " percent."
    Box(
        modifier
            .background(Color(0xA6000000), RoundedCornerShape(3.dp))
            .semantics { contentDescription = accessibility },
    ) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
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
                channels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                    drawPath(path(it, true), color.copy(alpha = 0.6f), blendMode = BlendMode.Plus)
                }
            }
            channels.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let {
                drawPath(path(it, false), Color.White.copy(alpha = 0.7f), style = Stroke(width = 1.dp.toPx()))
            }
            // Precise bin-driven endpoint markers: no inference from graph
            // height, which is deliberately normalized and peak-clamped.
            if (darkPct >= 0.1) drawLine(
                Color(0xFFFFB65C), Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()
            )
            if (brightPct >= 0.1) drawLine(
                Color(0xFFFF7777), Offset(size.width, 0f), Offset(size.width, size.height), 2.dp.toPx()
            )
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("RGB 0  " + "%.1f".format(darkPct) + "%", color = Color(0xFFFFC889), fontSize = 10.sp, maxLines = 1)
            Text("RGB 255  " + "%.1f".format(brightPct) + "%", color = Color(0xFFFFA4A4), fontSize = 10.sp, maxLines = 1)
        }
    }
}
