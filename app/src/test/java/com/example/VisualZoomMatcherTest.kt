package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.util.VisualZoomMatcher
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VisualZoomMatcherTest {
    @Test
    fun findsExpectedZoomForShiftedWiderSecondaryFrame() {
        val scene = createScene()
        val primary = scene.cropAndScale(x = 210, y = 120, width = 360, height = 270)
        val secondary = scene.cropAndScale(x = 90, y = 80, width = 540, height = 405)

        val zoom = VisualZoomMatcher.calculate(primary, secondary, minZoom = 1f, maxZoom = 2.2f)

        assertTrue("Expected zoom around 1.5x, got $zoom", zoom in 1.3f..1.7f)
    }

    @Test
    fun zoomIsStableUnderExposureShiftAndNoise() {
        // Simulates two app starts where AE/AGC has converged differently:
        // the second secondary frame is brighter, lower-contrast and noisy.
        // The calibration result must stay (nearly) identical.
        val scene = createScene()
        val primary = scene.cropAndScale(x = 210, y = 120, width = 360, height = 270)
        val secondaryClean = scene.cropAndScale(x = 90, y = 80, width = 540, height = 405)
        val secondaryShifted = secondaryClean.withExposureShiftAndNoise(
            gain = 1.3f,
            offset = 18f,
            noiseAmplitude = 6,
            seed = 42L
        )

        val zoomClean = VisualZoomMatcher.calculate(primary, secondaryClean, minZoom = 1f, maxZoom = 2.2f)
        val zoomShifted = VisualZoomMatcher.calculate(primary, secondaryShifted, minZoom = 1f, maxZoom = 2.2f)

        assertTrue("Expected zoom around 1.5x for shifted frame, got $zoomShifted", zoomShifted in 1.3f..1.7f)
        assertTrue(
            "Zoom must be stable under exposure shift: clean=$zoomClean shifted=$zoomShifted",
            kotlin.math.abs(zoomClean - zoomShifted) <= 0.06f
        )
    }

    @Test
    fun resultIsDeterministicForIdenticalInputs() {
        val scene = createScene()
        val primary = scene.cropAndScale(x = 210, y = 120, width = 360, height = 270)
        val secondary = scene.cropAndScale(x = 90, y = 80, width = 540, height = 405)

        val first = VisualZoomMatcher.calculate(primary, secondary, minZoom = 1f, maxZoom = 2.2f)
        val second = VisualZoomMatcher.calculate(primary, secondary, minZoom = 1f, maxZoom = 2.2f)

        assertTrue("Identical inputs must produce identical zoom: $first vs $second", first == second)
    }

    private fun Bitmap.withExposureShiftAndNoise(
        gain: Float,
        offset: Float,
        noiseAmplitude: Int,
        seed: Long
    ): Bitmap {
        val random = Random(seed)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val noise = random.nextInt(noiseAmplitude * 2 + 1) - noiseAmplitude
            val r = ((Color.red(color) * gain) + offset + noise).toInt().coerceIn(0, 255)
            val g = ((Color.green(color) * gain) + offset + noise).toInt().coerceIn(0, 255)
            val b = ((Color.blue(color) * gain) + offset + noise).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun createScene(): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(18, 21, 27))

        paint.strokeWidth = 3f
        for (x in 0 until 900 step 45) {
            paint.color = if ((x / 45) % 2 == 0) Color.rgb(70, 95, 125) else Color.rgb(42, 61, 82)
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 600f, paint)
        }
        for (y in 0 until 600 step 40) {
            paint.color = if ((y / 40) % 2 == 0) Color.rgb(95, 78, 64) else Color.rgb(50, 70, 68)
            canvas.drawLine(0f, y.toFloat(), 900f, y.toFloat(), paint)
        }

        val random = Random(7)
        for (i in 0 until 80) {
            paint.color = Color.rgb(
                50 + random.nextInt(180),
                50 + random.nextInt(180),
                50 + random.nextInt(180)
            )
            val cx = random.nextInt(900).toFloat()
            val cy = random.nextInt(600).toFloat()
            val radius = 7f + random.nextInt(22)
            if (i % 3 == 0) {
                canvas.drawRect(cx, cy, cx + radius * 2f, cy + radius * 1.4f, paint)
            } else {
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }
        return bitmap
    }

    private fun Bitmap.cropAndScale(
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Bitmap {
        val cropped = Bitmap.createBitmap(this, x, y, width, height)
        return Bitmap.createScaledBitmap(cropped, 360, 270, true)
    }
}
