package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.PhotoFlag
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import org.json.JSONObject

/** A photo's capture and descriptive metadata, as Info and rating shows it (`photo.inspect`). */
data class PhotoMeta(
    val camera: String,
    val lens: String,
    val focalMm: Double?,
    val aperture: Double?,
    val shutter: String,
    val iso: Int?,
    val fileSize: Long,
    val title: String,
    val caption: String,
    val copyright: String,
    val keywords: List<String>,
)

/** `photo.inspect`'s result read as [PhotoMeta]; null when it can't be read. */
fun parsePhotoMeta(json: String): PhotoMeta? = runCatching {
    val o = JSONObject(json)
    val m = o.optJSONObject("meta") ?: JSONObject()
    fun num(k: String) = if (!m.has(k) || m.isNull(k)) null else m.optDouble(k).takeIf { !it.isNaN() }
    PhotoMeta(
        camera = m.optString("camera"),
        lens = m.optString("lens"),
        focalMm = num("focal_mm"),
        aperture = num("aperture"),
        shutter = m.optString("shutter"),
        iso = num("iso")?.toInt(),
        fileSize = o.optLong("file_size"),
        title = m.optString("title"),
        caption = m.optString("caption"),
        copyright = m.optString("copyright"),
        keywords = m.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() },
    )
}.getOrNull()

/** A photo's pixels as the reference writes them: `24.2 MP`, `12 MP` (whole numbers without a decimal). */
internal fun megapixels(width: Int, height: Int): String {
    val mp = width.toLong() * height / 1_000_000.0
    val s = String.format(Locale.US, "%.1f", mp)
    return (if (s.endsWith(".0")) s.dropLast(2) else s) + " MP"
}

/** A shutter speed with its unit, as the desktop writes it: `1/250 s`, `8 s` (kept as it is when it has one). */
internal fun shutterText(s: String): String = if (s.trimEnd().endsWith("s")) s else "$s s"

/** A file's size: `860 KB`, `2.4 MB`, `1.1 GB`. */
internal fun fileSizeText(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1_000_000 -> "${(bytes + 500) / 1000} KB"
    bytes < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
}

/** Keywords typed as the reference takes them, comma separated, added to [current] (trimmed, no repeats). */
internal fun withKeywords(current: List<String>, typed: String): List<String> =
    (current + typed.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinctBy { it.lowercase(Locale.ROOT) }

/** A capture time (ISO 8601) as its day and its time, each as the reference writes it (`Jan 1, 2027`, `12:00:00 PM`). */
internal fun takenDayAndTime(iso: String): Pair<String, String>? = takenAt(iso)?.split(" | ")?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

private val infoText = Color(0xFFC8C8C8)
private val infoTextSoft = Color(0xFFA2A2A2)
private val fieldFill = Color(0xFF080808)
private val fieldEdge = Color(0xFF393939)
private val chipFill = Color(0xFF0E0E0E)
private val chipEdge = Color(0xFF6A6A6A)

/**
 * Info and rating (the editor's More menu), as the reference's: the photo under a bar with Back and
 * the screen's name, a sheet at its foot with a grip, the stars (38 dp each) and Reject and Pick at
 * the right, then INFO (camera and lens, day and time, file and size, exposure) and METADATA (title,
 * caption, copyright and keywords, saved as they're left) as tabs.
 */
@Composable
fun InfoPage(vm: AppViewModel, ed: EditorState, onDismiss: () -> Unit) {
    LaunchedEffect(ed.photoId) { vm.loadPhotoMeta() }
    var tab by rememberSaveable { mutableStateOf("info") }
    LcOverlay(onDismiss, Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().background(Lc.canvas).windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(MdIcon.ArrowBack, "Back", size = 48.dp, tint = Lc.barIcon, onClick = onDismiss)
                Text("Info and rating", color = Lc.barIcon, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp))
            }
            // speed review, as the reference's: sideways for the next or previous photo; up or down on
            // the left half for a star more or less, on the right half to flag it (up toward pick, down
            // toward reject, a step at a time); what it now is shows a moment over the photo
            var hint by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(hint) {
                if (hint != null) {
                    delay(900)
                    hint = null
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(ed.photoId) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var last = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            last = c.position
                            c.consume()
                        }
                        val d = last - down.position
                        val far = 48.dp.toPx()
                        when {
                            abs(d.x) > abs(d.y) && abs(d.x) > far -> vm.infoStep(if (d.x < 0) 1 else -1)
                            abs(d.y) > abs(d.x) && abs(d.y) > far -> {
                                val up = d.y < 0
                                if (down.position.x < size.width / 2) {
                                    val stars = (ed.info.rating.toInt() + if (up) 1 else -1).coerceIn(0, 5)
                                    vm.setRating(stars)
                                    hint = when (stars) {
                                        0 -> "No stars"
                                        1 -> "1 star"
                                        else -> "$stars stars"
                                    }
                                } else {
                                    val order = listOf(PhotoFlag.REJECT, PhotoFlag.UNFLAGGED, PhotoFlag.PICK)
                                    val at = order.indexOf(ed.info.flag).coerceAtLeast(0)
                                    val flag = order[(at + if (up) 1 else -1).coerceIn(0, 2)]
                                    vm.setFlag(flag)
                                    hint = when (flag) {
                                        PhotoFlag.PICK -> "Pick"
                                        PhotoFlag.REJECT -> "Reject"
                                        PhotoFlag.UNFLAGGED -> "Unflagged"
                                    }
                                }
                            }
                        }
                    }
                }.semantics { contentDescription = "${ed.info.fileName}: swipe sideways for the next photo, up or down on the left to rate, on the right to flag" },
                contentAlignment = Alignment.Center,
            ) {
                ed.image?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                hint?.let {
                    Text(
                        it,
                        color = Color.White,
                        fontFamily = Inter,
                        fontSize = 22.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).background(Lc.panel)) {
                // the grip
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF5C5C5C)))
                }
                RatingRow(vm, ed)
                Tabs(listOf("info" to "INFO", "metadata" to "METADATA"), tab, textSize = 14f) { tab = it }
                val meta = vm.photoMeta
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (tab == "info") InfoTab(ed, meta) else if (meta != null) MetadataTab(vm, meta)
                }
            }
        }
    }
}

/** Five stars, then Reject and Pick at the right; a tap on the photo's own rating or flag takes it away. */
@Composable
private fun RatingRow(vm: AppViewModel, ed: EditorState) {
    val rating = ed.info.rating.toInt()
    val flag = ed.info.flag
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        for (stars in 1..5) {
            Box(Modifier.size(38.dp).clickable(role = Role.Button) { vm.setRating(if (rating == stars) 0 else stars) }, contentAlignment = Alignment.Center) {
                LcIcon(
                    if (stars <= rating) LcIcon.StarFilled else LcIcon.Star,
                    if (stars == 1) "1 star" else "$stars stars",
                    tint = if (stars <= rating) Lc.star else infoTextSoft,
                    size = 22.dp,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        FlagButton(flag == PhotoFlag.REJECT, LcIcon.FlagRejectSolid, LcIcon.FlagReject, "Reject") {
            vm.setFlag(if (flag == PhotoFlag.REJECT) PhotoFlag.UNFLAGGED else PhotoFlag.REJECT)
        }
        FlagButton(flag == PhotoFlag.PICK, LcIcon.FlagPickSolid, LcIcon.FlagPick, "Pick", Modifier.padding(end = 3.dp)) {
            vm.setFlag(if (flag == PhotoFlag.PICK) PhotoFlag.UNFLAGGED else PhotoFlag.PICK)
        }
    }
}

@Composable
private fun FlagButton(on: Boolean, solid: Glyph, outline: Glyph, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(38.dp).clickable(role = Role.Checkbox, onClick = onClick).semantics { contentDescription = if (on) "$label (on)" else label },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(if (on) solid else outline, null, tint = if (on) Color.White else infoTextSoft, size = 22.dp)
    }
}

/** A line of Info: an icon, then its text lines (10 dp after it), 8 dp above and below. */
@Composable
private fun InfoLine(icon: Glyph, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        GlyphIcon(icon, null, tint = infoTextSoft, size = 22.dp)
        Column(Modifier.padding(start = 10.dp).weight(1f), content = content)
    }
}

@Composable
private fun InfoText(text: String, soft: Boolean = false, size: Int = 15) =
    Text(text, color = if (soft) infoTextSoft else infoText, fontFamily = Inter, fontSize = size.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoTab(ed: EditorState, meta: PhotoMeta?) {
    val info = ed.info
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val camera = meta?.camera?.ifBlank { null } ?: info.camera.ifBlank { null }
        val lens = listOfNotNull(
            (meta?.lens ?: info.lens).ifBlank { null },
            meta?.focalMm?.let { String.format(Locale.US, "%.0f mm", it) },
        ).joinToString(" · ")
        if (camera != null || lens.isNotEmpty()) {
            InfoLine(MdIcon.PhotoCamera) {
                camera?.let { InfoText(it) }
                if (lens.isNotEmpty()) InfoText(lens, soft = true)
            }
        }
        val taken = info.captured?.let(::takenDayAndTime)
        if (taken != null) {
            InfoLine(LcIcon.Calendar) {
                InfoText(taken.first)
                InfoText(taken.second, soft = true)
            }
        }
        InfoLine(LcIcon.Photos) {
            InfoText(info.fileName.replace(Regex("([-_.])"), "$1​"))
            FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                val facts = listOfNotNull(
                    megapixels(info.width.toInt(), info.height.toInt()),
                    "${info.width} × ${info.height}",
                    meta?.fileSize?.let(::fileSizeText)?.ifEmpty { null },
                )
                facts.forEachIndexed { i, f ->
                    if (i > 0) Text("•", color = infoTextSoft, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    InfoText(f, soft = true)
                }
                // the file's type on a light chip
                Text(
                    if (info.raw) info.format else info.format.uppercase(Locale.ROOT),
                    color = Color.Black,
                    fontFamily = Inter,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp).background(infoText, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp),
                )
            }
        }
        val exposure = listOfNotNull(
            meta?.shutter?.ifBlank { null }?.let { "Shutter" to shutterText(it) },
            meta?.aperture?.let { "Aperture" to String.format(Locale.US, "f/%s", trimNumber(it)) },
            meta?.iso?.let { "ISO" to "$it" },
        )
        if (exposure.isNotEmpty()) {
            InfoLine(MdIcon.Info) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    exposure.forEach { (name, value) ->
                        Row {
                            InfoText(name, soft = true)
                            Spacer(Modifier.width(6.dp))
                            InfoText(value)
                        }
                    }
                }
            }
        }
    }
}

private fun trimNumber(v: Double): String = String.format(Locale.US, "%.1f", v).removeSuffix(".0")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataTab(vm: AppViewModel, meta: PhotoMeta) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        MetaField("Title", meta.title) { vm.setPhotoMeta("title", it) }
        MetaField("Caption", meta.caption, note = "Will be exported as alt-text") { vm.setPhotoMeta("caption", it) }
        MetaField("Copyright", meta.copyright) { vm.setPhotoMeta("copyright", it) }
        MetaField("Keywords (comma separated)", "", keepsText = false) { typed ->
            val next = withKeywords(meta.keywords, typed)
            if (next != meta.keywords) vm.setPhotoKeywords(next)
        }
        if (meta.keywords.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 12.dp)) {
                meta.keywords.forEach { k ->
                    Row(
                        Modifier
                            .padding(end = 8.dp, bottom = 8.dp)
                            .height(30.dp)
                            .background(chipFill, RoundedCornerShape(5.dp))
                            .border(1.dp, chipEdge, RoundedCornerShape(5.dp))
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(k, color = infoTextSoft, fontFamily = Inter, fontSize = 15.sp)
                        Box(
                            Modifier.padding(start = 5.dp).size(20.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                                vm.setPhotoKeywords(meta.keywords - k)
                            }.semantics { contentDescription = "Remove $k" },
                            contentAlignment = Alignment.Center,
                        ) {
                            MdIcon(MdIcon.Close, null, tint = infoTextSoft, size = 14.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A metadata field, as the reference's: its name (15 sp), then a 40 dp box (near black, a 2 dp grey
 * edge, 4 dp corners) of 17 sp text. Saved when Done is pressed, it loses focus or the page closes;
 * [keepsText] false: emptied once saved (keywords).
 */
@Composable
private fun MetaField(label: String, value: String, note: String? = null, keepsText: Boolean = true, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val focus = LocalFocusManager.current
    val latest by rememberUpdatedState(text)
    val save by rememberUpdatedState(onSave)
    fun commit() {
        if (latest != value) {
            save(latest)
            if (!keepsText) text = ""
        }
    }
    DisposableEffect(Unit) { onDispose { if (latest != value) save(latest) } }
    Text(label, color = infoTextSoft, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    BasicTextField(
        text,
        { text = it },
        singleLine = true,
        textStyle = TextStyle(color = infoText, fontFamily = Inter, fontSize = 17.sp),
        cursorBrush = SolidColor(Lc.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focus.clearFocus()
        }),
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .onFocusChanged { if (!it.isFocused) commit() }
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().background(fieldFill, RoundedCornerShape(4.dp)).border(2.dp, fieldEdge, RoundedCornerShape(4.dp)).padding(start = 15.dp, end = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
    note?.let { Text(it, color = infoTextSoft, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
}

/** Organize (the editor's More menu), as the reference's: "Add to", then the photo's possible albums. */
@Composable
fun OrganizeSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    // as the reference's: add to an album; from an album, move there instead, or make the photo its cover
    var page by remember { mutableStateOf<String?>(null) }
    val inAlbum = vm.view.kind == "album" && vm.editor?.let { ed -> ed.device == null && vm.photos.any { it.id == ed.photoId } } == true
    LcSheet(onDismiss) {
        when (page) {
            null -> {
                SheetHeading("Organize")
                SheetDivider()
                SheetRow(LcIcon.Photos, "Add to album", chevron = true) { page = "add" }
                if (inAlbum) {
                    SheetRow(LcIcon.PhotoStack, "Move to album", chevron = true) { page = "move" }
                    SheetDivider()
                    SheetRow(LcIcon.PhotoStack, "Set album cover") {
                        onDismiss()
                        vm.useAsAlbumCover()
                    }
                }
            }
            else -> {
                SheetHeading(if (page == "move") "Move to" else "Add to")
                SheetDivider()
                Text("Your albums", color = Lc.textDim, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                val here = vm.view.albumId.takeIf { vm.view.kind == "album" }
                val albums = vm.overview?.albums.orEmpty().filter { !it.smart && !it.folder && (page != "move" || it.id != here) }
                if (albums.isEmpty()) {
                    Text("No albums yet", color = Lc.textDim, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
                }
                albums.forEach { a ->
                    SheetRow(LcIcon.Photos, a.name, detail = "${a.count}") {
                        onDismiss()
                        if (page == "move") vm.moveOpenPhotoToAlbum(a) else vm.addOpenPhotoToAlbum(a)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Select mode's Edit Info, as the reference's: a bar with Cancel, "Edit Info" and Apply, then Title,
 * Caption and Copyright (each with a clear button; left untouched, each photo keeps its own), the
 * stars and the flags (each with a clear button). Apply sets what was changed on every selected photo.
 */
@Composable
fun BatchInfoPage(vm: AppViewModel, onDismiss: () -> Unit) {
    // null: unchanged; "" after a clear: emptied
    var title by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf<String?>(null) }
    var copyright by remember { mutableStateOf<String?>(null) }
    var rating by remember { mutableStateOf<Int?>(null) }
    var flag by remember { mutableStateOf<String?>(null) }
    LcOverlay(onDismiss, Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().background(Lc.canvas).windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(MdIcon.Close, "Cancel", size = 56.dp, tint = Color.White, onClick = onDismiss)
                Text("Edit Info", color = Color.White, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.padding(start = 3.dp).weight(1f))
                BarButton(MdIcon.Check, "Apply", size = 56.dp, tint = Color.White) {
                    onDismiss()
                    vm.editSelectedInfo(
                        listOfNotNull(title?.let { "title" to it }, caption?.let { "caption" to it }, copyright?.let { "copyright" to it }).toMap(),
                        rating,
                        flag,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                BatchField("Title", "No Title", title) { title = it }
                BatchField("Caption", "No Caption", caption) { caption = it }
                BatchField("Copyright", "No Copyright", copyright) { copyright = it }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (stars in 1..5) {
                        val on = (rating ?: 0) >= stars
                        Box(Modifier.size(38.dp).clickable(role = Role.Button) { rating = stars }, contentAlignment = Alignment.Center) {
                            LcIcon(if (on) LcIcon.StarFilled else LcIcon.Star, if (stars == 1) "1 star" else "$stars stars", tint = if (on) Lc.star else infoTextSoft, size = 22.dp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    BarButton(MdIcon.Close, "Clear the rating", size = 38.dp, iconSize = 18.dp, tint = infoTextSoft) { rating = 0 }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlagButton(flag == "pick", LcIcon.FlagPickSolid, LcIcon.FlagPick, "Pick") { flag = "pick" }
                    FlagButton(flag == "reject", LcIcon.FlagRejectSolid, LcIcon.FlagReject, "Reject") { flag = "reject" }
                    Spacer(Modifier.weight(1f))
                    BarButton(MdIcon.Close, "Clear the flag", size = 38.dp, iconSize = 18.dp, tint = infoTextSoft) { flag = "none" }
                }
            }
        }
    }
}

/** One of Edit Info's text fields: its name, then the text (its hint while unset) with a clear button. */
@Composable
private fun BatchField(label: String, hint: String, value: String?, onChange: (String) -> Unit) {
    Text(label, color = infoTextSoft, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(fieldFill, RoundedCornerShape(4.dp)).border(2.dp, fieldEdge, RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value.orEmpty(),
            onChange,
            singleLine = true,
            textStyle = TextStyle(color = infoText, fontFamily = Inter, fontSize = 17.sp),
            cursorBrush = SolidColor(Lc.primary),
            modifier = Modifier.weight(1f).padding(start = 15.dp).semantics { contentDescription = label },
            decorationBox = { inner ->
                Box {
                    if (value.isNullOrEmpty()) Text(hint, color = infoTextSoft, fontFamily = Inter, fontSize = 17.sp)
                    inner()
                }
            },
        )
        BarButton(MdIcon.Close, "Clear $label", size = 36.dp, iconSize = 16.dp, tint = infoTextSoft) { onChange("") }
    }
}
