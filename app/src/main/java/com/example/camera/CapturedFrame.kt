package com.example.camera

import android.graphics.Bitmap
import com.example.align.FrameMetadata

/**
 * One unprocessed frame of a capture: upright, full field of view of its lens (apart from a
 * zoom the camera itself may already have applied), not yet aligned to the other lenses.
 */
class CapturedFrame(
    val lens: CameraLensDetails,
    val bitmap: Bitmap,
    /** Clockwise rotation that was applied to the sensor image to make it upright. */
    val rotationDegrees: Int,
    /** Digital zoom the camera already applied when taking the picture (CameraX paths). */
    val appliedZoom: Float = 1f,
    val metadata: FrameMetadata? = null,
    /** The original JPEG, kept for the diagnostics export. */
    val jpeg: ByteArray? = null
) {
    /** Size of the upright frame, still valid after the bitmap has been recycled. */
    val width: Int = bitmap.width
    val height: Int = bitmap.height
}

/**
 * A frame straight from the camera: still encoded, so taking it costs no decoding time and
 * little memory. [decode] turns it into a [CapturedFrame] in the background.
 */
class RawFrame internal constructor(
    val lens: CameraLensDetails,
    internal val jpeg: CameraImages.Jpeg,
    val appliedZoom: Float = 1f,
    val metadata: FrameMetadata? = null
) {
    val bytes: ByteArray get() = jpeg.bytes
    val rotationDegrees: Int get() = jpeg.rotationDegrees

    fun decode(): CapturedFrame? = CameraImages.decode(jpeg)?.let { bitmap ->
        CapturedFrame(lens, bitmap, jpeg.rotationDegrees, appliedZoom, metadata, jpeg.bytes)
    }
}

/** All frames of one press of the shutter. */
class RawCapture(
    val frames: List<RawFrame>,
    /** True if all frames show the same instant (dual physical session or CameraX pair). */
    val simultaneous: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
