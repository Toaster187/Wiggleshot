package com.example.align

import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural test world plus a tiny camera model, so tests can render a "main" and an
 * "ultra wide" frame of the same scene with exactly known geometry.
 */
internal class SyntheticScene(seed: Long = 7L, val width: Int = 3200, val height: Int = 2400) {

    private val background: GrayImage
    private val foreground: GrayImage
    private val foregroundAlpha: GrayImage

    /** Foreground disc (world coordinates) used to simulate a near subject. */
    var foregroundRadius = 0.0
    val foregroundCenterX = width / 2.0
    val foregroundCenterY = height / 2.0

    init {
        val random = Random(seed)
        val bg = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            bg[y * width + x] = 90f + 40f * x / width + 25f * sin(y / 170.0).toFloat()
        }
        repeat(900) {
            val cx = random.nextInt(width)
            val cy = random.nextInt(height)
            val r = 4 + random.nextInt(45)
            val value = random.nextInt(200) - 100
            val sharp = random.nextBoolean()
            stamp(bg, cx, cy, r) { d2 ->
                val d = sqrt(d2) / r
                if (sharp) (if (d < 1) value.toFloat() else 0f) else (value * exp(-3 * d * d)).toFloat()
            }
        }
        repeat(260) {
            val x0 = random.nextInt(width)
            val y0 = random.nextInt(height)
            val w = 10 + random.nextInt(140)
            val h = 6 + random.nextInt(60)
            val value = random.nextInt(140) - 70
            for (y in max(0, y0) until min(height, y0 + h)) for (x in max(0, x0) until min(width, x0 + w)) {
                bg[y * width + x] += value
            }
        }
        background = GrayImage(width, height, bg).gaussianBlur(0.8)

        // Foreground subject: a distinct checker/blob texture on a disc.
        val fg = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val c = ((x / 23) + (y / 31)) % 2
            fg[y * width + x] = if (c == 0) 60f else 200f
        }
        repeat(120) {
            val cx = width / 2 + random.nextInt(600) - 300
            val cy = height / 2 + random.nextInt(600) - 300
            val r = 5 + random.nextInt(25)
            stamp(fg, cx, cy, r) { d2 -> if (d2 < r * r) -90f else 0f }
        }
        foreground = GrayImage(width, height, fg).gaussianBlur(0.8)
        foregroundAlpha = GrayImage(width, height)
    }

    private inline fun stamp(target: FloatArray, cx: Int, cy: Int, r: Int, value: (Double) -> Float) {
        for (y in max(0, cy - 2 * r) until min(height, cy + 2 * r)) {
            for (x in max(0, cx - 2 * r) until min(width, cx + 2 * r)) {
                val dx = (x - cx).toDouble()
                val dy = (y - cy).toDouble()
                target[y * width + x] += value(dx * dx + dy * dy)
            }
        }
    }

    /**
     * Camera looking at the world: camera pixel `p` sees world point
     * `centre + footprint * R(rotation) * (p - imageCentre)`.
     */
    data class Camera(
        val width: Int,
        val height: Int,
        val footprint: Double,
        val rotationDegrees: Double = 0.0,
        val centerX: Double,
        val centerY: Double,
        /** Horizontal world shift of the foreground subject as seen by this camera (parallax). */
        val foregroundShift: Double = 0.0,
        /**
         * Vertical lens-geometry error in pixels (v² + uv/2 pattern, like a distortion residue
         * or module tilt): pixel (x, y) shows what an ideal camera shows at y + verticalError.
         */
        val verticalWarp: Double = 0.0
    ) {
        fun verticalError(x: Double, y: Double): Double {
            if (verticalWarp == 0.0) return 0.0
            val u = (x - width / 2.0) / width
            val v = (y - height / 2.0) / width
            return verticalWarp * (v * v + 0.5 * u * v) * 4
        }

        /** Similarity mapping this camera's pixels to world coordinates. */
        fun toWorld(): Similarity = Similarity.of(footprint, rotationDegrees, 0.0, 0.0)
            .anchoredAt(width / 2.0, height / 2.0, centerX, centerY)
    }

    /** Ground truth primary px -> secondary px for the background (infinite depth). */
    fun trueTransform(primary: Camera, secondary: Camera): Similarity =
        secondary.toWorld().inverse().compose(primary.toWorld())

    /** Ground truth including the secondary's [Camera.verticalWarp] (background points). */
    fun trueMapping(primary: Camera, secondary: Camera, x: Double, y: Double): Pair<Double, Double> {
        val t = trueTransform(primary, secondary)
        val sx = t.mapX(x, y)
        var sy = t.mapY(x, y)
        repeat(10) { sy = t.mapY(x, y) - secondary.verticalError(sx, sy) }
        return sx to sy
    }

    data class Photometric(
        val gain: Double = 1.0,
        val offset: Double = 0.0,
        val gamma: Double = 1.0,
        val vignetting: Double = 0.0,
        val noise: Double = 0.0,
        val seed: Long = 1L
    )

    fun render(camera: Camera, photometric: Photometric = Photometric()): GrayImage {
        // Pre-filter to the camera's pixel footprint so the render does not alias.
        val sigma = 0.45 * camera.footprint
        val bg = if (sigma > 0.6) background.gaussianBlur(sigma) else background
        val fg = if (sigma > 0.6) foreground.gaussianBlur(sigma) else foreground
        val toWorld = camera.toWorld()
        val random = Random(photometric.seed)
        val out = FloatArray(camera.width * camera.height)
        val maxR2 = (camera.width / 2.0).pow(2) + (camera.height / 2.0).pow(2)
        for (y in 0 until camera.height) {
            for (x in 0 until camera.width) {
                val px = x + 0.5
                val py = y + 0.5 + camera.verticalError(x + 0.5, y + 0.5)
                val wx = toWorld.mapX(px, py)
                val wy = toWorld.mapY(px, py)
                var v = bg.sample(wx, wy).toDouble()
                if (foregroundRadius > 0) {
                    val fx = wx - camera.foregroundShift
                    val dx = fx - foregroundCenterX
                    val dy = wy - foregroundCenterY
                    val d = sqrt(dx * dx + dy * dy)
                    val alpha = ((foregroundRadius - d) / 1.5).coerceIn(0.0, 1.0)
                    if (alpha > 0) v = v * (1 - alpha) + fg.sample(fx, wy) * alpha
                }
                var t = (v / 255.0).coerceIn(0.0, 1.0).pow(photometric.gamma) * 255.0
                t = t * photometric.gain + photometric.offset
                if (photometric.vignetting > 0) {
                    val r2 = ((px - camera.width / 2.0).pow(2) + (py - camera.height / 2.0).pow(2)) / maxR2
                    t *= 1 - photometric.vignetting * r2
                }
                if (photometric.noise > 0) t += random.nextGaussian() * photometric.noise
                out[y * camera.width + x] = t.coerceIn(0.0, 255.0).toFloat()
            }
        }
        return GrayImage(camera.width, camera.height, out)
    }
}
