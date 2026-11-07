package com.example.live

import com.example.live.LiveGuide.Inputs
import com.example.live.LiveGuide.Level
import com.example.live.LiveGuide.Pause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGuideTest {

    private fun tips(inputs: Inputs) = LiveGuide.tips(inputs)

    @Test
    fun strongDepthSaysShoot() {
        val t = tips(Inputs(depthPercent = 2.2, focusDistanceM = 0.6))
        assertEquals(1, t.size)
        assertEquals(Level.GOOD, t[0].level)
        assertTrue(t[0].text, t[0].text.contains("2,2 %"))
    }

    @Test
    fun weakAndFarAsksToGetCloser() {
        val t = tips(Inputs(depthPercent = 0.4, focusDistanceM = 2.0))
        assertEquals(Level.BAD, t[0].level)
        assertEquals("👣", t[1].emoji)
        assertTrue(t[1].text, t[1].text.contains("2,0 m"))
    }

    @Test
    fun closeSubjectWithBackgroundRightBehind() {
        // Subject 0.8 m, spread 0.5 %: 1/far = 1.25 - 0.5/1.54 = 0.925 -> far 1.08 m, gap ~0.3 m.
        val t = tips(Inputs(depthPercent = 0.5, focusDistanceM = 0.8))
        assertEquals("🏞️", t[1].emoji)
        assertTrue(t[1].text, t[1].text.contains("25 cm") || t[1].text.contains("30 cm"))
    }

    @Test
    fun tooCloseWarns() {
        val t = tips(Inputs(depthPercent = 4.6, focusDistanceM = 0.25))
        assertEquals(Level.WARN, t[0].level)
        assertEquals("⚠️", t[0].emoji)
    }

    @Test
    fun movingPausesTheMeasurementTip() {
        val t = tips(Inputs(depthPercent = 2.0, pause = Pause.MOVING))
        assertEquals(1, t.size)
        assertEquals("✋", t[0].emoji)
    }

    @Test
    fun landscapeAndDarkComeFirstAndAreCapped() {
        val t = tips(Inputs(depthPercent = 0.3, landscape = true, exposureMs = 66.0, focusDistanceM = 2.0))
        assertEquals(LiveGuide.MAX_TIPS, t.size)
        assertEquals("📱", t[0].emoji)
    }

    @Test
    fun darkSuggestsNightModeOnlyWhenOff() {
        assertTrue(tips(Inputs(iso = 3200)).any { it.emoji == "🌙" })
        assertTrue(tips(Inputs(iso = 3200, nightMode = true)).none { it.emoji == "🌙" })
    }

    @Test
    fun powerSaveIsExplained() {
        val t = tips(Inputs(pause = Pause.POWER_SAVE))
        assertEquals("🔋", t.single().emoji)
    }

    @Test
    fun lowStructure() {
        assertEquals("🔍", tips(Inputs(lowStructure = true)).single().emoji)
    }
}
