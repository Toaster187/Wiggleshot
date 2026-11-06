package com.example.align

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 4-DOF similarity transform (uniform scale, rotation, translation) that maps a point of the
 * **primary** frame to the corresponding point of the **secondary** frame:
 *
 * ```
 * xs = a * xp - b * yp + tx
 * ys = b * xp + a * yp + ty
 * ```
 *
 * The mapping direction (output -> source) is what resampling needs, and it is linear in all
 * four parameters, which keeps the Gauss-Newton solver trivial.
 *
 * Why a similarity and not a homography: two lenses a centimetre apart differ by a rotation
 * (tiny, depth independent) plus a translation whose effect depends on scene depth (parallax).
 * The parallax is exactly what makes the wiggle, so it must not be "corrected" away. What has
 * to be removed is the scale (focal length ratio), the roll and the offset at the pivot depth
 * — precisely the four parameters here.
 */
data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {

    val scale: Double get() = hypot(a, b)
    val rotationRadians: Double get() = atan2(b, a)
    val rotationDegrees: Double get() = Math.toDegrees(rotationRadians)

    fun mapX(x: Double, y: Double): Double = a * x - b * y + tx
    fun mapY(x: Double, y: Double): Double = b * x + a * y + ty

    fun inverse(): Similarity {
        val d = a * a + b * b
        val ia = a / d
        val ib = -b / d
        return Similarity(ia, ib, -(ia * tx - ib * ty), -(ib * tx + ia * ty))
    }

    /** `this` applied after [first]: `x -> this(first(x))`. */
    fun compose(first: Similarity): Similarity = Similarity(
        a = a * first.a - b * first.b,
        b = b * first.a + a * first.b,
        tx = a * first.tx - b * first.ty + tx,
        ty = b * first.tx + a * first.ty + ty
    )

    /**
     * Expresses this transform in other pixel grids. Each grid is given by how it maps to
     * the grid this transform is defined in: `x_this = scale * x_other + offset`.
     */
    fun reexpress(
        primaryScale: Double,
        primaryOffsetX: Double = 0.0,
        primaryOffsetY: Double = 0.0,
        secondaryScale: Double,
        secondaryOffsetX: Double = 0.0,
        secondaryOffsetY: Double = 0.0
    ): Similarity {
        // x_s_other = (T(primaryScale * x + o_p) - o_s) / secondaryScale
        val na = a * primaryScale / secondaryScale
        val nb = b * primaryScale / secondaryScale
        val ntx = (mapX(primaryOffsetX, primaryOffsetY) - secondaryOffsetX) / secondaryScale
        val nty = (mapY(primaryOffsetX, primaryOffsetY) - secondaryOffsetY) / secondaryScale
        return Similarity(na, nb, ntx, nty)
    }

    /** Same scale and rotation, translation changed so that [px],[py] maps to [sx],[sy]. */
    fun anchoredAt(px: Double, py: Double, sx: Double, sy: Double): Similarity =
        copy(tx = sx - (a * px - b * py), ty = sy - (b * px + a * py))

    /**
     * Largest distance, in **primary** pixels, between where this and [other] place the
     * corners of a [width] x [height] primary frame. A direct measure of how far the wiggle
     * frames would visibly jump apart.
     */
    fun maxCornerDeviation(other: Similarity, width: Double, height: Double): Double {
        var worst = 0.0
        for ((x, y) in listOf(0.0 to 0.0, width to 0.0, 0.0 to height, width to height, width / 2 to height / 2)) {
            val d = hypot(mapX(x, y) - other.mapX(x, y), mapY(x, y) - other.mapY(x, y))
            worst = maxOf(worst, d)
        }
        return worst / scale
    }

    override fun toString(): String =
        "Similarity(scale=%.5f, rot=%.4f°, t=(%.2f, %.2f))".format(scale, rotationDegrees, tx, ty)

    companion object {
        val IDENTITY = Similarity(1.0, 0.0, 0.0, 0.0)

        fun of(scale: Double, rotationDegrees: Double, tx: Double, ty: Double): Similarity {
            val r = Math.toRadians(rotationDegrees)
            return Similarity(scale * cos(r), scale * sin(r), tx, ty)
        }

        /** Maps the centre of a primary frame onto the centre of a secondary frame. */
        fun centered(
            scale: Double,
            rotationDegrees: Double,
            primaryWidth: Double,
            primaryHeight: Double,
            secondaryWidth: Double,
            secondaryHeight: Double
        ): Similarity = of(scale, rotationDegrees, 0.0, 0.0)
            .anchoredAt(primaryWidth / 2, primaryHeight / 2, secondaryWidth / 2, secondaryHeight / 2)
    }
}
