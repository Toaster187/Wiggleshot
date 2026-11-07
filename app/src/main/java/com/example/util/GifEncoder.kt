package com.example.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns the frames of a wiggle into an animated GIF; the encoding itself is in [GifWriter]. */
object GifEncoder {

    /** Longest edge of an exported GIF. */
    const val DEFAULT_MAX_DIMENSION = 1080

    /**
     * Encodes [bitmaps] in order (repeat a bitmap for ping-pong), scaled to fit
     * [maxDimension]. Throws if encoding fails instead of returning a broken file.
     */
    fun encode(bitmaps: List<Bitmap>, delayMs: Int, maxDimension: Int = DEFAULT_MAX_DIMENSION): ByteArray {
        require(bitmaps.isNotEmpty()) { "No frames" }
        val (width, height) = fitSize(bitmaps[0].width, bitmaps[0].height, maxDimension)
        val pixels = IdentityHashMap<Bitmap, IntArray>()
        val frames = bitmaps.map { bitmap -> pixels.getOrPut(bitmap) { pixelsOf(bitmap, width, height) } }
        val out = ByteArrayOutputStream(width * height * pixels.size)
        GifWriter.write(out, frames, width, height, delayMs)
        return out.toByteArray()
    }

    /** Size that fits [maxDimension] on the longest edge, keeping the aspect ratio. */
    fun fitSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun pixelsOf(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        return pixels
    }
}
