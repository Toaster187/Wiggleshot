package com.example.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Accuracy tests of the lens alignment on synthetic main / ultra wide pairs with known
 * geometry. Errors are measured as the largest displacement of the frame corners in primary
 * working pixels (1024 px wide); 0.1 px here is about 0.4 px in a 4K frame.
 */
class LensAlignerTest {

    companion object {
        private lateinit var scene: SyntheticScene

        /** "Main lens": 1024 x 768, 1.25 world px per pixel. */
        private val MAIN = SyntheticScene.Camera(
            width = 1024, height = 768, footprint = 1.25, centerX = 1600.0, centerY = 1200.0
        )

        /**
         * "Ultra wide": twice the field of view, slightly rotated and with its optical axis
         * off-centre — the situation of the Pixel 9's 0.5x module relative to the main one.
         * Its working image is 1000 x 750, so the true scale is 1.25 / 2.6 = 0.48.
         */
        private val ULTRA_WIDE = SyntheticScene.Camera(
            width = 1000, height = 750, footprint = 2.6, rotationDegrees = 0.35,
            centerX = 1600.0 + 21.0, centerY = 1200.0 - 13.0
        )

        private val UW_PHOTOMETRY = SyntheticScene.Photometric(
            gain = 0.82, offset = 14.0, gamma = 1.15, vignetting = 0.3, noise = 2.5, seed = 3
        )

        private lateinit var main: GrayImage
        private lateinit var ultraWide: GrayImage

        @BeforeClass
        @JvmStatic
        fun render() {
            scene = SyntheticScene()
            main = scene.render(MAIN, SyntheticScene.Photometric(noise = 1.5, seed = 2))
            ultraWide = scene.render(ULTRA_WIDE, UW_PHOTOMETRY)
        }
    }

    private val truth get() = scene.trueTransform(MAIN, ULTRA_WIDE)

    private fun cornerError(estimate: Similarity) =
        estimate.maxCornerDeviation(truth, MAIN.width.toDouble(), MAIN.height.toDouble())

    @Test
    fun recoversUltraWideGeometryFromRoughPrior() {
        // A metadata prior: scale 3 % off, no rotation, optical centres assumed centred.
        val prior = Similarity.centered(
            scale = truth.scale * 1.03,
            rotationDegrees = 0.0,
            primaryWidth = MAIN.width.toDouble(),
            primaryHeight = MAIN.height.toDouble(),
            secondaryWidth = ULTRA_WIDE.width.toDouble(),
            secondaryHeight = ULTRA_WIDE.height.toDouble()
        )
        val result = LensAligner.align(main, ultraWide, prior)
        assertNotNull(result)
        result!!
        println("with prior: $result, truth=$truth, error=${cornerError(result.global)}")

        assertTrue("corner error ${cornerError(result.global)} px", cornerError(result.global) < 0.1)
        assertEquals(truth.scale, result.global.scale, truth.scale * 0.001)
        assertEquals(truth.rotationDegrees, result.global.rotationDegrees, 0.04)
        assertTrue("confidence ${result.confidence}", result.confidence > 0.5)
    }

    @Test
    fun toleratesOffCentreMetadataPrior() {
        // Metadata prior whose optical centre is 5 % of the frame width off (another principal
        // point convention), searched with the wider window the app uses for metadata priors.
        val prior = truth.copy(tx = truth.tx + 50.0, ty = truth.ty - 25.0).let {
            Similarity(it.a * 1.04, 0.0, it.tx, it.ty)
        }
        val result = LensAligner.align(
            main, ultraWide, prior,
            LensAligner.Options(priorScaleTolerance = 0.07, priorTranslationFraction = 0.12)
        )!!
        println("off-centre prior: $result, error=${cornerError(result.global)}")
        assertTrue("corner error ${cornerError(result.global)} px", cornerError(result.global) < 0.1)
    }

    @Test
    fun findsUltraWideWithoutAnyPrior() {
        val result = LensAligner.align(
            main, ultraWide, prior = null,
            options = LensAligner.Options(minScale = 0.25, maxScale = 1.1)
        )
        assertNotNull(result)
        result!!
        println("no prior: $result, error=${cornerError(result.global)}")
        assertTrue("corner error ${cornerError(result.global)} px", cornerError(result.global) < 0.1)
    }

    @Test
    fun densityMatchedInputIsSubPixelAccurate() {
        // This is how the app feeds the aligner: the ultra wide working image is kept at
        // twice the resolution so one working pixel covers the same angle in both frames.
        val uwCamera = ULTRA_WIDE.copy(width = 2000, height = 1500, footprint = 1.3)
        val uwImage = scene.render(uwCamera, UW_PHOTOMETRY)
        val truthDense = scene.trueTransform(MAIN, uwCamera)
        val prior = Similarity.centered(
            truthDense.scale * 0.97, 0.0,
            MAIN.width.toDouble(), MAIN.height.toDouble(),
            uwCamera.width.toDouble(), uwCamera.height.toDouble()
        )
        val result = LensAligner.align(main, uwImage, prior)!!
        val error = result.global.maxCornerDeviation(truthDense, MAIN.width.toDouble(), MAIN.height.toDouble())
        println("dense: $result, truth=$truthDense, error=$error")
        assertTrue("corner error $error px", error < 0.1)
    }

    @Test
    fun resultIsDeterministic() {
        val prior = Similarity.centered(
            truth.scale, 0.0,
            MAIN.width.toDouble(), MAIN.height.toDouble(),
            ULTRA_WIDE.width.toDouble(), ULTRA_WIDE.height.toDouble()
        )
        val first = LensAligner.align(main, ultraWide, prior)!!.pivoted
        val second = LensAligner.align(main, ultraWide, prior)!!.pivoted
        assertEquals(first, second)
    }

    @Test
    fun pivotLocksOntoNearSubjectWhileGlobalFitIgnoresIt() {
        // Near subject in the centre: the ultra wide sees it 12 world px further right
        // (parallax), the background is unchanged.
        val parallaxScene = SyntheticScene()
        parallaxScene.foregroundRadius = 230.0
        val shift = 12.0
        val uw = ULTRA_WIDE.copy(foregroundShift = shift)
        val mainImage = parallaxScene.render(MAIN, SyntheticScene.Photometric(noise = 1.5, seed = 2))
        val uwImage = parallaxScene.render(uw, UW_PHOTOMETRY)
        val trueBackground = parallaxScene.trueTransform(MAIN, uw)

        val result = LensAligner.align(mainImage, uwImage, prior = trueBackground.copy(tx = trueBackground.tx + 3))!!
        println("parallax: $result")

        val globalError = result.global.maxCornerDeviation(trueBackground, MAIN.width.toDouble(), MAIN.height.toDouble())
        assertTrue("global fit must follow the background, error $globalError px", globalError < 1.0)

        // At the subject the ultra wide pixel is shifted by shift / footprint, rotated by the
        // module roll.
        val cx = MAIN.width / 2.0
        val cy = MAIN.height / 2.0
        val r = Math.toRadians(-ULTRA_WIDE.rotationDegrees)
        val expectedX = trueBackground.mapX(cx, cy) + shift / ULTRA_WIDE.footprint * kotlin.math.cos(r)
        val expectedY = trueBackground.mapY(cx, cy) + shift / ULTRA_WIDE.footprint * kotlin.math.sin(r)
        val pivotError = hypot(result.pivoted.mapX(cx, cy) - expectedX, result.pivoted.mapY(cx, cy) - expectedY)
        assertTrue("pivot error $pivotError secondary px", pivotError < 0.4)
        assertTrue(abs(result.pivotShift) > 5.0)
    }

    @Test
    fun similarityAlgebraIsConsistent() {
        val t = Similarity.of(0.5, 0.3, 12.0, -7.0)
        val roundTrip = t.inverse().compose(t)
        assertEquals(1.0, roundTrip.a, 1e-12)
        assertEquals(0.0, roundTrip.b, 1e-12)
        assertEquals(0.0, roundTrip.tx, 1e-9)
        assertEquals(0.0, roundTrip.ty, 1e-9)

        // Re-expressing for 4x / 2x downsampled grids and back is lossless.
        val working = t.reexpress(primaryScale = 4.0, secondaryScale = 2.0)
        val back = working.reexpress(primaryScale = 0.25, secondaryScale = 0.5)
        assertEquals(t.a, back.a, 1e-12)
        assertEquals(t.tx, back.tx, 1e-9)
        assertEquals(t.mapX(400.0, 300.0) / 2.0, working.mapX(100.0, 75.0), 1e-9)
    }
}
