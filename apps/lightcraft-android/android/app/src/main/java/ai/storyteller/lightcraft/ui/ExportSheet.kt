package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.ImageSize
import ai.storyteller.lightcraft.core.SliderTrack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

/** A watermark as the export page's Customize sets it (the reference's options and defaults). */
data class WatermarkChoice(
    /** A graphic (the image at [image]) instead of [text]. */
    val graphic: Boolean = false,
    val text: String = "",
    /** A copy of the chosen graphic in the app's files (empty: none chosen). */
    val image: String = "",
    /** White, else black. */
    val white: Boolean = true,
    val shadow: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 1–100: the text's height (half a percent of the short edge each) or the graphic's width (a percent of the photo's). */
    val size: Int = 10,
    /** 0–100. */
    val opacity: Int = 100,
    /** 0–100: the margins from the side and from the top or bottom, percents of the photo's width and height. */
    val offsetX: Int = 3,
    val offsetY: Int = 3,
    /** `topLeft` … `bottomRight` (one of nine places). */
    val anchor: String = "bottomLeft",
    /** 0, 90, 180 or 270 (degrees, clockwise). */
    val rotation: Int = 0,
) {
    /** Whether there is anything to draw. */
    val usable: Boolean get() = if (graphic) image.isNotBlank() else text.isNotBlank()

    fun json(): JSONObject = JSONObject()
        .put("text", if (graphic) "" else text)
        .put("image", if (graphic) image else "")
        .put("imageWidth", size.coerceIn(1, 100) / 100.0)
        .put("size", (size.coerceIn(1, 100) / 200.0).coerceAtLeast(0.005))
        .put("opacity", opacity.coerceIn(0, 100) / 100.0)
        .put("offsetX", offsetX.coerceIn(0, 100) / 100.0)
        .put("offsetY", offsetY.coerceIn(0, 100) / 100.0)
        .put("anchor", anchor)
        .put("color", org.json.JSONArray(if (white) listOf(255, 255, 255) else listOf(0, 0, 0)))
        .put("shadow", shadow)
        .put("bold", bold)
        .put("italic", italic)
        .put("rotation", rotation)

    companion object {
        fun from(o: JSONObject?): WatermarkChoice {
            val d = WatermarkChoice()
            o ?: return d
            return WatermarkChoice(
                graphic = o.optBoolean("graphic", d.graphic),
                text = o.optString("text", d.text),
                image = o.optString("image", d.image),
                white = o.optBoolean("white", d.white),
                shadow = o.optBoolean("shadow", d.shadow),
                bold = o.optBoolean("bold", d.bold),
                italic = o.optBoolean("italic", d.italic),
                size = o.optInt("size", d.size).coerceIn(1, 100),
                opacity = o.optInt("opacity", d.opacity).coerceIn(0, 100),
                offsetX = o.optInt("offsetX", d.offsetX).coerceIn(0, 100),
                offsetY = o.optInt("offsetY", d.offsetY).coerceIn(0, 100),
                anchor = o.optString("anchor", d.anchor).takeIf { a -> watermarkAnchors.contains(a) } ?: d.anchor,
                rotation = o.optInt("rotation", d.rotation).takeIf { it in listOf(0, 90, 180, 270) } ?: d.rotation,
            )
        }
    }

    fun prefs(): JSONObject = JSONObject().put("graphic", graphic).put("text", text).put("image", image).put("white", white).put("shadow", shadow)
        .put("bold", bold).put("italic", italic).put("size", size).put("opacity", opacity).put("offsetX", offsetX).put("offsetY", offsetY)
        .put("anchor", anchor).put("rotation", rotation)
}

/** The nine watermark places, row by row. */
val watermarkAnchors = listOf("topLeft", "top", "topRight", "left", "center", "right", "bottomLeft", "bottom", "bottomRight")

/** What a saved or shared copy is (the reference's Export as page; the desktop's export options); kept between launches. */
data class ExportChoice(
    /** `jpeg`, `tiff`, `avif`, `dng` (a raw's data, raws only) or `original`, as `app.export` names them. */
    val format: String = "jpeg",
    /** `small` (2048 px), `full` or `custom` ([customEdge] on the long side). */
    val dimensions: String = "full",
    val customEdge: Int = 3000,
    /** 1–100 (JPEG, AVIF). */
    val quality: Int = 90,
    /** Metadata at all (off: copyright only), with the camera's and the location's. */
    val includeMetadata: Boolean = true,
    val cameraInfo: Boolean = true,
    val locationInfo: Boolean = true,
    /** TIFF: 8 or 16 bits and `none`, `lzw` or `zip`. */
    val bitDepth: Int = 16,
    val tiffCompression: String = "zip",
    val watermark: Boolean = false,
    val wm: WatermarkChoice = WatermarkChoice(),
    /** A border around the photo (the reference's): its shape ([exportBorderRatios]), its thickness (0–25 %) and colour (`0xRRGGBB`). */
    val border: Boolean = false,
    val borderRatio: String = "original",
    val borderThickness: Int = 10,
    val borderColor: Int = 0xFFFFFF,
    /** `original` (the file's name), `dateName` (the date, then the name), `custom` ([customName] and a number from [startNumber]) or `date`. */
    val naming: String = "original",
    val customName: String = "LightCraft",
    val startNumber: Int = 1,
    /** `none`, `screen`, `glossy` or `matte`, and `low`, `standard` or `high`. */
    val sharpen: String = "none",
    val sharpenAmount: String = "standard",
    /** `srgb`, `displayP3`, `adobeRgb` or `proPhoto`. */
    val colorSpace: String = "srgb",
) {
    /** The long edge in pixels; 0: full size. */
    val longEdge: Int get() = when (dimensions) {
        "small" -> 2048
        "custom" -> customEdge.coerceIn(1, 65_000)
        else -> 0
    }

    /** Has a quality setting. */
    val lossy: Boolean get() = format == "jpeg" || format == "avif"

    /** Renders the photo (the original and a raw's data are copied as they are). */
    val rendered: Boolean get() = format != "original" && format != "dng"

    /** The name template (`app.export`'s tokens): braces and path characters left out of the custom text. */
    val template: String get() {
        val name = customName.replace(Regex("[{}\\\\/:*?\"<>|]"), "").trim().ifEmpty { "LightCraft" }
        return when (naming) {
            "dateName" -> "{date}-{name}"
            "custom" -> "$name-{seq:1}"
            "date" -> "{date}"
            else -> "{name}"
        }
    }

    /** The options as the engine's export takes them. */
    fun json(): String {
        val o = JSONObject()
            .put("format", format)
            .put("quality", quality)
            .put("metadata", if (!includeMetadata) "copyright" else if (cameraInfo) "all" else "allExceptCamera")
            .put("removeLocation", !includeMetadata || !locationInfo)
            .put("naming", template)
            .put("startNumber", startNumber)
            .put("sharpen", sharpen)
            .put("sharpenAmount", sharpenAmount)
            .put("colorSpace", colorSpace)
        if (rendered && longEdge > 0) o.put("longEdge", longEdge)
        if (format == "tiff") o.put("bitDepth", bitDepth).put("tiffCompression", tiffCompression)
        if (rendered && watermark && wm.usable) o.put("watermark", wm.json())
        if (rendered && border) {
            val rgb = org.json.JSONArray(listOf((borderColor shr 16) and 0xFF, (borderColor shr 8) and 0xFF, borderColor and 0xFF))
            o.put("border", JSONObject().put("thickness", borderThickness.coerceIn(0, 25)).put("ratio", borderRatio).put("color", rgb))
        }
        return o.toString()
    }

    /** These settings with an export preset's `app.export` params over them (what it doesn't name stays). */
    fun applying(p: JSONObject): ExportChoice {
        var c = this
        p.optString("format").takeIf { f -> exportFormats.any { it.first == f } }?.let { c = c.copy(format = it) }
        if (p.has("quality")) c = c.copy(quality = ((p.optInt("quality", quality) + 5) / 10 * 10).coerceIn(10, 100))
        if (p.has("longEdge")) {
            val edge = p.optInt("longEdge", 0)
            c = c.copy(dimensions = if (edge <= 0) "full" else if (edge == 2048) "small" else "custom", customEdge = if (edge > 0) edge else customEdge)
        }
        p.optString("colorSpace").takeIf { s -> exportColorSpaces.any { it.first == s } }?.let { c = c.copy(colorSpace = it) }
        p.optString("sharpen").takeIf { s -> exportSharpening.any { it.first == s } }?.let { c = c.copy(sharpen = it) }
        p.optString("sharpenAmount").takeIf { s -> exportSharpenAmounts.any { it.first == s } }?.let { c = c.copy(sharpenAmount = it) }
        when (p.optString("metadata")) {
            "all" -> c = c.copy(includeMetadata = true, cameraInfo = true)
            "allExceptCamera" -> c = c.copy(includeMetadata = true, cameraInfo = false)
            "copyright", "none" -> c = c.copy(includeMetadata = false)
        }
        if (p.has("removeLocation")) c = c.copy(locationInfo = !p.optBoolean("removeLocation"))
        if (p.has("bitDepth")) c = c.copy(bitDepth = p.optInt("bitDepth", bitDepth).takeIf { b -> exportBitDepths.any { it.first == b.toString() } } ?: bitDepth)
        p.optString("tiffCompression").takeIf { s -> exportTiffCompressions.any { it.first == s } }?.let { c = c.copy(tiffCompression = it) }
        if (p.has("startNumber")) c = c.copy(startNumber = p.optInt("startNumber", startNumber).coerceIn(0, 999_999))
        // its own name template, read back as the page's choices where it is one of them
        p.optString("naming").takeIf { it.isNotBlank() }?.let { n ->
            c = when {
                n == "{name}" -> c.copy(naming = "original")
                n == "{date}-{name}" -> c.copy(naming = "dateName")
                n == "{date}" -> c.copy(naming = "date")
                n.endsWith("-{seq:1}") && !n.dropLast(8).contains('{') -> c.copy(naming = "custom", customName = n.dropLast(8))
                else -> c
            }
        }
        c = c.copy(watermark = p.has("watermark"), border = p.has("border"))
        p.optJSONObject("watermark")?.let { w ->
            val image = w.optString("image")
            c = c.copy(
                wm = c.wm.copy(
                    graphic = image.isNotBlank(),
                    text = w.optString("text", c.wm.text),
                    image = image,
                    size = if (image.isNotBlank()) (w.optDouble("imageWidth", c.wm.size / 100.0) * 100).roundToInt().coerceIn(1, 100) else (w.optDouble("size", c.wm.size / 200.0) * 200).roundToInt().coerceIn(1, 100),
                    opacity = (w.optDouble("opacity", c.wm.opacity / 100.0) * 100).roundToInt().coerceIn(0, 100),
                    offsetX = (w.optDouble("offsetX", c.wm.offsetX / 100.0) * 100).roundToInt().coerceIn(0, 100),
                    offsetY = (w.optDouble("offsetY", c.wm.offsetY / 100.0) * 100).roundToInt().coerceIn(0, 100),
                    anchor = w.optString("anchor", c.wm.anchor).takeIf { a -> watermarkAnchors.contains(a) } ?: c.wm.anchor,
                    shadow = w.optBoolean("shadow", c.wm.shadow),
                    bold = w.optBoolean("bold", c.wm.bold),
                    italic = w.optBoolean("italic", c.wm.italic),
                    rotation = w.optInt("rotation", c.wm.rotation).takeIf { it in listOf(0, 90, 180, 270) } ?: c.wm.rotation,
                    white = w.optJSONArray("color")?.optInt(0, 255)?.let { it >= 128 } ?: c.wm.white,
                ),
            )
        }
        p.optJSONObject("border")?.let { b ->
            val rgb = b.optJSONArray("color")
            val color = rgb?.let { (it.optInt(0) shl 16) or (it.optInt(1) shl 8) or it.optInt(2) } ?: c.borderColor
            c = c.copy(
                borderThickness = b.optDouble("thickness", c.borderThickness.toDouble()).roundToInt().coerceIn(0, 25),
                borderRatio = b.optString("ratio", c.borderRatio).takeIf { r -> exportBorderRatios.any { it.first == r } } ?: c.borderRatio,
                borderColor = color and 0xFFFFFF,
            )
        }
        return c
    }

    /** Everything the page shows, for the next time. */
    fun prefs(): String = JSONObject()
        .put("format", format).put("dimensions", dimensions).put("customEdge", customEdge).put("quality", quality)
        .put("includeMetadata", includeMetadata).put("cameraInfo", cameraInfo).put("locationInfo", locationInfo)
        .put("bitDepth", bitDepth).put("tiffCompression", tiffCompression).put("watermark", watermark).put("wm", wm.prefs())
        .put("border", border).put("borderRatio", borderRatio).put("borderThickness", borderThickness).put("borderColor", borderColor)
        .put("naming", naming).put("customName", customName).put("startNumber", startNumber)
        .put("sharpen", sharpen).put("sharpenAmount", sharpenAmount).put("colorSpace", colorSpace)
        .toString()

    companion object {
        /** From [prefs] (and the sheet's older keys: `longEdge`, `metadata`, `removeLocation`). */
        fun fromPrefs(text: String?): ExportChoice {
            val o = runCatching { JSONObject(text ?: "{}") }.getOrDefault(JSONObject())
            val d = ExportChoice()
            fun pick(key: String, options: List<Pair<String, String>>, default: String) = o.optString(key, default).takeIf { v -> options.any { it.first == v } } ?: default
            val oldEdge = o.optInt("longEdge", 0)
            val oldMeta = o.optString("metadata", "all")
            return ExportChoice(
                format = pick("format", exportFormats, d.format),
                dimensions = o.optString("dimensions", if (oldEdge == 2048) "small" else if (oldEdge > 0) "custom" else "full")
                    .takeIf { v -> exportDimensions.any { it.first == v } } ?: d.dimensions,
                customEdge = o.optInt("customEdge", if (oldEdge > 0) oldEdge else d.customEdge).coerceIn(1, 65_000),
                quality = ((o.optInt("quality", d.quality) + 5) / 10 * 10).coerceIn(10, 100),
                includeMetadata = o.optBoolean("includeMetadata", oldMeta != "copyright" && oldMeta != "none"),
                cameraInfo = o.optBoolean("cameraInfo", oldMeta == "all"),
                locationInfo = o.optBoolean("locationInfo", !o.optBoolean("removeLocation", false)),
                bitDepth = o.optInt("bitDepth", d.bitDepth).takeIf { b -> exportBitDepths.any { it.first == b.toString() } } ?: d.bitDepth,
                tiffCompression = pick("tiffCompression", exportTiffCompressions, d.tiffCompression),
                watermark = o.optBoolean("watermark", d.watermark),
                wm = WatermarkChoice.from(o.optJSONObject("wm")),
                border = o.optBoolean("border", d.border),
                borderRatio = pick("borderRatio", exportBorderRatios, d.borderRatio),
                borderThickness = o.optInt("borderThickness", d.borderThickness).coerceIn(0, 25),
                borderColor = o.optInt("borderColor", d.borderColor) and 0xFFFFFF,
                naming = pick("naming", exportNamings, if (o.optString("naming") == "customSeq") "custom" else d.naming),
                customName = o.optString("customName", d.customName),
                startNumber = o.optInt("startNumber", d.startNumber).coerceIn(0, 999_999),
                sharpen = pick("sharpen", exportSharpening, d.sharpen),
                sharpenAmount = pick("sharpenAmount", exportSharpenAmounts, d.sharpenAmount),
                colorSpace = pick("colorSpace", exportColorSpaces, d.colorSpace),
            )
        }
    }
}

val exportFormats = listOf("jpeg" to "JPG", "tiff" to "TIF", "avif" to "AVIF", "dng" to "DNG", "original" to "Original")
val exportDimensions = listOf("small" to "Small (2048 px)", "full" to "Full size", "custom" to "Custom")
/** The reference's quality steps, 100 % to 10 %. */
val exportQualities = (100 downTo 10 step 10).map { "$it" to "$it%" }
val exportBitDepths = listOf("8" to "8 bits per channel", "16" to "16 bits per channel")
val exportTiffCompressions = listOf("none" to "None", "lzw" to "LZW", "zip" to "ZIP")
val exportNamings = listOf("original" to "File name", "dateName" to "Date - file name", "custom" to "Custom name", "date" to "Date")
val exportSharpening = listOf("none" to "None", "screen" to "Screen", "glossy" to "Glossy paper", "matte" to "Matte paper")
val exportSharpenAmounts = listOf("low" to "Low", "standard" to "Standard", "high" to "High")
val exportColorSpaces = listOf("srgb" to "sRGB", "displayP3" to "Display P3", "adobeRgb" to "Adobe RGB (1998)", "proPhoto" to "ProPhoto RGB")
/** The border's shapes, in the reference's order: the photo's own, an even margin, then fixed ratios. */
val exportBorderRatios = listOf("original" to "Original", "even" to "Even", "1:1" to "1:1", "2:3" to "2:3", "3:2" to "3:2", "4:5" to "4:5", "5:4" to "5:4", "9:16" to "9:16", "2:1" to "2:1")

/**
 * A border's margins around a `w × h` photo (left, right, top, bottom), as the engine's export draws
 * them: an even margin is half the thickness of the long edge; otherwise the photo is padded out to
 * the shape and that frame grows by the thickness.
 */
fun borderMargins(w: Double, h: Double, thickness: Int, ratio: String): DoubleArray {
    val t = thickness.coerceIn(0, 100) / 100.0
    if (ratio == "even") return DoubleArray(4) { kotlin.math.max(w, h) * t / 2 }
    val (a, b) = ratio.split(':').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (w to h)
    val scale = kotlin.math.max(w / a, h / b)
    val x = (a * scale * (1 + t) - w) / 2
    val y = (b * scale * (1 + t) - h) / 2
    return doubleArrayOf(x, x, y, y)
}

/** The export page's colours (the reference's: page, fields, labels, values, lines). */
private val pageColor = Color(0xFF252525)
private val fieldColor = Color(0xFF080808)
private val fieldEdge = Color(0xFF393939)
private val labelColor = Color(0xFF999999)
private val valueColor = Color(0xFFE2E2E2)
private val optionColor = Color(0xFFE8E8E8)
private val titleColor = Color(0xFFCDCDCD)
private val lineColor = Color(0xFF1A1A1A)

/**
 * The editor's Share, as the reference's: a sheet of ways to send the photo. Share to… sends it at
 * the last export's settings through the phone's share menu; Export as… opens those settings first;
 * Save copy to device opens them to save. (The reference's row of recent apps is left out.)
 */
@Composable
fun ShareSheet(onDismiss: () -> Unit, onShareTo: () -> Unit, onExportAs: () -> Unit, onSave: () -> Unit) {
    LcSheet(onDismiss) {
        SheetHeading("Share")
        SheetDivider()
        SheetRow(LcIcon.Share, "Share to…") {
            onDismiss()
            onShareTo()
        }
        SheetRow(LcIcon.Sliders, "Export as…") {
            onDismiss()
            onExportAs()
        }
        SheetRow(LcIcon.Download, "Save copy to device") {
            onDismiss()
            onSave()
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Save a copy (or share one), as the reference's Export as page: a bar with Cancel, its title and
 * Apply; the file type, dimensions (small, full or a long side of your own), quality, a TIFF's bit
 * depth and compression; a watermark (Customize: text or a graphic, style, colour, shadow, size,
 * opacity, offsets and place, over a preview) and a border (shape, thickness, colour); then More
 * options: metadata, file naming, output sharpening and colour space. Apply saves (to
 * Pictures/LightCraft) or shares.
 */
@Composable
fun ExportSheet(vm: AppViewModel, ed: EditorState, share: Boolean, onDismiss: () -> Unit) {
    var c by remember { mutableStateOf(vm.exportChoice.let { if (it.format == "dng" && !ed.info.raw) it.copy(format = "jpeg") else it }) }
    var page by remember { mutableStateOf("main") }
    // full size as the photo is cropped (not the original's size)
    val full by produceState<ImageSize?>(null, ed.info.settingsHash) { value = vm.exportSize(ed, ExportChoice()) }
    val apply = {
        vm.export(c, share)
        onDismiss()
    }
    LcOverlay({ if (page != "main") page = "main" else onDismiss() }, Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().background(pageColor).windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            when (page) {
                "more" -> {
                    PageBar("More options", back = true, onClose = { page = "main" })
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 48.dp)) { MoreOptions(c, ed) { c = it } }
                }
                "watermark" -> {
                    PageBar("Customize watermark", back = true, onClose = { page = "main" })
                    WatermarkEditor(vm, ed, c) { c = it }
                }
                "border" -> {
                    PageBar("Border options", back = true, onClose = { page = "main" })
                    BorderEditor(vm, ed, c) { c = it }
                }
                else -> {
                    PageBar(if (share) "Share as" else "Export as", back = false, onClose = onDismiss, onApply = apply)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
                        // the reference's first line: Settings and which of its four the settings are
                        SettingsLine(c) { c = it }
                        MainOptions(c, ed, full) { c = it }
                        if (c.rendered) {
                            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).fillMaxWidth().height(1.dp).background(fieldEdge))
                            OptionSwitch("Include watermark", c.watermark, inset = 20.dp) { c = c.copy(watermark = it) }
                            if (c.watermark) {
                                Row(
                                    Modifier.padding(start = 36.dp, end = 20.dp).fillMaxWidth().height(48.dp).clickable(role = Role.Button) { page = "watermark" },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (c.wm.usable) "Customize" else "Customize (add its text or graphic)", color = valueColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    MdIcon(MdIcon.ChevronRight, null, tint = labelColor)
                                }
                            }
                            // a border, as the reference's: a switch, then Customize
                            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).fillMaxWidth().height(1.dp).background(fieldEdge))
                            OptionSwitch("Include border", c.border, inset = 20.dp) { c = c.copy(border = it) }
                            if (c.border) {
                                Row(
                                    Modifier.padding(start = 36.dp, end = 20.dp).fillMaxWidth().height(48.dp).clickable(role = Role.Button) { page = "border" },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Customize", color = valueColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    MdIcon(MdIcon.ChevronRight, null, tint = labelColor)
                                }
                            }
                        }
                        // More options: a 48 dp row between two lines
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(fieldEdge))
                            Row(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button) { page = "more" }, verticalAlignment = Alignment.CenterVertically) {
                                Text("More options", color = titleColor, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.weight(1f))
                                MdIcon(MdIcon.ChevronRight, null, tint = titleColor)
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(fieldEdge))
                        }
                    }
                }
            }
        }
    }
}

/** The page's bar: Cancel (or Back), its title, and Apply (the main page's). */
@Composable
private fun PageBar(title: String, back: Boolean, onClose: () -> Unit, onApply: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(if (back) MdIcon.ArrowBack else MdIcon.Close, if (back) "Back" else "Cancel", size = 56.dp, tint = titleColor, onClick = onClose)
        Text(title, color = titleColor, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.padding(start = 3.dp).weight(1f))
        onApply?.let { BarButton(MdIcon.Check, "Apply", size = 56.dp, tint = titleColor, onClick = it) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(lineColor))
}

/** File type, dimensions, quality, and a TIFF's bit depth and compression. */
@Composable
private fun MainOptions(c: ExportChoice, ed: EditorState, full: ImageSize?, set: (ExportChoice) -> Unit) {
    val formats = exportFormats.filter { (id, _) -> id != "dng" || ed.info.raw }
    Field("File type", formats, c.format, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { set(c.copy(format = it)) }
    if (c.rendered) {
        val sizes = exportDimensions.map { (id, label) -> id to (if (id == "full") full?.let { "$label (${it.width} × ${it.height})" } ?: label else label) }
        Field("Dimensions", sizes, c.dimensions, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) { set(c.copy(dimensions = it)) }
        if (c.border) {
            Text("The dimensions are the photo's own; the border adds to them.", color = Color.White, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
        }
        if (c.dimensions == "custom") {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Long side", color = labelColor, fontFamily = Inter, fontSize = 15.sp)
                NumberField(c.customEdge, Modifier.padding(start = 10.dp).width(110.dp), max = 65_000) { set(c.copy(customEdge = it.coerceAtLeast(1))) }
                Text("px", color = labelColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (c.lossy) {
        Field("Quality", exportQualities, ((c.quality + 5) / 10 * 10).coerceIn(10, 100).toString(), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            set(c.copy(quality = it.toInt()))
        }
    }
    if (c.format == "tiff") {
        Field("Bit depth", exportBitDepths, c.bitDepth.toString(), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) { set(c.copy(bitDepth = it.toInt())) }
        Field("Compression", exportTiffCompressions, c.tiffCompression, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) { set(c.copy(tiffCompression = it)) }
    }
    if (c.format == "original") {
        Text("The original file as it came in, without the edits.", color = labelColor, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/** More options: metadata, file naming (with an example), output sharpening and colour space. */
@Composable
private fun MoreOptions(c: ExportChoice, ed: EditorState, set: (ExportChoice) -> Unit) {
    OptionSwitch("Include metadata", c.includeMetadata) { set(c.copy(includeMetadata = it)) }
    if (c.includeMetadata) {
        OptionSwitch("Camera and raw info", c.cameraInfo) { set(c.copy(cameraInfo = it)) }
        OptionSwitch("Location info", c.locationInfo) { set(c.copy(locationInfo = it)) }
    }
    Text("Copyright is always kept.", color = labelColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    Box(Modifier.padding(top = 4.dp, bottom = 8.dp).fillMaxWidth().height(1.dp).background(lineColor))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Field("File naming", exportNamings, c.naming, Modifier.padding(top = 16.dp)) { set(c.copy(naming = it)) }
        if (c.naming == "custom") {
            Text("Custom text", color = labelColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            TextBox(c.customName, Modifier.fillMaxWidth()) { set(c.copy(customName = it)) }
            Text("Start number", color = labelColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            NumberField(c.startNumber, Modifier.fillMaxWidth(), max = 999_999) { set(c.copy(startNumber = it)) }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Example", color = labelColor, fontFamily = Inter, fontSize = 15.sp)
            Text(exampleName(c, ed.info.fileName, ed.info.captured ?: ed.info.imported), color = Color(0xFFB4B4B4), fontFamily = Inter, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
        }
    }
    Box(Modifier.padding(top = 12.dp, bottom = 8.dp).fillMaxWidth().height(1.dp).background(lineColor))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Field("Output sharpening", exportSharpening, c.sharpen, Modifier.padding(top = 16.dp)) { set(c.copy(sharpen = it)) }
        if (c.sharpen != "none") Field("Amount", exportSharpenAmounts, c.sharpenAmount, Modifier.padding(top = 16.dp)) { set(c.copy(sharpenAmount = it)) }
    }
    Box(Modifier.padding(top = 32.dp, bottom = 8.dp).fillMaxWidth().height(1.dp).background(lineColor))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Field("Color space", exportColorSpaces, c.colorSpace, Modifier.padding(top = 16.dp)) { set(c.copy(colorSpace = it)) }
    }
}

/** The name an export of [fileName] (taken at [taken], ISO) gets, as the engine's tokens expand it. */
private fun exampleName(c: ExportChoice, fileName: String, taken: String): String {
    val stem = fileName.substringBeforeLast('.')
    val ext = when (c.format) {
        "jpeg" -> "jpg"
        "tiff" -> "tif"
        "original" -> fileName.substringAfterLast('.', "jpg").lowercase()
        else -> c.format
    }
    val date = taken.take(10).replace("-", "")
    val name = c.template.replace("{name}", stem).replace("{seq:1}", c.startNumber.toString()).replace("{date}", date)
    return "$name.$ext"
}

/** A labelled choice, as the reference's: its name over a dark 40 dp box with the choice and a caret; a tap lists the others. */
@Composable
private fun Field(label: String, options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, color = labelColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(fieldColor)
                    .border(2.dp, fieldEdge, RoundedCornerShape(4.dp))
                    .clickable(role = Role.DropdownList) { open = true }
                    .semantics { contentDescription = label }
                    .padding(start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), color = valueColor, fontFamily = Inter, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                // the caret: a small solid triangle pointing down
                Canvas(Modifier.size(10.dp)) {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height * 0.25f)
                        lineTo(size.width, size.height * 0.25f)
                        lineTo(size.width / 2, size.height * 0.8f)
                        close()
                    }
                    drawPath(p, valueColor)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Lc.bottomBar) {
                options.forEach { (id, text) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(text, fontFamily = Inter, fontSize = 15.sp, color = if (id == selected) Lc.selected else Lc.text) },
                        trailingIcon = if (id == selected) ({ MdIcon(MdIcon.Check, "Selected", tint = Lc.selected, size = 20.dp) }) else null,
                        onClick = {
                            open = false
                            onPick(id)
                        },
                    )
                }
            }
        }
    }
}

/** A switch row, as the reference's options (48 dp, 17 sp). */
@Composable
private fun OptionSwitch(label: String, on: Boolean, inset: androidx.compose.ui.unit.Dp = 16.dp, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).toggleable(on, role = Role.Switch, onValueChange = onChange).padding(start = inset, end = inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = optionColor, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.weight(1f))
        LcSwitch(on, behind = pageColor)
    }
}

/** A text box in the fields' style. */
@Composable
private fun TextBox(value: String, modifier: Modifier = Modifier, keyboard: KeyboardOptions = KeyboardOptions.Default, onChange: (String) -> Unit) {
    BasicTextField(
        value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = keyboard,
        textStyle = TextStyle(color = valueColor, fontFamily = Inter, fontSize = 15.sp),
        cursorBrush = SolidColor(valueColor),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(4.dp)).background(fieldColor).border(2.dp, fieldEdge, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/** A whole number from 0 to [max] in a text box (digits only). */
@Composable
private fun NumberField(value: Int, modifier: Modifier = Modifier, max: Int, onChange: (Int) -> Unit) {
    // its own text while typing (an empty box stays empty), the number from it
    var text by remember { mutableStateOf(value.toString()) }
    TextBox(text, modifier, KeyboardOptions(keyboardType = KeyboardType.Number)) { t ->
        val digits = t.filter { it.isDigit() }.take(max.toString().length)
        text = digits
        digits.toIntOrNull()?.let { onChange(it.coerceAtMost(max)) }
    }
}

/**
 * Customize: the watermark over a preview of this photo, as the reference's: text or a graphic (one
 * of the phone's pictures); bold, italic, a quarter turn, white or black; a drop shadow; its size,
 * opacity and offsets. A tap on the preview puts it in one of nine places.
 */
@Composable
private fun WatermarkEditor(vm: AppViewModel, ed: EditorState, c: ExportChoice, set: (ExportChoice) -> Unit) {
    val wm = c.wm
    fun put(w: WatermarkChoice) = set(c.copy(wm = w))
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.stageWatermark(it) { path -> put(wm.copy(image = path)) } } }
    // the preview: this photo exported small with the watermark, again a moment after each change
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(wm) {
        delay(250)
        busy = true
        preview = vm.exportPreview(ed, c.copy(watermark = true, border = false)) ?: preview
        busy = false
    }
    val current by androidx.compose.runtime.rememberUpdatedState(wm)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black).pointerInput(preview) {
                // a tap (or the end of a drag) on the photo: the third it's in, across and down
                val img = preview ?: return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var at = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        at = ch.position
                        ch.consume()
                    }
                    val r = fittedRect(IntSize(size.width, size.height), IntSize(img.width, img.height))
                    val col = (((at.x - r.left) / r.width) * 3).toInt().coerceIn(0, 2)
                    val row = (((at.y - r.top) / r.height) * 3).toInt().coerceIn(0, 2)
                    put(current.copy(anchor = watermarkAnchors[row * 3 + col]))
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            preview?.let { Image(it, "Preview: tap where the watermark goes", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            if (busy && preview == null) CircularProgressIndicator(Modifier.size(32.dp), color = Lc.highlight, trackColor = Lc.divider)
        }
        // text or graphic, as tabs
        Row(Modifier.padding(top = 5.dp).fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            listOf(false to "Text", true to "Graphic").forEach { (g, label) ->
                val on = wm.graphic == g
                Column(Modifier.selectable(on, role = Role.Tab) { put(wm.copy(graphic = g)) }.padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = if (on) optionColor else labelColor, fontFamily = Inter, fontSize = 15.sp)
                    Box(Modifier.padding(top = 6.dp).width(48.dp).height(2.dp).background(if (on) optionColor else Color.Transparent))
                }
            }
        }
        if (!wm.graphic) {
            TextBox(wm.text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(44.dp)) { put(wm.copy(text = it)) }
        } else {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { runCatching { pick.launch(arrayOf("image/jpeg", "image/png")) } }, verticalAlignment = Alignment.CenterVertically) {
                LcIcon(LcIcon.AddPhoto, null, tint = valueColor, size = 24.dp, modifier = Modifier.padding(10.dp))
                Text(wm.image.substringAfterLast('/').ifEmpty { "Choose a graphic" }, color = valueColor, fontFamily = Inter, fontSize = 17.sp, maxLines = 1)
            }
        }
        // the reference's row of 48 dp buttons: bold and italic (text), turn, white and black (text)
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wm.graphic) {
                StyleButton("B", "Bold", wm.bold, FontWeight.Bold, null) { put(wm.copy(bold = !wm.bold)) }
                StyleButton("I", "Italic", wm.italic, FontWeight.Normal, androidx.compose.ui.text.font.FontStyle.Italic) { put(wm.copy(italic = !wm.italic)) }
            }
            Box(
                Modifier.padding(horizontal = 10.dp).size(48.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { put(wm.copy(rotation = (wm.rotation + 90) % 360)) }
                    .semantics { contentDescription = "Turn (now ${wm.rotation}°)" },
                contentAlignment = Alignment.Center,
            ) { LcIcon(LcIcon.RotateRight, null, tint = valueColor, size = 24.dp) }
            if (!wm.graphic) {
                listOf(true to Color.White, false to Color.Black).forEach { (white, color) ->
                    val on = wm.white == white
                    Box(
                        Modifier.size(48.dp).selectable(on, role = Role.RadioButton) { put(wm.copy(white = white)) }.semantics { contentDescription = if (white) "White" else "Black" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (on) Lc.selected else labelColor, CircleShape).padding(4.dp).clip(CircleShape).background(color))
                    }
                }
            }
        }
        if (!wm.graphic) OptionSwitch("Drop shadow", wm.shadow) { put(wm.copy(shadow = it)) }
        WatermarkSlider("Size", "watermark.size", 1.0, 100.0, 10.0, wm.size) { put(wm.copy(size = it)) }
        WatermarkSlider("Opacity", "watermark.opacity", 0.0, 100.0, 100.0, wm.opacity) { put(wm.copy(opacity = it)) }
        WatermarkSlider("X offset", "watermark.offsetX", 0.0, 100.0, 3.0, wm.offsetX) { put(wm.copy(offsetX = it)) }
        WatermarkSlider("Y offset", "watermark.offsetY", 0.0, 100.0, 3.0, wm.offsetY) { put(wm.copy(offsetY = it)) }
        Spacer(Modifier.height(32.dp))
    }
}

/** A 48 dp text-style toggle (B, I), on with a light square behind it. */
@Composable
private fun StyleButton(letter: String, name: String, on: Boolean, weight: FontWeight, style: androidx.compose.ui.text.font.FontStyle?, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Color(0xFF4B4B4B) else Color.Transparent)
            .toggleable(on, role = Role.Checkbox) { onClick() }.semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = valueColor, fontFamily = Inter, fontWeight = weight, fontStyle = style, fontSize = 20.sp)
    }
}

@Composable
private fun WatermarkSlider(label: String, id: String, min: Double, max: Double, default: Double, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        DevelopSlider(
            DevelopControl(id, label, "", "", min, max, default, 1.0, 0u, value.toDouble(), SliderTrack.Plain),
            onChange = { onChange(it.roundToInt()) },
            onFinished = {},
            onReset = { onChange(default.roundToInt()) },
        )
    }
}

/**
 * The border's swatches: LightCraft's own row, greys from white to black, then hues round the
 * wheel (the reference also offers a row of swatches and a picker).
 */
private val borderSwatches: List<Int> by lazy {
    listOf(0xFFFFFF, 0xC4C4C4, 0x777777, 0x3B3B3B, 0x000000) +
        (0 until 12).map { i -> android.graphics.Color.HSVToColor(floatArrayOf(i * 30f, 0.55f, 0.85f)) and 0xFFFFFF } +
        (0 until 12).map { i -> android.graphics.Color.HSVToColor(floatArrayOf(i * 30f, 0.35f, 0.55f)) and 0xFFFFFF }
}

/**
 * Border options, as the reference's: the border's shape (Border size: the photo's own, even, or a
 * ratio), its thickness (0–25, 10 at first) and colour (swatches, or the picker: a tap or a drag on
 * the photo takes the colour there), over a preview of this photo with it.
 */
@Composable
private fun BorderEditor(vm: AppViewModel, ed: EditorState, c: ExportChoice, set: (ExportChoice) -> Unit) {
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(c.borderRatio, c.borderThickness, c.borderColor) {
        delay(250)
        preview = vm.exportPreview(ed, c.copy(border = true, watermark = false)) ?: preview
    }
    val latest by androidx.compose.runtime.rememberUpdatedState(c)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black).pointerInput(preview, picking) {
                // the picker: the photo's colour under the finger (the border around it is left out)
                val img = preview ?: return@pointerInput
                val photo = ed.image ?: return@pointerInput
                if (!picking) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun sample(at: Offset) {
                        val r = fittedRect(IntSize(size.width, size.height), IntSize(img.width, img.height))
                        val cc = latest
                        val m = borderMargins(photo.width.toDouble(), photo.height.toDouble(), cc.borderThickness, cc.borderRatio)
                        val fw = photo.width + m[0] + m[1]
                        val fh = photo.height + m[2] + m[3]
                        val u = (((at.x - r.left) / r.width) * fw - m[0]) / photo.width
                        val v = (((at.y - r.top) / r.height) * fh - m[2]) / photo.height
                        if (u !in 0.0..1.0 || v !in 0.0..1.0) return
                        val px = runCatching { photo.asAndroidBitmap().getPixel((u * (photo.width - 1)).toInt(), (v * (photo.height - 1)).toInt()) }.getOrNull() ?: return
                        set(cc.copy(borderColor = px and 0xFFFFFF))
                    }
                    sample(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        sample(ch.position)
                        ch.consume()
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            preview?.let { Image(it, "Preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: CircularProgressIndicator(Modifier.size(32.dp), color = Lc.highlight, trackColor = Lc.divider)
        }
        Text("Border size", color = labelColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            exportBorderRatios.forEach { (id, label) ->
                val on = c.borderRatio == id
                Box(
                    Modifier.padding(horizontal = 4.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Lc.primaryBlue else fieldColor)
                        .border(1.dp, if (on) Lc.primaryBlue else fieldEdge, RoundedCornerShape(18.dp))
                        .selectable(on, role = Role.RadioButton) { set(c.copy(borderRatio = id)) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) Color.White else valueColor, fontFamily = Inter, fontSize = 14.sp) }
            }
        }
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
            DevelopSlider(
                DevelopControl("watermark.border", "Thickness", "", "", 0.0, 25.0, 10.0, 1.0, 0u, c.borderThickness.toDouble(), SliderTrack.Plain),
                onChange = { set(c.copy(borderThickness = it.roundToInt().coerceIn(0, 25))) },
                onFinished = {},
                onReset = { set(c.copy(borderThickness = 10)) },
            )
        }
        Text("Color", color = labelColor, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            // the picker first: on, a tap on the photo takes its colour
            Box(
                Modifier.size(48.dp).selectable(picking, role = Role.Checkbox) { picking = !picking }.semantics { contentDescription = "Pick a colour from the photo" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (picking) Lc.primaryBlue else fieldColor).border(2.dp, if (picking) Lc.primaryBlue else labelColor, CircleShape), contentAlignment = Alignment.Center) {
                    MdIcon(MdIcon.Colorize, null, tint = Color.White, size = 18.dp)
                }
            }
            borderSwatches.forEach { rgb ->
                val on = c.borderColor == rgb
                Box(
                    Modifier.size(44.dp).selectable(on, role = Role.RadioButton) {
                        picking = false
                        set(c.copy(borderColor = rgb))
                    }.semantics { contentDescription = "#%06X".format(rgb) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).border(2.dp, if (on) Lc.selected else labelColor, CircleShape).padding(4.dp).clip(CircleShape).background(Color(0xFF000000 or rgb.toLong())))
                }
            }
        }
    }
}

/** The reference's export settings, a fixed four: a small JPG, a large one, the original file, or the page's own. */
private val exportSettings = listOf("small" to "Small JPG", "large" to "Large JPG", "original" to "Original", "custom" to "Custom")

/** Which of the four [c] is: Small JPG (2048 px), Large JPG (full size), Original, or else Custom. */
internal fun exportSettingsOf(c: ExportChoice): String = when {
    c.format == "original" -> "original"
    c.format == "jpeg" && c.dimensions == "small" -> "small"
    c.format == "jpeg" && c.dimensions == "full" -> "large"
    else -> "custom"
}

/**
 * The page's first line, as the reference's: "Settings" and an inline dropdown with which of its four
 * the settings are. Small and Large JPG set the file type and size (the rest stays); Original, the
 * original file; Custom leaves them for the fields under it.
 */
@Composable
private fun SettingsLine(c: ExportChoice, onChange: (ExportChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Custom stays picked once chosen, even while the fields still match one of the others
    var custom by remember { mutableStateOf(false) }
    val current = if (custom) "custom" else exportSettingsOf(c)
    val name = exportSettings.first { it.first == current }.second
    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp).fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Settings", color = titleColor, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Box(Modifier.padding(start = 8.dp)) {
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(4.dp)).clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Export settings: $name" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, color = titleColor, fontFamily = Inter, fontSize = 17.sp, maxLines = 1)
                MdIcon(MdIcon.ExpandMore, null, tint = titleColor, size = 20.dp, modifier = Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Lc.bottomBar) {
                exportSettings.forEach { (id, label) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label, fontFamily = Inter, fontSize = 15.sp, color = if (id == current) Lc.selected else Lc.text) },
                        onClick = {
                            open = false
                            custom = id == "custom"
                            when (id) {
                                "small" -> onChange(c.copy(format = "jpeg", dimensions = "small"))
                                "large" -> onChange(c.copy(format = "jpeg", dimensions = "full"))
                                "original" -> onChange(c.copy(format = "original"))
                            }
                        },
                    )
                }
            }
        }
    }
}
