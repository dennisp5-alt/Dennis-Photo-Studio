package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.CropFrame
import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.PhotoFlag
import ai.storyteller.lightcraft.core.SliderTrack
import ai.storyteller.lightcraft.core.PhotoInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The screens' pure helpers: the grid's days and the white balance selector's point on the photo. */
class LayoutLogicTest {
    private fun photo(id: Int, captured: String?, aspect: Double = 1.5) = PhotoInfo(
        id.toULong(), "IMG_$id.jpg", "JPEG", false, 4000u, 3000u, captured, 0u, PhotoFlag.UNFLAGGED, false, 0u, "", "", "", aspect, "2026-10-01T10:00:00", null,
    )

    private val days = listOf(
        photo(1, "2025-09-10T18:00:00"),
        photo(2, "2025-09-10T09:30:00"),
        photo(3, "2025-09-09T12:00:00"),
        photo(4, "2024-12-31T23:00:00"),
    )

    @Test
    fun photosSegmentByTheUnitOfTheSortDate() {
        val byDay = segments(days, "captureDate", "day")
        assertEquals(listOf("2025-09-10", "2025-09-09", "2024-12-31"), byDay.map { it.key })
        assertEquals(listOf(2, 1, 1), byDay.map { it.photos.size })
        assertEquals(listOf(1, 2), byDay[0].photos.map { it.id.toInt() })
        assertEquals(listOf("2025-09", "2024-12"), segments(days, "captureDate", "month").map { it.key })
        assertEquals(listOf("2025", "2024"), segments(days, "captureDate", "year").map { it.key })
        assertEquals(4, segments(days, "captureDate", "hour").size)
        // every photo here was imported at the same hour
        assertEquals(1, segments(days, "importDate", "day").size)
    }

    @Test
    fun noSegmentsWithoutAUnitOrADateSort() {
        listOf(segments(days, "captureDate", "none"), segments(days, "fileName", "day")).forEach {
            assertEquals(1, it.size)
            assertEquals(null, it[0].label)
            assertEquals(4, it[0].photos.size)
        }
        assertEquals(0, segments(emptyList(), "captureDate", "day").size)
    }

    @Test
    fun photosWithoutAUsableDateShareOneHeading() {
        val runs = segments(listOf(photo(1, null), photo(2, "not a date"), photo(3, "")), "captureDate", "day")
        assertEquals(1, runs.size)
        assertEquals("", runs[0].key)
        assertEquals("Unknown date", runs[0].label)
        assertEquals(3, runs[0].photos.size)
    }

    @Test
    fun justifiedRowsFillTheWidthAtMostTheTargetTall() {
        // four 3:2 photos in a 400 wide grid, 120 tall at most, gaps of 4
        val rows = justify(listOf(1.5, 1.5, 1.5, 1.5, 0.75), 400f, 120f, 4f)
        // two 3:2 photos fill 400 at (400 - 4) / 3 = 132 > 120, three at (400 - 8) / 4.5 = 87.1
        assertEquals(GridRow(0, 3, (400f - 8f) / 4.5f), rows[0])
        // the rest keeps the target height, at the left
        assertEquals(GridRow(3, 5, 120f), rows[1])
        assertEquals(0, justify(listOf(1.0), 0f, 120f, 4f).size)
    }

    @Test
    fun squareRowsHoldColumnsEach() {
        assertEquals(listOf(GridRow(0, 4, 100f), GridRow(4, 8, 100f), GridRow(8, 9, 100f)), squareRows(9, 4, 100f))
        assertEquals(0, squareRows(3, 0, 100f).size)
    }

    @Test
    fun theViewsCentreIsThePhotosCentre() {
        // a 3:2 photo fitted to a tall view: letterboxed above and below
        val p = photoPoint(Offset(500f, 1000f), IntSize(1000, 2000), 3000, 2000, 1f, Offset.Zero)
        assertNotNull(p)
        assertEquals(0.5f, p!!.x, 1e-4f)
        assertEquals(0.5f, p.y, 1e-4f)
    }

    @Test
    fun theLetterboxIsOffThePhoto() {
        // the photo fills 1000 × 667 in the middle of the 2000 px tall view: y = 100 is above it
        assertNull(photoPoint(Offset(500f, 100f), IntSize(1000, 2000), 3000, 2000, 1f, Offset.Zero))
    }

    @Test
    fun zoomAndPanAreTakenBack() {
        // zoomed 2× about the centre and moved 200 px right: the view's centre shows the point
        // 100 px left of the photo's centre, a tenth of the photo's 1000 px width
        val p = photoPoint(Offset(500f, 1000f), IntSize(1000, 2000), 3000, 2000, 2f, Offset(200f, 0f))
        assertNotNull(p)
        assertEquals(0.4f, p!!.x, 1e-4f)
        assertEquals(0.5f, p.y, 1e-4f)
    }

    @Test
    fun degenerateSizesAreNoPoint() {
        assertNull(photoPoint(Offset(1f, 1f), IntSize(0, 0), 3000, 2000, 1f, Offset.Zero))
        assertNull(photoPoint(Offset(1f, 1f), IntSize(100, 100), 0, 2000, 1f, Offset.Zero))
        assertNull(photoPoint(Offset(1f, 1f), IntSize(100, 100), 3000, 2000, 0f, Offset.Zero))
    }

    @Test
    fun aZoomedPhotoMovesOnlyAsFarAsItsEdges() {
        // a wide photo (1000 × 667 fitted) in a tall view: at 2× it is 2000 × 1334, wider than the
        // view by 1000 (500 either way) but still shorter than it, so it can't move up or down
        assertEquals(Offset(500f, 0f), zoomBounds(IntSize(1000, 2000), IntSize(1000, 667), 2f))
        // at 4× it is 2668 tall: 334 either way
        val b = zoomBounds(IntSize(1000, 2000), IntSize(1000, 667), 4f)
        assertEquals(1500f, b.x, 1e-3f)
        assertEquals(334f, b.y, 1e-3f)
        assertEquals(Offset.Zero, zoomBounds(IntSize(1000, 2000), IntSize(1000, 667), 1f))
    }

    @Test
    fun aZoomedViewRendersOnlyWhatIsOnScreen() {
        // a 6000 × 4000 photo fitted to a 1000 × 2000 view (its render 1000 × 667)
        val view = IntSize(1000, 2000)
        val image = IntSize(1000, 667)
        assertNull("at fit the whole-photo render is enough", detailWindow(view, image, 6000, Int.MAX_VALUE, 1f, Offset.Zero))
        // 4× in about the middle: the frame is the photo at 4000 px across, and the window the
        // middle quarter of it, about the view's size
        val (frame, window) = detailWindow(view, image, 6000, Int.MAX_VALUE, 4f, Offset.Zero)!!
        assertEquals(IntSize(4000, 2668), frame)
        assertEquals(2000, (window.left + window.right) / 2)
        assertEquals(1468, window.left)
        assertEquals(302, window.top)
        // past the photo's own pixels the frame stops growing, and the window shrinks with the zoom
        val (deepFrame, deepWindow) = detailWindow(view, image, 6000, Int.MAX_VALUE, 12f, Offset.Zero)!!
        assertEquals(6000, deepFrame.width)
        assert(deepWindow.width <= 1000 * 6 / 12 + 64)
        // a phone short of memory stays within the 2560 px preview, magnified past it
        val (smallFrame, smallWindow) = detailWindow(view, image, 6000, 2560, 4f, Offset.Zero)!!
        assertEquals(IntSize(2560, 1708), smallFrame)
        assert(smallWindow.width <= 1000 * 2560 / 4000 + 64)
        // moved off the photo (free scroll), there's nothing to render
        assertNull(detailWindow(view, image, 6000, Int.MAX_VALUE, 4f, Offset(100_000f, 0f)))
        assertNull(detailWindow(IntSize(0, 0), image, 6000, Int.MAX_VALUE, 4f, Offset.Zero))
    }

    private fun assertPoint(expected: Pair<Double, Double>, actual: Pair<Double, Double>) {
        assertEquals(expected.first, actual.first, 1e-9)
        assertEquals(expected.second, actual.second, 1e-9)
    }

    @Test
    fun curvePointsMoveBetweenTheirNeighbours() {
        val line = curvePoints(emptyList())
        assertEquals(listOf(0.0 to 0.0, 1.0 to 1.0), line)
        val (three, i) = withPoint(line, 0.5, 0.5)!!
        assertEquals(1, i)
        // an inner point keeps 1 % from its neighbours; y stays in 0..1
        assertPoint(0.99 to 1.0, movedPoint(three, 1, 1.4, 1.2)[1])
        assertPoint(0.01 to 0.0, movedPoint(three, 1, -0.3, -0.1)[1])
        // the ends keep to their side
        assertPoint(0.0 to 0.2, movedPoint(three, 0, -0.5, 0.2)[0])
        assertPoint(0.49 to 0.3, movedPoint(three, 0, 0.8, 0.3)[0])
        assertPoint(0.51 to 0.9, movedPoint(three, 2, 0.1, 0.9)[2])
        // no second point at the same input
        assertNull(withPoint(three, 0.505, 0.2))
        assertEquals(listOf(0.0, 0.0, 0.5, 0.5, 1.0, 1.0), three.flat())
        assertEquals(three, movedPoint(three, 7, 0.5, 0.5))
    }

    @Test
    fun aSampledCurveIsReadBetweenItsSamples() {
        assertEquals(0.25, sampledAt(listOf(0.0, 0.5, 1.0), 0.25), 1e-9)
        assertEquals(1.0, sampledAt(listOf(0.0, 0.5, 1.0), 2.0), 1e-9)
        assertEquals(0.3, sampledAt(emptyList(), 0.3), 1e-9)
        assertEquals("curve.shadows", curveRegion(0.1, 25.0, 50.0, 75.0))
        assertEquals("curve.darks", curveRegion(0.3, 25.0, 50.0, 75.0))
        assertEquals("curve.lights", curveRegion(0.6, 25.0, 50.0, 75.0))
        assertEquals("curve.highlights", curveRegion(0.9, 25.0, 50.0, 75.0))
    }

    @Test
    fun aWheelsPointIsAHueAroundItAndASaturationOut() {
        assertPoint(0.0 to 100.0, wheelValue(1.0, 0.0, 1.0))
        // up the screen is 90°, as on the desktop's wheels
        assertPoint(90.0 to 100.0, wheelValue(0.0, -1.0, 1.0))
        assertPoint(180.0 to 50.0, wheelValue(-0.5, 0.0, 1.0))
        assertPoint(270.0 to 100.0, wheelValue(0.0, 3.0, 1.0))
        assertPoint(0.0 to 0.0, wheelValue(0.0, 0.0, 0.0))
    }

    @Test
    fun theGallerysPhotosRunByDayInThePhonesZone() {
        val utc = java.time.ZoneId.of("UTC")
        val day = 86_400_000L
        // newest first: two on the 2nd of January 1970, one on the 1st
        val runs = byDay(listOf(day + 5_000L, day + 1_000L, 2_000L), utc) { it }
        assertEquals(listOf("1970-01-02", "1970-01-01"), runs.map { it.first })
        assertEquals(listOf(2, 1), runs.map { it.second.size })
        // the same moment is the 1st in New York
        assertEquals("1970-01-01", byDay(listOf(day + 1_000L), java.time.ZoneId.of("America/New_York")) { it }.single().first)
        assertEquals(0, byDay(emptyList<Long>(), utc) { it }.size)
    }

    @Test
    fun theGallerysHeadingsFollowItsSegmentation() {
        val utc = java.time.ZoneId.of("UTC")
        val hour = 3_600_000L
        val day = 24 * hour
        val times = listOf(40 * day + 3 * hour, 40 * day + 1_000L, 2 * day, 0L)
        assertEquals(listOf("1970", ""), byDate(times, utc, "year") { it }.map { it.first })
        assertEquals(listOf("1970-02", "1970-01", ""), byDate(times, utc, "month") { it }.map { it.first })
        assertEquals(listOf("1970-02-10", "1970-01-03", ""), byDate(times, utc, "day") { it }.map { it.first })
        assertEquals(listOf("1970-02-10T03", "1970-02-10T00", "1970-01-03T00", ""), byDate(times, utc, "hour") { it }.map { it.first })
    }

    private fun control(id: String, min: Double, max: Double, default: Double, decimals: Int = 0) =
        DevelopControl(id, id, "", "", min, max, default, 1.0, decimals.toUByte(), default, SliderTrack.Plain)

    @Test
    fun valuesAreSignedAsTheReferenceShowsThem() {
        // any value but 0 gets its sign and a space, a one-way slider's too (the reference's Blending reads "+ 50")
        assertEquals("+ 50", control("grading.blending", 0.0, 100.0, 50.0).format(50.0))
        assertEquals("− 25", control("light.contrast", -100.0, 100.0, 0.0).format(-25.0))
        assertEquals("0", control("light.contrast", -100.0, 100.0, 0.0).format(0.0))
        assertEquals("+ 0.35", control("light.exposure", -5.0, 5.0, 0.0, decimals = 2).format(0.35))
        assertEquals("0.00", control("light.exposure", -5.0, 5.0, 0.0, decimals = 2).format(0.0))
        assertEquals("5500K", control("wb.temp", 2000.0, 50000.0, 5500.0).format(5500.0))
        // a brush's sizes read plain, as the reference's Remove does
        assertEquals("25", control("brush.size", 2.0, 100.0, 25.0).format(25.0))
    }

    @Test
    fun curvePointsKeepASixteenthApartAndSixteenAtMost() {
        val line = listOf(0.0 to 0.0, 1.0 to 1.0)
        assertNotNull(withPoint(line, 0.5, 0.5))
        assertNull(withPoint(line, 0.05, 0.1))
        val full = (0 until CURVE_MAX_POINTS).map { it / (CURVE_MAX_POINTS - 1.0) to it / (CURVE_MAX_POINTS - 1.0) }
        assertNull(withPoint(full, 0.5, 0.5))
    }

    private fun crop(rect: List<Double>, angle: Double = 0.0, w: UInt = 0u, h: UInt = 0u) = CropFrame(emptyList(), rect, angle, w, h, 4000.0, 3000.0)

    @Test
    fun anUntouchedCropIsTheOriginalAChangedFreeOneCustom() {
        assertEquals("original", shownAspect(crop(listOf(0.0, 0.0, 1.0, 1.0))))
        assertEquals("Original", aspectName(crop(listOf(0.0, 0.0, 1.0, 1.0))))
        assertEquals("custom", shownAspect(crop(listOf(0.1, 0.0, 1.0, 1.0))))
        assertEquals("custom", shownAspect(crop(listOf(0.0, 0.0, 1.0, 1.0), angle = 2.0)))
        assertEquals("1x1", shownAspect(crop(listOf(0.1, 0.0, 0.85, 1.0), w = 1u, h = 1u)))
        assertEquals("original", shownAspect(crop(listOf(0.0, 0.0, 1.0, 1.0), w = 4u, h = 3u)))
    }

    @Test
    fun fileInfoWritesTheCaptureTimeAsTheReferenceDoes() {
        val was = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("Jan 1, 2027 | 12:00:00 PM", takenAt("2027-01-01T12:00:00"))
            assertEquals("Oct 9, 2026 | 6:04:05 PM", takenAt("2026-10-09T18:04:05+02:00"))
            assertNull(takenAt("not a date"))
            // a UTC time reads in the phone's zone
            val zone = java.util.TimeZone.getDefault()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            try {
                assertEquals("Jan 1, 2027 | 7:00:00 AM", takenAt("2027-01-01T12:00:00Z"))
            } finally {
                java.util.TimeZone.setDefault(zone)
            }
        } finally {
            java.util.Locale.setDefault(was)
        }
    }

    @Test
    fun ratiosAreNamedThePhotosWayRound() {
        assertEquals("16x9", shownAspect(crop(listOf(0.0, 0.1, 1.0, 0.9), w = 16u, h = 9u)))
        assertEquals("16 × 9", aspectName(crop(listOf(0.0, 0.1, 1.0, 0.9), w = 16u, h = 9u)))
        assertEquals("11 × 8.5", aspectName(crop(listOf(0.0, 0.0, 0.97, 1.0), w = 1100u, h = 850u)))
        // an upright photo's crop is upright too
        assertEquals("9 × 16", aspectName(CropFrame(emptyList(), listOf(0.1, 0.0, 0.9, 1.0), 0.0, 9u, 16u, 3000.0, 4000.0)))
        assertEquals("Custom", aspectName(crop(listOf(0.0, 0.0, 0.5, 1.0), w = 13u, h = 7u)))
    }

    @Test
    fun infoWritesSizesAndKeywordsAsTheReferenceDoes() {
        assertEquals("24 MP", megapixels(6000, 4000))
        assertEquals("12.2 MP", megapixels(4032, 3024))
        assertEquals("860 KB", fileSizeText(860_000))
        assertEquals("2.4 MB", fileSizeText(2_400_000))
        assertEquals("", fileSizeText(0))
        assertEquals("1/250 s", shutterText("1/250"))
        assertEquals("30 s", shutterText("30 s"))
        // comma separated, trimmed, no repeats (whatever their case)
        assertEquals(listOf("sea", "Dusk", "rocks"), withKeywords(listOf("sea"), " Dusk, rocks , ,SEA"))
    }

    @Test
    fun theDialNeverWritesMinusZero() {
        assertEquals("0.00°", dialLabel(-0.002))
        assertEquals("0.00°", dialLabel(0.0))
        assertEquals("2.50°", dialLabel(2.5))
        assertEquals("-1.39°", dialLabel(-1.39))
    }

    @Test
    fun borderMarginsMatchTheEnginesExport() {
        fun px(m: DoubleArray) = m.map { Math.round(it * 1000) / 1000.0 }
        // even: half the thickness of the long edge on every side (400 × 300, 10 %: 20 px)
        assertEquals(listOf(20.0, 20.0, 20.0, 20.0), px(borderMargins(400.0, 300.0, 10, "even")))
        // the photo's own shape grows by the thickness: 440 × 330
        assertEquals(listOf(20.0, 20.0, 15.0, 15.0), px(borderMargins(400.0, 300.0, 10, "original")))
        // 1:1 with no thickness pads the short side only; at 10 % the square is 440 on a side
        assertEquals(listOf(0.0, 0.0, 50.0, 50.0), px(borderMargins(400.0, 300.0, 0, "1:1")))
        assertEquals(listOf(20.0, 20.0, 70.0, 70.0), px(borderMargins(400.0, 300.0, 10, "1:1")))
    }

    /** The luminance picker's lightness and the engine's range scale map back and forth as the engine's (`masks::range_from_lightness`). */
    @Test
    fun luminanceLightnessMapsToTheEnginesScale() {
        for (l in listOf(0.05, 0.3, 0.46, 0.66, 0.9, 0.99)) assertEquals(l, lightnessFromRange(rangeFromLightness(l)), 1e-9)
        // middle grey (L* 49.5) sits at 8/12 of the scale; white is its top, black its foot
        assertEquals(8.0 / 12.0, rangeFromLightness((116.0 * Math.cbrt(0.18) - 16.0) / 100.0), 1e-6)
        assertEquals(1.0, rangeFromLightness(1.0), 0.0)
        assertEquals(1.0, lightnessFromRange(1.0), 0.0)
        assertEquals(0.0, rangeFromLightness(0.0), 0.0)
        assertEquals(0.0, lightnessFromRange(0.0), 0.0)
    }
}
