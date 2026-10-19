package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.ToneCurveInfo
import ai.storyteller.lightcraft.core.pointCurve
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The Curve tool's channels: the parametric curve, then the point curves (all, red, green, blue). */
val curveChannels = listOf("parametric", "master", "red", "green", "blue")

/** What a channel is called (its button's description). */
fun curveChannelName(channel: String) = when (channel) {
    "parametric" -> "Parametric curve"
    "master" -> "Point curve"
    "red" -> "Red channel"
    "green" -> "Green channel"
    else -> "Blue channel"
}

/** A channel's line colour, as the reference draws its curves. */
fun curveColor(channel: String) = when (channel) {
    "red" -> Color(0xFFFF4C4C)
    "green" -> Color(0xFF4CFF4C)
    "blue" -> Color(0xFF4C4CFF)
    else -> Color.White
}

/** How close two of a curve's points may be across (a sixteenth of it), and how many it may hold, as the reference's. */
const val CURVE_MIN_GAP = 1.0 / 16
const val CURVE_MAX_POINTS = 16

/** A point curve's points as pairs, `[x0, y0, x1, y1, …]`; fewer than two: a straight line's ends. */
fun curvePoints(flat: List<Double>): List<Pair<Double, Double>> {
    val pts = flat.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
    return if (pts.size < 2) listOf(0.0 to 0.0, 1.0 to 1.0) else pts
}

fun List<Pair<Double, Double>>.flat(): List<Double> = flatMap { (x, y) -> listOf(x, y) }

/** The open channel's points in [c] (the parametric channel has none). */
fun channelPoints(c: ToneCurveInfo, channel: String): List<Double> = when (channel) {
    "red" -> c.red
    "green" -> c.green
    "blue" -> c.blue
    else -> c.master
}

/**
 * [points] with point [i] moved to [x], [y], all in 0..1: an inner point stays between its
 * neighbours (1 % apart), the ends only keep to their side of them, as on the desktop.
 */
fun movedPoint(points: List<Pair<Double, Double>>, i: Int, x: Double, y: Double): List<Pair<Double, Double>> {
    if (i !in points.indices) return points
    val cx = x.coerceIn(0.0, 1.0)
    val lo = if (i == 0) 0.0 else points[i - 1].first + 0.01
    val hi = if (i == points.lastIndex) 1.0 else points[i + 1].first - 0.01
    val nx = when {
        points.size == 1 -> cx
        i == 0 -> min(cx, hi)
        i == points.lastIndex -> max(cx, lo)
        lo <= hi -> cx.coerceIn(lo, hi)
        else -> points[i].first
    }
    return points.toMutableList().also { it[i] = nx to y.coerceIn(0.0, 1.0) }
}

/** [points] with one at [x], [y] added in order, and its index; none closer than [CURVE_MIN_GAP] to another in x, at most [CURVE_MAX_POINTS]. */
fun withPoint(points: List<Pair<Double, Double>>, x: Double, y: Double): Pair<List<Pair<Double, Double>>, Int>? {
    val cx = x.coerceIn(0.0, 1.0)
    if (points.size >= CURVE_MAX_POINTS || points.any { abs(it.first - cx) < CURVE_MIN_GAP }) return null
    val i = points.indexOfFirst { it.first > cx }.let { if (it < 0) points.size else it }
    return points.toMutableList().apply { add(i, cx to y.coerceIn(0.0, 1.0)) } to i
}

/** The value of a curve sampled evenly over 0..1 ([samples]) at [x], between its samples. */
fun sampledAt(samples: List<Double>, x: Double): Double {
    if (samples.isEmpty()) return x
    if (samples.size == 1) return samples[0]
    val f = x.coerceIn(0.0, 1.0) * (samples.size - 1)
    val i = f.toInt().coerceAtMost(samples.size - 2)
    val t = f - i
    return samples[i] * (1 - t) + samples[i + 1] * t
}

/** The parametric curve's region under [x] (0..1), by its splits (in percent): that region's slider. */
fun curveRegion(x: Double, splitShadows: Double, splitMid: Double, splitHighlights: Double): String = when {
    x < splitShadows / 100 -> "curve.shadows"
    x < splitMid / 100 -> "curve.darks"
    x < splitHighlights / 100 -> "curve.lights"
    else -> "curve.highlights"
}

/**
 * The square the curve is drawn on, as the reference places it: in the panel (sideways), the box
 * itself; over the photo, a square 16 dp in from the view's sides, centred in what's left under the
 * top bar ([top]). Either way the graph sits [inset] inside it, so the end points show whole.
 */
private fun curveSquare(view: Size, inPanel: Boolean, top: Float, margin: Float, inset: Float, foot: Float = 0f): Rect {
    val outer = if (inPanel) {
        Rect(Offset.Zero, Size(min(view.width, view.height - foot), min(view.width, view.height - foot)))
    } else {
        // the parametric curve's split band ([foot]) goes under the square: the two are centred together
        val room = (view.height - top).coerceAtLeast(1f)
        val side = (min(view.width, room - foot) - 2 * margin).coerceAtLeast(1f)
        Rect(Offset((view.width - side) / 2, top + (room - side - foot) / 2), Size(side, side))
    }
    return outer.deflate(inset)
}

/** The parametric curve's split band under its square (dp): a gradient bar with a thumb at each split, as the reference's. */
private const val BAND = 57f
private val splitIds = listOf("curve.splitShadows", "curve.splitMid", "curve.splitHighlights")

/**
 * Light › Curve, over the photo: the open channel's curve on a square of the view. Point curves:
 * drag a point (one undo step a drag), drag the line elsewhere to add a point there and move it,
 * tap the line to add one, double-tap a point or drag it off the square to take it away. The
 * parametric curve: drag up or down over a region to raise or lower it (its slider moves along).
 */
@Composable
fun CurveOverlay(vm: AppViewModel, ed: EditorState, curve: ToneCurveInfo, modifier: Modifier, inPanel: Boolean = false) {
    val channel = ed.curveChannel
    val parametric = channel == "parametric"
    val points = ed.liveCurve ?: curvePoints(channelPoints(curve, channel))
    val line = remember(points, parametric, curve) {
        if (parametric) curve.parametric else runCatching { pointCurve(points.flat(), 129u) }.getOrDefault(emptyList())
    }
    var dragging by remember { mutableStateOf<Int?>(null) }
    val current by rememberUpdatedState(points)
    val currentLine by rememberUpdatedState(line)
    val color = curveColor(channel)
    Canvas(
        modifier.pointerInput(channel, inPanel) {
            var lastTap = 0L
            var lastTapped = -1
            awaitEachGesture {
                val down = awaitFirstDown()
                val foot = if (parametric) BAND.dp.toPx() else 0f
                val sq = curveSquare(Size(size.width.toFloat(), size.height.toFloat()), inPanel, TOP_BAR.dp.toPx(), 16.dp.toPx(), INSET.dp.toPx(), foot)
                // the reference's reach: 5 % of the graph (at least a fingertip)
                val hit = maxOf(sq.width * 0.05f, 16.dp.toPx())
                if (parametric && down.position.y > sq.bottom + hit && down.position.y < sq.bottom + foot + hit && down.position.x > sq.left - hit && down.position.x < sq.right + hit) {
                    // the split band: the nearest thumb follows the finger across, kept between its neighbours
                    val values = splitIds.map { ed.control(it)?.value ?: 50.0 }
                    val near = values.indices.minByOrNull { abs(sq.left + (values[it] / 100 * sq.width).toFloat() - down.position.x) } ?: return@awaitEachGesture
                    val control = ed.control(splitIds[near]) ?: return@awaitEachGesture
                    val lo = values.getOrNull(near - 1)?.plus(5.0) ?: control.min
                    val hi = values.getOrNull(near + 1)?.minus(5.0) ?: control.max
                    down.consume()
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        moved = true
                        val v = ((change.position.x - sq.left) / sq.width * 100).toDouble().coerceIn(lo, hi).coerceIn(control.min, control.max)
                        vm.onControlChange(control, v.roundToInt().toDouble())
                    }
                    if (moved) vm.onControlChangeFinished()
                    return@awaitEachGesture
                }
                if (!sq.inflate(hit).contains(down.position)) return@awaitEachGesture
                val side = sq.width.toDouble()
                fun xOf(p: Offset) = ((p.x - sq.left) / side).coerceIn(0.0, 1.0)
                fun screen(p: Pair<Double, Double>) = Offset(sq.left + (p.first * side).toFloat(), sq.bottom - (p.second * side).toFloat())
                val slop = viewConfiguration.touchSlop
                if (parametric) {
                    val id = curveRegion(
                        xOf(down.position),
                        ed.control("curve.splitShadows")?.value ?: 25.0,
                        ed.control("curve.splitMid")?.value ?: 50.0,
                        ed.control("curve.splitHighlights")?.value ?: 75.0,
                    )
                    val control = ed.control(id) ?: return@awaitEachGesture
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - down.position.y
                        if (!moved && abs(dy) > slop) moved = true
                        if (moved) {
                            change.consume()
                            vm.onControlChange(control, (control.value - dy / side * 200).coerceIn(control.min, control.max).roundToInt().toDouble())
                        }
                    }
                    if (moved) vm.onControlChangeFinished()
                    return@awaitEachGesture
                }
                val pts = current
                val near = pts.indices.minByOrNull { (screen(pts[it]) - down.position).getDistance() }
                    ?.takeIf { (screen(pts[it]) - down.position).getDistance() < hit }
                var base = pts
                var index = near
                var start = near?.let { pts[it] }
                var moved = false
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val d = change.position - down.position
                    if (!moved && d.getDistance() > slop) {
                        if (index == null) {
                            // the line taken where the finger went down: a new point there
                            val x = xOf(down.position)
                            val added = withPoint(pts, x, sampledAt(currentLine, x)) ?: break
                            base = added.first
                            index = added.second
                            start = base[added.second]
                        }
                        moved = true
                        dragging = index
                        vm.beginCurveDrag()
                    }
                    val i = index ?: continue
                    val s = start ?: continue
                    if (!moved) continue
                    change.consume()
                    // an inner point dragged off the square goes (back on it, it comes back)
                    val off = i != 0 && i != base.lastIndex && !sq.inflate(hit).contains(change.position)
                    vm.dragCurve(if (off) base.filterIndexed { k, _ -> k != i } else movedPoint(base, i, s.first + d.x / side, s.second - d.y / side))
                }
                dragging = null
                if (moved) {
                    vm.endCurveDrag()
                    return@awaitEachGesture
                }
                val now = System.currentTimeMillis()
                when {
                    near != null && near == lastTapped && now - lastTap < 400 -> {
                        // double tap: an inner point goes
                        lastTapped = -1
                        if (near != 0 && near != pts.lastIndex) vm.setCurvePoints(pts.filterIndexed { k, _ -> k != near })
                    }
                    near != null -> {
                        lastTapped = near
                        lastTap = now
                    }
                    sq.contains(down.position) -> {
                        val x = xOf(down.position)
                        withPoint(pts, x, sampledAt(currentLine, x))?.let { vm.setCurvePoints(it.first) }
                    }
                }
            }
        },
    ) {
        val sq = curveSquare(size, inPanel, TOP_BAR.dp.toPx(), 16.dp.toPx(), INSET.dp.toPx(), if (parametric) BAND.dp.toPx() else 0f)
        val hair = 1.dp.toPx()
        // the reference's frame and its 4 x 4 grid: faint white, the inner lines stopping short of the frame
        val grid = Color.White.copy(alpha = 0.2f)
        drawRect(grid, sq.topLeft, sq.size, style = Stroke(hair))
        val short = 5.dp.toPx()
        for (k in 1..3) {
            val f = k / 4f
            drawLine(grid, Offset(sq.left + sq.width * f, sq.top + short), Offset(sq.left + sq.width * f, sq.bottom - short), hair)
            drawLine(grid, Offset(sq.left + short, sq.top + sq.height * f), Offset(sq.right - short, sq.top + sq.height * f), hair)
        }
        if (parametric) {
            // the regions' edges, and under the square the split band: a dark-to-light bar, a thumb at each split
            val barTop = sq.bottom + 20.dp.toPx()
            val bar = 6.dp.toPx()
            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White), sq.left, sq.right), Offset(sq.left, barTop), Size(sq.width, bar))
            drawRect(grid, Offset(sq.left, barTop), Size(sq.width, bar), style = Stroke(hair))
            for (id in splitIds) {
                val v = (ed.control(id)?.value ?: continue) / 100
                val x = sq.left + (v * sq.width).toFloat()
                drawLine(grid, Offset(x, sq.top + short), Offset(x, sq.bottom - short), hair)
                val tip = barTop + bar + 2.dp.toPx()
                val thumb = Path().apply {
                    moveTo(x, tip)
                    lineTo(x - 7.dp.toPx(), tip + 12.dp.toPx())
                    lineTo(x + 7.dp.toPx(), tip + 12.dp.toPx())
                    close()
                }
                drawPath(thumb, Color(0xFFCCCCCC))
                drawPath(thumb, Color.Black.copy(alpha = 0.5f), style = Stroke(hair))
            }
        }
        if (line.size >= 2) {
            val path = Path()
            line.forEachIndexed { i, y ->
                val px = sq.left + sq.width * i / (line.size - 1)
                val py = sq.bottom - sq.height * y.toFloat().coerceIn(0f, 1f)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            // a 1 dp line on a 3 dp shadow, as the reference's (it reads over any photo)
            drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(width = 3.dp.toPx()))
            drawPath(path, color, style = Stroke(width = hair))
        }
        if (!parametric) {
            points.forEachIndexed { i, p ->
                val c = Offset(sq.left + (p.first * sq.width).toFloat(), sq.bottom - (p.second * sq.height).toFloat())
                drawCircle(Color(0xFFCCCCCC), (if (i == dragging) 7.dp else 6.dp).toPx(), c)
            }
        }
    }
}

/** The top bar's height over the photo (the graph centres under it) and the graph's inset in its square (dp). */
private const val TOP_BAR = 56f
private const val INSET = 11.5f
