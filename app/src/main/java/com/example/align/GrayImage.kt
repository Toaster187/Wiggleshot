package com.example.align

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Single channel float image used by the alignment code.
 *
 * Coordinate convention (identical to `android.graphics.Matrix` / Canvas): pixel `(i, j)`
 * covers the square `[i, i + 1) x [j, j + 1)`, so its centre is at `(i + 0.5, j + 0.5)`.
 * Every transform in this package maps these continuous coordinates. Keeping one convention
 * everywhere matters: a half pixel mismatch at the working resolution is several pixels in
 * the 4K output.
 *
 * Deliberately free of Android classes so the whole alignment pipeline runs in plain JVM
 * unit tests.
 */
class GrayImage(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {

    init {
        require(width > 0 && height > 0) { "Empty image ${width}x$height" }
        require(data.size == width * height) { "Data size ${data.size} != ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    /** True if a bilinear sample at continuous `(x, y)` only touches real pixels. */
    fun containsForSampling(x: Double, y: Double): Boolean =
        x >= 0.5 && y >= 0.5 && x <= width - 0.5 && y <= height - 0.5

    /** Bilinear sample at continuous coordinates; coordinates outside are clamped. */
    fun sample(x: Double, y: Double): Float {
        val u = (x - 0.5).coerceIn(0.0, (width - 1).toDouble())
        val v = (y - 0.5).coerceIn(0.0, (height - 1).toDouble())
        val x0 = min(floor(u).toInt(), width - 1)
        val y0 = min(floor(v).toInt(), height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (u - x0).toFloat()
        val fy = (v - y0).toFloat()
        val row0 = y0 * width
        val row1 = y1 * width
        val top = data[row0 + x0] + (data[row0 + x1] - data[row0 + x0]) * fx
        val bottom = data[row1 + x0] + (data[row1 + x1] - data[row1 + x0]) * fx
        return top + (bottom - top) * fy
    }

    fun copy(): GrayImage = GrayImage(width, height, data.copyOf())

    /** Sub image `[x0, x0 + w) x [y0, y0 + h)`; continuous coordinates shift by `(x0, y0)`. */
    fun crop(x0: Int, y0: Int, w: Int, h: Int): GrayImage {
        require(x0 >= 0 && y0 >= 0 && w > 0 && h > 0 && x0 + w <= width && y0 + h <= height)
        val out = FloatArray(w * h)
        for (y in 0 until h) System.arraycopy(data, (y0 + y) * width + x0, out, y * w, w)
        return GrayImage(w, h, out)
    }

    /**
     * Halves the resolution with a [1, 3, 3, 1] / 8 binomial kernel. The kernel is centred
     * between the two source pixels it replaces, so continuous coordinates map exactly as
     * `x_full = 2 * x_half` — no half pixel drift accumulates over pyramid levels.
     */
    fun halve(): GrayImage {
        val newW = max(1, width / 2)
        val newH = max(1, height / 2)
        val tmp = FloatArray(newW * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until newW) {
                val c = 2 * x
                val a = data[row + max(c - 1, 0)]
                val b = data[row + c]
                val d = data[row + min(c + 1, width - 1)]
                val e = data[row + min(c + 2, width - 1)]
                tmp[y * newW + x] = (a + 3f * b + 3f * d + e) * 0.125f
            }
        }
        val out = FloatArray(newW * newH)
        for (y in 0 until newH) {
            val c = 2 * y
            val r0 = max(c - 1, 0) * newW
            val r1 = c * newW
            val r2 = min(c + 1, height - 1) * newW
            val r3 = min(c + 2, height - 1) * newW
            for (x in 0 until newW) {
                out[y * newW + x] = (tmp[r0 + x] + 3f * tmp[r1 + x] + 3f * tmp[r2 + x] + tmp[r3 + x]) * 0.125f
            }
        }
        return GrayImage(newW, newH, out)
    }

    /** Approximate Gaussian blur (three box passes), edges clamped. */
    fun gaussianBlur(sigma: Double): GrayImage {
        if (sigma <= 0.3) return copy()
        val radius = boxRadiusForSigma(sigma)
        var cur = data
        repeat(3) {
            cur = boxBlurVertical(boxBlurHorizontal(cur, radius), radius)
        }
        return GrayImage(width, height, cur)
    }

    /**
     * Local contrast normalisation: `(I - mean) / sqrt(var + eps^2)` over a Gaussian window.
     *
     * Removes everything that differs between two lenses but is not geometry — exposure,
     * gain, white balance, vignetting and local tone mapping — while keeping the edge
     * structure the alignment is driven by. [epsilon] stops sensor noise in flat areas from
     * being amplified into fake texture.
     */
    fun localContrastNormalized(sigma: Double, epsilon: Float): GrayImage {
        val mean = gaussianBlur(sigma).data
        val sq = FloatArray(data.size) { data[it] * data[it] }
        val meanSq = GrayImage(width, height, sq).gaussianBlur(sigma).data
        val eps2 = epsilon * epsilon
        val out = FloatArray(data.size)
        for (i in data.indices) {
            val variance = max(0f, meanSq[i] - mean[i] * mean[i])
            out[i] = ((data[i] - mean[i]) / sqrt(variance + eps2)).coerceIn(-4f, 4f)
        }
        return GrayImage(width, height, out)
    }

    /** Central difference gradients (d/dx, d/dy). */
    fun gradients(): Pair<GrayImage, GrayImage> {
        val gx = FloatArray(data.size)
        val gy = FloatArray(data.size)
        for (y in 0 until height) {
            val ym = max(y - 1, 0)
            val yp = min(y + 1, height - 1)
            val dyScale = if (yp - ym == 2) 0.5f else 1f
            for (x in 0 until width) {
                val xm = max(x - 1, 0)
                val xp = min(x + 1, width - 1)
                val dxScale = if (xp - xm == 2) 0.5f else 1f
                gx[y * width + x] = (data[y * width + xp] - data[y * width + xm]) * dxScale
                gy[y * width + x] = (data[yp * width + x] - data[ym * width + x]) * dyScale
            }
        }
        return GrayImage(width, height, gx) to GrayImage(width, height, gy)
    }

    fun mean(): Double = data.fold(0.0) { acc, v -> acc + v } / data.size

    fun standardDeviation(): Double {
        val m = mean()
        var sum = 0.0
        for (v in data) sum += (v - m) * (v - m)
        return sqrt(sum / data.size)
    }

    private fun boxBlurHorizontal(src: FloatArray, r: Int): FloatArray {
        val out = FloatArray(src.size)
        val norm = 1f / (2 * r + 1)
        for (y in 0 until height) {
            val row = y * width
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                out[row + x] = acc * norm
                val add = src[row + (x + r + 1).coerceAtMost(width - 1)]
                val sub = src[row + (x - r).coerceAtLeast(0)]
                acc += add - sub
            }
        }
        return out
    }

    private fun boxBlurVertical(src: FloatArray, r: Int): FloatArray {
        val out = FloatArray(src.size)
        val norm = 1f / (2 * r + 1)
        val acc = FloatArray(width)
        for (k in -r..r) {
            val row = k.coerceIn(0, height - 1) * width
            for (x in 0 until width) acc[x] += src[row + x]
        }
        for (y in 0 until height) {
            val addRow = (y + r + 1).coerceAtMost(height - 1) * width
            val subRow = (y - r).coerceAtLeast(0) * width
            val outRow = y * width
            for (x in 0 until width) {
                out[outRow + x] = acc[x] * norm
                acc[x] += src[addRow + x] - src[subRow + x]
            }
        }
        return out
    }

    companion object {
        /** Box radius whose three-fold convolution approximates a Gaussian of [sigma]. */
        internal fun boxRadiusForSigma(sigma: Double): Int {
            // Variance of a box of width w is (w^2 - 1) / 12; three passes triple it.
            val w = sqrt(12.0 * sigma * sigma / 3.0 + 1.0)
            return max(1, ((w - 1.0) / 2.0).roundToInt())
        }

        /**
         * Builds a grayscale image from ARGB pixels, averaging [factor] x [factor] blocks.
         * Continuous coordinates map as `x_full = factor * x_small`.
         */
        fun fromArgb(pixels: IntArray, width: Int, height: Int, factor: Int = 1): GrayImage {
            require(factor >= 1)
            val outW = max(1, width / factor)
            val outH = max(1, height / factor)
            val out = FloatArray(outW * outH)
            val norm = 1f / (factor * factor)
            for (oy in 0 until outH) {
                for (ox in 0 until outW) {
                    var sum = 0f
                    for (dy in 0 until factor) {
                        val row = (oy * factor + dy) * width + ox * factor
                        for (dx in 0 until factor) sum += luma(pixels[row + dx])
                    }
                    out[oy * outW + ox] = sum * norm
                }
            }
            return GrayImage(outW, outH, out)
        }

        fun luma(argb: Int): Float {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }
    }
}
