package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.SliderTrack
import ai.storyteller.lightcraft.core.SpotInfo
import ai.storyteller.lightcraft.core.ViewGeometry
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A 2-D affine map `x' = a·x + c·y + e`, `y' = b·x + d·y + f` (the engine's layout of the six numbers). */
class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun apply(x: Double, y: Double): Pair<Double, Double> = (a * x + c * y + e) to (b * x + d * y + f)

    fun inverse(): Affine? {
        val det = a * d - b * c
        if (abs(det) < 1e-12) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    companion object {
        fun of(v: List<Double>): Affine? = if (v.size == 6) Affine(v[0], v[1], v[2], v[3], v[4], v[5]) else null
    }
}

/** The view ↔ photo maps of [g], and how many screen pixels a photo pixel is when the view fills [shown] (its rectangle on screen). */
class ViewMap(g: ViewGeometry, private val shown: Rect) {
    private val toPhoto = Affine.of(g.toPhoto) ?: Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    private val toView = toPhoto.inverse() ?: Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    private val long = max(g.width, g.height)

    /** Screen pixels per photo pixel. */
    val scale: Double = shown.width / hypot(toPhoto.a * g.width, toPhoto.b * g.height).coerceAtLeast(1e-9)

    fun photo(p: Offset): Pair<Double, Double> = toPhoto.apply(((p.x - shown.left) / shown.width).toDouble(), ((p.y - shown.top) / shown.height).toDouble())

    fun screen(x: Double, y: Double): Offset {
        val (u, v) = toView.apply(x, y)
        return Offset(shown.left + (u * shown.width).toFloat(), shown.top + (v * shown.height).toFloat())
    }

    /** A size given as a fraction of the photo's long edge, in screen pixels. */
    fun radius(size: Double): Float = (size * long * scale).toFloat()
}

/**
 * The spots over the photo, and the finger's brush: paint over something to take it away (one
 * spot along the path), tap for a round spot, tap a spot to pick it. The picked spot moves when
 * dragged, and so does its source; tap it again (or hold any spot) for its menu, as the reference's.
 */
@Composable
fun RemoveOverlay(vm: AppViewModel, ed: EditorState, geometry: ViewGeometry, imageSize: IntSize, modifier: Modifier) {
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // the picked spot (`spot`) or its source (`source`) on the move, and how far (screen pixels)
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragBy by remember { mutableStateOf(Offset.Zero) }
    val spots = ed.spots
    val picked by rememberUpdatedState(ed.selectedSpot)
    Canvas(
        modifier.pointerInput(geometry, imageSize, spots) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val map = ViewMap(geometry, fittedRect(size, imageSize))
                fun near(s: SpotInfo, at: Offset, by: List<Double> = listOf(0.0, 0.0)) =
                    spotPoints(s).any { (x, y) -> (map.screen(x + by[0], y + by[1]) - at).getDistance() <= max(map.radius(s.size), 24f) }
                val hit = spots.indexOfFirst { near(it, down.position) }
                val sel = picked?.let { i -> spots.getOrNull(i)?.let { i to it } }
                // the finger on the picked spot's source, or on the picked spot itself
                val grab = when {
                    sel == null -> null
                    sel.second.source?.takeIf { it.size == 2 }?.let { near(sel.second, down.position, it) } == true && hit != sel.first -> "source"
                    hit == sel.first -> "spot"
                    else -> null
                }
                // still, moving, or held (a long press)
                var last = down.position
                val start = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: return@withTimeoutOrNull "tap"
                        if (!c.pressed) return@withTimeoutOrNull "tap"
                        // a second finger: the photo moves, nothing here
                        if (ev.changes.count { it.pressed } > 1 || c.isConsumed) return@withTimeoutOrNull "photo"
                        last = c.position
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            c.consume()
                            return@withTimeoutOrNull "move"
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    "tap"
                }
                when (start) {
                    "photo" -> {}
                    null -> {
                        // held on a spot: it's picked and its menu opens; elsewhere nothing
                        if (hit >= 0) {
                            vm.selectSpot(hit)
                            ed.spotMenu = true
                        }
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                    }
                    "tap" -> when {
                        // the picked spot again: its menu
                        hit >= 0 && hit == sel?.first -> ed.spotMenu = true
                        hit >= 0 -> vm.selectSpot(hit)
                        else -> vm.addSpot(listOf(map.photo(down.position)))
                    }
                    else -> if (grab != null) {
                        // the picked spot or its source follows the finger; let go, it stays there (one step)
                        dragging = grab
                        dragBy = last - down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            if (ev.changes.count { it.pressed } > 1 || c.isConsumed) break
                            last = c.position
                            dragBy = last - down.position
                            c.consume()
                        }
                        val (x0, y0) = map.photo(down.position)
                        val (x1, y1) = map.photo(last)
                        if (grab == "spot") vm.moveSpot(x1 - x0, y1 - y0) else vm.moveSpotSource(x1 - x0, y1 - y0)
                        dragging = null
                        dragBy = Offset.Zero
                    } else {
                        val path = mutableListOf(down.position, last)
                        stroke = path.toList()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            // a second finger: the photo moves instead, and the stroke is dropped
                            if (ev.changes.count { it.pressed } > 1 || c.isConsumed) {
                                stroke = emptyList()
                                return@awaitEachGesture
                            }
                            if ((c.position - path.last()).getDistance() > 4f) {
                                path += c.position
                                stroke = path.toList()
                            }
                            c.consume()
                        }
                        stroke = emptyList()
                        vm.addSpot(path.map { map.photo(it) })
                    }
                }
            }
        },
    ) {
        val map = ViewMap(geometry, fittedRect(IntSize(size.width.toInt(), size.height.toInt()), imageSize))
        spots.forEachIndexed { i, s ->
            val selected = i == ed.selectedSpot
            val color = if (selected) Color.White else Color.White.copy(alpha = 0.55f)
            // the picked spot drawn where the finger has it (its source moves along)
            val shift = if (selected && dragging == "spot") dragBy else Offset.Zero
            val pts = spotPoints(s).map { (x, y) -> map.screen(x, y) + shift }
            val r = map.radius(s.size)
            drawSpot(pts, r, color, selected)
            // a picked spot shows where it takes its pixels from
            val src = s.source
            if (selected && src != null && src.size == 2) {
                val srcShift = if (dragging == "source") dragBy else shift
                val from = spotPoints(s).map { (x, y) -> map.screen(x + src[0], y + src[1]) + srcShift }
                drawSpot(from, r, Color.White.copy(alpha = 0.4f), false)
                if (pts.isNotEmpty() && from.isNotEmpty()) drawLine(Color.White.copy(alpha = 0.6f), from.first(), pts.first(), 1.dp.toPx())
            }
        }
        if (stroke.isNotEmpty()) {
            val r = map.radius(ed.brushSize.toDouble())
            val path = Path().apply {
                moveTo(stroke[0].x, stroke[0].y)
                stroke.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(width = 2 * r, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (stroke.size == 1) drawCircle(Color.White.copy(alpha = 0.35f), r, stroke[0])
        }
    }
}

private fun spotPoints(s: SpotInfo): List<Pair<Double, Double>> = s.points.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }

/** A spot's outline: a circle, or the brushed path's outline as a wide stroke's edges. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpot(pts: List<Offset>, r: Float, color: Color, selected: Boolean) {
    if (pts.isEmpty()) return
    val w = if (selected) 2.dp.toPx() else 1.5.dp.toPx()
    if (pts.size == 1) {
        drawCircle(color, r, pts[0], style = Stroke(w))
        return
    }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
    }
    // the brushed area, faint, with its centre line
    drawPath(path, color.copy(alpha = color.alpha * 0.25f), style = Stroke(width = 2 * r, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A synthetic slider of the brush (and the picked spot). */
private fun brushControl(id: String, label: String, min: Double, max: Double, default: Double, value: Double) =
    DevelopControl(id, label, "", "", min, max, default, 1.0, 0u, value, SliderTrack.Plain)

/** The spot menu's sheet colour (the reference's). */
private val spotMenuColor = Color(0xFF232323)

/**
 * The picked spot's menu, as the reference's: remove, heal or clone (the one it is ticked), and for
 * a heal or a clone, a new source (the engine's next-best place to take its pixels from).
 */
@Composable
fun SpotMenuSheet(vm: AppViewModel, ed: EditorState, onDismiss: () -> Unit) {
    val spot = ed.selectedSpot?.let { ed.spots.getOrNull(it) }
    LcSheet(onDismiss, background = spotMenuColor) {
        Spacer(Modifier.height(8.dp))
        removeModes.forEach { (id, label, icon) ->
            SpotMenuRow(icon, label, checked = spot?.mode == id) {
                onDismiss()
                vm.setRemoveMode(id)
            }
        }
        if (spot != null && spot.mode != "remove") {
            SpotMenuRow(MdIcon.RotateRight, "New source") {
                onDismiss()
                vm.newSpotSource()
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A row of the spot menu (the reference's: 48 dp, a 22 dp icon at 16 dp, the label 32 dp after it, a tick at the end). */
@Composable
private fun SpotMenuRow(icon: Glyph, label: String, checked: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(icon, null, tint = spotMenuText, size = 22.dp)
        Text(label, color = spotMenuText, fontFamily = Inter, fontSize = 16.sp, modifier = Modifier.padding(start = 32.dp).weight(1f))
        if (checked) MdIcon(MdIcon.Check, "Selected", tint = spotMenuText)
    }
}

private val spotMenuText = Color(0xFFEBEBEB)

/** How Remove takes things away: its name and icon. */
private val removeModes = listOf<Triple<String, String, Glyph>>(Triple("remove", "Remove", LcIcon.Heal), Triple("heal", "Heal", MdIcon.Healing), Triple("clone", "Clone", LcIcon.Clone))

/**
 * Remove's panel, as the reference's: the way it takes things away (a 40 dp blue button, its name
 * under it; tap it for the others) beside the brush's size; under them, at the right, Refine, which opens the picked spot's (or the brush's) feather and opacity and its
 * delete in the panel's place. Each spot is a step of its own (no cancel).
 */
@Composable
fun RemoveSheet(vm: AppViewModel, ed: EditorState, modifier: Modifier) {
    val selected = ed.selectedSpot?.let { ed.spots.getOrNull(it) }
    // the brush's settings: a picked spot's while one is picked (changing them changes it)
    val size = ed.brushSize.toDouble() * 1000
    val feather = ed.brushFeather.toDouble()
    val opacity = ed.brushOpacity.toDouble()
    val mode = selected?.mode ?: ed.removeMode
    var refine by remember { mutableStateOf(false) }
    Column(modifier) {
        if (refine) {
            LazyColumn(Modifier.sheetList(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "head") {
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clickable(role = Role.Button) { refine = false }.semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
                            LcIcon(LcIcon.Back, null, tint = Lc.panelText, size = 22.dp, weight = 1.2f)
                        }
                        Text("Refine", color = Lc.panelText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (selected != null) PanelButton("Delete", LcIcon.Trash) { vm.deleteSpot() }
                    }
                }
                item(key = "size") {
                    val c = brushControl("brush.size", "Size", 2.0, 100.0, 25.0, size)
                    DevelopSlider(c, onChange = { vm.setBrush(size = (it / 1000).toFloat()) }, onFinished = { vm.brushDone() }, onReset = { vm.setBrush(size = 0.025f); vm.brushDone() })
                }
                item(key = "feather") {
                    val c = brushControl("brush.feather", "Feather", 0.0, 100.0, 50.0, feather)
                    DevelopSlider(c, onChange = { vm.setBrush(feather = it.toFloat()) }, onFinished = { vm.brushDone() }, onReset = { vm.setBrush(feather = 50f); vm.brushDone() })
                }
                item(key = "opacity") {
                    val c = brushControl("brush.opacity", "Opacity", 1.0, 100.0, 100.0, opacity)
                    DevelopSlider(c, onChange = { vm.setBrush(opacity = it.toFloat()) }, onFinished = { vm.brushDone() }, onReset = { vm.setBrush(opacity = 100f); vm.brushDone() })
                }
            }
            return@Column
        }
        // sideways, as the reference's: the way a pill with its name in it, the size under it
        val sideways = LocalSheetLimit.current == null
        LazyColumn(Modifier.sheetList(), contentPadding = PaddingValues(top = 20.dp, bottom = 8.dp)) {
            item(key = "mode-size") {
                val c = brushControl("brush.size", "Size", 2.0, 100.0, 25.0, size)
                val slider = @Composable { m: Modifier ->
                    DevelopSlider(
                        c,
                        onChange = { vm.setBrush(size = (it / 1000).toFloat()) },
                        onFinished = { vm.brushDone() },
                        onReset = { vm.setBrush(size = 0.025f); vm.brushDone() },
                        modifier = m,
                    )
                }
                if (sideways) {
                    Column(Modifier.fillMaxWidth()) {
                        RemoveModeButton(mode, pill = true, modifier = Modifier.padding(start = 20.dp)) { vm.setRemoveMode(it) }
                        slider(Modifier.padding(top = 12.dp))
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp)) {
                        RemoveModeButton(mode) { vm.setRemoveMode(it) }
                        slider(Modifier.weight(1f).padding(start = 4.dp))
                    }
                }
            }
            item(key = "buttons") {
                // the reference puts Refine on its sheet's last row, 140 dp down (its 184 dp sheet)
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 68.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f))
                    // as the reference's: faint until there is a spot to refine
                    PanelButton("Refine", LcIcon.Pencil, enabled = ed.spots.isNotEmpty()) { refine = true }
                }
            }
        }
    }
}

/**
 * The way Remove works, as the reference's: a 40 dp blue button with a corner mark (it has a list),
 * its name under it; sideways ([pill]) its name beside its icon in the button. A tap lists the ways
 * over it: the one in use filled blue, the others grey.
 */
@Composable
private fun RemoveModeButton(mode: String, pill: Boolean = false, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (_, label, icon) = removeModes.firstOrNull { it.first == mode } ?: removeModes.first()
    Column(modifier.then(if (pill) Modifier else Modifier.width(40.dp)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier
                    .then(if (pill) Modifier.height(40.dp).widthIn(min = 111.dp) else Modifier.size(40.dp))
                    .clip(RoundedCornerShape(5.dp))
                    .background(Lc.primaryBlue)
                    .clickable(role = Role.DropdownList) { open = true }
                    .semantics { contentDescription = "$label: change how spots are removed" },
                contentAlignment = Alignment.Center,
            ) {
                if (pill) {
                    // sideways: its name beside its icon, in the button
                    Row(Modifier.padding(start = 9.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(icon, null, tint = Color.White, size = 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = Color.White, fontFamily = Inter, fontSize = 16.sp, maxLines = 1)
                    }
                } else {
                    GlyphIcon(icon, null, tint = Color.White, size = 24.dp)
                }
                // the mark that says it lists more: a small white corner
                Canvas(Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 5.dp).size(8.dp, 6.dp)) {
                    drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }, Color.White)
                }
            }
            if (open) {
                val at = with(LocalDensity.current) { IntOffset((-4).dp.roundToPx(), 28.dp.roundToPx()) }
                Popup(offset = at, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                    // a hairline edge under the rows: the blue one covers it, as the reference's
                    Column(Modifier.width(126.dp).background(modeMenu).drawBehind { drawRect(modeMenuEdge, Offset(0.5f, 0.5f), Size(size.width - 1f, size.height - 1f), style = Stroke(1f)) }) {
                        removeModes.forEach { (id, name, ic) ->
                            val on = id == mode
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(if (on) Lc.primaryBlue else Color.Transparent)
                                    .selectable(on, role = Role.RadioButton) {
                                        open = false
                                        onPick(id)
                                    }
                                    .padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GlyphIcon(ic, null, tint = if (on) Color.White else modeMenuText, size = 18.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(name, color = if (on) Color.White else modeMenuText, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        if (!pill) Text(label, color = Lc.panelText, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 9.dp).wrapContentWidth(unbounded = true))
    }
}

/** The ways' list: its grey and edge, the ways not in use. */
private val modeMenu = Color(0xFF232323)
private val modeMenuEdge = Color(0xFF494949)
private val modeMenuText = Color(0xFFA2A2A2)
