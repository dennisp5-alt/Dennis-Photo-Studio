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
}
