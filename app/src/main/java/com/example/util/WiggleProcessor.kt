package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object WiggleProcessor {
    private const val TAG = "WiggleProcessor"

    /** Longest edge a frame is decoded to for playback and GIF export. */
    const val MAX_PLAYBACK_DIMENSION = 1920



    /**
     * Save a bitmap to internal app directories for stable local retrieval.
     */
    fun saveToInternalFiles(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            val directory = File(context.filesDir, "wiggle_captures")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val file = File(directory, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d(TAG, "Saved to internal storage: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save locally", e)
            null
        }
    }

    /**
     * Save a bitmap to the device's public photo gallery.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, publicName: String): String? {
        val resolver = context.contentResolver
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$publicName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WiggleCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        return try {
            val uri = resolver.insert(imageCollection, contentValues) ?: return null
            resolver.openOutputStream(uri).use { out ->
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Log.d(TAG, "Successfully exported to public gallery URI: $uri")
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to MediaStore", e)
            null
        }
    }

    /**
     * Loads an image for playback, downsampled so its longest edge stays within
     * [maxDimension].
     *
     * A wiggle can hold four 4K frames; decoding those at full size needs well over 100 MB
     * of heap and was a reliable way to get the app killed. The full resolution originals
     * are untouched in the gallery.
     */
    fun loadBitmap(path: String, maxDimension: Int = MAX_PLAYBACK_DIMENSION): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding file: $path", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory decoding file: $path", e)
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Save an animated GIF to the device's public photo gallery.
     */
    fun saveGifToGallery(context: Context, gifBytes: ByteArray, publicName: String): String? {
        val resolver = context.contentResolver
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$publicName.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WiggleCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        return try {
            val uri = resolver.insert(imageCollection, contentValues) ?: return null
            resolver.openOutputStream(uri).use { out ->
                out?.write(gifBytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Log.d(TAG, "Successfully exported GIF to public gallery URI: $uri")
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving GIF to MediaStore", e)
            null
        }
    }
}
