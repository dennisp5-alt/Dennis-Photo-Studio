package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.MaskInfo
import ai.storyteller.lightcraft.core.SliderTrack
import ai.storyteller.lightcraft.core.ViewGeometry
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * A colour or luminance range being picked, as the reference's picker: a part just made (its Cancel
 * takes the part away) or one picked again (its Cancel puts it back), sampled at the ring ([mode]
 * `point`, [ring] where it is: none yet, the view's middle) or over an area dragged on the photo
 * ([mode] `area`, [area] its corners: x0, y0, x1, y1). Its sheet's Apply waits for a sample.
 */
class RangePicker(val kind: String, val mask: UInt, val part: Int, val creating: Boolean, mode: String, ring: Pair<Double, Double>?, area: List<Double>?) {
    var mode by mutableStateOf(mode)
    var ring by mutableStateOf(ring)
    var area by mutableStateOf(area)
    var sampled by mutableStateOf(!creating)

    /** Where the history was as it opened: its Cancel takes back every step after it. */
    internal var mark: ULong? = null

    val color: Boolean get() = kind == "colorRange"
}

// ------------------------------------------------------------------------------ luminance scale

/** The engine's middle grey (`tone::GREY`). */
private const val GREY = 0.18

/**
 * A luminance range's value (the engine's scale: log luminance, −8..+4 EV round middle grey) as the
 * picker shows it: lightness 0..1, CIE L* / 100 of the scene's luminance, white the top of the
 * scale and black its foot (`masks::lightness_from_range`).
 */
fun lightnessFromRange(v: Double): Double {
    if (v >= 1.0) return 1.0
    if (v <= 0.0) return 0.0
    val y = GREY * 2.0.pow(12.0 * v - 8.0)
    val lstar = if (y > 0.008856) 116.0 * Math.cbrt(y) - 16.0 else 903.3 * y
    return (lstar / 100.0).coerceAtMost(1.0)
}

/** Lightness 0..1 on the engine's luminance range scale (`masks::range_from_lightness`). */
fun rangeFromLightness(l: Double): Double {
    if (l >= 1.0) return 1.0
    if (l <= 0.0) return 0.0
    val lstar = l * 100.0
    val y = if (lstar > 8.0) ((lstar + 16.0) / 116.0).pow(3) else lstar / 903.3
    return ((log2(y / GREY) + 8.0) / 12.0).coerceIn(0.0, 1.0)
}

/**
 * A luminance range's shape as the slider's four stops: where the fade starts, full starts, full
 * ends, the fade ends. A ranked range (the reference's: ranks in the photo's own luminance) shows
 * its values as they are; an older one its lightnesses.
 */
fun luminanceStops(shape: JSONObject): List<Double> {
    val lo = shape.optDouble("lo", 0.0)
    val hi = shape.optDouble("hi", 1.0)
    val shown: (Double) -> Double = if (shape.optBoolean("ranked")) { v -> v.coerceIn(0.0, 1.0) } else ::lightnessFromRange
    return listOf(
        shown(lo - shape.optDouble("lo_feather", 0.0)),
        shown(lo),
        shown(hi),
        shown(hi + shape.optDouble("hi_feather", 0.0)),
    )
}

/** The slider's four stops back as the engine's luminance range (`lo`, `hi` and their fades). */
fun luminanceShape(shape: JSONObject, stops: List<Double>): JSONObject {
    val (a, b, c, d) = if (shape.optBoolean("ranked")) stops.map { it.coerceIn(0.0, 1.0) } else stops.map(::rangeFromLightness)
    return JSONObject(shape.toString()).put("lo", b).put("hi", c).put("lo_feather", max(b - a, 0.0)).put("hi_feather", max(d - c, 0.0))
}

// ------------------------------------------------------------------------------- picked colour

/**
 * The colour a range part took, as near as the photo shows it: a colour range's sample (or its
 * area's middle), OkLab of the scene colour (an older range's squeezed `c / (1 + c)`: unsqueezed),
 * shown as sRGB; a luminance range's grey (the middle of its full range). Null before anything is
 * picked.
 */
fun pickedColor(kind: String, shape: JSONObject): Color? {
    if (kind == "luminanceRange") {
        if ((shape.optJSONArray("picked")?.length() ?: 0) == 0) return null
        val s = luminanceStops(shape)
        val l = ((s[1] + s[2]) / 2).toFloat().coerceIn(0f, 1f)
        return Color(l, l, l)
    }
    val lab = shape.optJSONArray("samples")?.optJSONArray(0)?.let { a -> DoubleArray(3) { a.optDouble(it, 0.0) } }
        ?: shape.optJSONArray("area")?.let { box ->
            val lo = box.optJSONArray(0)
            val hi = box.optJSONArray(1)
            if (lo == null || hi == null) null else DoubleArray(3) { (lo.optDouble(it, 0.0) + hi.optDouble(it, 0.0)) / 2 }
        }
        ?: return null
    return oklabColor(lab[0], lab[1], lab[2], squeezed = !shape.optBoolean("hue_first"))
}

private fun oklabColor(l: Double, a: Double, b: Double, squeezed: Boolean): Color {
    val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    val rgb = doubleArrayOf(
        4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
        -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
        -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
    ).map { t ->
        val c = if (squeezed) t.coerceIn(0.0, 0.999).let { it / (1 - it) }.coerceAtMost(1.0) else t.coerceIn(0.0, 1.0)
        (if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
    }
    return Color(rgb[0], rgb[1], rgb[2])
}

// ---------------------------------------------------------------------------------------- sheet

private val pickerSheet = Color(0xFF1C1C1C)
private val pickerText = Color(0xFFEBEBEB)
private val modeOff = Color(0xFF393939)
private val modeOn = Color(0xFF7C7C7C)
private val applyOff = Color(0xFF3E3E3E)
private val applyOffText = Color(0xFF6E6E6E)
private val stopText = Color(0xFFB0B0B0)

/**
 * The picker's sheet, as the reference's: "Select color" (Refine under it, from 1 to 100) or "Select
 * luminance" (the range's slider and its four values) with the mask overlay's toggle at the right;
 * under them Cancel, Mode (the ring or an area) and Apply (blue once there's a sample). Sideways
 * ([sideways]) it fills the panel: Mode under the title, Cancel and Apply at the foot.
 */
@Composable
fun RangePickerSheet(vm: AppViewModel, ed: EditorState, picker: RangePicker, modifier: Modifier = Modifier, sideways: Boolean = false) {
    val mask = ed.masks.firstOrNull { it.id == picker.mask }
    val shape = mask?.components?.getOrNull(picker.part)?.let { runCatching { JSONObject(it.shape) }.getOrNull() } ?: JSONObject()
    // the reference's insets: the colour sheet's 8 dp, the luminance sheet's 12 dp
    val inset = if (picker.color) 8.dp else 12.dp
    val title = @Composable { top: Dp ->
        Row(Modifier.fillMaxWidth().padding(top = top), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (picker.color) "Select color" else "Select luminance",
                color = Color.White,
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.padding(start = if (picker.color || !sideways) 12.dp else 4.dp).weight(1f),
            )
            Box(Modifier.padding(end = 8.dp)) {
                BarButton(
                    LcIcon.MaskOverlay,
                    if (ed.showMaskOverlay) "Hide mask" else "Show mask",
                    size = 48.dp,
                    tint = if (ed.showMaskOverlay) Lc.barIconOn else pickerText,
                    onClick = vm::toggleMaskOverlay,
                )
            }
        }
    }
    val controls = @Composable {
        if (picker.color) {
            val refine = ed.liveShape["refine"] ?: shape.optDouble("refine", 50.0)
            val control = DevelopControl("mask.pickRefine", "Refine", "", "", 1.0, 100.0, 50.0, 1.0, 0u, refine.coerceIn(1.0, 100.0), SliderTrack.Plain)
            Spacer(Modifier.height(if (sideways) 23.dp else 12.dp))
            DevelopSlider(
                control,
                onChange = { vm.pickerShapeValue("refine", it) },
                onFinished = vm::pickerSliderDone,
                onReset = { vm.pickerShapeValue("refine", 50.0) },
                enabled = picker.sampled,
            )
            Spacer(Modifier.height(15.dp))
        } else {
            val stops = ed.liveStops ?: luminanceStops(shape)
            // upright the reference's slider starts 8 dp under the title, over the foot of its toggle's row
            val up = if (sideways) Modifier else Modifier.offset(y = (-5).dp)
            RampedRangeSlider(stops, enabled = picker.sampled, onChange = vm::pickerStops, onDone = vm::pickerSliderDone, modifier = up.fillMaxWidth().height(70.dp))
            Row(up.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 7.dp)) {
                val pct = stops.map { (it * 100).roundToInt() }
                Text("${pct[0]} / ${pct[1]}", color = stopText, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${pct[2]} / ${pct[3]}", color = stopText, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
            Spacer(Modifier.height(19.dp))
        }
    }
    val modes = @Composable {
        Row(Modifier.clip(RoundedCornerShape(4.dp)).background(modeOff)) {
            listOf("point" to "Point", "area" to "Area").forEach { (id, label) ->
                val on = picker.mode == id
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (on) modeOn else modeOff)
                        .selectable(on, role = Role.RadioButton) { vm.setPickerMode(id) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(30.dp)) { if (id == "point") drawRingIcon() else drawAreaIcon() }
                }
            }
        }
    }
    val cancel = @Composable { BarButton(MdIcon.Close, "Cancel", size = 40.dp, tint = pickerText, onClick = vm::cancelRangePicker) }
    val apply = @Composable {
        Box(
            Modifier
                .padding(end = if (picker.color || sideways) 8.dp else 4.dp)
                .size(width = 90.dp, height = 48.dp)
                .clip(RoundedCornerShape(50))
                .background(if (picker.sampled) Lc.primaryBlue else applyOff)
                .clickable(enabled = picker.sampled, role = Role.Button, onClick = vm::applyRangePicker),
            contentAlignment = Alignment.Center,
        ) {
            Text("Apply", color = if (picker.sampled) Color.White else applyOffText, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
    if (sideways) {
        // sideways, as the reference's: the title at the top, Mode under it, then the controls; Cancel
        // and Apply at the panel's foot
        Column(modifier.fillMaxWidth().fillMaxHeight().background(pickerSheet).padding(start = inset, end = inset, bottom = 28.dp)) {
            title(20.dp)
            Row(Modifier.padding(top = if (picker.color) 16.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mode", color = pickerText, fontFamily = Inter, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = if (picker.color) 8.dp else 0.dp, end = 12.dp))
                modes()
            }
            controls()
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                cancel()
                Spacer(Modifier.weight(1f))
                apply()
            }
        }
        return
    }
    Column(modifier.fillMaxWidth().background(pickerSheet).padding(start = inset, end = inset, bottom = 28.dp)) {
        title(if (picker.color) 16.dp else 11.7.dp)
        controls()
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            cancel()
            // the modes 108 dp in from the sheet's edge whatever the word's width (the reference's)
            Text("Mode", color = pickerText, fontFamily = Inter, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = 7.7.dp).width(52.3.dp))
            modes()
            Spacer(Modifier.weight(1f))
            apply()
        }
    }
}

/** The point mode's icon: a ring with four ticks. */
private fun DrawScope.drawRingIcon() {
    val c = center
    val r = size.minDimension * 0.27f
    val w = 1.8.dp.toPx()
    drawCircle(pickerText, r, c, style = Stroke(w))
    val t = size.minDimension * 0.47f
    drawLine(pickerText, Offset(c.x, c.y - t), Offset(c.x, c.y - r), w)
    drawLine(pickerText, Offset(c.x, c.y + r), Offset(c.x, c.y + t), w)
    drawLine(pickerText, Offset(c.x - t, c.y), Offset(c.x - r, c.y), w)
    drawLine(pickerText, Offset(c.x + r, c.y), Offset(c.x + t, c.y), w)
}

/** The area mode's icon: a dashed square. */
private fun DrawScope.drawAreaIcon() {
    val s = size.minDimension * 0.66f
    val tl = Offset((size.width - s) / 2, (size.height - s) / 2)
    drawRect(pickerText, tl, Size(s, s), style = Stroke(1.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.4.dp.toPx(), 2.6.dp.toPx()))))
}

// ------------------------------------------------------------------------------- range slider

private val checkLight = Color(0xFF5C5C5C)
private val checkDark = Color(0xFF454545)
private val knobFace = Color(0xFFEFEFEF)
private val knobLine = Color(0xFFA2A2A2)
private val pinFace = Color(0xFFE1E1E1)
private val unpicked = Color(0xFF404040)

/**
 * A luminance range as the reference's slider shows it (70 dp): the scale from dark to light over a
 * checkered track (35 dp, 15 dp in from each side), clear where the range leaves out and fading
 * where it fades; a framed box over the part fully in, with a knob in its middle (drag it to move
 * the whole range) and grips on its ends (drag to move an end); a pin above each end of the fade
 * (drag to move it). [stops]: where the fade starts, full starts, full ends, the fade ends (0..1).
 */
@Composable
fun RampedRangeSlider(stops: List<Double>, enabled: Boolean, onChange: (List<Double>) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(stops)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Canvas(
        modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val left = 15.dp.toPx()
                val w = (size.width - 2 * left).coerceAtLeast(1f)
                val trackTop = 30.dp.toPx()
                fun x(v: Double) = left + (v * w).toFloat()
                val s = current
                // the grips, then the pins, then the box (the nearest of those the finger is on)
                val grip = 15.dp.toPx()
                val pin = 9.5.dp.toPx()
                val hits = buildList {
                    if (down.position.y >= trackTop - 4.dp.toPx()) {
                        if (abs(down.position.x - x(s[1])) <= grip) add(1 to abs(down.position.x - x(s[1])))
                        if (abs(down.position.x - x(s[2])) <= grip) add(2 to abs(down.position.x - x(s[2])))
                    }
                    if (abs(down.position.x - x(s[0])) <= pin) add(0 to abs(down.position.x - x(s[0])) + 0.5f)
                    if (abs(down.position.x - x(s[3])) <= pin) add(3 to abs(down.position.x - x(s[3])) + 0.5f)
                }
                val handle = hits.minByOrNull { it.second }?.first
                    ?: if (down.position.x in x(s[1])..x(s[2]) && down.position.y >= trackTop) 4 else return@awaitEachGesture
                val start = s
                val x0 = down.position.x
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    c.consume()
                    val d = ((c.position.x - x0) / w).toDouble()
                    change(moved(start, handle, d))
                }
                done()
            }
        },
    ) {
        val left = 15.dp.toPx()
        val w = size.width - 2 * left
        val top = 30.dp.toPx()
        val bottom = top + 35.dp.toPx()
        fun x(v: Double) = left + (v * w).toFloat()
        // nothing picked yet: a plain grey track (the reference's)
        if (!enabled) drawRect(unpicked, Offset(left, top), Size(w, bottom - top))
        // the checkered track, the scale over it as much as it's selected
        val sq = 11.7.dp.toPx()
        if (enabled) clipRect(left, top, left + w, bottom) {
            var i = 0
            var cx = left
            while (cx < left + w) {
                var cy = top
                var j = 0
                while (cy < bottom) {
                    drawRect(if ((i + j) % 2 == 0) checkDark else checkLight, Offset(cx, cy), Size(sq, sq))
                    cy += sq
                    j++
                }
                cx += sq
                i++
            }
        }
        val steps = if (enabled) 120 else 0
        for (k in 0 until steps) {
            val v = (k + 0.5) / steps
            val a = weight(stops, v).toFloat()
            if (a <= 0f) continue
            val g = v.toFloat()
            drawRect(Color(g, g, g, a), Offset(left + w * k / steps, top), Size(w / steps + 0.6f, bottom - top))
        }
        // the full range's box, its knob and grips
        val bx0 = x(stops[1])
        val bx1 = x(stops[2])
        val frame = 2.5.dp.toPx()
        drawRoundRect(knobFace, Offset(bx0, top - 1.5.dp.toPx()), Size(max(bx1 - bx0, 1f), bottom - top + 3.dp.toPx()), CornerRadius(4.dp.toPx()), style = Stroke(frame))
        val mid = Offset((bx0 + bx1) / 2, (top + bottom) / 2)
        drawCircle(Color.Black, 8.dp.toPx(), mid)
        drawCircle(Color.White, 6.6.dp.toPx(), mid)
        drawCircle(Color.Black, 4.2.dp.toPx(), mid, style = Stroke(1.4.dp.toPx()))
        for (gx in listOf(bx0, bx1)) {
            val gw = 10.dp.toPx()
            val gh = 24.dp.toPx()
            drawRoundRect(knobFace, Offset(gx - gw / 2, mid.y - gh / 2), Size(gw, gh), CornerRadius(2.dp.toPx()))
            for (dx in listOf(-1.5f, 1.5f)) {
                drawLine(knobLine, Offset(gx + dx.dp.toPx(), mid.y - 7.dp.toPx()), Offset(gx + dx.dp.toPx(), mid.y + 7.dp.toPx()), 1.dp.toPx())
            }
        }
        // the fade's pins, above the track, a line down through it (not where it meets the box)
        for ((i, v) in listOf(0 to stops[0], 3 to stops[3])) {
            val px = x(v)
            // a fade of nothing (its pin on the box's edge, as near as the values show) has no pin
            if ((i == 0 && v >= stops[1] - 0.005) || (i == 3 && v <= stops[2] + 0.005)) continue
            drawLine(pinFace, Offset(px, top - 6.dp.toPx()), Offset(px, bottom), 1.5.dp.toPx())
            drawPin(Offset(px, top - 6.dp.toPx()))
        }
    }
}

/** How much of lightness [v] the range [s] selects: none outside the fade, all inside the full range. */
private fun weight(s: List<Double>, v: Double): Double = when {
    v < s[0] || v > s[3] -> 0.0
    v < s[1] -> if (s[1] > s[0]) (v - s[0]) / (s[1] - s[0]) else 1.0
    v <= s[2] -> 1.0
    else -> if (s[3] > s[2]) (s[3] - v) / (s[3] - s[2]) else 1.0
}

/** A pin's head, its point at [tip]: 15 dp wide, rounded at the top. */
private fun DrawScope.drawPin(tip: Offset) {
    val w = 15.dp.toPx()
    val h = 20.dp.toPx()
    val r = 3.dp.toPx()
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - w / 2, tip.y - h * 0.32f)
        lineTo(tip.x - w / 2, tip.y - h + r)
        quadraticTo(tip.x - w / 2, tip.y - h, tip.x - w / 2 + r, tip.y - h)
        lineTo(tip.x + w / 2 - r, tip.y - h)
        quadraticTo(tip.x + w / 2, tip.y - h, tip.x + w / 2, tip.y - h + r)
        lineTo(tip.x + w / 2, tip.y - h * 0.32f)
        close()
    }
    drawPath(path, pinFace)
}

/** The stops after dragging [handle] (0 to 3, 4 the box) by [d] of the scale, kept in order and on the scale. */
private fun moved(s: List<Double>, handle: Int, d: Double): List<Double> {
    val (a, b, c, e) = s
    return when (handle) {
        0 -> listOf((a + d).coerceIn(0.0, b), b, c, e)
        3 -> listOf(a, b, c, (e + d).coerceIn(c, 1.0))
        1 -> {
            val nb = (b + d).coerceIn(0.0, c)
            listOf(min(a, nb), nb, c, e)
        }
        2 -> {
            val nc = (c + d).coerceIn(b, 1.0)
            listOf(a, b, nc, max(e, nc))
        }
        else -> {
            // the whole range, its full part kept on the scale, the fades shortened at its ends
            val k = d.coerceIn(-b, 1.0 - c)
            listOf((a + k).coerceIn(0.0, 1.0), b + k, c + k, (e + k).coerceIn(0.0, 1.0))
        }
    }
}

// ------------------------------------------------------------------------------------- overlay

/**
 * The picker on the photo, as the reference's: the ring (115 dp, a white circle with a crosshair
 * in its middle, the picked colour inside its edge) that a finger moves to sample what's under its
 * middle as it goes, a finger elsewhere left to the photo; or, for an area, a dashed box a finger
 * draws, sampled when it lifts.
 */
@Composable
fun RangePickerOverlay(vm: AppViewModel, ed: EditorState, picker: RangePicker, mask: MaskInfo?, geometry: ViewGeometry, imageSize: IntSize, modifier: Modifier) {
    val shape = mask?.components?.getOrNull(picker.part)?.let { runCatching { JSONObject(it.shape) }.getOrNull() } ?: JSONObject()
    val picked = if (picker.sampled) pickedColor(picker.kind, shape) else null
    // the box being drawn (screen), before it's sampled
    var drawing by remember { mutableStateOf<Rect?>(null) }
    val currentGeometry by rememberUpdatedState(geometry)
    val currentSize by rememberUpdatedState(imageSize)
    Canvas(
        modifier.pointerInput(picker, picker.mode) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val map = ViewMap(currentGeometry, fittedRect(size, currentSize))
                if (picker.mode == "point") {
                    val ring = picker.ring?.let { (x, y) -> map.screen(x, y) } ?: Offset(size.width / 2f, size.height / 2f)
                    // on the ring (or 20 dp round it): it follows the finger; elsewhere the photo's
                    if ((down.position - ring).getDistance() > (57.5 + 20).dp.toPx()) return@awaitEachGesture
                    down.consume()
                    val grab = ring - down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (ev.changes.size > 1) break
                        c.consume()
                        val (x, y) = map.photo(c.position + grab)
                        if (x in 0.0..1.0 && y in 0.0..1.0) vm.pickAt(x, y)
                    }
                    vm.pickDone()
                } else {
                    var end = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        // two fingers: the photo's
                        if (ev.changes.size > 1) {
                            drawing = null
                            return@awaitEachGesture
                        }
                        if (!c.pressed) break
                        c.consume()
                        end = c.position
                        drawing = Rect(down.position, end)
                    }
                    drawing = null
                    val r = Rect(down.position, end)
                    // less than a dp either way isn't an area (the reference's)
                    if (abs(r.width) <= 1.dp.toPx() || abs(r.height) <= 1.dp.toPx()) return@awaitEachGesture
                    val (x0, y0) = map.photo(r.topLeft)
                    val (x1, y1) = map.photo(r.bottomRight)
                    vm.pickArea(x0, y0, x1, y1)
                }
            }
        },
    ) {
        val map = ViewMap(geometry, fittedRect(IntSize(size.width.toInt(), size.height.toInt()), imageSize))
        if (picker.mode == "point") {
            // none yet: the view's middle (the reference's)
            val c = picker.ring?.let { (x, y) -> map.screen(x, y) } ?: center
            drawPickerRing(c, picked)
        } else {
            val shown = drawing ?: picker.area?.let { a -> Rect(map.screen(a[0], a[1]), map.screen(a[2], a[3])) }
            if (shown != null) {
                val r = Rect(min(shown.left, shown.right), min(shown.top, shown.bottom), max(shown.left, shown.right), max(shown.top, shown.bottom))
                if (r.width >= 1f || r.height >= 1f) {
                    drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                }
            }
        }
    }
}

/** The reference's ring at [c]: a 3 dp white circle 56 dp round, the picked colour 4 dp wide inside it, a crosshair in its middle. */
private fun DrawScope.drawPickerRing(c: Offset, picked: Color?) {
    drawCircle(Color.Black.copy(alpha = 0.25f), 57.5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
    drawCircle(Color.White, 56.dp.toPx(), c, style = Stroke(3.dp.toPx()))
    if (picked != null) drawCircle(picked, 52.9.dp.toPx(), c, style = Stroke(4.dp.toPx()))
    val cross = Color(0xE6CCCCCC)
    drawCircle(cross, 7.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    val tick = Color(0xE6FFFFFF)
    val (a, b) = 7.5.dp.toPx() to 10.5.dp.toPx()
    drawLine(tick, Offset(c.x, c.y - b), Offset(c.x, c.y - a), 1.dp.toPx())
    drawLine(tick, Offset(c.x, c.y + a), Offset(c.x, c.y + b), 1.dp.toPx())
    drawLine(tick, Offset(c.x - b, c.y), Offset(c.x - a, c.y), 1.dp.toPx())
    drawLine(tick, Offset(c.x + a, c.y), Offset(c.x + b, c.y), 1.dp.toPx())
}
