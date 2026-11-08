package com.example.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Depth map, focus detection, lens blur and in-between views on a synthetic pair: a near
 * textured disc in front of a far background, both frames in the same geometry (as after
 * the alignment), the disc shifted by a known parallax.
 */
class DepthEffectsTest {

    private val w = 384
    private val h = 512
    private val shift = 12 // disc parallax in px

    private fun scene(dx: Int, blurBackground: Boolean): GrayImage {
        val scene = SyntheticScene(seed = 5, width = 900, height = 1100)
        val cam = SyntheticScene.Camera(w, h, footprint = 1.0, centerX = 450.0, centerY = 550.0)
        val bg = scene.render(cam)
        val img = if (blurBackground) bg.gaussianBlur(2.5) else bg
        val out = img.copy()
        val cx = w / 2 + dx
        val cy = h / 2
        val r = 90
        for (y in 0 until h) for (x in 0 until w) {
            val ddx = x - cx
            val ddy = y - cy
            if (ddx * ddx + ddy * ddy < r * r) {
                // Texture moving with the disc.
                val u = x - dx
                out.data[y * w + x] = if (((u / 7) + (y / 9)) % 2 == 0) 60f else 190f
            }
        }
        return out
    }

    private val primary = scene(0, blurBackground = true)   // main lens: background out of focus
    private val secondary = scene(shift, blurBackground = false) // ultra wide: all sharp

    @Test
    fun disparityFindsDiscAndBackground() {
        val maps = StereoMatcher.matchPair(primary, secondary, 30)
        val disc = maps.primary.median(0.42, 0.58, 0.42, 0.58, 0f)!!
        val bg = maps.primary.median(0.0, 0.2, 0.0, 0.2, 0f)!!
        println("disc $disc background $bg reliable ${maps.primary.reliableFraction()}")
        assertEquals(shift.toDouble(), disc, 1.0)
        assertEquals(0.0, bg, 1.0)
        // The secondary map describes the same depths.
        assertEquals(shift.toDouble(), maps.secondary.median(0.45, 0.62, 0.42, 0.58, 0f)!!, 1.0)
    }

    @Test
    fun focusIsOnTheSharpDisc() {
        val maps = StereoMatcher.matchPair(primary, secondary, 30)
        val focus = FocusFinder.focusDisparity(primary, secondary, maps.primary)
        println("focus $focus")
        assertNotNull(focus)
        assertTrue("focus $focus", abs(focus!! - shift) < 3)
    }

    @Test
    fun lensBlurSoftensOnlyTheBackground() {
        val maps = StereoMatcher.matchPair(primary, secondary, 30)
        val argb = IntArray(w * h) { val v = secondary.data[it].toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        // Large aperture so the 12 px parallax gives a clearly visible blur (~12 px).
        val (out, stats) = LensBlur.apply(argb, w, h, maps.secondary, 1.0, shift.toDouble(), 1.0, 0.0)!!
        println("max blur ${stats.maxDiameter}")
        fun detail(img: IntArray, x0: Int, y0: Int): Double {
            var sum = 0.0
            for (y in y0 until y0 + 40) for (x in x0 until x0 + 40) {
                val d = (img[y * w + x] and 0xFF) - (img[y * w + x + 1] and 0xFF)
                sum += d * d
            }
            return sum
        }
        val bgBefore = detail(argb, 20, 20)
        val bgAfter = detail(out, 20, 20)
        val discBefore = detail(argb, w / 2 + shift - 20, h / 2 - 20)
        val discAfter = detail(out, w / 2 + shift - 20, h / 2 - 20)
        println("background detail $bgBefore -> $bgAfter, disc $discBefore -> $discAfter")
        assertTrue(bgAfter < bgBefore * 0.7)
        assertTrue(discAfter > discBefore * 0.9)
    }

    @Test
    fun inBetweenViewMovesTheDiscHalfway() {
        val maps = StereoMatcher.matchPair(primary, secondary, 30)
        fun argb(g: GrayImage) = IntArray(w * h) { val v = g.data[it].toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val occP = ViewSynthesis.occlusion(maps.primary, maps.secondary, 1.0)
        val occS = ViewSynthesis.occlusion(maps.secondary, maps.primary, -1.0)
        val t = 0.25
        val view = ViewSynthesis.render(t, argb(primary), argb(secondary), w, h, maps.primary, maps.secondary, occP, occS, 1.0)
        // The disc texture must sit t·shift to the right of its place in the main frame.
        val y = h / 2
        var bestOffset = -1.0
        var bestScore = Double.MAX_VALUE
        for (k in 0..4 * shift) {
            val off = k / 4.0
            var score = 0.0
            for (x in w / 2 - 50 until w / 2 + 50) {
                val u = Math.floorDiv((x - off).toInt(), 1)
                val expectedValue = if (((u / 7) + (y / 9)) % 2 == 0) 60 else 190
                score += abs((view[y * w + x] and 0xFF) - expectedValue)
            }
            if (score < bestScore) {
                bestScore = score
                bestOffset = off
            }
        }
        val expected = t * shift
        val edge = bestOffset
        println("disc offset $edge expected $expected")
        assertEquals(expected, edge, 1.0)
    }
}
