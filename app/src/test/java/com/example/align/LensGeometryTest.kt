package com.example.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LensGeometryTest {

    private fun lens(
        id: String,
        fx: Float,
        cx: Float,
        cy: Float,
        orientation: Int = 90,
        width: Int = 4000,
        height: Int = 3000
    ) = LensGeometry(
        cameraId = id,
        sensorOrientation = orientation,
        activeArray = LensGeometry.IntRect(8, 8, width, height),
        preCorrectionArray = LensGeometry.IntRect(0, 0, width + 16, height + 16),
        intrinsics = floatArrayOf(fx, fx, cx, cy, 0f)
    )

    @Test
    fun projectionRotatesPrincipalPointLikeTheBitmap() {
        // Principal point 100 px right of / 50 px below the centre (pre-correction coords
        // are offset by 8 px from the active array).
        val g = lens("uw", fx = 2000f, cx = 2000f + 100f + 8f, cy = 1500f + 50f + 8f)
        val p = g.projection(outWidth = 3000, outHeight = 4000)!!
        // Upright after a 90° clockwise rotation: (x, y) -> (H - y, x).
        assertEquals(3000 - 1550.0, p.cx, 1e-6)
        assertEquals(2100.0, p.cy, 1e-6)
        assertEquals(2000.0, p.focal, 1e-6)

        val half = g.projection(outWidth = 1500, outHeight = 2000)!!
        assertEquals(p.cx / 2, half.cx, 1e-6)
        assertEquals(1000.0, half.focal, 1e-6)
    }

    @Test
    fun priorMapsOpticalCentresAndFocalRatio() {
        val main = lens("main", fx = 3400f, cx = 2008f, cy = 1508f)
        val uw = lens("uw", fx = 1650f, cx = 2008f + 40f, cy = 1508f - 20f)
        val pm = main.projection(3000, 4000)!!
        val pu = uw.projection(3000, 4000)!!
        val prior = pu.priorFrom(pm)
        assertEquals(1650.0 / 3400.0, prior.scale, 1e-9)
        // The main lens' optical centre lands on the ultra wide's optical centre.
        assertEquals(pu.cx, prior.mapX(pm.cx, pm.cy), 1e-6)
        assertEquals(pu.cy, prior.mapY(pm.cx, pm.cy), 1e-6)
    }

    @Test
    fun focalLengthFallsBackToDataSheet() {
        val g = LensGeometry(
            cameraId = "x",
            sensorOrientation = 90,
            activeArray = LensGeometry.IntRect(0, 0, 4000, 3000),
            preCorrectionArray = LensGeometry.IntRect(0, 0, 4000, 3000),
            focalLengthMm = 6.9f,
            sensorWidthMm = 9.8f,
            pixelArrayWidth = 4000
        )
        val f = g.focalLengthPx()
        assertNotNull(f)
        assertEquals(6.9 / 9.8 * 4000, f!!, 0.5)
    }

    @Test
    fun implausibleCropRegionIsIgnored() {
        val g = lens("uw", fx = 2000f, cx = 2008f, cy = 1508f)
        val reference = g.projection(3000, 4000)!!
        val withBogusCrop = g.projection(3000, 4000, cropRegion = LensGeometry.IntRect(-500, 0, 9000, 3000))!!
        assertEquals(reference.focal, withBogusCrop.focal, 1e-9)
    }
}
