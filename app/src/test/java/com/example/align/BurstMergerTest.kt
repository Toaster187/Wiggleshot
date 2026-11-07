package com.example.align

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sqrt

/**
 * Night mode merge on a synthetic hand-held burst: every frame is shifted and rotated a
 * little (nobody holds still to the millimetre), carries its own strong noise, and one frame
 * shows an object that was not there in the reference (something moved through the scene).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BurstMergerTest {

    private val base = SyntheticScene.Camera(1024, 768, footprint = 1.25, centerX = 1600.0, centerY = 1200.0)

    /** Hand shake per frame: world shift (px) and roll (degrees). */
    private val shake = listOf(
        Triple(0.0, 0.0, 0.0),
        Triple(7.0, -4.0, 0.15),
        Triple(-5.0, 6.0, -0.2),
        Triple(3.0, 9.0, 0.1),
        Triple(-8.0, -3.0, -0.12)
    )

    private fun GrayImage.toBitmap(): Bitmap {
        val px = IntArray(width * height) { i ->
            val v = data[i].toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    @Test
    fun alignsHandShakeAveragesNoiseAndRejectsMotion() {
        val scene = SyntheticScene()
        val clean = scene.render(base)
        val frames = shake.mapIndexed { i, (dx, dy, rot) ->
            val camera = base.copy(centerX = base.centerX + dx, centerY = base.centerY + dy, rotationDegrees = rot)
            val img = scene.render(camera, SyntheticScene.Photometric(noise = 9.0, seed = 10L + i))
            if (i == 2) {
                // Something moves through the picture in frame 2 only.
                for (y in 300 until 380) for (x in 600 until 700) img.data[y * img.width + x] = 250f
            }
            img
        }

        val result = BurstMerger().merge(frames.size, reference = 0) { frames[it].toBitmap() }!!
        println("burst: $result")
        assertEquals(5, result.used)

        val merged = result.bitmap.toGrayImage(1)
        fun rmsError(img: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var sum = 0.0
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val d = img[x, y] - clean[x, y]
                sum += d * d
                n++
            }
            return sqrt(sum / n)
        }
        // Inner area (away from the borders that not every frame covers), without the mover.
        val single = rmsError(frames[0], 60, 60, 560, 700)
        val night = rmsError(merged, 60, 60, 560, 700)
        println("noise rms single %.2f, merged %.2f".format(single, night))
        assertTrue("noise must drop clearly: $single -> $night", night < single * 0.6)

        // The moving object must not leave a ghost.
        val ghost = rmsError(merged, 610, 310, 690, 370)
        println("rms at the moving object %.2f (object contrast ~%.0f)".format(ghost, 250 - clean[650, 340]))
        assertTrue("moving object must be rejected, error $ghost", ghost < 25.0)
    }

    @Test
    fun sharpestFrameScoresHighest() {
        val scene = SyntheticScene()
        val sharp = scene.render(base).toBitmap()
        val blurred = scene.render(base).gaussianBlur(2.0).toBitmap()
        assertTrue(BurstMerger.sharpness(sharp) > BurstMerger.sharpness(blurred) * 2)
    }
}
