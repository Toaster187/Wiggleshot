package com.example.align

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Estimates the [Similarity] that maps the primary frame onto the secondary frame of one
 * wiggle capture.
 *
 * Pipeline:
 *  1. **Photometric normalisation** – both images are local-contrast normalised, so the
 *     different exposure, white balance, vignetting and tone mapping of the two lenses no
 *     longer influence the match.
 *  2. **Coarse search** – at a ~100 px wide pyramid level an exhaustive ZNCC search over
 *     scale and translation finds the basin of the right solution. With a calibration or
 *     metadata prior the search is narrow; without one it covers the whole lens range.
 *  3. **Robust Gauss-Newton refinement** (Lucas-Kanade on a Gaussian pyramid) of scale,
 *     rotation and translation with Cauchy weights. Pixels whose residual is dominated by
 *     parallax, moving objects or blown highlights are down-weighted automatically. Accuracy
 *     is a fraction of a working pixel, i.e. a few pixels in the 4K output.
 *  4. **Pivot** – the translation is refined once more on the image centre only. That is the
 *     point that stays still in the wiggle; everything in front of or behind it moves.
 *
 * The old matcher only searched a scalar zoom on a 48x48 grid of preview frames and threw the
 * translation it found away, so every capture was centre cropped regardless of the real
 * optical centres of the two modules — the main reason the frames visibly jumped.
 */
object LensAligner {

    data class Options(
        /** Scale search range (secondary px per primary px) when no prior is known. */
        val minScale: Double = 0.2,
        val maxScale: Double = 5.0,
        /** Relative scale range searched around a prior. */
        val priorScaleTolerance: Double = 0.06,
        /** Translation search window around a prior, relative to the coarse image width. */
        val priorTranslationFraction: Double = 0.07,
        /** Translation search window without prior, relative to the coarse image width. */
        val freeTranslationFraction: Double = 0.2,
        /** Approximate primary width of the coarse search level. */
        val coarseWidth: Int = 96,
        /** Cap of samples per Gauss-Newton iteration; larger levels are subsampled. */
        val maxSamplesPerLevel: Int = 180_000,
        /** Side of the centred pivot window relative to the frame. */
        val pivotFraction: Double = 0.34,
        /** Pivot corrections larger than this (relative to the width) are rejected. */
        val maxPivotShiftFraction: Double = 0.06,
        val estimateRotation: Boolean = true,
        val alignPivot: Boolean = true
    )

    class Result(
        /** Best global fit (dominant depth), primary px -> secondary px. */
        val global: Similarity,
        /** [global] with the translation refined on the pivot window. Use this for the wiggle. */
        val pivoted: Similarity,
        /** ZNCC of the normalised images under [global], roughly 0 (no match) .. 1. */
        val confidence: Double,
        /** Same score for the prior transform, if one was given. */
        val priorConfidence: Double?,
        val coarseScore: Double,
        /** Size of the pivot correction in primary pixels. */
        val pivotShift: Double,
        val iterations: Int,
        val elapsedMs: Long
    ) {
        override fun toString(): String =
            "Result(global=$global, pivoted=$pivoted, conf=%.3f, prior=%s, coarse=%.3f, pivot=%.2fpx, it=$iterations, ${elapsedMs}ms)"
                .format(confidence, priorConfidence?.let { "%.3f".format(it) }, coarseScore, pivotShift)
    }

    private const val LCN_SIGMA = 5.0
    private const val LCN_EPSILON = 3f
    private const val PRE_BLUR_SIGMA = 0.7
    private const val CAUCHY_C = 2.385
    private const val BORDER = 2

    fun align(
        primary: GrayImage,
        secondary: GrayImage,
        prior: Similarity? = null,
        options: Options = Options()
    ): Result? {
        val start = System.nanoTime()

        // ---- 0. bring both images to a similar pixel scale --------------------------------
        // Only exact halvings are used: they keep the mapping uniform (x_input = 2^n * x),
        // whereas resizing to rounded sizes would scale x and y very slightly differently and
        // bias the estimate. Callers that feed density matched images skip this entirely.
        val expectedScale = prior?.scale ?: sqrt(options.minScale * options.maxScale)
        var p = primary
        var s = secondary
        var pScale = 1.0 // x_input = pScale * x_internal
        var sScale = 1.0
        var internalScale = expectedScale
        while (internalScale > 1.45 && s.width >= 64 && s.height >= 64) {
            s = s.halve()
            sScale *= 2
            internalScale /= 2
        }
        while (internalScale < 0.69 && p.width >= 64 && p.height >= 64) {
            p = p.halve()
            pScale *= 2
            internalScale *= 2
        }
        val internalPrior = prior?.reexpress(primaryScale = pScale, secondaryScale = sScale)
        val scaleFactor = pScale / sScale // internal scale = input scale * scaleFactor
        val minScale = options.minScale * scaleFactor
        val maxScale = options.maxScale * scaleFactor

        // ---- 1. pyramids of normalised images ---------------------------------------------
        val pLevels = mutableListOf(p)
        val sLevels = mutableListOf(s)
        while (pLevels.last().width / 2 >= options.coarseWidth && pLevels.last().height / 2 >= 16 &&
            sLevels.last().width / 2 >= 16 && sLevels.last().height / 2 >= 16
        ) {
            pLevels += pLevels.last().halve()
            sLevels += sLevels.last().halve()
        }
        val pNorm = pLevels.map { normalize(it) }
        val sNorm = sLevels.map { normalize(it) }
        val sGrad = sNorm.map { it.gradients() }
        val top = pLevels.lastIndex

        // ---- 2. coarse search ---------------------------------------------------------------
        val coarse: Coarse = if (internalPrior != null) {
            coarseSearch(
                p = pNorm[top],
                s = sNorm[top],
                prior = internalPrior.reexpress(primaryScale = levelScale(top), secondaryScale = levelScale(top)),
                minScale = minScale,
                maxScale = maxScale,
                options = options
            ).firstOrNull() ?: return null
        } else {
            // Without prior the full scale range is searched one level coarser first, then
            // the three best distinct candidates are re-examined at the regular coarse level.
            val tinyP = normalize(pLevels[top].halve())
            val tinyS = normalize(sLevels[top].halve())
            val candidates = mutableListOf<Coarse>()
            for (c in coarseSearch(tinyP, tinyS, null, minScale, maxScale, options)) {
                if (candidates.none { abs(ln(it.transform.scale / c.transform.scale)) < 0.05 }) candidates += c
                if (candidates.size == 3) break
            }
            val narrow = options.copy(priorScaleTolerance = 0.05, priorTranslationFraction = 0.05)
            candidates.mapNotNull { c ->
                coarseSearch(
                    p = pNorm[top],
                    s = sNorm[top],
                    prior = c.transform.reexpress(primaryScale = 0.5, secondaryScale = 0.5),
                    minScale = minScale,
                    maxScale = maxScale,
                    options = narrow
                ).firstOrNull()
            }.maxByOrNull { it.score } ?: return null
        }

        // ---- 3. robust refinement, coarse to fine -------------------------------------------
        var transform = coarse.transform // in level `top` coordinates
        var iterations = 0
        for (level in top downTo 0) {
            if (level != top) {
                transform = transform.reexpress(primaryScale = 0.5, secondaryScale = 0.5)
            }
            val maxIt = if (level == 0) 20 else 12
            val refined = refine(
                p = pNorm[level],
                s = sNorm[level],
                sgx = sGrad[level].first,
                sgy = sGrad[level].second,
                initial = transform,
                maxIterations = maxIt,
                estimateScaleRotation = true,
                estimateRotation = options.estimateRotation,
                window = null,
                maxSamples = options.maxSamplesPerLevel
            )
            transform = refined.first
            iterations += refined.second
        }
        val global = transform

        // ---- 4. pivot ---------------------------------------------------------------------
        var pivoted = global
        if (options.alignPivot) {
            val w = pNorm[0].width
            val h = pNorm[0].height
            val side = options.pivotFraction
            val window = Window(
                x0 = (w * (0.5 - side / 2)).roundToInt(),
                y0 = (h * (0.5 - side / 2)).roundToInt(),
                x1 = (w * (0.5 + side / 2)).roundToInt(),
                y1 = (h * (0.5 + side / 2)).roundToInt()
            )
            val refined = refine(
                p = pNorm[0],
                s = sNorm[0],
                sgx = sGrad[0].first,
                sgy = sGrad[0].second,
                initial = global,
                maxIterations = 10,
                estimateScaleRotation = false,
                estimateRotation = false,
                window = window,
                maxSamples = options.maxSamplesPerLevel
            )
            iterations += refined.second
            val shift = global.maxCornerDeviation(refined.first, w.toDouble(), h.toDouble())
            if (shift <= options.maxPivotShiftFraction * w) {
                pivoted = refined.first
            }
        }

        val confidence = zncc(pNorm[0], sNorm[0], global)
        val priorConfidence = internalPrior?.let { zncc(pNorm[0], sNorm[0], it) }

        // ---- back to the caller's pixel grids ------------------------------------------------
        val toInput = { t: Similarity -> t.reexpress(primaryScale = 1.0 / pScale, secondaryScale = 1.0 / sScale) }
        val globalOut = toInput(global)
        val pivotedOut = toInput(pivoted)
        return Result(
            global = globalOut,
            pivoted = pivotedOut,
            confidence = confidence,
            priorConfidence = priorConfidence,
            coarseScore = coarse.score,
            pivotShift = globalOut.maxCornerDeviation(pivotedOut, primary.width.toDouble(), primary.height.toDouble()),
            iterations = iterations,
            elapsedMs = (System.nanoTime() - start) / 1_000_000
        )
    }

    /**
     * ZNCC between the primary and the warped secondary over their overlap. Exposed for
     * diagnostics, e.g. to compare the stored calibration against a fresh estimate.
     */
    fun score(primary: GrayImage, secondary: GrayImage, transform: Similarity): Double =
        zncc(normalize(primary), normalize(secondary), transform)

    // ------------------------------------------------------------------------------------

    private fun levelScale(level: Int): Double = (1 shl level).toDouble()

    private fun normalize(image: GrayImage): GrayImage =
        image.gaussianBlur(PRE_BLUR_SIGMA).localContrastNormalized(LCN_SIGMA, LCN_EPSILON)

    private class Coarse(val transform: Similarity, val score: Double)

    private class Window(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /**
     * Exhaustive ZNCC search over (scale, integer translation) at the coarsest level, returning
     * the best translation of every scale, best first. Each
     * scale is resampled once into a padded buffer; translations are then plain index shifts
     * with box sums from summed area tables, so the search costs a few million
     * multiply-adds per scale.
     */
    private fun coarseSearch(
        p: GrayImage,
        s: GrayImage,
        prior: Similarity?,
        minScale: Double,
        maxScale: Double,
        options: Options
    ): List<Coarse> {
        val rx0 = (p.width * 0.08).roundToInt()
        val ry0 = (p.height * 0.08).roundToInt()
        val rw = p.width - 2 * rx0
        val rh = p.height - 2 * ry0
        if (rw < 8 || rh < 8) return emptyList()

        val radius = max(
            2,
            (p.width * if (prior != null) options.priorTranslationFraction else options.freeTranslationFraction).roundToInt()
        )

        val scales: List<Double> = if (prior != null) {
            val tol = options.priorScaleTolerance
            val steps = max(1, (tol / 0.01).roundToInt())
            (-steps..steps).map { prior.scale * exp(it * tol / steps) }
        } else {
            val lo = ln(max(1e-3, minScale))
            val hi = ln(max(minScale, maxScale))
            val steps = max(1, ((hi - lo) / 0.025).roundToInt())
            (0..steps).map { exp(lo + (hi - lo) * it / steps) }
        }
        val rotation = prior?.rotationDegrees ?: 0.0
        val pcx = p.width / 2.0
        val pcy = p.height / 2.0
        val anchorX = prior?.mapX(pcx, pcy) ?: (s.width / 2.0)
        val anchorY = prior?.mapY(pcx, pcy) ?: (s.height / 2.0)

        // Primary statistics over the fixed region.
        val n = rw * rh
        var sumP = 0.0
        var sumP2 = 0.0
        val pr = FloatArray(n)
        for (y in 0 until rh) for (x in 0 until rw) {
            val v = p[rx0 + x, ry0 + y]
            pr[y * rw + x] = v
            sumP += v
            sumP2 += v * v
        }
        val meanP = sumP / n
        val varP = sumP2 - n * meanP * meanP
        if (varP <= 1e-6) return emptyList()
        for (i in pr.indices) pr[i] = (pr[i] - meanP).toFloat()

        val bw = rw + 2 * radius
        val bh = rh + 2 * radius
        val buffer = FloatArray(bw * bh)
        val satStride = bw + 1
        val sat = DoubleArray(satStride * (bh + 1))
        val sat2 = DoubleArray(satStride * (bh + 1))

        val bestPerScale = mutableListOf<Coarse>()
        for (scale in scales) {
            var best: Coarse? = null
            var bestScore = -2.0
            if (scale < minScale * 0.999 || scale > maxScale * 1.001) continue
            val t = Similarity.of(scale, rotation, 0.0, 0.0).anchoredAt(pcx, pcy, anchorX, anchorY)
            for (by in 0 until bh) {
                val py = ry0 - radius + by + 0.5
                for (bx in 0 until bw) {
                    val px = rx0 - radius + bx + 0.5
                    buffer[by * bw + bx] = s.sample(t.mapX(px, py), t.mapY(px, py))
                }
            }
            for (y in 0 until bh) {
                var row = 0.0
                var row2 = 0.0
                for (x in 0 until bw) {
                    val v = buffer[y * bw + x].toDouble()
                    row += v
                    row2 += v * v
                    sat[(y + 1) * satStride + x + 1] = sat[y * satStride + x + 1] + row
                    sat2[(y + 1) * satStride + x + 1] = sat2[y * satStride + x + 1] + row2
                }
            }
            for (dy in 0..2 * radius) {
                for (dx in 0..2 * radius) {
                    var cross = 0.0
                    for (y in 0 until rh) {
                        val pRow = y * rw
                        val bRow = (y + dy) * bw + dx
                        var acc = 0f
                        for (x in 0 until rw) acc += pr[pRow + x] * buffer[bRow + x]
                        cross += acc
                    }
                    val x1 = dx + rw
                    val y1 = dy + rh
                    val sumB = sat[y1 * satStride + x1] - sat[dy * satStride + x1] - sat[y1 * satStride + dx] + sat[dy * satStride + dx]
                    val sumB2 = sat2[y1 * satStride + x1] - sat2[dy * satStride + x1] - sat2[y1 * satStride + dx] + sat2[dy * satStride + dx]
                    val varB = sumB2 - sumB * sumB / n
                    if (varB <= 1e-6) continue
                    // pr is zero mean, so the mean of B drops out of the cross term.
                    var score = cross / sqrt(varP * varB)
                    // Gentle preference for small shifts breaks ties on repetitive texture.
                    val ddx = (dx - radius).toDouble() / radius
                    val ddy = (dy - radius).toDouble() / radius
                    score -= 0.01 * hypot(ddx, ddy)
                    if (score > bestScore) {
                        bestScore = score
                        // Matching transform: x -> t(x + d)
                        val ox = (dx - radius).toDouble()
                        val oy = (dy - radius).toDouble()
                        best = Coarse(
                            Similarity(t.a, t.b, t.tx + t.a * ox - t.b * oy, t.ty + t.b * ox + t.a * oy),
                            score
                        )
                    }
                }
            }
            best?.let { bestPerScale += it }
        }
        return bestPerScale.sortedByDescending { it.score }
    }

    /**
     * Robust Gauss-Newton on the residual `S(T(x)) - P(x)` of the normalised images.
     *
     * Parameters are expressed around the primary centre (`a, b, tau_x, tau_y` with
     * `tau = T(centre)`), which keeps the normal equations well conditioned.
     * Returns the refined transform and the number of iterations used.
     */
    private fun refine(
        p: GrayImage,
        s: GrayImage,
        sgx: GrayImage,
        sgy: GrayImage,
        initial: Similarity,
        maxIterations: Int,
        estimateScaleRotation: Boolean,
        estimateRotation: Boolean,
        window: Window?,
        maxSamples: Int
    ): Pair<Similarity, Int> {
        val x0 = window?.x0?.coerceAtLeast(BORDER) ?: BORDER
        val y0 = window?.y0?.coerceAtLeast(BORDER) ?: BORDER
        val x1 = window?.x1?.coerceAtMost(p.width - BORDER) ?: (p.width - BORDER)
        val y1 = window?.y1?.coerceAtMost(p.height - BORDER) ?: (p.height - BORDER)
        if (x1 - x0 < 4 || y1 - y0 < 4) return initial to 0
        val area = (x1 - x0).toLong() * (y1 - y0)
        val step = max(1, sqrt(area.toDouble() / maxSamples).roundToInt())

        val cx = p.width / 2.0
        val cy = p.height / 2.0
        var a = initial.a
        var b = initial.b
        var taux = initial.mapX(cx, cy)
        var tauy = initial.mapY(cx, cy)

        // Index of the parameters actually solved for.
        val params = when {
            !estimateScaleRotation -> intArrayOf(2, 3)
            estimateRotation -> intArrayOf(0, 1, 2, 3)
            else -> intArrayOf(0, 2, 3)
        }
        val m = params.size
        val capacity = (((x1 - x0) / step) + 1) * (((y1 - y0) / step) + 1)
        val residuals = FloatArray(capacity)
        val absResiduals = FloatArray(capacity)
        val jac = Array(4) { FloatArray(capacity) }
        val maxRadius = hypot(p.width / 2.0, p.height / 2.0)

        var iteration = 0
        while (iteration < maxIterations) {
            iteration++
            var count = 0
            var y = y0
            while (y < y1) {
                val v = y + 0.5 - cy
                var x = x0
                while (x < x1) {
                    val u = x + 0.5 - cx
                    val sx = a * u - b * v + taux
                    val sy = b * u + a * v + tauy
                    if (s.containsForSampling(sx, sy)) {
                        val e = s.sample(sx, sy) - p[x, y]
                        val gx = sgx.sample(sx, sy)
                        val gy = sgy.sample(sx, sy)
                        residuals[count] = e
                        absResiduals[count] = abs(e)
                        jac[0][count] = (gx * u + gy * v).toFloat()
                        jac[1][count] = (-gx * v + gy * u).toFloat()
                        jac[2][count] = gx
                        jac[3][count] = gy
                        count++
                    }
                    x += step
                }
                y += step
            }
            if (count < 50) break

            val sigma = max(1e-3, 1.4826 * median(absResiduals, count))
            val c = CAUCHY_C * sigma
            val h = DoubleArray(m * m)
            val g = DoubleArray(m)
            for (i in 0 until count) {
                val e = residuals[i].toDouble()
                val r = e / c
                val w = 1.0 / (1.0 + r * r)
                for (j in 0 until m) {
                    val jj = jac[params[j]][i].toDouble()
                    g[j] += w * jj * e
                    for (k in j until m) {
                        h[j * m + k] += w * jj * jac[params[k]][i]
                    }
                }
            }
            for (j in 0 until m) for (k in 0 until j) h[j * m + k] = h[k * m + j]
            // Tiny Levenberg damping keeps degenerate (texture-less) windows stable.
            for (j in 0 until m) h[j * m + j] *= 1.0 + 1e-6
            val delta = solve(h, g.map { -it }.toDoubleArray(), m) ?: break

            var change = 0.0
            for (j in 0 until m) {
                when (params[j]) {
                    0 -> { a += delta[j]; change = max(change, abs(delta[j]) * maxRadius) }
                    1 -> { b += delta[j]; change = max(change, abs(delta[j]) * maxRadius) }
                    2 -> { taux += delta[j]; change = max(change, abs(delta[j])) }
                    3 -> { tauy += delta[j]; change = max(change, abs(delta[j])) }
                }
            }
            if (change < 0.01) break
        }
        val result = Similarity(a, b, 0.0, 0.0).anchoredAt(cx, cy, taux, tauy)
        return result to iteration
    }

    private fun zncc(p: GrayImage, s: GrayImage, t: Similarity): Double {
        val step = max(1, sqrt(p.width.toDouble() * p.height / 120_000).roundToInt())
        var n = 0
        var sp = 0.0
        var ss = 0.0
        var spp = 0.0
        var sss = 0.0
        var sps = 0.0
        var y = BORDER
        while (y < p.height - BORDER) {
            var x = BORDER
            while (x < p.width - BORDER) {
                val px = x + 0.5
                val py = y + 0.5
                val sx = t.mapX(px, py)
                val sy = t.mapY(px, py)
                if (s.containsForSampling(sx, sy)) {
                    val a = p[x, y].toDouble()
                    val b = s.sample(sx, sy).toDouble()
                    n++
                    sp += a
                    ss += b
                    spp += a * a
                    sss += b * b
                    sps += a * b
                }
                x += step
            }
            y += step
        }
        if (n < 50) return 0.0
        val cov = sps - sp * ss / n
        val vp = spp - sp * sp / n
        val vs = sss - ss * ss / n
        return if (vp > 0 && vs > 0) cov / sqrt(vp * vs) else 0.0
    }

    private fun median(values: FloatArray, count: Int): Double {
        if (count == 0) return 0.0
        // Subsample large sets: the median of 20k values is plenty for a noise estimate.
        val stride = max(1, count / 20_000)
        val copy = FloatArray((count + stride - 1) / stride) { values[min(it * stride, count - 1)] }
        copy.sort()
        return copy[copy.size / 2].toDouble()
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(matrix: DoubleArray, rhs: DoubleArray, n: Int): DoubleArray? {
        val a = matrix.copyOf()
        val b = rhs.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r * n + col]) > abs(a[pivot * n + col])) pivot = r
            if (abs(a[pivot * n + col]) < 1e-12) return null
            if (pivot != col) {
                for (k in 0 until n) {
                    val tmp = a[col * n + k]; a[col * n + k] = a[pivot * n + k]; a[pivot * n + k] = tmp
                }
                val tmp = b[col]; b[col] = b[pivot]; b[pivot] = tmp
            }
            for (r in col + 1 until n) {
                val f = a[r * n + col] / a[col * n + col]
                for (k in col until n) a[r * n + k] -= f * a[col * n + k]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var sum = b[r]
            for (k in r + 1 until n) sum -= a[r * n + k] * x[k]
            x[r] = sum / a[r * n + r]
        }
        return x
    }
}
