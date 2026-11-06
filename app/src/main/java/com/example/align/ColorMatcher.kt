package com.example.align

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Matches the colours of the secondary lens to the primary lens.
 *
 * Both lenses run their own auto exposure and white balance and have different colour
 * science, so without correction the wiggle visibly flickers. Per channel histogram matching
 * is used because it only depends on the statistics of the two frames, not on a pixel exact
 * overlay — parallax and small residual misalignment do not disturb it.
 */
object ColorMatcher {

    class Luts(val red: IntArray, val green: IntArray, val blue: IntArray) {
        fun apply(argb: Int): Int {
            val a = argb ushr 24
            val r = red[(argb shr 16) and 0xFF]
            val g = green[(argb shr 8) and 0xFF]
            val b = blue[argb and 0xFF]
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun applyInPlace(pixels: IntArray, offset: Int = 0, count: Int = pixels.size - offset) {
            for (i in offset until offset + count) pixels[i] = apply(pixels[i])
        }

        /** Largest change any LUT applies; useful to report how different the lenses were. */
        fun maxCorrection(): Int = (0..255).maxOf { v ->
            max(kotlin.math.abs(red[v] - v), max(kotlin.math.abs(green[v] - v), kotlin.math.abs(blue[v] - v)))
        }

        companion object {
            val IDENTITY = Luts(IntArray(256) { it }, IntArray(256) { it }, IntArray(256) { it })
        }
    }

    /**
     * Builds LUTs that give [source] the per channel distribution of [reference]. Both arrays
     * are ARGB pixels of the overlapping region (any size, need not be equal). Pixels with
     * alpha 0 are ignored. [strength] blends between identity (0) and full matching (1).
     */
    fun compute(reference: IntArray, source: IntArray, strength: Float = 1f): Luts {
        val refHist = Array(3) { LongArray(256) }
        val srcHist = Array(3) { LongArray(256) }
        accumulate(reference, refHist)
        accumulate(source, srcHist)
        if (refHist[0].sum() < 64 || srcHist[0].sum() < 64) return Luts.IDENTITY
        return Luts(
            red = channelLut(srcHist[0], refHist[0], strength),
            green = channelLut(srcHist[1], refHist[1], strength),
            blue = channelLut(srcHist[2], refHist[2], strength)
        )
    }

    private fun accumulate(pixels: IntArray, hist: Array<LongArray>) {
        for (p in pixels) {
            if ((p ushr 24) == 0) continue
            hist[0][(p shr 16) and 0xFF]++
            hist[1][(p shr 8) and 0xFF]++
            hist[2][p and 0xFF]++
        }
    }

    private fun channelLut(srcHist: LongArray, refHist: LongArray, strength: Float): IntArray {
        val srcCdf = cdf(srcHist)
        val refCdf = cdf(refHist)
        val raw = DoubleArray(256)
        var j = 0
        for (v in 0 until 256) {
            // Evaluate the source CDF at the bin centre so a one-to-one mapping is identity.
            val target = if (v == 0) srcCdf[0] / 2 else (srcCdf[v - 1] + srcCdf[v]) / 2
            while (j < 255 && refCdf[j] < target) j++
            val lowCdf = if (j == 0) 0.0 else refCdf[j - 1]
            val highCdf = refCdf[j]
            val frac = if (highCdf > lowCdf) ((target - lowCdf) / (highCdf - lowCdf)).coerceIn(0.0, 1.0) else 0.5
            raw[v] = j - 0.5 + frac
        }
        // Smooth (removes the staircase of sparse histograms) and keep it monotone.
        val smooth = DoubleArray(256)
        val radius = 4
        for (v in 0 until 256) {
            var sum = 0.0
            var n = 0
            for (k in max(0, v - radius)..min(255, v + radius)) {
                sum += raw[k]
                n++
            }
            smooth[v] = sum / n
        }
        val lut = IntArray(256)
        var last = 0
        for (v in 0 until 256) {
            val blended = v + strength * (smooth[v] - v)
            val value = blended.roundToInt().coerceIn(0, 255)
            last = max(last, value)
            lut[v] = last
        }
        return lut
    }

    private fun cdf(hist: LongArray): DoubleArray {
        val total = hist.sum().toDouble().coerceAtLeast(1.0)
        val out = DoubleArray(256)
        var acc = 0L
        for (i in 0 until 256) {
            acc += hist[i]
            out[i] = acc / total
        }
        return out
    }
}
