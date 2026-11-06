package com.example.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy

/**
 * Bitmap conversions shared by all capture paths.
 *
 * Every function returns null instead of throwing: a single decode failure must never
 * take down a capture, it only reduces the number of frames in the resulting wiggle.
 *
 * Frames are deliberately **not** zoomed or cropped here any more. The old code centre
 * cropped each lens by its zoom factor right after decoding, which threw away the area the
 * aligner needs to correct the real (off-centre) optical axis of the secondary lens.
 */
internal object CameraImages {
    private const val TAG = "CameraImages"

    class Decoded(val bitmap: Bitmap, val rotationDegrees: Int, val jpeg: ByteArray?)

    /** Decodes a JPEG [ImageProxy], applies its crop rect (without stretching) and rotation. */
    fun fromImageProxy(image: ImageProxy): Decoded? = try {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded == null) {
            null
        } else {
            val crop = image.cropRect
            val scaleX = decoded.width.toFloat() / image.width.toFloat()
            val scaleY = decoded.height.toFloat() / image.height.toFloat()
            val cropWidth = (crop.width() * scaleX).toInt().coerceIn(1, decoded.width)
            val cropHeight = (crop.height() * scaleY).toInt().coerceIn(1, decoded.height)
            val cropX = (crop.left * scaleX).toInt().coerceIn(0, decoded.width - cropWidth)
            val cropY = (crop.top * scaleY).toInt().coerceIn(0, decoded.height - cropHeight)
            val rotation = image.imageInfo.rotationDegrees
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            createBitmapSafely(decoded, cropX, cropY, cropWidth, cropHeight, matrix)
                ?.let { Decoded(it, rotation, bytes) }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed converting ImageProxy", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory converting ImageProxy", e)
        null
    }

    /** Decodes a raw JPEG from the Camera2 path and rotates it upright. */
    fun fromJpeg(bytes: ByteArray, rotationDegrees: Int): Bitmap? = try {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded == null) {
            null
        } else {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            createBitmapSafely(decoded, 0, 0, decoded.width, decoded.height, matrix)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed decoding JPEG", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory decoding JPEG", e)
        null
    }

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
