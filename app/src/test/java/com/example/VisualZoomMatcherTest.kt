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
