package com.example.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy

/**
 * JPEG handling shared by all capture paths.
 *
 * Capturing only copies the encoded JPEG; decoding (tens of megabytes per 4K frame) happens
 * later in the background processing queue, so the shutter is free again right away.
 *
 * Every function returns null instead of throwing: a single decode failure must never
 * take down a capture, it only reduces the number of frames in the resulting wiggle.
 */
internal object CameraImages {
    private const val TAG = "CameraImages"

    /** An encoded frame plus what is needed to decode it upright. */
    class Jpeg(
        val bytes: ByteArray,
        val rotationDegrees: Int,
        /** Crop (CameraX viewport) in the coordinates of a [sourceWidth] x [sourceHeight] image. */
        val crop: Rect? = null,
        val sourceWidth: Int = 0,
        val sourceHeight: Int = 0
    )

    /** Copies the JPEG out of an [ImageProxy] without decoding it. */
    fun jpegOf(image: ImageProxy): Jpeg? = try {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val full = image.cropRect.left == 0 && image.cropRect.top == 0 &&
            image.cropRect.width() == image.width && image.cropRect.height() == image.height
        Jpeg(
            bytes = bytes,
            rotationDegrees = image.imageInfo.rotationDegrees,
            crop = if (full) null else Rect(image.cropRect),
            sourceWidth = image.width,
            sourceHeight = image.height
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed copying JPEG", e)
        null
    }

    /** Decodes [jpeg], applies its crop (without stretching) and rotates it upright. */
    fun decode(jpeg: Jpeg): Bitmap? =
        decode(jpeg.bytes, jpeg.rotationDegrees, jpeg.crop, jpeg.sourceWidth, jpeg.sourceHeight)

    /** Decodes a JPEG, optionally crops it and rotates it upright. */
    fun decode(
        bytes: ByteArray,
        rotationDegrees: Int,
        crop: Rect? = null,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0
    ): Bitmap? = try {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded == null) {
            null
        } else {
            var x = 0
            var y = 0
            var w = decoded.width
            var h = decoded.height
            if (crop != null && sourceWidth > 0 && sourceHeight > 0) {
                val sx = decoded.width.toFloat() / sourceWidth
                val sy = decoded.height.toFloat() / sourceHeight
                w = (crop.width() * sx).toInt().coerceIn(1, decoded.width)
                h = (crop.height() * sy).toInt().coerceIn(1, decoded.height)
                x = (crop.left * sx).toInt().coerceIn(0, decoded.width - w)
                y = (crop.top * sy).toInt().coerceIn(0, decoded.height - h)
            }
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            createBitmapSafely(decoded, x, y, w, h, matrix)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed decoding JPEG", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory decoding JPEG", e)
        null
    }

    /** Decodes a raw JPEG from the Camera2 path and rotates it upright. */
    fun fromJpeg(bytes: ByteArray, rotationDegrees: Int): Bitmap? = decode(bytes, rotationDegrees)

    private fun createBitmapSafely(
        source: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        matrix: Matrix
    ): Bitmap? = try {
        val result = Bitmap.createBitmap(source, x, y, width, height, matrix, true)
        // createBitmap may hand back the source instance when the transform is a no-op.
        if (result !== source) {
            source.recycle()
        }
        result
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory transforming bitmap ${source.width}x${source.height}", e)
        source.recycle()
        null
    }
}
