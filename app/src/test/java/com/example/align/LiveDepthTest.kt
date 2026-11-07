package com.example.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live measurement on preview sized frames, full and fast path. */
class LiveDepthTest {

    private val main = SyntheticScene.Camera(480, 640, footprint = 2.0, centerX = 1600.0, centerY = 1200.0)
    private val uw = SyntheticScene.Camera(1080, 1440, footprint = 1.62, centerX = 1612.0, centerY = 1195.0)

    private fun frames(shift: Double): Pair<GrayImage, GrayImage> {
        val scene = SyntheticScene()
        scene.foregroundRadius = 420.0
        val p = scene.render(main, SyntheticScene.Photometric(noise = 1.5, seed = 2))
        val s = scene.render(uw.copy(foregroundShift = shift), SyntheticScene.Photometric(gain = 0.9, noise = 2.0, seed = 3))
        return p to s
    }

    @Test
    fun nearSubjectGivesDepthAndFastPathAgrees() {
        val (p, s) = frames(shift = 24.0)
        val full = LiveDepth.measure(p, s, previous = null, scaleHint = 1.2, baselineX = 1.0, baselineY = 0.0)
        assertNotNull(full)
        full!!
        println("full: depth=${full.depthPercent} conf=${full.confidence} patches=${full.patches} ${full.elapsedMs} ms")
        assertTrue(full.accepted)
        // 24 world px foreground shift = 12 primary px of 480 -> 2.5 % of the width.
        val depth = full.depthPercent!!
        assertEquals(2.5, depth, 0.4)

        val fast = LiveDepth.measureFast(p, s, full.transform, 1.0, 0.0)!!
        println("fast: depth=${fast.depthPercent} conf=${fast.confidence} ${fast.elapsedMs} ms")
        assertEquals(depth, fast.depthPercent!!, 0.1)
    }

    @Test
    fun flatSceneHasNoDepth() {
        val (p, s) = frames(shift = 0.0)
        val full = LiveDepth.measure(p, s, previous = null, scaleHint = 1.2, baselineX = 1.0, baselineY = 0.0)!!
        println("flat: depth=${full.depthPercent}")
        assertTrue("depth ${full.depthPercent}", full.depthPercent!! < 0.3)
    }
}
