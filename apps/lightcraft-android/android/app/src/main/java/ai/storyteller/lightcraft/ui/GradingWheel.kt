package ai.storyteller.lightcraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A point of a grading wheel, [dx] right and [dy] down from its centre, as a hue (degrees, 0 at the
 * right, counter-clockwise, as the desktop's wheels) and a saturation (0–100, the rim is 100).
 */
fun wheelValue(dx: Double, dy: Double, radius: Double): Pair<Double, Double> {
    val hue = (Math.toDegrees(atan2(-dy, dx)) + 360.0) % 360.0
    val sat = if (radius <= 0.0) 0.0 else min(hypot(dx, dy) / radius * 100.0, 100.0)
    return hue.roundToInt().toDouble() % 360.0 to sat.roundToInt().toDouble()
}

/** The hues around the wheel at full strength, from the right going clockwise on screen (hue 360 − angle). */
private val wheelColors: List<Color> = (0..12).map { i -> Color.hsv(((360 - i * 30) % 360).toFloat(), 1f, 1f) }

/** The wheel's radius and how far outside its rim the hue handle runs (dp), as the reference's. */
private const val WHEEL_RADIUS = 90f
private const val HUE_HANDLE_OUT = 12f

/**
 * Colour grading's wheel for [range], as the reference's: 180 dp, white in the middle to full colour
 * at the rim, in a 230 dp box. Two handles move it, and only they do: the ring at the colour chosen
 * (its hue round the wheel, its saturation out from the middle), and the dot outside the rim at
 * that hue (round the rim: the hue alone); a finger keeps its distance from the handle it took. A
 * sideways swipe elsewhere pages to the next range ([onSwipe] gets +1 or −1). One undo step a drag.
 * [active] false: a neighbour, drawn ([alpha]) but not touched.
 */
@Composable
fun GradingWheel(vm: AppViewModel, range: String, hue: Double, sat: Double, active: Boolean = true, alpha: Float = 1f, onSwipe: (Int) -> Unit = {}) {
    val current by rememberUpdatedState(hue to sat)
    val swipe by rememberUpdatedState(onSwipe)
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .semantics { contentDescription = "$range wheel" }
            .then(
                if (!active) {
                    Modifier
                } else {
                    Modifier.pointerInput(range) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val r = WHEEL_RADIUS.dp.toPx()
                            val (h, s) = current
                            val a = Math.toRadians(h)
                            val dir = Offset(cos(a).toFloat(), -sin(a).toFloat())
                            val satAt = c + dir * (r * (s / 100.0).toFloat().coerceIn(0f, 1f))
                            val hueAt = c + dir * (r + HUE_HANDLE_OUT.dp.toPx())
                            val grab = 24.dp.toPx()
                            val handle = when {
                                (down.position - satAt).getDistance() <= grab -> "sat"
                                (down.position - hueAt).getDistance() <= grab -> "hue"
                                else -> null
                            }
                            if (handle == null) {
                                // not on a handle: a sideways swipe pages the wheels; up or down is the list's
                                var dx = 0f
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (abs(dy) > abs(dx) && abs(dy) > viewConfiguration.touchSlop) return@awaitEachGesture
                                }
                                if (abs(dx) > 48.dp.toPx()) swipe(if (dx < 0) 1 else -1)
                                return@awaitEachGesture
                            }
                            // the list under it doesn't scroll while a handle moves
                            down.consume()
                            val keep = (if (handle == "sat") satAt else hueAt) - down.position
                            vm.beginWheel(range)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val p = change.position + keep - c
                                val (nh, ns) = wheelValue(p.x.toDouble(), p.y.toDouble(), r.toDouble())
                                vm.dragWheel(range, nh, if (handle == "hue") current.second else ns)
                            }
                            vm.endWheel()
                        }
                    }
                },
            ),
    ) {
        val c = center
        val r = WHEEL_RADIUS.dp.toPx()
        drawCircle(Brush.sweepGradient(wheelColors, c), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), c, r), r, c)
        val a = Math.toRadians(hue)
        val dir = Offset(cos(a).toFloat(), -sin(a).toFloat())
        val satAt = c + dir * (r * (sat / 100.0).toFloat().coerceIn(0f, 1f))
        val hueAt = c + dir * (r + HUE_HANDLE_OUT.dp.toPx())
        drawCircle(if (sat > 0.0) Color.hsv(hue.toFloat().coerceIn(0f, 359.9f), 1f, 1f) else Color(0xFF7F7F7F), 7.8.dp.toPx(), hueAt)
        drawCircle(Color.White, 9.5.dp.toPx(), satAt)
        drawCircle(Color(0xFFA5A5A5), 9.5.dp.toPx(), satAt, style = Stroke(1.5.dp.toPx()))
        drawCircle(Color(0xFF6F6F6F), 2.6.dp.toPx(), satAt)
    }
}
