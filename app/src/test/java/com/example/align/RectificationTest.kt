package com.example.align

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * The Pixel 9 test capture had up to ~25 px of vertical misalignment that no similarity can
 * remove (distortion-correction residue), which made walls look broken in the wiggle. These
 * tests render an ultra wide with exactly such an error plus a near subject with parallax.
 */
class RectificationTest {

    private val main = SyntheticScene.Camera(1024, 768, footprint = 1.25, centerX = 1600.0, centerY = 1200.0)
    private val uw = SyntheticScene.Camera(
        2000, 1500, footprint = 1.3, rotationDegrees = 0.3, centerX = 1615.0, centerY = 1190.0,
        verticalWarp = 60.0
    )

    @Test
    fun removesVerticalErrorButKeepsParallax() {
        val scene = SyntheticScene()
        scene.foregroundRadius = 160.0
        val uwNear = uw.copy(foregroundShift = 14.0)
        val p = scene.render(main, SyntheticScene.Photometric(noise = 1.5, seed = 2))
        val s = scene.render(uwNear, SyntheticScene.Photometric(gain = 0.85, offset = 10.0, gamma = 1.1, noise = 2.0, seed = 3))

        val result = LensAligner.align(
            p, s, Similarity.centered(0.96, 0.0, 1024.0, 768.0, 2000.0, 1500.0),
            LensAligner.Options(priorScaleTolerance = 0.07, priorTranslationFraction = 0.12, rectify = true)
        )!!
        val rect = result.rectification
        assertNotNull("rectification must succeed", rect)
        rect!!
        println("rectification: $rect")

        // Vertical error against the ground truth on background points, without / with field.
        var worstBefore = 0.0
        var worstAfter = 0.0
        for (i in 1..9) for (j in 1..9) {
            val x = 1024.0 * i / 10
            val y = 768.0 * j / 10
            val dx = x - 512
            val dy = y - 384
            // skip the near subject (radius 160 world px = 128 primary px)
            if (dx * dx + dy * dy < 150.0 * 150.0) continue
            val (tx, ty) = scene.trueMapping(main, uwNear, x, y)
            worstBefore = max(worstBefore, abs(result.global.mapY(x, y) - ty))
            worstAfter = max(worstAfter, abs(rect.global.mapY(x, y) - ty))
        }
        println("vertical error before %.2f px, after %.2f px".format(worstBefore, worstAfter))
        assertTrue("there must be something to correct", worstBefore > 3.0)
        assertTrue("vertical error after rectification $worstAfter px", worstAfter < 0.5)

        // The pivot stays on the near subject along the baseline (horizontal parallax kept).
        val (sx, _) = scene.trueMapping(main, uwNear, 512.0, 384.0)
        val expected = sx + 14.0 / 1.3
        val actual = rect.pivoted.mapX(512.0, 384.0)
        assertTrue("pivot x $actual vs expected $expected", abs(actual - expected) < 0.6)
    }

    @Test
    fun noErrorMeansNoCorrection() {
        val scene = SyntheticScene()
        val p = scene.render(main)
        val s = scene.render(uw.copy(verticalWarp = 0.0))
        val result = LensAligner.align(
            p, s, Similarity.centered(0.96, 0.0, 1024.0, 768.0, 2000.0, 1500.0),
            LensAligner.Options(rectify = true)
        )!!
        val maxOffset = result.rectification?.global?.maxOffset() ?: 0.0
        println("clean pair correction: ${result.rectification}")
        assertTrue("a clean pair must not be bent: $maxOffset px", maxOffset < 0.4)
    }

    @Test
    fun warpFieldInverseIsExact() {
        val base = Similarity.of(0.5, 0.2, 300.0, 400.0)
        val field = WarpField(base, 0.01, -0.99995, DoubleArray(WarpField.TERMS) { (it - 4) * 3.0 }, 1440.0, 1920.0)
        for ((x, y) in listOf(0.0 to 0.0, 700.0 to 900.0, 1440.0 to 1920.0, 100.0 to 1800.0)) {
            val sx = field.mapX(x, y)
            val sy = field.mapY(x, y)
            val (ix, iy) = field.inverse(sx, sy)
            assertTrue(abs(ix - x) < 0.01 && abs(iy - y) < 0.01)
        }
    }
}
