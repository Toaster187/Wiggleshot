package com.example.align

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * "Disposable camera with flash": a bright, slightly blown subject, a background that drops
 * into darkness, warm colours, hard contrast, a soft plastic lens, vignetting and grain.
 *
 * [falloff] does the lighting with the depth map: a flash next to the lens lights each point
 * with 1/distance², so relative to the focused subject a point gets (D / D_focus)² where D is
 * its distance in diopters. [style] does the rest of the look. Pure Kotlin, ARGB arrays.
 */
object FlashLook {

    /**
     * How much of the light remains where the flash does not reach. The real flash already
     * does most of the falloff; the depth based one only supports it (and errors in the
     * depth map must not turn into dark blotches).
     */
    const val AMBIENT = 0.5f

    /** Exposure of the flash-lit subject (> 1 = a bit blown, like a fixed-power flash). */
    const val FLASH_GAIN = 1.22f

    /** Disparity per diopter as a fraction of the frame width (16 mm baseline, f ≈ 0.96 × width). */
    const val DISPARITY_PER_DIOPTER = 0.0154

    /**
     * Applies the flash falloff in place. [map] is this frame's disparity relative to the
     * pivot (map px, [mapScale] output px per map px), [focus] the disparity of the focused
     * subject on the same scale, [focusDiopters] its distance in diopters (1 / metres).
     */
    fun falloff(argb: IntArray, w: Int, h: Int, map: DisparityMap, mapScale: Double, focus: Double, focusDiopters: Double) {
        val df = focusDiopters.coerceIn(0.5, 10.0)
        // Map px of disparity per diopter, from the output width.
        val perDiopter = DISPARITY_PER_DIOPTER * w / mapScale
        val gain = FloatArray(map.d.size)
        for (i in gain.indices) {
            val d = df + (map.d[i] - focus) / perDiopter
            val relative = if (d <= 0) 0.0 else (d / df).pow(2)
            gain[i] = (AMBIENT + (1 - AMBIENT) * relative.coerceAtMost(1.4)).toFloat() * FLASH_GAIN
        }
        // The light changes smoothly; soften the map well so depth errors do not show.
        val smooth = gaussian(gain, map.width, map.height, 3.0)
        val grid = GridSampler(map.width, map.height, w, h, mapScale)
        for (y in 0 until h) {
            grid.row(y)
            for (x in 0 until w) {
                val i = y * w + x
                argb[i] = scale(argb[i], grid.sample(smooth, x))
            }
        }
    }

    /** Without a depth map: the brightest, central part is taken as the flash-lit subject. */
    fun falloffWithoutDepth(argb: IntArray, w: Int, h: Int) {
        val cx = w / 2.0
        val cy = h * 0.55
        val r = 0.75 * max(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx) / r
            val dy = (y - cy) / r
            val g = (AMBIENT + (1 - AMBIENT) * exp(-3.0 * (dx * dx + dy * dy))).toFloat() * FLASH_GAIN
            val i = y * w + x
            argb[i] = scale(argb[i], g)
        }
    }

    /** Tone, colour, plastic lens, bloom, vignette and grain. [seed] varies the grain per frame. */
    fun style(src: IntArray, w: Int, h: Int, seed: Long): IntArray {
        // Plastic lens: a touch soft.
        val soft = binomial3(src, w, h)

        // Bloom: highlights glow into their surroundings (computed at 1/4 resolution).
        val f = 4
        val bw = (w + f - 1) / f
        val bh = (h + f - 1) / f
        val planes = downsampleArgb(soft, w, h, f)
        val glow = Array(3) { c ->
            val p = planes[c]
            gaussian(FloatArray(p.size) { i -> max(0f, luma(planes, i) - 215f) / 40f * p[i] }, bw, bh, 2.5)
        }

        // Curves: crushed blacks, a hard S and a soft shoulder; warm, slightly green shadows.
        val curveR = curve(1.06f, 0f)
        val curveG = curve(1.00f, 0f)
        val curveB = curve(0.92f, 1.5f)
        val grid = GridSampler(bw, bh, w, h, f.toDouble())
        val out = IntArray(src.size)
        var state = seed * 6364136223846793005L + 1442695040888963407L
        val cx = w / 2.0
        val cy = h / 2.0
        val maxR2 = cx * cx + cy * cy
        for (y in 0 until h) {
            grid.row(y)
            val dy2 = (y - cy) * (y - cy)
            for (x in 0 until w) {
                val i = y * w + x
                val p = soft[i]
                var r = ((p shr 16) and 0xFF) + grid.sample(glow[0], x) * 0.5f
                var g = ((p shr 8) and 0xFF) + grid.sample(glow[1], x) * 0.45f
                var b = (p and 0xFF) + grid.sample(glow[2], x) * 0.4f
                // A little extra saturation, like cheap colour negative film.
                val l = 0.3f * r + 0.59f * g + 0.11f * b
                r = l + (r - l) * 1.12f
                g = l + (g - l) * 1.12f
                b = l + (b - l) * 1.12f
                // Vignette of a simple lens.
                val rr = ((x - cx) * (x - cx) + dy2) / maxR2
                val v = (1.0 - 0.5 * rr.pow(1.3)).toFloat()
                // Grain: stronger in the mid tones.
                state = state * 6364136223846793005L + 1442695040888963407L
                val n1 = ((state ushr 40) and 0xFFFF).toInt() / 65535f
                state = state * 6364136223846793005L + 1442695040888963407L
                val n2 = ((state ushr 40) and 0xFFFF).toInt() / 65535f
                val noise = (n1 + n2 - 1f) * 10f * (0.35f + 0.65f * (1f - abs1(l / 127.5f - 1f)))
                out[i] = (0xFF shl 24) or
                    (curveR[clamp(r * v + noise)] shl 16) or
                    (curveG[clamp(g * v + noise)] shl 8) or
                    curveB[clamp(b * v + noise)]
            }
        }
        return out
    }

    private fun luma(planes: Array<FloatArray>, i: Int) = 0.3f * planes[0][i] + 0.59f * planes[1][i] + 0.11f * planes[2][i]

    private fun abs1(v: Float) = if (v < 0) -v else v

    private fun clamp(v: Float) = v.roundToInt().coerceIn(0, 255)

    /** Channel curve: gain, then crushed toe, hard S, soft shoulder; [lift] shifts the shadows. */
    private fun curve(gain: Float, lift: Float): IntArray = IntArray(256) { v ->
        val x = (v / 255f * gain).coerceIn(0f, 1.2f)
        // Toe: everything below ~12 % goes black (the unlit background).
        val toe = max(0f, (x - 0.12f) / 0.88f)
        // S-curve (smoothstep blend) plus shoulder that rolls into white.
        val s = toe * toe * (3 - 2 * min(1f, toe))
        val mixed = 0.55f * s + 0.45f * toe
        val shoulder = 1f - exp(-2.6f * mixed) / 1f
        val y = (0.35f * mixed + 0.65f * shoulder / (1f - exp(-2.6f))).coerceIn(0f, 1f)
        val shadowLift = lift * (1f - y) * (1f - y)
        (y * 255f + shadowLift).roundToInt().coerceIn(0, 255)
    }

    private fun scale(p: Int, g: Float): Int {
        val r = (((p shr 16) and 0xFF) * g).roundToInt().coerceIn(0, 255)
        val gg = (((p shr 8) and 0xFF) * g).roundToInt().coerceIn(0, 255)
        val b = ((p and 0xFF) * g).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
    }
}
