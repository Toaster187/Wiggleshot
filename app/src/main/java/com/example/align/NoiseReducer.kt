package com.example.align

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Noise reduction for the ultra wide frame before it is magnified ~2.3x onto the main frame.
 *
 * In low light the Pixel 9's small ultra wide sensor (about a third of the main sensor's
 * area) delivers frames with
 *  - **specks**: isolated 1-3 px dark/bright dots with ~15-20 levels of contrast,
 *  - **mottle**: blotchy low-frequency luma noise left over by the camera's own NR,
 *  - **chroma blotches**: green/magenta patches.
 * The magnification turns all of it into coarse, very visible grain, and the colour match
 * (which brightens the darker ultra wide) amplifies it further. The main frame is already
 * clean, so without this the wiggle flickers between a clean and a noisy frame.
 *
 * Pipeline (all at the ultra wide's native resolution, i.e. on few pixels):
 *  1. Specks: grey-scale closing / opening with a 3x3 element; only connected blobs of at
 *     most [MAX_SPECK_AREA] pixels that stand out by more than the noise level are
 *     replaced. Thin lines and real details are larger and stay untouched.
 *  2. Luma: self-guided filter (edge preserving; flat areas are averaged, edges kept), with
 *     its regularisation derived from the measured mottle level.
 *  3. Chroma: guided filter with the denoised luma as guide, so colour stays inside edges.
 */
object NoiseReducer {

    const val MAX_SPECK_AREA = 8

    /** Rows of context around a strip: covers the 3x3 morphology and both guided filters. */
    private const val STRIP_MARGIN = 24

    class Stats(val mottleSigma: Double, val speckPixels: Int, val elapsedMs: Long)

    /**
     * Denoises [argb] ([width] x [height]) in place. [strength] scales the smoothing
     * (1 = default, about 2 = strong, 0 = only speck removal).
     */
    fun denoise(argb: IntArray, width: Int, height: Int, strength: Double = 1.0): Stats {
        val start = System.nanoTime()
        val sigma = estimateSigma(argb, width, height)
        val specks = denoiseWithSigma(argb, width, height, sigma, strength)
        return Stats(sigma, specks, (System.nanoTime() - start) / 1_000_000)
    }

    /**
     * Same result as [denoise] but processed in horizontal strips, so the extra memory stays
     * at a few megabytes even for a 12 MP frame. The noise level is measured once on the
     * centre of the frame.
     */
    fun denoiseInStrips(argb: IntArray, width: Int, height: Int, strength: Double = 1.0, stripRows: Int = 256): Stats {
        val start = System.nanoTime()
        val sigma = estimateSigma(argb, width, height)
        var specks = 0
        var y0 = 0
        while (y0 < height) {
            val y1 = min(height, y0 + stripRows)
            val top = max(0, y0 - STRIP_MARGIN)
            val bottom = min(height, y1 + STRIP_MARGIN)
            val strip = argb.copyOfRange(top * width, bottom * width)
            specks += denoiseWithSigma(strip, width, bottom - top, sigma, strength)
            System.arraycopy(strip, (y0 - top) * width, argb, y0 * width, (y1 - y0) * width)
            y0 = y1
        }
        return Stats(sigma, specks, (System.nanoTime() - start) / 1_000_000)
    }

    /** Mottle level of the frame, measured on (at most) its central 1024 x 1024 pixels. */
    private fun estimateSigma(argb: IntArray, width: Int, height: Int): Double {
        val cw = min(width, 1024)
        val ch = min(height, 1024)
        val x0 = (width - cw) / 2
        val y0 = (height - ch) / 2
        val luma = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) luma[y * cw + x] = GrayImage.luma(argb[(y0 + y) * width + x0 + x])
        return mottleSigma(GrayImage(cw, ch, luma))
    }

    private fun denoiseWithSigma(argb: IntArray, width: Int, height: Int, sigma: Double, strength: Double): Int {
        val n = width * height
        require(argb.size >= n)
        val y = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        for (i in 0 until n) {
            val p = argb[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            y[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cb[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            cr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        val threshold = (3 * sigma + 6).toFloat()
        var specks = 0
        specks += removeSpecks(y, cb, cr, width, height, threshold, dark = true)
        specks += removeSpecks(y, cb, cr, width, height, threshold, dark = false)

        if (strength > 0) {
            val eps = ((2.5 * sigma * strength) * (2.5 * sigma * strength)).toFloat().coerceAtLeast(0.25f)
            val luma = guidedFilter(y, y, width, height, radius = 3, eps = eps)
            val cbOut = guidedFilter(luma, cb, width, height, radius = 8, eps = eps * 0.25f)
            val crOut = guidedFilter(luma, cr, width, height, radius = 8, eps = eps * 0.25f)
            System.arraycopy(luma, 0, y, 0, n)
            System.arraycopy(cbOut, 0, cb, 0, n)
            System.arraycopy(crOut, 0, cr, 0, n)
        }

        for (i in 0 until n) {
            val yy = y[i]
            val u = cb[i] - 128f
            val v = cr[i] - 128f
            val r = (yy + 1.402f * v).roundToInt().coerceIn(0, 255)
            val g = (yy - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
            val b = (yy + 1.772f * u).roundToInt().coerceIn(0, 255)
            argb[i] = (argb[i] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return specks
    }

    /**
     * Mottle level: robust spread of a 1..5 px band-pass of the luma in the flattest 30 % of
     * the frame (pixel level noise is already removed by the camera, the band is what shows).
     */
    fun mottleSigma(luma: GrayImage): Double {
        val fine = luma.gaussianBlur(0.7).data
        val coarse = luma.gaussianBlur(5.0).data
        val (gx, gy) = luma.gaussianBlur(4.0).gradients()
        val n = luma.data.size
        val stride = max(1, n / 200_000)
        val grads = FloatArray((n + stride - 1) / stride)
        val bands = FloatArray(grads.size)
        var k = 0
        var i = 0
        while (i < n && k < grads.size) {
            grads[k] = gx.data[i] * gx.data[i] + gy.data[i] * gy.data[i]
            bands[k] = kotlin.math.abs(fine[i] - coarse[i])
            k++
            i += stride
        }
        val sortedGrads = grads.copyOf(k).also { it.sort() }
        val limit = sortedGrads[(k * 0.3).toInt().coerceIn(0, k - 1)]
        val flat = (0 until k).filter { grads[it] <= limit }.map { bands[it] }.sorted()
        if (flat.isEmpty()) return 1.0
        return 1.4826 * flat[flat.size / 2]
    }

    /** Removes small dark (or bright) blobs; returns the number of changed pixels. */
    private fun removeSpecks(
        y: FloatArray,
        cb: FloatArray,
        cr: FloatArray,
        w: Int,
        h: Int,
        threshold: Float,
        dark: Boolean
    ): Int {
        // Closing (dark specks) = erode(dilate(y)); opening (bright) = dilate(erode(y)).
        val reference = if (dark) {
            morph(morph(y, w, h, max = true), w, h, max = false)
        } else {
            morph(morph(y, w, h, max = false), w, h, max = true)
        }
        val n = w * h
        val mask = BooleanArray(n) { i -> (if (dark) reference[i] - y[i] else y[i] - reference[i]) > threshold }
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        val component = IntArray(MAX_SPECK_AREA)
        val cbBlur = boxMean(cb, w, h, 2)
        val crBlur = boxMean(cr, w, h, 2)
        var changed = 0
        for (start in 0 until n) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var area = 0
            while (head < tail) {
                val p = queue[head++]
                if (area < MAX_SPECK_AREA) component[area] = p
                area++
                val px = p % w
                val py = p / w
                for (dy in -1..1) for (dx in -1..1) {
                    val qx = px + dx
                    val qy = py + dy
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy * w + qx
                    if (mask[q] && !visited[q]) {
                        visited[q] = true
                        queue[tail++] = q
                    }
                }
            }
            if (area <= MAX_SPECK_AREA) {
                for (j in 0 until area) {
                    val p = component[j]
                    y[p] = reference[p]
                    cb[p] = cbBlur[p]
                    cr[p] = crBlur[p]
                }
                changed += area
            }
        }
        return changed
    }

    /** 3x3 grey-scale dilation (max) or erosion (min), separable, clamped borders. */
    private fun morph(src: FloatArray, w: Int, h: Int, max: Boolean): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val a = src[row + max(0, x - 1)]
                val b = src[row + x]
                val c = src[row + min(w - 1, x + 1)]
                tmp[row + x] = if (max) maxOf(a, b, c) else minOf(a, b, c)
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val up = max(0, y - 1) * w
            val row = y * w
            val down = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val a = tmp[up + x]
                val b = tmp[row + x]
                val c = tmp[down + x]
                out[row + x] = if (max) maxOf(a, b, c) else minOf(a, b, c)
            }
        }
        return out
    }

    /** Guided filter (He et al.): output = mean(a) * guide + mean(b). */
    private fun guidedFilter(guide: FloatArray, input: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = boxMean(guide, w, h, radius)
        val meanP = boxMean(input, w, h, radius)
        val product = FloatArray(n) { guide[it] * input[it] }
        val meanIp = boxMean(product, w, h, radius)
        for (i in 0 until n) product[i] = guide[i] * guide[i]
        val meanIi = boxMean(product, w, h, radius)
        // a -> meanIp, b -> meanIi (buffers reused to keep the footprint small)
        for (i in 0 until n) {
            val variance = max(0f, meanIi[i] - meanI[i] * meanI[i])
            val covariance = meanIp[i] - meanI[i] * meanP[i]
            val a = covariance / (variance + eps)
            meanIp[i] = a
            meanIi[i] = meanP[i] - a * meanI[i]
        }
        val meanA = boxMean(meanIp, w, h, radius)
        val meanB = boxMean(meanIi, w, h, radius)
        for (i in 0 until n) product[i] = meanA[i] * guide[i] + meanB[i]
        return product
    }

    /**
     * Mean over a (2r+1)² box, edges clamped, O(1) per pixel via running sums. The sums are
     * kept in double precision: with squared intensities (up to 65025) float running sums
     * drift by several units over a row, which is enough to make a variance negative.
     */
    internal fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val norm = 1.0 / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0.0
            for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = (acc * norm).toFloat()
                acc += src[row + min(w - 1, x + r + 1)] - src[row + max(0, x - r)]
            }
        }
        val out = FloatArray(src.size)
        val col = DoubleArray(w)
        for (k in -r..r) {
            val row = k.coerceIn(0, h - 1) * w
            for (x in 0 until w) col[x] += tmp[row + x]
        }
        for (y in 0 until h) {
            val add = min(h - 1, y + r + 1) * w
            val sub = max(0, y - r) * w
            val row = y * w
            for (x in 0 until w) {
                out[row + x] = (col[x] * norm).toFloat()
                col[x] += (tmp[add + x] - tmp[sub + x]).toDouble()
            }
        }
        return out
    }
}
