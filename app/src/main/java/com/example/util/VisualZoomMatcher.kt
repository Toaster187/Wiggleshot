package com.example.util

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.roundToInt
import kotlin.math.sqrt

object VisualZoomMatcher {
    private const val TAG = "VisualZoomMatcher"
    private const val GRID_SIZE = 48
    private const val REF_FRACTION = 0.56f
    private const val ZOOM_CROP_PENALTY = 0.025f
    private const val OFFSET_BONUS = 0.012f
    private val OFFSET_STEPS = floatArrayOf(-1f, -0.66f, -0.33f, 0f, 0.33f, 0.66f, 1f)

    fun calculate(
        bmpA: Bitmap,
        bmpB: Bitmap,
        minZoom: Float,
        maxZoom: Float
    ): Float {
        val minBound = minZoom.coerceAtLeast(0.5f)
        val maxBound = maxZoom.coerceAtLeast(minBound)
        if (bmpA.width < 4 || bmpA.height < 4 || bmpB.width < 4 || bmpB.height < 4) {
            return minBound
        }

        val pixelsA = IntArray(bmpA.width * bmpA.height)
        bmpA.getPixels(pixelsA, 0, bmpA.width, 0, 0, bmpA.width, bmpA.height)
        val pixelsB = IntArray(bmpB.width * bmpB.height)
        bmpB.getPixels(pixelsB, 0, bmpB.width, 0, 0, bmpB.width, bmpB.height)

        val refW = (bmpA.width * REF_FRACTION).roundToInt().coerceIn(1, bmpA.width)
        val refH = (bmpA.height * REF_FRACTION).roundToInt().coerceIn(1, bmpA.height)
        val refX0 = (bmpA.width - refW) / 2
        val refY0 = (bmpA.height - refH) / 2

        val lumA = sampleRegion(
            pixels = pixelsA,
            bitmapWidth = bmpA.width,
            bitmapHeight = bmpA.height,
            x0 = refX0,
            y0 = refY0,
            width = refW,
            height = refH
        )
        val meanA = lumA.average().toFloat()
        var varianceA = 0.0
        for (value in lumA) {
            val delta = (value - meanA).toDouble()
            varianceA += delta * delta
        }
        if (varianceA <= 0.0) {
            return minBound
        }

        var bestZoom = minBound
        var bestRawScore = -2f
        var bestAdjustedScore = -2f
        var bestOffsetX = 0f
        var bestOffsetY = 0f
        val zoomRange = (maxBound - minBound).coerceAtLeast(0.0001f)
        val zoomStep = if (zoomRange > 1.2f) 0.02f else 0.01f

        var zoom = minBound
        while (zoom <= maxBound + 0.0001f) {
            val cropW = (bmpB.width / zoom).roundToInt().coerceIn(1, bmpB.width)
            val cropH = (bmpB.height / zoom).roundToInt().coerceIn(1, bmpB.height)
            val cropX0 = (bmpB.width - cropW) / 2
            val cropY0 = (bmpB.height - cropH) / 2
            val innerW = (cropW * REF_FRACTION).roundToInt().coerceIn(1, cropW)
            val innerH = (cropH * REF_FRACTION).roundToInt().coerceIn(1, cropH)
            val maxShiftX = ((cropW - innerW) / 2f).coerceAtLeast(0f)
            val maxShiftY = ((cropH - innerH) / 2f).coerceAtLeast(0f)
            val centeredX0 = cropX0 + (cropW - innerW) / 2f
            val centeredY0 = cropY0 + (cropH - innerH) / 2f

            for (offsetY in OFFSET_STEPS) {
                for (offsetX in OFFSET_STEPS) {
                    val sampleX0 = (centeredX0 + offsetX * maxShiftX)
                        .roundToInt()
                        .coerceIn(cropX0, cropX0 + cropW - innerW)
                    val sampleY0 = (centeredY0 + offsetY * maxShiftY)
                        .roundToInt()
                        .coerceIn(cropY0, cropY0 + cropH - innerH)

                    val lumB = sampleRegion(
                        pixels = pixelsB,
                        bitmapWidth = bmpB.width,
                        bitmapHeight = bmpB.height,
                        x0 = sampleX0,
                        y0 = sampleY0,
                        width = innerW,
                        height = innerH
                    )
                    val rawScore = zncc(lumA, meanA, varianceA, lumB)
                    val cropPenalty = ((zoom - minBound) / zoomRange) * ZOOM_CROP_PENALTY
                    val offsetDistance = sqrt(
                        (offsetX * offsetX + offsetY * offsetY).toDouble()
                    ).toFloat() / sqrt(2f)
                    val adjustedScore = rawScore - cropPenalty + offsetDistance * OFFSET_BONUS

                    if (adjustedScore > bestAdjustedScore) {
                        bestAdjustedScore = adjustedScore
                        bestRawScore = rawScore
                        bestZoom = zoom
                        bestOffsetX = offsetX
                        bestOffsetY = offsetY
                    }
                }
            }

            zoom += zoomStep
        }

        Log.d(
            TAG,
            "Translation-aware zoom=$bestZoom raw=$bestRawScore adjusted=$bestAdjustedScore offset=($bestOffsetX,$bestOffsetY)"
        )
        return bestZoom.coerceIn(minBound, maxBound)
    }

    private fun sampleRegion(
        pixels: IntArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        x0: Int,
        y0: Int,
        width: Int,
        height: Int
    ): FloatArray {
        val values = FloatArray(GRID_SIZE * GRID_SIZE)
        val maxX = (x0 + width - 1).coerceIn(0, bitmapWidth - 1)
        val maxY = (y0 + height - 1).coerceIn(0, bitmapHeight - 1)

        for (gy in 0 until GRID_SIZE) {
            val y = if (GRID_SIZE == 1) {
                y0
            } else {
                y0 + ((maxY - y0) * gy) / (GRID_SIZE - 1)
            }.coerceIn(0, bitmapHeight - 1)
            for (gx in 0 until GRID_SIZE) {
                val x = if (GRID_SIZE == 1) {
                    x0
                } else {
                    x0 + ((maxX - x0) * gx) / (GRID_SIZE - 1)
                }.coerceIn(0, bitmapWidth - 1)
                val color = pixels[y * bitmapWidth + x]
                values[gy * GRID_SIZE + gx] = luminance(color)
            }
        }
        return values
    }

    private fun luminance(color: Int): Float {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return red * 0.299f + green * 0.587f + blue * 0.114f
    }

    private fun zncc(
        lumA: FloatArray,
        meanA: Float,
        varianceA: Double,
        lumB: FloatArray
    ): Float {
        var sumB = 0.0
        for (value in lumB) {
            sumB += value
        }
        val meanB = (sumB / lumB.size).toFloat()

        var crossSum = 0.0
        var varianceB = 0.0
        for (i in lumA.indices) {
            val deltaA = (lumA[i] - meanA).toDouble()
            val deltaB = (lumB[i] - meanB).toDouble()
            crossSum += deltaA * deltaB
            varianceB += deltaB * deltaB
        }

        val denominator = sqrt(varianceA * varianceB)
        return if (denominator > 0.0) {
            (crossSum / denominator).toFloat()
        } else {
            0f
        }
    }
}
