package com.example.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow

class ColorMatcherTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun scene(seed: Long, n: Int = 60_000): IntArray {
        val random = Random(seed)
        return IntArray(n) {
            val base = (random.nextGaussian() * 45 + 120).toInt().coerceIn(0, 255)
            argb(
                (base + random.nextInt(30)).coerceIn(0, 255),
                (base * 0.9 + random.nextInt(20)).toInt().coerceIn(0, 255),
                (base * 0.7 + 30 + random.nextInt(25)).toInt().coerceIn(0, 255)
            )
        }
    }

    /** Different lens: darker, warmer white balance and another tone curve. */
    private fun otherLens(p: Int): Int {
        fun curve(v: Int, gain: Double, gamma: Double) =
            (255 * (v / 255.0).pow(gamma) * gain).toInt().coerceIn(0, 255)
        return argb(
            curve((p shr 16) and 0xFF, 0.95, 1.1),
            curve((p shr 8) and 0xFF, 0.85, 1.2),
            curve(p and 0xFF, 0.75, 1.25)
        )
    }

    @Test
    fun removesExposureAndWhiteBalanceDifference() {
        val reference = scene(1)
        val source = reference.map(::otherLens).toIntArray()
        val luts = ColorMatcher.compute(reference, source)

        var before = 0.0
        var after = 0.0
        for (i in reference.indices) {
            val corrected = luts.apply(source[i])
            for (shift in intArrayOf(16, 8, 0)) {
                val ref = (reference[i] shr shift) and 0xFF
                before += abs(ref - ((source[i] shr shift) and 0xFF))
                after += abs(ref - ((corrected shr shift) and 0xFF))
            }
        }
        before /= reference.size * 3
        after /= reference.size * 3
        println("mean abs channel error before=$before after=$after")
        assertTrue("error must shrink a lot: $before -> $after", after < before * 0.15)
        assertTrue(after < 3.0)
    }

    @Test
    fun identicalInputsGiveIdentity() {
        val reference = scene(2)
        val luts = ColorMatcher.compute(reference, reference)
        for (v in 20..235) {
            assertEquals(v.toDouble(), luts.green[v].toDouble(), 2.0)
        }
    }

    @Test
    fun lutsAreMonotone() {
        val luts = ColorMatcher.compute(scene(3), scene(4).map(::otherLens).toIntArray())
        for (lut in listOf(luts.red, luts.green, luts.blue)) {
            for (v in 1..255) assertTrue(lut[v] >= lut[v - 1])
        }
    }
}
