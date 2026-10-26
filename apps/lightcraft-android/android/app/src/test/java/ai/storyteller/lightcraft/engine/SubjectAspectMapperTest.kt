package ai.storyteller.lightcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectAspectMapperTest {
    @Test fun portraitLetterboxDoesNotStretchSubject() {
        val f = SubjectAspectMapper.fit(2000, 4000, 512)
        assertEquals(256, f.width)
        assertEquals(512, f.height)
        assertEquals(128, f.left)
        assertEquals(0, f.top)
    }
    @Test fun landscapeLetterboxKeepsFullWidth() {
        val f = SubjectAspectMapper.fit(4000, 2000, 512)
        assertEquals(512, f.width)
        assertEquals(256, f.height)
        assertEquals(0, f.left)
        assertEquals(128, f.top)
    }
    @Test fun squarePreservesAllCoordinates() {
        val f = SubjectAspectMapper.fit(3000, 3000, 512)
        val m = SubjectAspectMapper.SampleMap(8, f, 512)
        val plane = Array(8) { y -> FloatArray(8) { x -> (y * 8 + x).toFloat() } }
        for (y in 0..7) for (x in 0..7) {
            assertEquals(plane[y][x], m.at(plane, x, y), 0.0001f)
        }
    }
    @Test fun portraitSamplesRealImageNotGreyPadding() {
        val f = SubjectAspectMapper.fit(1000, 2000, 512)
        val m = SubjectAspectMapper.SampleMap(8, f, 512)
        // The portrait is contained in source grid columns 2..5. Those are
        // spread across the full destination mask grid without pad readings.
        val plane = Array(8) { _ ->
            FloatArray(8) { x -> if (x < 2 || x > 5) -10f else 4f }
        }
        for (y in 0..7) for (x in 0..7) {
            assertTrue("padding must not enter subject mask", m.at(plane, x, y) > 3.0f)
        }
    }
    @Test fun nearSquarePhotosHaveNoLargePadding() {
        val f = SubjectAspectMapper.fit(4000, 3000, 512)
        assertTrue(f.height > 380)
        assertTrue(f.top in 0..64)
    }
}