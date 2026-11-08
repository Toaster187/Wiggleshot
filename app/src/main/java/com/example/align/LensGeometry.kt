package com.example.align

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Factory calibration of one camera module as published by Camera2
 * (`LENS_INTRINSIC_CALIBRATION`, `LENS_POSE_*`, sensor array sizes, ...).
 *
 * All sensor coordinates are in the sensor's native (landscape) orientation. Intrinsics are
 * relative to the pre-correction active array, as defined by the Camera2 documentation.
 */
data class LensGeometry(
    val cameraId: String,
    val sensorOrientation: Int,
    val activeArray: IntRect,
    val preCorrectionArray: IntRect,
    /** `[f_x, f_y, c_x, c_y, s]` or null if the HAL does not publish it. */
    val intrinsics: FloatArray? = null,
    val focalLengthMm: Float? = null,
    val sensorWidthMm: Float? = null,
    val pixelArrayWidth: Int? = null,
    val distortion: FloatArray? = null,
    /** Quaternion `(x, y, z, w)`. */
    val poseRotation: FloatArray? = null,
    val poseTranslation: FloatArray? = null,
    val poseReference: Int? = null,
    /** f-number of the (fixed) aperture. */
    val apertureF: Float? = null
) {
    data class IntRect(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Entrance pupil diameter in mm (focal length / f-number), null if unknown. */
    val apertureDiameterMm: Double?
        get() {
            val f = focalLengthMm ?: return null
            val n = apertureF ?: return null
            return if (n > 0f) f / n.toDouble() else null
        }

    /** Pinhole projection of an output frame: focal length and principal point in output px. */
    data class Projection(val focal: Double, val cx: Double, val cy: Double, val width: Int, val height: Int) {
        /** Similarity mapping a point of [primary] to this frame, ignoring the module rotation. */
        fun priorFrom(primary: Projection): Similarity =
            Similarity.of(focal / primary.focal, 0.0, 0.0, 0.0).anchoredAt(primary.cx, primary.cy, cx, cy)
    }

    val hasIntrinsics: Boolean
        get() = intrinsics != null && intrinsics.size >= 4 && intrinsics[0] > 1f && intrinsics[1] > 1f

    /** Focal length in active array pixels, from the calibration or from the lens data sheet. */
    fun focalLengthPx(dynamicIntrinsics: FloatArray? = null): Double? {
        val k = dynamicIntrinsics?.takeIf { it.size >= 4 && it[0] > 1f } ?: intrinsics?.takeIf { hasIntrinsics }
        if (k != null) return (k[0] + k[1]) / 2.0
        val focal = focalLengthMm ?: return null
        val sensorW = sensorWidthMm ?: return null
        val pixels = pixelArrayWidth ?: activeArray.width
        if (sensorW <= 0f) return null
        return focal / sensorW * pixels.toDouble()
    }

    /**
     * Projection of an upright output frame of [outWidth] x [outHeight] pixels.
     *
     * [cropRegion] is the region of the active array the frame was produced from (the
     * capture result's `SCALER_CROP_REGION`), default the whole array. The output is the
     * largest centred part of that region with the output aspect ratio, which is how the
     * camera pipeline fits a stream into the crop. [digitalZoom] shrinks the region further,
     * e.g. for a CameraX capture with a zoom ratio applied. [rotationDegrees] is the clockwise
     * rotation that was applied to make the frame upright.
     */
    fun projection(
        outWidth: Int,
        outHeight: Int,
        cropRegion: IntRect? = null,
        dynamicIntrinsics: FloatArray? = null,
        digitalZoom: Double = 1.0,
        rotationDegrees: Int = sensorOrientation
    ): Projection? {
        val focal = focalLengthPx(dynamicIntrinsics) ?: return null
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val rotated = rotation % 180 != 0
        // Output size in sensor orientation.
        val sensorOutW = if (rotated) outHeight else outWidth
        val sensorOutH = if (rotated) outWidth else outHeight

        val full = IntRect(0, 0, activeArray.width, activeArray.height)
        // Ignore crop regions that cannot describe this sensor (some HALs report the logical
        // camera's region in physical results).
        val crop = cropRegion?.takeIf {
            it.left >= 0 && it.top >= 0 && it.width >= activeArray.width * 0.2 &&
                it.left + it.width <= activeArray.width && it.top + it.height <= activeArray.height
        } ?: full
        var cw = crop.width.toDouble()
        var ch = crop.height.toDouble()
        val outAspect = sensorOutW.toDouble() / sensorOutH
        if (cw / ch > outAspect) cw = ch * outAspect else ch = cw / outAspect
        cw /= digitalZoom
        ch /= digitalZoom
        val left = crop.left + (crop.width - cw) / 2
        val top = crop.top + (crop.height - ch) / 2
        val k = sensorOutW / cw

        val (ppx, ppy) = principalPointActive(dynamicIntrinsics)
        val sx = (ppx - left) * k
        val sy = (ppy - top) * k
        // Rotate the principal point into the upright frame exactly like the bitmap is rotated
        // (android.graphics.Matrix.postRotate followed by re-origin to (0, 0)).
        val (ux, uy) = when (rotation) {
            90 -> (sensorOutH - sy) to sx
            180 -> (sensorOutW - sx) to (sensorOutH - sy)
            270 -> sy to (sensorOutW - sx)
            else -> sx to sy
        }
        return Projection(focal * k, ux, uy, outWidth, outHeight)
    }

    /** Principal point in active array coordinates (origin at the active array's corner). */
    fun principalPointActive(dynamicIntrinsics: FloatArray? = null): Pair<Double, Double> {
        val k = dynamicIntrinsics?.takeIf { it.size >= 4 && it[0] > 1f } ?: intrinsics?.takeIf { hasIntrinsics }
            ?: return activeArray.width / 2.0 to activeArray.height / 2.0
        val dx = activeArray.left - preCorrectionArray.left
        val dy = activeArray.top - preCorrectionArray.top
        val cx = k[2] - dx
        val cy = k[3] - dy
        // A principal point far outside the array means the HAL uses another convention;
        // the array centre is the safer assumption then.
        val plausible = cx in 0.0..activeArray.width.toDouble() && cy in 0.0..activeArray.height.toDouble()
        return if (plausible) cx.toDouble() to cy.toDouble() else activeArray.width / 2.0 to activeArray.height / 2.0
    }

    companion object {
        /**
         * Relative roll (rotation around the optical axis) between two modules in degrees,
         * derived from `LENS_POSE_ROTATION`. Only used for diagnostics: the sign convention of
         * the pose differs between HALs, the image based estimate is authoritative.
         */
        fun relativeRollDegrees(primary: LensGeometry, secondary: LensGeometry): Double? {
            val qa = primary.poseRotation ?: return null
            val qb = secondary.poseRotation ?: return null
            if (qa.size < 4 || qb.size < 4) return null
            val ra = rotationMatrix(qa)
            val rb = rotationMatrix(qb)
            // R_rel = R_b * R_a^T
            val rel = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var sum = 0.0
                for (k in 0 until 3) sum += rb[i * 3 + k] * ra[j * 3 + k]
                rel[i * 3 + j] = sum
            }
            return Math.toDegrees(atan2(rel[3], rel[0]))
        }

        /**
         * Direction from the primary to the secondary lens in the upright (portrait) frame, from
         * `LENS_POSE_TRANSLATION`. The pose uses the Android sensor axes (x right, y up in the
         * natural orientation); image y points down. Null if the pose is not published.
         */
        fun baselineInImage(primary: LensGeometry, secondary: LensGeometry): Pair<Double, Double>? {
            val ta = primary.poseTranslation ?: return null
            val tb = secondary.poseTranslation ?: return null
            if (ta.size < 3 || tb.size < 3) return null
            val dx = (tb[0] - ta[0]).toDouble()
            val dy = -(tb[1] - ta[1]).toDouble()
            val n = sqrt(dx * dx + dy * dy)
            return if (n > 1e-4) (dx / n) to (dy / n) else null
        }

        /** Baseline between two modules in millimetres from `LENS_POSE_TRANSLATION`. */
        fun baselineMm(primary: LensGeometry, secondary: LensGeometry): Double? {
            val ta = primary.poseTranslation ?: return null
            val tb = secondary.poseTranslation ?: return null
            if (ta.size < 3 || tb.size < 3) return null
            val dx = (ta[0] - tb[0]).toDouble()
            val dy = (ta[1] - tb[1]).toDouble()
            val dz = (ta[2] - tb[2]).toDouble()
            val metres = sqrt(dx * dx + dy * dy + dz * dz)
            return if (metres > 0 && metres < 1) metres * 1000 else null
        }

        private fun rotationMatrix(q: FloatArray): DoubleArray {
            var x = q[0].toDouble()
            var y = q[1].toDouble()
            var z = q[2].toDouble()
            var w = q[3].toDouble()
            val n = sqrt(x * x + y * y + z * z + w * w)
            if (n < 1e-9 || abs(n - 1) > 0.5) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            x /= n; y /= n; z /= n; w /= n
            return doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is LensGeometry && other.cameraId == cameraId &&
        other.activeArray == activeArray && other.intrinsics.contentEqualsNullable(intrinsics)

    override fun hashCode(): Int = cameraId.hashCode() * 31 + activeArray.hashCode()

    private fun FloatArray?.contentEqualsNullable(other: FloatArray?): Boolean =
        if (this == null || other == null) this === other else contentEquals(other)
}
