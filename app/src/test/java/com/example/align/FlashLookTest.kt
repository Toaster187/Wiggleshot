package com.example.align

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashLookTest {

    private val w = 200
    private val h = 160

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun lum(p: Int) = ((p shr 16) and 0xFF) * 0.3 + ((p shr 8) and 0xFF) * 0.59 + (p and 0xFF) * 0.11

    @Test
    fun flashLightsTheSubjectAndDropsTheBackground() {
        // Left half: subject at the focus disparity; right half: background 30 px "farther".
        val mw = 50
        val mh = 40
        val map = DisparityMap(mw, mh, FloatArray(mw * mh) { if (it % mw < mw / 2) 0f else -30f }, FloatArray(mw * mh) { 1f })
        val px = IntArray(w * h) { gray(120) }
        FlashLook.falloff(px, w, h, map, 4.0, focus = 0.0, focusDiopters = 2.5)
        val subject = lum(px[h / 2 * w + 20])
        val background = lum(px[h / 2 * w + w - 20])
        println("subject $subject background $background")
        assertTrue("subject brighter than before: $subject", subject > 125)
        assertTrue("background darker: $background", background < 90)
    }

    @Test
    fun styleCrushesBlacksWarmsAndVariesGrain() {
        val px = IntArray(w * h) { i -> if (i % w < w / 2) gray(18) else gray(170) }
        val a = FlashLook.style(px, w, h, seed = 1)
        val b = FlashLook.style(px, w, h, seed = 2)
        val dark = a[h / 2 * w + 30]
        val mid = a[h / 2 * w + w - 60]
        assertTrue("near black stays black: ${lum(dark)}", lum(dark) < 12)
        val r = (mid shr 16) and 0xFF
        val bl = mid and 0xFF
        assertTrue("warm: r=$r b=$bl", r > bl)
        assertFalse("grain differs per frame", a.contentEquals(b))
    }
}
