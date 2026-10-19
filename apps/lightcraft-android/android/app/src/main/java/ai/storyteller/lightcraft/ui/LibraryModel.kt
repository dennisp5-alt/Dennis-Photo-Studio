package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.PhotoInfo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the grid shows, picked from the title's menu: one of the library's views, or an album. */
data class LibraryView(val kind: String, val name: String, val albumId: ULong? = null) {
    companion object {
        val ALL = LibraryView("all", "All photos")
        val EDITED = LibraryView("edited", "Your edits")
        val UNEDITED = LibraryView("unedited", "Unedited")
        val RECENT = LibraryView("recentlyAdded", "Recently added")
        val DELETED = LibraryView("deleted", "Deleted")

        fun album(id: ULong, name: String) = LibraryView("album", name, id)
    }
}

/** The filter sheet's choices; all unset: every photo of the view. */
data class LibraryFilter(
    /** At least (or exactly, or at most) this many stars; 0: any. */
    val rating: Int = 0,
    /** `atLeast`, `exactly` or `atMost` (the ≥ button steps through them). */
    val ratingOp: String = "atLeast",
    /** `pick`, `none` (unflagged) or `reject`. */
    val flag: String? = null,
    /** `image`, `raw` or `video`. */
    val kind: String? = null,
    val camera: String? = null,
    val person: String? = null,
    /** A place name (city, else state, country or location line). */
    val place: String? = null,
    val keyword: String? = null,
    val edited: Boolean? = null,
    /** The search field's words. */
    val text: String = "",
) {
    /** The sheet's own choices narrow the view (the filter button turns blue; the search shows apart). */
    val narrowed: Boolean
        get() = rating > 0 || flag != null || kind != null || camera != null || person != null || place != null || keyword != null || edited != null

    val active: Boolean
        get() = narrowed || text.isNotBlank()
}

/** The sort sheet's keys, as the engine names them, with their labels. */
val sortKeys = listOf(
    "captureDate" to "Capture date",
    "importDate" to "Import date",
    "editDate" to "Modified date",
    "fileName" to "File name",
    "rating" to "Star rating",
)

/** "Segment by" choices: none, or how much of the date starts a new heading. */
val segmentUnits = listOf("none" to "None", "year" to "Year", "month" to "Month", "day" to "Day", "hour" to "Hour")

/** Photos of the grid under one heading ([label] null: no heading, the grid isn't segmented). */
class Segment(val key: String, val label: String?, val photos: List<PhotoInfo>)

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

/** The date the sort orders [p] by (ISO 8601), or null when the sort isn't by a date. */
private fun sortDate(p: PhotoInfo, sortKey: String): String? = when (sortKey) {
    "captureDate" -> p.captured.orEmpty()
    "importDate" -> p.imported
    "editDate" -> p.modified ?: p.imported
    else -> null
}

/**
 * The grid's photos (already in sort order) cut into segments at each new year, month, day or
 * hour of the date the sort uses. Not segmented (one segment without a heading) for `none` or a
 * sort that isn't by date.
 */
fun segments(photos: List<PhotoInfo>, sortKey: String, unit: String): List<Segment> {
    val length = when (unit) {
        "year" -> 4
        "month" -> 7
        "day" -> 10
        "hour" -> 13
        else -> 0
    }
    if (length == 0 || photos.isEmpty() || sortKeys.none { it.first == sortKey } || sortDate(photos.first(), sortKey) == null) {
        return if (photos.isEmpty()) emptyList() else listOf(Segment("", null, photos))
    }
    val out = mutableListOf<Segment>()
    var key: String? = null
    var run = mutableListOf<PhotoInfo>()
    fun flush() {
        val k = key ?: return
        out += Segment(k, segmentLabel(k), run)
    }
    for (p in photos) {
        val k = sortDate(p, sortKey)?.takeIf { validDate(it) }?.take(length).orEmpty()
        if (k != key) {
            flush()
            key = k
            run = mutableListOf()
        }
        run += p
    }
    flush()
    return out
}

private fun validDate(iso: String) = iso.length >= 10 && runCatching { LocalDate.parse(iso.take(10)) }.isSuccess

/** A segment's heading: `2025`, `September 2025`, `September 10, 2025` or `September 10, 2025, 2 PM`. */
fun segmentLabel(key: String): String = runCatching {
    when (key.length) {
        4 -> key
        7 -> LocalDate.parse("$key-01").let { "${it.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())} ${it.year}" }
        10 -> LocalDate.parse(key).format(dayFormat)
        13 -> LocalDateTime.parse("$key:00:00").let {
            "${it.toLocalDate().format(dayFormat)}, ${it.format(DateTimeFormatter.ofPattern("h a", Locale.getDefault()))}"
        }
        else -> "Unknown date"
    }
}.getOrDefault("Unknown date")

/** One row of the grid: photos [start] until [end] of a segment, [height] dp tall. */
data class GridRow(val start: Int, val end: Int, val height: Float)

/**
 * A justified grid's rows: photos side by side at their own shape ([aspects], width over
 * height), each row exactly as wide as [width] (gaps of [gap] between photos) and so at most
 * [target] tall; the last row keeps [target] and starts at the left.
 */
fun justify(aspects: List<Double>, width: Float, target: Float, gap: Float): List<GridRow> {
    val rows = mutableListOf<GridRow>()
    if (width <= 0f || target <= 0f) return rows
    var start = 0
    var sum = 0.0
    for (i in aspects.indices) {
        // a panorama or a sliver shouldn't take over a row
        sum += aspects[i].coerceIn(0.25, 4.0)
        val height = ((width - gap * (i - start)) / sum).toFloat()
        if (height <= target) {
            rows += GridRow(start, i + 1, height)
            start = i + 1
            sum = 0.0
        }
    }
    if (start < aspects.size) rows += GridRow(start, aspects.size, target)
    return rows
}

/** A square grid's rows: [columns] photos each, [cell] dp tall. */
fun squareRows(count: Int, columns: Int, cell: Float): List<GridRow> =
    if (columns <= 0) emptyList() else (0 until count step columns).map { GridRow(it, minOf(it + columns, count), cell) }

/** What Local storage shows: the phone's space (bytes) and what LightCraft's library takes. */
class StorageInfo(val deviceTotal: Long, val deviceFree: Long, val libraryBytes: Long, val photos: Int, val libraryPath: String)
