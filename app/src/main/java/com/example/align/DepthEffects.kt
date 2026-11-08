package com.example.align

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Depth based finishing of a wiggle pair, after both frames are in the same geometry and
 * the parallax runs along the image x axis (the lens baseline in portrait):
 *
 *  - [StereoMatcher]  dense disparity map (how far each pixel moves between the frames),
 *  - [FocusFinder]    which depth the main lens focused on (sharpness main vs ultra wide),
 *  - [LensBlur]       gives the ultra wide the main lens' shallow depth of field,
 *  - [ViewSynthesis]  in-between views, so the wiggle turns smoothly around the subject.
 *
 * Pure Kotlin (no Android), so it is tested on the JVM with real captures.
 */

/** Dense disparity along x in its own pixel grid: `reference(x) ≈ other(x + d(x))`. */
class DisparityMap(val width: Int, val height: Int, val d: FloatArray, val confidence: FloatArray) {

    /** Bilinear sample at continuous coordinates (pixel centres at +0.5), clamped. */
    fun sample(x: Double, y: Double): Float = bilinear(d, width, height, x, y)

    fun sampleConfidence(x: Double, y: Double): Float = bilinear(confidence, width, height, x, y)

    /** `out(x) = d(x + shift) - offset`: re-references the map of a frame shifted by [shift]. */
    fun reReferenced(shift: Double, offset: Double): DisparityMap {
        val out = FloatArray(d.size)
        val conf = FloatArray(d.size)
        for (y in 0 until height) for (x in 0 until width) {
            out[y * width + x] = (sample(x + 0.5 + shift, y + 0.5) - offset).toFloat()
            conf[y * width + x] = sampleConfidence(x + 0.5 + shift, y + 0.5)
        }
        return DisparityMap(width, height, out, conf)
    }

    fun minus(offset: Double): DisparityMap =
        DisparityMap(width, height, FloatArray(d.size) { (d[it] - offset).toFloat() }, confidence)

    /** Median disparity of confident pixels inside the given fractions of the frame. */
    fun median(x0: Double = 0.0, x1: Double = 1.0, y0: Double = 0.0, y1: Double = 1.0, minConfidence: Float = 0.15f): Double? {
        val values = ArrayList<Float>()
        for (y in (y0 * height).toInt() until (y1 * height).toInt()) for (x in (x0 * width).toInt() until (x1 * width).toInt()) {
            val i = y * width + x
            if (confidence[i] >= minConfidence) values += d[i]
        }
        if (values.size < 50) return null
        values.sort()
        return values[values.size / 2].toDouble()
    }

    /** Fraction of pixels with a reliable match. */
    fun reliableFraction(minConfidence: Float = 0.15f): Double = confidence.count { it >= minConfidence }.toDouble() / confidence.size
}

object StereoMatcher {

    private const val WINDOW = 2

    /** SGM runs at about this width (the cost volume grows with width² × range). */
    private const val SGM_MAX_WIDTH = 420
    private const val CENSUS_BITS = 24
    private const val P1 = 3
    private const val P2 = 24

    /** Disparity maps of both frames: `primary(x) ≈ secondary(x + dP(x))`, `secondary(y) ≈ primary(y − dS(y))`. */
    class Result(val primary: DisparityMap, val secondary: DisparityMap)

    /**
     * Dense disparity between two frames of the same size, within ±[maxDisparity] px.
     *
     * Semi-global matching on census transforms (robust against the different exposure,
     * tone and noise of the two lenses) at ~400 px width with a left-right check, then an
     * edge aware fill (weighted guided filter), so flat areas take the depth of their
     * surroundings and depth edges follow image edges. The maps have the matching
     * resolution (inputs wider than ~400 px are halved first; values in map pixels).
     */
    fun matchPair(primary: GrayImage, secondary: GrayImage, maxDisparity: Int): Result {
        require(primary.width == secondary.width && primary.height == secondary.height)
        var factor = 1
        var pc = primary
        var sc = secondary
        while (pc.width > SGM_MAX_WIDTH) {
            pc = pc.halve()
            sc = sc.halve()
            factor *= 2
        }
        val range = max(2, ceil(maxDisparity / factor.toDouble()).toInt())
        val sgm = semiGlobal(pc, sc, range)
        val texP = textureWeight(pc)
        val texS = textureWeight(sc)
        val n = pc.width * pc.height
        val cP = FloatArray(n) { sgm.leftValid[it] * texP[it] }
        val cS = FloatArray(n) { sgm.rightValid[it] * texS[it] }
        return Result(
            DisparityMap(pc.width, pc.height, edgeAwareFill(pc, sgm.left, cP), cP),
            DisparityMap(sc.width, sc.height, edgeAwareFill(sc, sgm.right, cS), cS)
        )
    }

    private class Sgm(val left: FloatArray, val leftValid: FloatArray, val right: FloatArray, val rightValid: FloatArray)

    /**
     * Semi-global matching (4 paths) of census costs. Returns the disparity of the left
     * (primary) and, from the same cost volume, of the right (secondary) frame, each with
     * validity from a left-right consistency check.
     */
    private fun semiGlobal(left: GrayImage, right: GrayImage, range: Int): Sgm {
        val w = left.width
        val h = left.height
        val n = w * h
        val dCount = 2 * range + 1
        val cl = census(left)
        val cr = census(right)
        // Cost volume, index (y * w + x) * dCount + k, disparity = k - range.
        val cost = ByteArray(n * dCount)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val base = (row + x) * dCount
                val c = cl[row + x]
                for (k in 0 until dCount) {
                    val xr = x + k - range
                    cost[base + k] = if (xr < 0 || xr >= w) CENSUS_BITS.toByte() else Integer.bitCount(c xor cr[row + xr]).toByte()
                }
            }
        }
        val sum = ShortArray(n * dCount)
        val g = left.data
        val prev = IntArray(dCount)
        val cur = IntArray(dCount)
        // Horizontal paths, both directions.
        for (dir in intArrayOf(1, -1)) {
            for (y in 0 until h) {
                val row = y * w
                var x = if (dir == 1) 0 else w - 1
                var first = true
                var prevMin = 0
                var prevGray = 0f
                while (x in 0 until w) {
                    val base = (row + x) * dCount
                    if (first) {
                        for (k in 0 until dCount) cur[k] = cost[base + k].toInt()
                        first = false
                    } else {
                        val p2 = penalty(g[row + x], prevGray)
                        step(cost, base, prev, prevMin, p2, cur, dCount)
                    }
                    var m = Int.MAX_VALUE
                    for (k in 0 until dCount) {
                        sum[base + k] = (sum[base + k] + cur[k]).toShort()
                        prev[k] = cur[k]
                        if (cur[k] < m) m = cur[k]
                    }
                    prevMin = m
                    prevGray = g[row + x]
                    x += dir
                }
            }
        }
        // Vertical paths, both directions (one row of state per column).
        val prevRow = IntArray(w * dCount)
        val prevRowMin = IntArray(w)
        for (dir in intArrayOf(1, -1)) {
            var y = if (dir == 1) 0 else h - 1
            var first = true
            while (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val base = (row + x) * dCount
                    val state = x * dCount
                    if (first) {
                        for (k in 0 until dCount) cur[k] = cost[base + k].toInt()
                    } else {
                        for (k in 0 until dCount) prev[k] = prevRow[state + k]
                        val p2 = penalty(g[row + x], g[row - dir * w + x])
                        step(cost, base, prev, prevRowMin[x], p2, cur, dCount)
                    }
                    var m = Int.MAX_VALUE
                    for (k in 0 until dCount) {
                        sum[base + k] = (sum[base + k] + cur[k]).toShort()
                        prevRow[state + k] = cur[k]
                        if (cur[k] < m) m = cur[k]
                    }
                    prevRowMin[x] = m
                }
                first = false
                y += dir
            }
        }

        // Winner takes all with sub-pixel refinement, for the left and the right frame.
        val dl = FloatArray(n)
        val dr = FloatArray(n)
        val bestRight = IntArray(n) { Int.MAX_VALUE }
        val bestRightK = IntArray(n) { -1 }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val base = (row + x) * dCount
                var best = 0
                var bestCost = Int.MAX_VALUE
                for (k in 0 until dCount) {
                    val c = sum[base + k].toInt()
                    if (c < bestCost) {
                        bestCost = c
                        best = k
                    }
                    val xr = x + k - range
                    if (xr in 0 until w && c < bestRight[row + xr]) {
                        bestRight[row + xr] = c
                        bestRightK[row + xr] = k
                    }
                }
                dl[row + x] = best - range + parabola(sum, base, best, dCount)
            }
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val k = bestRightK[row + x]
                if (k < 0) {
                    dr[row + x] = 0f
                    continue
                }
                val xl = x - (k - range)
                dr[row + x] = (k - range) + if (xl in 0 until w) parabola(sum, (row + xl) * dCount, k, dCount) else 0f
            }
        }
        val lv = FloatArray(n)
        val rv = FloatArray(n)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val xr = (x + dl[i]).roundToInt()
                lv[i] = if (xr in 0 until w && abs(dr[row + xr] - dl[i]) <= 1f) 1f else 0f
                val xl = (x - dr[i]).roundToInt()
                rv[i] = if (bestRightK[i] >= 0 && xl in 0 until w && abs(dl[row + xl] - dr[i]) <= 1f) 1f else 0f
            }
        }
        return Sgm(dl, lv, dr, rv)
    }

    private fun penalty(a: Float, b: Float): Int = max(P1 + 1, (P2 / (1f + abs(a - b) / 12f)).toInt())

    private fun step(cost: ByteArray, base: Int, prev: IntArray, prevMin: Int, p2: Int, out: IntArray, dCount: Int) {
        for (k in 0 until dCount) {
            var best = prev[k]
            if (k > 0 && prev[k - 1] + P1 < best) best = prev[k - 1] + P1
            if (k < dCount - 1 && prev[k + 1] + P1 < best) best = prev[k + 1] + P1
            if (prevMin + p2 < best) best = prevMin + p2
            out[k] = cost[base + k] + best - prevMin
        }
    }

    private fun parabola(sum: ShortArray, base: Int, k: Int, count: Int): Float {
        if (k <= 0 || k >= count - 1) return 0f
        val c0 = sum[base + k - 1].toFloat()
        val c1 = sum[base + k].toFloat()
        val c2 = sum[base + k + 1].toFloat()
        val denom = c0 - 2 * c1 + c2
        return if (denom > 1e-6f) (0.5f * (c0 - c2) / denom).coerceIn(-0.5f, 0.5f) else 0f
    }

    /** 5×5 census: one bit per neighbour, set if it is brighter than the centre. */
    private fun census(image: GrayImage): IntArray {
        val w = image.width
        val h = image.height
        val src = image.gaussianBlur(0.6).data
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = src[y * w + x]
            var bits = 0
            for (dy in -2..2) for (dx in -2..2) {
                if (dx == 0 && dy == 0) continue
                val v = src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                bits = (bits shl 1) or (if (v > c + 0.5f) 1 else 0)
            }
            out[y * w + x] = bits
        }
        return out
    }

    /** 0 on flat (noise only) areas, towards 1 on clear structure. */
    private fun textureWeight(image: GrayImage): FloatArray {
        val std = localStd(image.data, image.width, image.height, WINDOW)
        return FloatArray(std.size) { val t = std[it]; t * t / (t * t + 36f) }
    }

    /**
     * Weighted guided filter: flat or ambiguous areas get the disparity of their (image
     * wise) similar surroundings; a small and a large radius are blended by how much
     * reliable data is nearby.
     */
    private fun edgeAwareFill(guide: GrayImage, d: FloatArray, confidence: FloatArray): FloatArray {
        val w = guide.width
        val h = guide.height
        val n = w * h
        val g = FloatArray(n) { guide.data[it] / 255f }
        val dc = FloatArray(n) { d[it] * confidence[it] }
        val small = max(3, w / 120)
        val large = max(12, w / 25)
        val num1 = guidedFilter(g, dc, w, h, small, 1e-3f)
        val den1 = guidedFilter(g, confidence, w, h, small, 1e-3f)
        val num2 = guidedFilter(g, dc, w, h, large, 1e-3f)
        val den2 = guidedFilter(g, confidence, w, h, large, 1e-3f)
        // Very large areas without structure: a heavy box average of the reliable values.
        val num3 = boxMean(dc, w, h, max(24, w / 8))
        val den3 = boxMean(confidence, w, h, max(24, w / 8))
        var total = 0.0
        var weight = 0.0
        for (i in 0 until n) {
            total += dc[i]
            weight += confidence[i]
        }
        val global = if (weight > 1e-6) (total / weight).toFloat() else 0f
        val out = FloatArray(n)
        for (i in 0 until n) {
            val w3 = den3[i]
            val wide = if (w3 > 1e-4f) num3[i] / w3 else global
            val a3 = (w3 / (w3 + 0.005f)).coerceIn(0f, 1f)
            val base = a3 * wide + (1 - a3) * global
            val w2 = den2[i]
            val mid = if (w2 > 1e-3f) num2[i] / w2 else base
            val a2 = (w2 / (w2 + 0.03f)).coerceIn(0f, 1f)
            val broad = a2 * mid + (1 - a2) * base
            val w1 = den1[i]
            val near = if (w1 > 1e-3f) num1[i] / w1 else broad
            val a1 = (w1 / (w1 + 0.08f)).coerceIn(0f, 1f)
            out[i] = a1 * near + (1 - a1) * broad
        }
        return out
    }
}

/** Which depth the main lens is focused on, from where it is as sharp as the ultra wide. */
object FocusFinder {

    private const val BINS = 20

    /**
     * Focus disparity (in [map] px) or null if the main lens is sharp at all depths (deep
     * depth of field) or there is too little structure to tell.
     */
    fun focusDisparity(reference: GrayImage, other: GrayImage, map: DisparityMap): Double? {
        val w = reference.width
        val h = reference.height
        // The ultra wide, moved onto the main frame's pixels.
        val aligned = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            aligned[y * w + x] = other.sample(x + 0.5 + map.d[y * w + x], y + 0.5)
        }
        val eP = laplacianEnergy(reference.gaussianBlur(0.6).data, w, h)
        val eS = laplacianEnergy(GrayImage(w, h, aligned).gaussianBlur(0.6).data, w, h)

        val reliable = ArrayList<Int>()
        for (i in 0 until w * h) if (map.confidence[i] >= 0.2f && eS[i] > 1.5f) reliable += i
        if (reliable.size < 500) return null
        val sorted = reliable.map { map.d[it] }.sorted()
        val lo = sorted[(sorted.size * 0.02).toInt()]
        val hi = sorted[(sorted.size * 0.98).toInt()]
        if (hi - lo < 2f) return null

        val sumP = DoubleArray(BINS)
        val sumS = DoubleArray(BINS)
        val sumD = DoubleArray(BINS)
        val count = IntArray(BINS)
        for (i in reliable) {
            val b = (((map.d[i] - lo) / (hi - lo)) * BINS).toInt()
            if (b < 0 || b >= BINS) continue
            sumP[b] += eP[i]
            sumS[b] += eS[i]
            sumD[b] += map.d[i]
            count[b]++
        }
        val minCount = max(150, reliable.size / 200)
        val ratios = (0 until BINS).filter { count[it] >= minCount }.associateWith { sumP[it] / sumS[it] }
        if (ratios.size < 3) return null
        val best = ratios.maxBy { it.value }.value
        val typical = ratios.values.sorted()[ratios.size / 2]
        // A clear peak means the main lens is sharp at one depth and soft elsewhere.
        if (best < typical * 1.2) return null
        // Centre of the depths that are about as sharp as the best one.
        val threshold = typical + 0.5 * (best - typical)
        var sum = 0.0
        var weight = 0.0
        for ((b, r) in ratios) {
            if (r < threshold) continue
            val wgt = count[b] * (r - threshold + 1e-3)
            sum += wgt * sumD[b] / count[b]
            weight += wgt
        }
        return if (weight > 0) sum / weight else null
    }

    private fun laplacianEnergy(src: FloatArray, w: Int, h: Int): FloatArray {
        val lap = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            lap[i] = abs(4 * src[i] - src[i - 1] - src[i + 1] - src[i - w] - src[i + w])
        }
        return boxMean(lap, w, h, 2)
    }
}

/**
 * Gives the ultra wide frame the main lens' depth of field. The blur diameter of the main
 * lens grows with the distance from the focus plane in diopters, and so does the parallax:
 * `blur = (aperture diameter / baseline) × |disparity − focus disparity|` (both in pixels).
 */
object LensBlur {

    /** Blur diameters (output px) of the precomputed levels; level 0 is the sharp frame. */
    private val LEVELS = doubleArrayOf(0.0, 3.0, 6.0, 10.0, 15.0, 22.0, 32.0, 45.0, 64.0)

    class Stats(val maxDiameter: Double, val elapsedMs: Long)

    /** One blur level, downsampled by [factor], once for each side of the focus plane. */
    private class Level(val factor: Int, val width: Int, val height: Int, val far: IntArray, val near: IntArray)

    /**
     * Returns a blurred copy of [src] (ARGB, [w]×[h]) or null if nothing needs blurring.
     * [map] is the disparity of this frame (map pixels), [mapScale] output px per map px,
     * [focus] the disparity of the focus plane; [ratio] and [ownRatio] are aperture
     * diameter / baseline of the main lens and of this (ultra wide) lens.
     */
    fun apply(
        src: IntArray,
        w: Int,
        h: Int,
        map: DisparityMap,
        mapScale: Double,
        focus: Double,
        ratio: Double,
        ownRatio: Double
    ): Pair<IntArray, Stats>? {
        val start = System.nanoTime()
        val k = sqrt(max(0.0, ratio * ratio - ownRatio * ownRatio))
        var maxDelta = 0.0
        for (v in map.d) maxDelta = max(maxDelta, abs(v - focus))
        val maxDiameter = maxDelta * mapScale * k
        if (maxDiameter < 1.5) return null
        val used = min(LEVELS.size, LEVELS.count { it <= maxDiameter } + 1)
        val levels = LEVELS.copyOf(max(2, used))

        // Side of the focus plane per map pixel: far (1) or near (0). A blurred far area
        // must not pick up the sharp subject in front of it, and vice versa.
        val farMask = FloatArray(map.d.size) { if (map.d[it] < focus) 1f else 0f }
        val farShare = farMask.average()

        // Level 1: small blur (binomial 3×3, ~3 px) at full resolution, no masks needed.
        val level1 = binomial3(src, w, h)
        val coarse = ArrayList<Level>()
        // Downsampled colour planes and side masks, built once per factor (each from the
        // previous one).
        val planeCache = HashMap<Int, Array<FloatArray>>()
        val maskCache = HashMap<Int, FloatArray>()
        fun sizeAt(f: Int) = ((w + f - 1) / f) to ((h + f - 1) / f)
        // Every coarse level is at least 2× downsampled, so full resolution float planes are
        // never needed (they would cost ~12 bytes per pixel).
        fun planesAt(f: Int): Array<FloatArray> = planeCache.getOrPut(f) {
            if (f <= 2) {
                downsampleArgb(src, w, h, 2)
            } else {
                val (pw, ph) = sizeAt(f / 2)
                planesAt(f / 2).let { prev -> Array(3) { c -> downsample(prev[c], pw, ph, 2) } }
            }
        }
        fun maskAt(f: Int): FloatArray = maskCache.getOrPut(f) {
            val (lw, lh) = sizeAt(f)
            val mask = FloatArray(lw * lh)
            for (y in 0 until lh) for (x in 0 until lw) {
                mask[y * lw + x] = bilinear(farMask, map.width, map.height, (x + 0.5) * f / mapScale, (y + 0.5) * f / mapScale)
            }
            mask
        }
        for (li in 2 until levels.size) {
            val diameter = levels[li]
            var f = 2
            while (f < 16 && diameter / (4.0 * f * 2) >= 0.75) f *= 2
            val (lw, lh) = sizeAt(f)
            val planes = planesAt(f)
            val mask = maskAt(f)
            val sigma = diameter / (4.0 * f)
            val sides = Array(2) { side ->
                val share = if (side == 0) farShare else 1 - farShare
                if (share < 0.001) return@Array IntArray(0)
                val m = if (side == 0) mask else FloatArray(mask.size) { 1f - mask[it] }
                val den = gaussian(m, lw, lh, sigma)
                val blurred = Array(3) { c ->
                    val pl = planes[c]
                    gaussian(FloatArray(pl.size) { pl[it] * m[it] }, lw, lh, sigma)
                }
                IntArray(lw * lh) { i ->
                    val dn = den[i]
                    if (dn > 1e-3f) {
                        pack(blurred[0][i] / dn, blurred[1][i] / dn, blurred[2][i] / dn)
                    } else {
                        pack(planes[0][i], planes[1][i], planes[2][i])
                    }
                }
            }
            coarse += Level(f, lw, lh, sides[0], sides[1])
        }

        // Signed blur diameter per map pixel (negative = behind the focus plane), then
        // interpolated per output pixel.
        val signed = FloatArray(map.d.size) { ((map.d[it] - focus) * mapScale * k).toFloat() }
        val grid = GridSampler(map.width, map.height, w, h, mapScale)
        val out = IntArray(src.size)
        for (y in 0 until h) {
            grid.row(y)
            for (x in 0 until w) {
                val i = y * w + x
                val sd = grid.sample(signed, x)
                val diameter = abs(sd).toDouble()
                if (diameter < 0.4) {
                    out[i] = src[i]
                    continue
                }
                val far = sd < 0
                var hi = 1
                while (hi < levels.size - 1 && levels[hi] < diameter) hi++
                val lo = hi - 1
                val t = ((diameter - levels[lo]) / (levels[hi] - levels[lo])).coerceIn(0.0, 1.0)
                val a = value(lo, i, x, y, src, level1, coarse, far)
                out[i] = if (t <= 0.0) a else mix(a, value(hi, i, x, y, src, level1, coarse, far), t)
            }
        }
        return out to Stats(maxDiameter, (System.nanoTime() - start) / 1_000_000)
    }

    private fun value(level: Int, i: Int, x: Int, y: Int, src: IntArray, level1: IntArray, coarse: List<Level>, far: Boolean): Int = when (level) {
        0 -> src[i]
        1 -> level1[i]
        else -> {
            val l = coarse[level - 2]
            val data = if (far) l.far else l.near
            if (data.isEmpty()) src[i] else sampleArgb(data, l.width, l.height, (x + 0.5) / l.factor, (y + 0.5) / l.factor)
        }
    }
}

/**
 * In-between views of the wiggle: each pixel moves the fraction t of its parallax, taken
 * from the main frame (t = 0) and the ultra wide (t = 1) and blended by t. Where one lens
 * cannot see a point (it is hidden behind something nearer), only the other one is used.
 */
object ViewSynthesis {

    /**
     * [primaryMap] / [secondaryMap]: disparity relative to the pivot (map px) of the main and
     * of the ultra wide frame, so a point at x in the main frame is at x + d in the ultra wide.
     */
    fun render(
        t: Double,
        primary: IntArray,
        secondary: IntArray,
        w: Int,
        h: Int,
        primaryMap: DisparityMap,
        secondaryMap: DisparityMap,
        occlusionPrimary: FloatArray,
        occlusionSecondary: FloatArray,
        mapScale: Double
    ): IntArray {
        // Views before the middle come from the main frame, the others from the ultra wide:
        // blending two different cameras would show their differences as ghosts and patches.
        val fromPrimary = t <= 0.5
        val map = if (fromPrimary) primaryMap else secondaryMap
        val other = if (fromPrimary) secondaryMap else primaryMap
        val occlusion = if (fromPrimary) occlusionPrimary else occlusionSecondary
        val source = if (fromPrimary) primary else secondary
        val fallback = if (fromPrimary) secondary else primary
        // Displacement of the source: primary moves by +t·d, the ultra wide back by −(1−t)·d.
        val move = if (fromPrimary) t else -(1 - t)
        val otherMove = if (fromPrimary) -(1 - t) else t
        val mw = map.width
        val mh = map.height
        // Source offsets (map px) per map pixel, by fixed-point iteration, and how hidden
        // that source point is; then interpolated per output pixel.
        val offset = FloatArray(mw * mh)
        val otherOffset = FloatArray(mw * mh)
        val hiddenAt = FloatArray(mw * mh)
        for (my in 0 until mh) for (mx in 0 until mw) {
            val xc = mx + 0.5
            val yc = my + 0.5
            var xs = xc
            repeat(4) { xs = xc - move * map.sample(xs, yc) }
            var xo = xc
            repeat(4) { xo = xc - otherMove * other.sample(xo, yc) }
            val i = my * mw + mx
            offset[i] = (xs - xc).toFloat()
            otherOffset[i] = (xo - xc).toFloat()
            hiddenAt[i] = bilinear(occlusion, mw, mh, xs, yc)
        }
        val grid = GridSampler(mw, mh, w, h, mapScale)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            grid.row(y)
            val yc = y + 0.5
            for (x in 0 until w) {
                val xc = x + 0.5
                val c = sampleArgb(source, w, h, xc + mapScale * grid.sample(offset, x), yc)
                val hidden = grid.sample(hiddenAt, x)
                out[y * w + x] = if (hidden < 0.05f) {
                    c
                } else {
                    // Revealed area this frame never saw: take it from the other frame.
                    mix(c, sampleArgb(fallback, w, h, xc + mapScale * grid.sample(otherOffset, x), yc), hidden.toDouble())
                }
            }
        }
        return out
    }

    /**
     * Per map pixel: how much the point is hidden in the other frame (0 visible .. 1 hidden),
     * because the other frame sees something nearer at the place it would move to.
     */
    fun occlusion(map: DisparityMap, otherMap: DisparityMap, direction: Double): FloatArray {
        val out = FloatArray(map.d.size)
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val i = y * map.width + x
            val d = map.d[i]
            val there = otherMap.sample(x + 0.5 + direction * d, y + 0.5)
            out[i] = ((there - d - 1.5f) / 2f).coerceIn(0f, 1f)
        }
        return out
    }
}

/**
 * Bilinear sampling of a coarse map grid at every output pixel: column weights are
 * precomputed once, row weights once per row.
 */
internal class GridSampler(private val mw: Int, private val mh: Int, w: Int, h: Int, scale: Double) {
    private val x0 = IntArray(w)
    private val x1 = IntArray(w)
    private val fx = FloatArray(w)
    private val rowScale = scale
    private var r0 = 0
    private var r1 = 0
    private var fy = 0f

    init {
        for (x in 0 until w) {
            val u = ((x + 0.5) / scale - 0.5).coerceIn(0.0, (mw - 1).toDouble())
            x0[x] = min(floor(u).toInt(), mw - 1)
            x1[x] = min(x0[x] + 1, mw - 1)
            fx[x] = (u - x0[x]).toFloat()
        }
    }

    fun row(y: Int) {
        val v = ((y + 0.5) / rowScale - 0.5).coerceIn(0.0, (mh - 1).toDouble())
        val y0 = min(floor(v).toInt(), mh - 1)
        r0 = y0 * mw
        r1 = min(y0 + 1, mh - 1) * mw
        fy = (v - y0).toFloat()
    }

    fun sample(data: FloatArray, x: Int): Float {
        val a = x0[x]
        val b = x1[x]
        val f = fx[x]
        val top = data[r0 + a] + (data[r0 + b] - data[r0 + a]) * f
        val bottom = data[r1 + a] + (data[r1 + b] - data[r1 + a]) * f
        return top + (bottom - top) * fy
    }
}

/** 3×3 binomial blur of packed ARGB (σ ≈ 0.7 px). */
internal fun binomial3(src: IntArray, w: Int, h: Int): IntArray {
    val tmp = IntArray(src.size)
    val out = IntArray(src.size)
    // Horizontal pass, channels kept ×4 in 10 bits each.
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val l = src[row + (x - 1).coerceAtLeast(0)]
            val c = src[row + x]
            val r = src[row + (x + 1).coerceAtMost(w - 1)]
            val rr = ((l shr 16) and 0xFF) + 2 * ((c shr 16) and 0xFF) + ((r shr 16) and 0xFF)
            val gg = ((l shr 8) and 0xFF) + 2 * ((c shr 8) and 0xFF) + ((r shr 8) and 0xFF)
            val bb = (l and 0xFF) + 2 * (c and 0xFF) + (r and 0xFF)
            tmp[row + x] = (rr shl 20) or (gg shl 10) or bb
        }
    }
    for (y in 0 until h) {
        val up = (y - 1).coerceAtLeast(0) * w
        val row = y * w
        val down = (y + 1).coerceAtMost(h - 1) * w
        for (x in 0 until w) {
            val a = tmp[up + x]
            val c = tmp[row + x]
            val b = tmp[down + x]
            val rr = (((a shr 20) and 0x3FF) + 2 * ((c shr 20) and 0x3FF) + ((b shr 20) and 0x3FF) + 8) shr 4
            val gg = (((a shr 10) and 0x3FF) + 2 * ((c shr 10) and 0x3FF) + ((b shr 10) and 0x3FF) + 8) shr 4
            val bb = ((a and 0x3FF) + 2 * (c and 0x3FF) + (b and 0x3FF) + 8) shr 4
            out[row + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }
    return out
}

internal fun mix(a: Int, b: Int, t: Double): Int {
    val ti = (t * 256).roundToInt().coerceIn(0, 256)
    val r = (((a shr 16) and 0xFF) * (256 - ti) + ((b shr 16) and 0xFF) * ti) shr 8
    val g = (((a shr 8) and 0xFF) * (256 - ti) + ((b shr 8) and 0xFF) * ti) shr 8
    val bl = ((a and 0xFF) * (256 - ti) + (b and 0xFF) * ti) shr 8
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

/** Bilinear ARGB sample at continuous coordinates, clamped. */
internal fun sampleArgb(data: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
    val u = (x - 0.5).coerceIn(0.0, (w - 1).toDouble())
    val v = (y - 0.5).coerceIn(0.0, (h - 1).toDouble())
    val x0 = min(floor(u).toInt(), w - 1)
    val y0 = min(floor(v).toInt(), h - 1)
    val x1 = min(x0 + 1, w - 1)
    val y1 = min(y0 + 1, h - 1)
    val fx = u - x0
    val fy = v - y0
    val top = mix(data[y0 * w + x0], data[y0 * w + x1], fx)
    val bottom = mix(data[y1 * w + x0], data[y1 * w + x1], fx)
    return mix(top, bottom, fy)
}

// ---------------------------------------------------------------------------------------
// Shared helpers

internal fun bilinear(data: FloatArray, w: Int, h: Int, x: Double, y: Double): Float {
    val u = (x - 0.5).coerceIn(0.0, (w - 1).toDouble())
    val v = (y - 0.5).coerceIn(0.0, (h - 1).toDouble())
    val x0 = min(floor(u).toInt(), w - 1)
    val y0 = min(floor(v).toInt(), h - 1)
    val x1 = min(x0 + 1, w - 1)
    val y1 = min(y0 + 1, h - 1)
    val fx = (u - x0).toFloat()
    val fy = (v - y0).toFloat()
    val top = data[y0 * w + x0] + (data[y0 * w + x1] - data[y0 * w + x0]) * fx
    val bottom = data[y1 * w + x0] + (data[y1 * w + x1] - data[y1 * w + x0]) * fx
    return top + (bottom - top) * fy
}

/** Box mean with radius [r] (clamped edges), double accumulators against drift. */
internal fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(src.size)
    val norm = 1.0 / (2 * r + 1)
    for (y in 0 until h) {
        val row = y * w
        var acc = 0.0
        for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            tmp[row + x] = (acc * norm).toFloat()
            acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
        }
    }
    val out = FloatArray(src.size)
    val acc = DoubleArray(w)
    for (k in -r..r) {
        val row = k.coerceIn(0, h - 1) * w
        for (x in 0 until w) acc[x] += tmp[row + x]
    }
    for (y in 0 until h) {
        val add = (y + r + 1).coerceAtMost(h - 1) * w
        val sub = (y - r).coerceAtLeast(0) * w
        val row = y * w
        for (x in 0 until w) {
            out[row + x] = (acc[x] * norm).toFloat()
            acc[x] += tmp[add + x] - tmp[sub + x]
        }
    }
    return out
}

internal fun guidedFilter(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
    val n = guide.size
    val meanI = boxMean(guide, w, h, r)
    val meanP = boxMean(p, w, h, r)
    val corrIP = boxMean(FloatArray(n) { guide[it] * p[it] }, w, h, r)
    val corrII = boxMean(FloatArray(n) { guide[it] * guide[it] }, w, h, r)
    val a = FloatArray(n)
    val b = FloatArray(n)
    for (i in 0 until n) {
        val varI = max(0f, corrII[i] - meanI[i] * meanI[i])
        val cov = corrIP[i] - meanI[i] * meanP[i]
        a[i] = cov / (varI + eps)
        b[i] = meanP[i] - a[i] * meanI[i]
    }
    val meanA = boxMean(a, w, h, r)
    val meanB = boxMean(b, w, h, r)
    return FloatArray(n) { meanA[it] * guide[it] + meanB[it] }
}

internal fun localStd(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val mean = boxMean(src, w, h, r)
    val sq = boxMean(FloatArray(src.size) { src[it] * src[it] }, w, h, r)
    return FloatArray(src.size) { sqrt(max(0f, sq[it] - mean[it] * mean[it])) }
}

internal fun median3(src: FloatArray, w: Int, h: Int): FloatArray {
    val out = src.copyOf()
    val v = FloatArray(9)
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        var k = 0
        for (dy in -1..1) for (dx in -1..1) v[k++] = src[(y + dy) * w + x + dx]
        v.sort()
        out[y * w + x] = v[4]
    }
    return out
}

internal fun upsample(src: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
    val out = FloatArray(w * h)
    val fx = sw.toDouble() / w
    val fy = sh.toDouble() / h
    for (y in 0 until h) for (x in 0 until w) {
        out[y * w + x] = bilinear(src, sw, sh, (x + 0.5) * fx, (y + 0.5) * fy)
    }
    return out
}

/** Separable Gaussian with an exact kernel (good for small sigmas). */
internal fun gaussian(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
    if (sigma < 0.3) return src.copyOf()
    val r = max(1, ceil(3 * sigma).toInt())
    val kernel = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)).toFloat() }
    val sum = kernel.sum()
    for (i in kernel.indices) kernel[i] /= sum
    val tmp = FloatArray(src.size)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += kernel[k + r] * src[row + (x + k).coerceIn(0, w - 1)]
            tmp[row + x] = acc
        }
    }
    val out = FloatArray(src.size)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += kernel[k + r] * tmp[(y + k).coerceIn(0, h - 1) * w + x]
            out[row + x] = acc
        }
    }
    return out
}

/** R, G, B planes of [argb] averaged over [f]×[f] blocks. */
internal fun downsampleArgb(argb: IntArray, w: Int, h: Int, f: Int): Array<FloatArray> {
    val lw = (w + f - 1) / f
    val lh = (h + f - 1) / f
    val planes = Array(3) { FloatArray(lw * lh) }
    for (y in 0 until lh) for (x in 0 until lw) {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (dy in 0 until f) {
            val yy = y * f + dy
            if (yy >= h) break
            for (dx in 0 until f) {
                val xx = x * f + dx
                if (xx >= w) break
                val p = argb[yy * w + xx]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        val i = y * lw + x
        planes[0][i] = r.toFloat() / n
        planes[1][i] = g.toFloat() / n
        planes[2][i] = b.toFloat() / n
    }
    return planes
}

internal fun pack(r: Float, g: Float, b: Float): Int =
    (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

/** Area average over [f]×[f] blocks (partial blocks at the edges average what exists). */
internal fun downsample(src: FloatArray, w: Int, h: Int, f: Int): FloatArray {
    if (f == 1) return src.copyOf()
    val lw = (w + f - 1) / f
    val lh = (h + f - 1) / f
    val out = FloatArray(lw * lh)
    for (y in 0 until lh) for (x in 0 until lw) {
        var s = 0f
        var n = 0
        for (dy in 0 until f) {
            val yy = y * f + dy
            if (yy >= h) break
            for (dx in 0 until f) {
                val xx = x * f + dx
                if (xx >= w) break
                s += src[yy * w + xx]
                n++
            }
        }
        out[y * lw + x] = s / n
    }
    return out
}
