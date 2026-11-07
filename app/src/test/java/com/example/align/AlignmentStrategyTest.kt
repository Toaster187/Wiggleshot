package com.example.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Regression tests for the failure seen on the Pixel 9: a starting value 16 % off in scale
 * with the optical centre a quarter frame off (vertical offset in portrait). The strategy
 * must still find the true transform instead of falling back to the bad prior.
 */
class AlignmentStrategyTest {

    companion object {
        private val MAIN = SyntheticScene.Camera(1024, 768, footprint = 1.25, centerX = 1600.0, centerY = 1200.0)
        private val UW = SyntheticScene.Camera(
            2000, 1500, footprint = 1.3, rotationDegrees = 0.35, centerX = 1621.0, centerY = 1187.0
        )
        private lateinit var scene: SyntheticScene
        private lateinit var main: GrayImage
        private lateinit var uw: GrayImage

        @BeforeClass
        @JvmStatic
        fun render() {
            scene = SyntheticScene()
            main = scene.render(MAIN, SyntheticScene.Photometric(noise = 1.5, seed = 2))
            uw = scene.render(UW, SyntheticScene.Photometric(gain = 0.8, offset = 12.0, gamma = 1.15, vignetting = 0.3, noise = 2.5, seed = 3))
        }
    }

    private val truth get() = scene.trueTransform(MAIN, UW)
    private fun error(t: Similarity) = t.maxCornerDeviation(truth, MAIN.width.toDouble(), MAIN.height.toDouble())

    /** What the Pixel 9 metadata produced: 16 % too little scale, centre 25 % of the height off. */
    private val badPrior: Similarity
        get() = Similarity.centered(truth.scale / 1.16, 0.0, 1024.0, 768.0, 2000.0, 1500.0)
            .let { it.copy(ty = it.ty + 0.25 * 1500) }

    @Test
    fun wrongCalibrationAndHintStillFindTheTruth() {
        val outcome = AlignmentStrategy.run(main, uw, calibration = badPrior, scaleHint = badPrior.scale)
        println("bad prior: path=${outcome.path} attempts=${outcome.attempts} result=${outcome.result}")
        assertTrue(outcome.accepted)
        assertTrue("error ${error(outcome.result!!.global)}", error(outcome.result!!.global) < 0.1)
        assertEquals(AlignmentStrategy.Path.WIDE_HINT, outcome.path)
    }

    @Test
    fun noPriorAtAllStillWorks() {
        val outcome = AlignmentStrategy.run(main, uw, calibration = null, scaleHint = null, freeRange = 0.3..3.0)
        println("no prior: path=${outcome.path} attempts=${outcome.attempts}")
        assertTrue(outcome.accepted)
        assertTrue("error ${error(outcome.result!!.global)}", error(outcome.result!!.global) < 0.1)
    }

    @Test
    fun goodCalibrationTakesTheFastPath() {
        val outcome = AlignmentStrategy.run(main, uw, calibration = truth.copy(tx = truth.tx + 4), scaleHint = null)
        assertEquals(AlignmentStrategy.Path.CALIBRATION, outcome.path)
        assertEquals(1, outcome.attempts.size)
        assertTrue(error(outcome.result!!.global) < 0.1)
    }

    @Test
    fun textureLessSceneIsRejected() {
        val flat = GrayImage(1024, 768, FloatArray(1024 * 768) { 120f + (it % 7) * 0.2f })
        val flatUw = GrayImage(2000, 1500, FloatArray(2000 * 1500) { 118f + (it % 5) * 0.2f })
        val outcome = AlignmentStrategy.run(flat, flatUw, calibration = null, scaleHint = 1.0)
        assertTrue("must not accept a match on a blank wall", !outcome.accepted)
    }
}
