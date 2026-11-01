package ai.storyteller.lightcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure regression tests: no model, photo library or network required. */
class SemanticMaskRefinementTest {
    @Test fun centralAnimalBeatsSmallBackgroundPerson() {
        val counts = IntArray(150)
        val middle = IntArray(150)
        counts[12] = 180; middle[12] = 25
        counts[126] = 2300; middle[126] = 1700
        assertEquals(126, SemanticMaskRefinement.selectSubject(counts, middle, 128, 12))
    }

    @Test fun realHumanPortraitKeepsPersonWhenAnAnimalAlsoExists() {
        val counts = IntArray(150)
        val middle = IntArray(150)
        counts[12] = 2800; middle[12] = 1500
        counts[126] = 500; middle[126] = 180
        assertEquals(12, SemanticMaskRefinement.selectSubject(counts, middle, 128, 126))
    }

    @Test fun noRecognisedForegroundKeepsLegacyDecision() {
        val counts = IntArray(150)
        val middle = IntArray(150)
        counts[126] = 3; middle[126] = 2
        assertEquals(65, SemanticMaskRefinement.selectSubject(counts, middle, 128, 65))
    }

    @Test fun skyKeepsMainSkyButDropsIsolatedWaterReflections() {
        val side = 32
        val values = FloatArray(side * side) { -5f }
        for (y in 0..11) for (x in 0 until side) values[y * side + x] = 5f
        for (y in 26..29) for (x in 18..20) values[y * side + x] = 4f
        val result = SemanticMaskRefinement.retainSkyRegions(values, side)
        assertTrue(result[4 * side + 10] > 0f)
        assertTrue(result[27 * side + 19] < 0f)
    }

    @Test fun imageGuidancePlacesBoundaryAcrossColorTransition() {
        val model = 8
        val pixelSide = 32
        val margin = FloatArray(model * model) { i ->
            if (i % model < model / 2) 6f else -6f
        }
        val photo = IntArray(pixelSide * pixelSide) { i ->
            if (i % pixelSide < pixelSide / 2) 0xFF214A24.toInt() else 0xFFDAA768.toInt()
        }
        val result = SemanticMaskRefinement.refine(margin, model, photo, pixelSide, 32)
        assertTrue("green region remains selected", result[16 * 32 + 14].toInt() > 0)
        assertTrue("tan region stays excluded", result[16 * 32 + 17].toInt() < 0)
    }

    @Test fun softRegionHasNoTextureDrivenHoles() {
        val side = 8
        val model = FloatArray(side * side) { 3.25f }
        val px = IntArray(32 * 32) { i ->
            if ((i + i / 32) % 2 == 0) 0xFFEEEEEE.toInt() else 0xFF222222.toInt()
        }
        val data = SemanticMaskRefinement.refine(model, side, px, 32, 32)
        assertTrue(data.all { it.toInt() > 0 })
    }

    @Test fun solidBackgroundCannotBecomeForegroundFromRefinement() {
        val input = FloatArray(8 * 8) { -4f }
        val px = IntArray(32 * 32) { 0xFF808080.toInt() }
        assertTrue(SemanticMaskRefinement.refine(input, 8, px, 32, 32).all { it.toInt() < 0 })
    }

    @Test fun existingSkyRefinementIsUnchanged() {
        val n=8
        val model=FloatArray(n*n){ i -> if(i/n<4)3.5f else -3.5f }
        val pixels=IntArray(32*32){ i -> if(i/32<16) 0xFFACD3F1.toInt() else 0xFF536A42.toInt() }
        val before=SemanticMaskRefinement.refine(model,n,pixels,32,32)
        val after=SemanticMaskRefinement.refine(model,n,pixels,32,32,subjectBoundary=false)
        assertTrue("Sky path must stay identical",before.contentEquals(after))
    }

    @Test fun subjectRefinementRespectsContrastingBackground() {
        val model=8
        val pxSide=32
        val margin=FloatArray(model*model){i-> if(i%model<4) 2.4f else -2.4f }
        val pixels=IntArray(pxSide*pxSide){i->
            if(i%pxSide<pxSide/2) 0xFFAB7557.toInt() else 0xFF7CC7FA.toInt()
        }
        val subject=SemanticMaskRefinement.refine(margin,model,pixels,pxSide,32,subjectBoundary=true)
        assertTrue("centre of subject must remain selected",subject[16*32+8].toInt()>0)
        assertTrue("background must remain excluded",subject[16*32+23].toInt()<0)
    }

    @Test fun subjectRefinementDoesNotPunchHolesInHairTexture() {
        val model=8
        val margin=FloatArray(model*model) { 4f }
        val px=IntArray(32*32){ i ->
            if((i+i/32)%4==0) 0xFF191A1C.toInt() else 0xFF48424B.toInt()
        }
        val result=SemanticMaskRefinement.refine(margin,model,px,32,32,subjectBoundary=true)
        assertTrue(result.all{it.toInt()>0})
    }

    @Test fun mattingInputKeepsProportionsAndModelStride() {
        val (w, h) = SemanticMaskRefinement.mattingInputSize(1536, 2048)
        assertEquals(512, w)
        assertEquals(672, h)
        val (lw, lh) = SemanticMaskRefinement.mattingInputSize(4000, 3000)
        assertEquals(672, lw)
        assertEquals(512, lh)
    }

    @Test fun backgroundIsTheExactComplementOfSubject() {
        val mw = 40
        val mh = 30
        val alpha = FloatArray(mw * mh) { i -> (i % mw) / (mw - 1f) }
        val subject = SemanticMaskRefinement.matteToLogits(alpha, mw, mh, null, 0, 64, invert = false)
        val background = SemanticMaskRefinement.matteToLogits(alpha, mw, mh, null, 0, 64, invert = true)
        for (i in subject.indices) assertEquals(-subject[i].toInt(), background[i].toInt())
        assertTrue("left edge not selected", subject[32 * 64 + 1] < 0)
        assertTrue("right edge selected", subject[32 * 64 + 62] > 0)
    }

    @Test fun matteFarFromAnyDetectedPersonIsDropped() {
        val side = 32
        // Scene model: a person only in the left third.
        val margins = FloatArray(side * side) { i -> if (i % side < side / 3) 6f else -8f }
        val support = SemanticMaskRefinement.personSupport(margins, side)
        assertTrue(support[16 * side + 4] > 0.99f)
        assertTrue(support[16 * side + 30] < 0.01f)
        // Matte says "person" everywhere (e.g. a poster on the right).
        val alpha = FloatArray(64 * 64) { 1f }
        val mask = SemanticMaskRefinement.matteToLogits(alpha, 64, 64, support, side, 64)
        assertTrue("person kept", mask[32 * 64 + 8] > 100)
        assertTrue("far poster dropped", mask[32 * 64 + 62] < -100)
    }

    @Test fun scatteredMatteOnCrowdIsRejectedButPortraitMatteAccepted() {
        val side = 32
        // Scene model: a prominent person in the middle third.
        val margins = FloatArray(side * side) { i -> if (i % side in 10..21) 5f else -6f }
        val good = FloatArray(64 * 64) { i -> if (i % 64 in 20..43) 1f else 0f }
        val scattered = FloatArray(64 * 64) { i -> if (i % 64 in 20..24) 1f else 0f }
        assertTrue(SemanticMaskRefinement.matteAgrees(good, 64, 64, margins, side))
        assertTrue(!SemanticMaskRefinement.matteAgrees(scattered, 64, 64, margins, side))
    }

    @Test fun flatSaliencyIsNotStretchedIntoAFakeSelection() {
        assertTrue(SemanticMaskRefinement.normaliseSaliency(FloatArray(100) { 0.2f + 0.001f * it }) == null)
        val n = SemanticMaskRefinement.normaliseSaliency(FloatArray(100) { if (it < 50) 0.1f else 0.9f })!!
        assertEquals(0f, n[0], 1e-6f)
        assertEquals(1f, n[99], 1e-6f)
    }

    @Test fun salientFlowerWithNoSceneClassIsUsed() {
        // A protea: no scene-model object, a compact salient blob in the middle.
        val s = 64
        val sal = FloatArray(s * s) { i -> val x = i % s; val y = i / s
            if ((x - 32) * (x - 32) + (y - 34) * (y - 34) < 18 * 18) 0.95f else 0.02f }
        assertTrue(SemanticMaskRefinement.saliencyUsable(sal, s, null, 32))
    }

    @Test fun daisyCentresInsideALargeFlowerObjectDeferToTheSceneModel() {
        val s = 64
        val side = 32
        // Scene model: "flower" over most of the frame.
        val flower = FloatArray(side * side) { i -> if (i / side in 4..29) 4f else -5f }
        // Saliency: only three small yellow centres, all inside the flowers.
        val centres = listOf(20 to 20, 44 to 30, 30 to 48)
        val sal = FloatArray(s * s) { i -> val x = i % s; val y = i / s
            if (centres.any { (cx, cy) -> (x - cx) * (x - cx) + (y - cy) * (y - cy) < 16 }) 1f else 0f }
        assertTrue(!SemanticMaskRefinement.saliencyUsable(sal, s, flower, side))
        // The same scene object, but the salient region is most of it: use saliency.
        val whole = FloatArray(s * s) { i -> if (i / s in 12..54 && i % s in 6..58) 0.9f else 0.05f }
        assertTrue(SemanticMaskRefinement.saliencyUsable(whole, s, flower, side))
    }

    @Test fun saliencyCoveringEverythingOrNothingIsRejected() {
        val s = 64
        assertTrue(!SemanticMaskRefinement.saliencyUsable(FloatArray(s * s) { 0.95f }, s, null, 32))
        assertTrue(!SemanticMaskRefinement.saliencyUsable(FloatArray(s * s) { if (it == 5) 1f else 0f }, s, null, 32))
    }

    @Test fun probabilitiesBecomeBoundedMargins() {
        val m = SemanticMaskRefinement.probabilityToMargins(floatArrayOf(0f, 0.5f, 1f))
        assertEquals(-12f, m[0], 1e-6f)
        assertEquals(0f, m[1], 1e-5f)
        assertEquals(12f, m[2], 1e-6f)
    }
}
