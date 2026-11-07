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
 *
 * In night mode [extras] holds the further frames of the same lens from the burst (same
 * zoom, taken right after [jpeg]); they are aligned and averaged before the wiggle is built.
 */
class RawFrame internal constructor(
    val lens: CameraLensDetails,
    internal val jpeg: CameraImages.Jpeg,
    val appliedZoom: Float = 1f,
    val metadata: FrameMetadata? = null,
    internal val extras: List<CameraImages.Jpeg> = emptyList()
) {
    val bytes: ByteArray get() = jpeg.bytes
    val rotationDegrees: Int get() = jpeg.rotationDegrees

    /** All frames of this lens: [jpeg] first, then the burst [extras]. */
    val frameCount: Int get() = 1 + extras.size

    /** Encoded bytes of every burst frame, [bytes] first. */
    val allBytes: List<ByteArray> get() = listOf(jpeg.bytes) + extras.map { it.bytes }

    /** Total encoded size, used to bound the processing queue. */
    val encodedSize: Long get() = jpeg.bytes.size.toLong() + extras.sumOf { it.bytes.size.toLong() }

    fun decode(): CapturedFrame? = decode(0)

    /** Decodes burst frame [index] (0 = [jpeg]). */
    fun decode(index: Int): CapturedFrame? {
        val source = if (index == 0) jpeg else extras.getOrNull(index - 1) ?: return null
        return CameraImages.decode(source)?.let { bitmap ->
            CapturedFrame(lens, bitmap, source.rotationDegrees, appliedZoom, metadata, source.bytes)
        }
    }

    /** Small decode (every [sampleSize]-th pixel) of burst frame [index], for picking the sharpest. */
    fun decodePreview(index: Int, sampleSize: Int): android.graphics.Bitmap? {
        val source = if (index == 0) jpeg else extras.getOrNull(index - 1) ?: return null
        return CameraImages.decodeSampled(source, sampleSize)
    }
}

/** All frames of one press of the shutter. */
class RawCapture(
    val frames: List<RawFrame>,
    /** True if all frames show the same instant (dual physical session or CameraX pair). */
    val simultaneous: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** How long the night burst took, i.e. how long the user had to hold still. */
    var burstDurationMs: Long = 0

    /** Night mode burst (more than one frame per lens). */
    val isBurst: Boolean get() = frames.any { it.frameCount > 1 }

    val encodedSize: Long get() = frames.sumOf { it.encodedSize }
}

/** Exposure and focus of a preview frame. [focusDiopters] is null if the lens is not calibrated. */
data class PreviewStats(
    val exposureNs: Long?,
    val iso: Int?,
    val focusDiopters: Float?,
    val timestampMs: Long
)
