package com.example.align

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Full primary -> secondary mapping: a [Similarity] plus a smooth correction along the
 * direction perpendicular to the lens baseline.
 *
 * Why only perpendicular: two lenses side by side see a scene point shifted *along* the
 * baseline by an amount that depends on its depth — that parallax is the wiggle. Across the
 * baseline there is no parallax at all, so any displacement left in that direction is pure
 * lens geometry (distortion-correction residue, module tilt) and must be zero. Left in, it
 * makes flat surfaces like walls bend or "break" when the wiggle plays. Correcting only this
 * direction removes that without touching the depth effect.
 *
 * The correction is a cubic polynomial in normalised primary coordinates
 * `u = (x - cx) / w`, `v = (y - cy) / w` (resolution independent), evaluated in secondary
 * pixels and applied along the unit normal (`normalX`, `normalY`).
 */
class WarpField(
    val base: Similarity,
    val normalX: Double,
    val normalY: Double,
    /** Coefficients of [BASIS] terms, in secondary pixels. */
    val coefficients: DoubleArray,
    /** Primary frame size the normalisation refers to. */
    val primaryWidth: Double,
    val primaryHeight: Double
) {
    init {
        require(coefficients.size == TERMS)
    }

    val isIdentity: Boolean get() = coefficients.all { it == 0.0 }

    /** Correction along the normal at primary point (x, y), secondary pixels. */
    fun offset(x: Double, y: Double): Double {
        val u = (x - primaryWidth / 2) / primaryWidth
        val v = (y - primaryHeight / 2) / primaryWidth
        var sum = 0.0
        val b = basis(u, v)
        for (i in 0 until TERMS) sum += coefficients[i] * b[i]
        return sum
    }

    fun mapX(x: Double, y: Double): Double = base.mapX(x, y) + normalX * offset(x, y)
    fun mapY(x: Double, y: Double): Double = base.mapY(x, y) + normalY * offset(x, y)

    /**
     * Inverse mapping (secondary -> primary) by fixed point iteration; the correction is
     * small and smooth, so a few steps of the similarity inverse converge to < 0.01 px.
     */
    fun inverse(sx: Double, sy: Double): Pair<Double, Double> {
        val inv = base.inverse()
        var x = inv.mapX(sx, sy)
        var y = inv.mapY(sx, sy)
        repeat(6) {
            val ex = mapX(x, y) - sx
            val ey = mapY(x, y) - sy
            // Undo the error with the linear part of the inverse similarity.
            x -= inv.a * ex - inv.b * ey
            y -= inv.b * ex + inv.a * ey
        }
        return x to y
    }

    /** Same geometry for a primary frame resampled by [primaryScale] and a secondary by [secondaryScale]. */
    fun reexpress(primaryScale: Double, secondaryScale: Double): WarpField = WarpField(
        base = base.reexpress(primaryScale = primaryScale, secondaryScale = secondaryScale),
        normalX = normalX,
        normalY = normalY,
        coefficients = DoubleArray(TERMS) { coefficients[it] / secondaryScale },
        primaryWidth = primaryWidth / primaryScale,
        primaryHeight = primaryHeight / primaryScale
    )

    /** Replaces the similarity (e.g. a different pivot) and keeps the correction. */
    fun withBase(newBase: Similarity) = WarpField(newBase, normalX, normalY, coefficients, primaryWidth, primaryHeight)

    /** Largest correction applied anywhere in the frame, secondary pixels. */
    fun maxOffset(): Double {
        var worst = 0.0
        for (i in 0..8) for (j in 0..8) {
            worst = maxOf(worst, abs(offset(primaryWidth * i / 8, primaryHeight * j / 8)))
        }
        return worst
    }

    companion object {
        const val TERMS = 10

        /** 1, u, v, u², uv, v², u³, u²v, uv², v³ */
        fun basis(u: Double, v: Double): DoubleArray = doubleArrayOf(
            1.0, u, v, u * u, u * v, v * v, u * u * u, u * u * v, u * v * v, v * v * v
        )

        fun identity(base: Similarity, primaryWidth: Double, primaryHeight: Double, baselineX: Double = 1.0, baselineY: Double = 0.0): WarpField {
            val (nx, ny) = normalOf(baselineX, baselineY)
            return WarpField(base, nx, ny, DoubleArray(TERMS), primaryWidth, primaryHeight)
        }

        /** Unit vector perpendicular to the baseline direction (image coordinates). */
        fun normalOf(baselineX: Double, baselineY: Double): Pair<Double, Double> {
            val n = hypot(baselineX, baselineY)
            if (n < 1e-9) return 0.0 to 1.0
            return (-baselineY / n) to (baselineX / n)
        }
    }
}
