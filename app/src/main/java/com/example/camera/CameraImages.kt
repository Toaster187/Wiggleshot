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
 */
internal object CameraImages {
    private const val TAG = "CameraImages"

    /**
     * Decodes a JPEG [ImageProxy], applies its crop rect and rotation and scales the crop
     * back to the original frame size so that all frames of one capture share dimensions.
     */
    fun fromImageProxy(image: ImageProxy): Bitmap? = try {
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

            val matrix = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                postScale(
                    decoded.width.toFloat() / cropWidth,
                    decoded.height.toFloat() / cropHeight
                )
            }
            createBitmapSafely(decoded, cropX, cropY, cropWidth, cropHeight, matrix)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed converting ImageProxy", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory converting ImageProxy", e)
        null
    }

    /**
     * Decodes a raw JPEG from the Camera2 path, centre-crops it by [zoom] and rotates it
     * upright. The crop is scaled back up so frames captured with different zoom factors
     * still line up pixel for pixel.
     */
    fun fromJpeg(bytes: ByteArray, rotationDegrees: Int, zoom: Float): Bitmap? = try {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded == null) {
            null
        } else {
            val safeZoom = if (zoom.isFinite()) zoom.coerceAtLeast(1f) else 1f
            val cropWidth = (decoded.width / safeZoom).toInt().coerceIn(1, decoded.width)
            val cropHeight = (decoded.height / safeZoom).toInt().coerceIn(1, decoded.height)
            val cropX = (decoded.width - cropWidth) / 2
            val cropY = (decoded.height - cropHeight) / 2
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                postScale(safeZoom, safeZoom)
            }
            createBitmapSafely(decoded, cropX, cropY, cropWidth, cropHeight, matrix)
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
