package com.example.align

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Synthetic low light ultra wide frame: smooth wall, a sharp edge, a 1 px dark line (like a
 * cable or plant leaf), blotchy correlated noise, chroma blotches and 2 px dark specks — the
 * ingredients seen in the Pixel 9's 0.5x frames.
 */
class NoiseReducerTest {

    private val w = 600
    private val h = 400

    private fun clean(x: Int, y: Int): FloatArray {
        var v = 80f + 60f * x / w
        if (x in 350..500 && y in 100..300) v += 60f // sharp edged object
        if (abs((x - 50) - (y * 0.9f).toInt()) < 1 && x < 300) v -= 45f // thin dark line
        return floatArrayOf(v * 1.05f, v, v * 0.85f)
    }

    private fun blurred(random: Random, sigma: Double, amplitude: Double): GrayImage {
        val raw = GrayImage(w, h, FloatArray(w * h) { (random.nextGaussian() * amplitude).toFloat() })
        return raw.gaussianBlur(sigma)
    }

    private class Frame(val clean: Array<FloatArray>, val noisy: IntArray, val specks: List<Int>)

    private fun frame(): Frame {
        val random = Random(4)
        val mottle = blurred(random, 1.5, 10.0)
        val chromaR = blurred(random, 3.0, 25.0)
        val chromaB = blurred(random, 3.0, 25.0)
        val cleanPixels = Array(w * h) { i -> clean(i % w, i / w) }
        val specks = mutableListOf<Int>()
        repeat(400) {
            val x = 5 + random.nextInt(w - 10)
            val y = 5 + random.nextInt(h - 10)
            specks += y * w + x
            specks += y * w + x + 1
        }
        val speckSet = specks.toHashSet()
        val noisy = IntArray(w * h) { i ->
            val c = cleanPixels[i]
            val m = mottle.data[i]
            val d = if (i in speckSet) -28f else 0f
            val r = (c[0] + m + chromaR.data[i] + d).toInt().coerceIn(0, 255)
            val g = (c[1] + m + d).toInt().coerceIn(0, 255)
            val b = (c[2] + m + chromaB.data[i] + d).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(cleanPixels, noisy, specks)
    }

    private fun lumaOf(p: Int) = GrayImage.luma(p)
    private fun lumaOf(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]

    @Test
    fun removesSpecksAndMottleButKeepsEdgesAndLines() {
        val f = frame()
        val out = f.noisy.copyOf()
        val stats = NoiseReducer.denoise(out, w, h)
        println("mottle sigma %.2f, speck pixels %d, %d ms".format(stats.mottleSigma, stats.speckPixels, stats.elapsedMs))

        // 1. specks
        fun speckDepth(pixels: IntArray) = f.specks.map { i -> lumaOf(f.clean[i]) - lumaOf(pixels[i]) }.average()
        val before = speckDepth(f.noisy)
        val after = speckDepth(out)
        println("speck depth before %.1f after %.1f".format(before, after))
        assertTrue("specks must be removed: $before -> $after", after < before * 0.25)

        // 2. flat area noise (luma and chroma)
        fun flatError(pixels: IntArray, channel: Int): Double {
            var sum = 0.0
            var n = 0
            for (y in 20 until 380) for (x in 20 until 330) {
                if (abs((x - 50) - (y * 0.9f).toInt()) < 6) continue
                val i = y * w + x
                if (i in f.specks.toHashSet()) continue
                val v = when (channel) {
                    0 -> lumaOf(pixels[i]) - lumaOf(f.clean[i])
                    else -> (((pixels[i] shr 16) and 0xFF) - ((pixels[i] and 0xFF))) - (f.clean[i][0] - f.clean[i][2])
                }
                sum += v * v
                n++
            }
            return sqrt(sum / n)
        }
        val lumaBefore = flatError(f.noisy, 0)
        val lumaAfter = flatError(out, 0)
        val chromaBefore = flatError(f.noisy, 1)
        val chromaAfter = flatError(out, 1)
        println("flat luma error %.2f -> %.2f, chroma %.2f -> %.2f".format(lumaBefore, lumaAfter, chromaBefore, chromaAfter))
        assertTrue("luma mottle must drop", lumaAfter < lumaBefore * 0.65)
        assertTrue("chroma blotches must drop", chromaAfter < chromaBefore * 0.55)

        // 3. sharp edge keeps its step
        fun step(pixels: IntArray): Double = (150..250).map { y ->
            lumaOf(pixels[y * w + 353]) - lumaOf(pixels[y * w + 346])
        }.average()
        val stepClean = (150..250).map { y -> lumaOf(f.clean[y * w + 353]) - lumaOf(f.clean[y * w + 346]) }.average()
        println("edge step clean %.1f, after %.1f".format(stepClean, step(out)))
        assertTrue("edge must stay sharp", step(out) > stepClean * 0.85)

        // 4. thin dark line keeps most of its contrast
        fun lineContrast(pixels: IntArray): Double = (40..300 step 7).map { y ->
            val x = 50 + (y * 0.9f).toInt()
            val i = y * w + x
            (lumaOf(pixels[i - 4]) + lumaOf(pixels[i + 4])) / 2 - lumaOf(pixels[i])
        }.average()
        val lineClean = (40..300 step 7).map { y ->
            val x = 50 + (y * 0.9f).toInt()
            val i = y * w + x
            (lumaOf(f.clean[i - 4]) + lumaOf(f.clean[i + 4])) / 2 - lumaOf(f.clean[i])
        }.average()
        println("line contrast clean %.1f, after %.1f".format(lineClean, lineContrast(out)))
        assertTrue("a 1 px line is not a speck and must stay", lineContrast(out) > lineClean * 0.6)
    }

    @Test
    fun stripProcessingMatchesWholeFrame() {
        val f = frame()
        val whole = f.noisy.copyOf()
        val strips = f.noisy.copyOf()
        NoiseReducer.denoise(whole, w, h)
        NoiseReducer.denoiseInStrips(strips, w, h, stripRows = 64)
        var worst = 0
        for (i in whole.indices) worst = maxOf(worst, abs(lumaOf(whole[i]) - lumaOf(strips[i])).toInt())
        assertTrue("strip seams must be invisible, worst luma difference $worst", worst <= 2)
    }

    @Test
    fun cleanImageIsLeftAlone() {
        val pixels = IntArray(w * h) { i ->
            val c = clean(i % w, i / w)
            (0xFF shl 24) or (c[0].toInt().coerceIn(0, 255) shl 16) or (c[1].toInt().coerceIn(0, 255) shl 8) or c[2].toInt().coerceIn(0, 255)
        }
        val out = pixels.copyOf()
        NoiseReducer.denoise(out, w, h)
        var worst = 0
        for (y in 10 until h - 10) for (x in 10 until w - 10) {
            if (x in 345..505 || abs((x - 50) - (y * 0.9f).toInt()) < 4) continue
            worst = maxOf(worst, abs(lumaOf(out[y * w + x]) - lumaOf(pixels[y * w + x])).toInt())
        }
        assertTrue("smooth areas of a clean image must not change: $worst", worst <= 2)
    }
}
