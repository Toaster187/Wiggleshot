package com.example.align

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraLensDetails
import com.example.camera.CapturedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * End-to-end test of the Android side of the alignment: real Bitmaps, Canvas resampling,
 * crop and colour matching (Robolectric native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class WiggleAlignerTest {

    private val mainCamera = SyntheticScene.Camera(
        width = 2048, height = 1536, footprint = 0.625, centerX = 1600.0, centerY = 1200.0
    )
    private val uwCamera = SyntheticScene.Camera(
        width = 2000, height = 1500, footprint = 1.3, rotationDegrees = 0.3,
        centerX = 1600.0 + 18.0, centerY = 1200.0 + 9.0
    )

    /** Gray render -> ARGB bitmap, optionally tinted (different white balance). */
    private fun GrayImage.toBitmap(r: Float = 1f, g: Float = 1f, b: Float = 1f): Bitmap {
        val pixels = IntArray(width * height) { i ->
            val v = data[i]
            val rr = (v * r).toInt().coerceIn(0, 255)
            val gg = (v * g).toInt().coerceIn(0, 255)
            val bb = (v * b).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    @Test
    fun ultraWideIsWarpedOntoMainFrame() {
        val scene = SyntheticScene()
        val mainGray = scene.render(mainCamera, SyntheticScene.Photometric(noise = 1.0, seed = 1))
        val uwGray = scene.render(uwCamera, SyntheticScene.Photometric(gain = 0.85, offset = 10.0, gamma = 1.1, noise = 2.0, seed = 2))
        val main = mainGray.toBitmap()
        val uw = uwGray.toBitmap(r = 1.05f, g = 0.95f, b = 0.8f)

        val mainLens = CameraLensDetails("test-main", "main", 6.9f, false, "test-logical")
        val uwLens = CameraLensDetails("test-uw", "uw", 2.0f, true, "test-logical")
        val aligner = WiggleAligner(ApplicationProvider.getApplicationContext())
        aligner.clearCalibration()

        val output = aligner.process(
            frames = listOf(CapturedFrame(mainLens, main, 90), CapturedFrame(uwLens, uw, 90)),
            zoomFor = { 1f },
            settings = WiggleAligner.Settings()
        )
        val report = output.reports.single()
        val truth = scene.trueTransform(mainCamera, uwCamera)
        val error = report.transform.maxCornerDeviation(truth, mainCamera.width.toDouble(), mainCamera.height.toDouble())
        println("report=${report.summary()} transform=${report.transform} truth=$truth error=$error crop=${output.crop}")

        assertEquals(WiggleAligner.Source.IMAGE, report.source)
        assertTrue("corner error $error px", error < 0.5)
        // Magnification of the ultra wide: footprint ratio, in output pixels.
        assertEquals(1.3 / 0.625 * 2000.0 / 2048.0, report.equivalentZoom, 0.003)

        // The ultra wide covers the whole main frame, so nothing is cropped.
        val (outMain, outUw) = output.frames
        assertEquals(2048, outMain.width)
        assertEquals(1536, outMain.height)
        assertEquals(outMain.width, outUw.width)
        assertEquals(outMain.height, outUw.height)

        // Structure lines up and colours were matched.
        val a = outMain.toGrayImage(4)
        val b = outUw.toGrayImage(4)
        val zncc = zncc(a.data, b.data)
        println("zncc=$zncc meanMain=${a.mean()} meanUw=${b.mean()}")
        assertTrue("warped ultra wide must match the main frame, zncc=$zncc", zncc > 0.9)
        assertTrue("colour/brightness must be matched", abs(a.mean() - b.mean()) < 3.0)
        for (shift in intArrayOf(16, 8, 0)) {
            val ma = channelMean(outMain, shift)
            val mb = channelMean(outUw, shift)
            assertTrue("channel $shift: $ma vs $mb", abs(ma - mb) < 4.0)
        }

        // A confident measurement is learned as calibration.
        assertTrue(aligner.storedCalibration("test-main", "test-uw") != null)
    }

    @Test
    fun primaryZoomCropsBothFramesIdentically() {
        val scene = SyntheticScene()
        val main = scene.render(mainCamera).toBitmap()
        val uw = scene.render(uwCamera).toBitmap()
        val mainLens = CameraLensDetails("z-main", "main", 6.9f, false, "z-logical")
        val uwLens = CameraLensDetails("z-uw", "uw", 2.0f, true, "z-logical")
        val aligner = WiggleAligner(ApplicationProvider.getApplicationContext())
        val output = aligner.process(
            frames = listOf(CapturedFrame(mainLens, main, 90), CapturedFrame(uwLens, uw, 90)),
            zoomFor = { lens -> if (lens.id == "z-main") 2f else 1f },
            settings = WiggleAligner.Settings(colorMatch = false)
        )
        assertEquals(1024, output.crop.width())
        assertEquals(768, output.crop.height())
        output.frames.forEach {
            assertEquals(1024, it.width)
            assertEquals(768, it.height)
        }
        val zncc = zncc(output.frames[0].toGrayImage(2).data, output.frames[1].toGrayImage(2).data)
        assertTrue("zncc=$zncc", zncc > 0.9)
    }

    private fun channelMean(bitmap: Bitmap, shift: Int): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.fold(0.0) { acc, p -> acc + ((p shr shift) and 0xFF) } / pixels.size
    }

    private fun zncc(a: FloatArray, b: FloatArray): Double {
        val ma = a.average()
        val mb = b.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in a.indices) {
            val da = a[i] - ma
            val db = b[i] - mb
            cov += da * db
            va += da * da
            vb += db * db
        }
        return cov / sqrt(va * vb)
    }
}
