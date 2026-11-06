package com.example.align

import android.graphics.Bitmap
import kotlin.math.max

/**
 * Grayscale working image of a bitmap, averaging [factor] x [factor] pixel blocks (exact
 * area averaging, so no aliasing). Read in row strips to keep the extra memory small.
 * Continuous coordinates map as `x_bitmap = factor * x_gray`.
 */
fun Bitmap.toGrayImage(factor: Int): GrayImage {
    require(factor >= 1)
    val w = max(1, width / factor)
    val h = max(1, height / factor)
    val rowPixels = IntArray(width * factor)
    val out = FloatArray(w * h)
    val norm = 1f / (factor * factor)
    for (oy in 0 until h) {
        getPixels(rowPixels, 0, width, 0, oy * factor, width, factor)
        for (ox in 0 until w) {
            var sum = 0f
            for (dy in 0 until factor) {
                val base = dy * width + ox * factor
                for (dx in 0 until factor) sum += GrayImage.luma(rowPixels[base + dx])
            }
            out[oy * w + ox] = sum * norm
        }
    }
    return GrayImage(w, h, out)
}
