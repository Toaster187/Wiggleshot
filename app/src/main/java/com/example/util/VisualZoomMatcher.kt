package com.example.util

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object VisualZoomMatcher {
    private const val TAG = "VisualZoomMatcher"
    private const val GRID_SIZE = 48
    private const val REF_FRACTION = 0.62f
    private const val ZOOM_CROP_PENALTY = 0.025f

    // Penalizes off-center matches so that, on flat ZNCC surfaces, the centered
    // alignment wins deterministically instead of a noise-driven offset.
    private const val OFFSET_PENALTY = 0.012f

    // Gradient (edge structure) correlation is far less sensitive to exposure /
    // gain differences between frames (e.g. AE still converging after a cold
    // start) than raw luminance correlation, so it gets the larger weight.
    private const val LUMA_WEIGHT = 0.4f
    private const val GRADIENT_WEIGHT = 0.6f

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

        // Summed-area tables make box-averaged sampling O(1) per grid cell.
        // Area averaging suppresses sensor noise / aliasing that nearest-neighbor
        // sampling would propagate straight into the ZNCC score.
        val integralA = integralLuminance(pixelsA, bmpA.width, bmpA.height)
        val integralB = integralLuminance(pixelsB, bmpB.width, bmpB.height)

        val refW = (bmpA.width * REF_FRACTION).roundToInt().coerceIn(1, bmpA.width)
        val refH = (bmpA.height * REF_FRACTION).roundToInt().coerceIn(1, bmpA.height)
        val refX0 = (bmpA.width - refW) / 2
        val refY0 = (bmpA.height - refH) / 2

        val lumA = sampleRegion(integralA, bmpA.width, bmpA.height, refX0, refY0, refW, refH)
        val statsLumA = PatchStats.of(lumA)
        if (statsLumA.variance <= 0.0) {
            return minBound
        }
        val gradA = gradientMagnitude(lumA)
        val statsGradA = PatchStats.of(gradA)

        var bestZoom = minBound
        var bestRawScore = -2f
        var bestAdjustedScore = -2f
        var bestOffsetX = 0f
        var bestOffsetY = 0f
        val zoomRange = (maxBound - minBound).coerceAtLeast(0.0001f)

        fun evaluate(zoomToCheck: Float, offsetStepsX: FloatArray, offsetStepsY: FloatArray): Triple<Float, Float, Float> {
            var localBestAdj = -2f
            var localBestRaw = -2f
            var localBestX = 0f
            var localBestY = 0f

            val cropW = (bmpB.width / zoomToCheck).roundToInt().coerceIn(1, bmpB.width)
            val cropH = (bmpB.height / zoomToCheck).roundToInt().coerceIn(1, bmpB.height)
            val cropX0 = (bmpB.width - cropW) / 2
            val cropY0 = (bmpB.height - cropH) / 2
            val innerW = (cropW * REF_FRACTION).roundToInt().coerceIn(1, cropW)
            val innerH = (cropH * REF_FRACTION).roundToInt().coerceIn(1, cropH)
            val maxShiftX = ((cropW - innerW) / 2f).coerceAtLeast(0f)
            val maxShiftY = ((cropH - innerH) / 2f).coerceAtLeast(0f)
            val centeredX0 = cropX0 + (cropW - innerW) / 2f
            val centeredY0 = cropY0 + (cropH - innerH) / 2f

            for (offsetY in offsetStepsY) {
                for (offsetX in offsetStepsX) {
                    val sampleX0 = (centeredX0 + offsetX * maxShiftX)
                        .roundToInt()
                        .coerceIn(cropX0, cropX0 + cropW - innerW)
                    val sampleY0 = (centeredY0 + offsetY * maxShiftY)
                        .roundToInt()
                        .coerceIn(cropY0, cropY0 + cropH - innerH)

                    val lumB = sampleRegion(
                        integral = integralB,
                        bitmapWidth = bmpB.width,
                        bitmapHeight = bmpB.height,
                        x0 = sampleX0,
                        y0 = sampleY0,
                        width = innerW,
                        height = innerH
                    )
                    val lumScore = zncc(lumA, statsLumA, lumB)
                    val gradScore = zncc(gradA, statsGradA, gradientMagnitude(lumB))
                    val rawScore = LUMA_WEIGHT * lumScore + GRADIENT_WEIGHT * gradScore

                    val cropPenalty = ((zoomToCheck - minBound) / zoomRange) * ZOOM_CROP_PENALTY
                    val offsetDistance = sqrt(
                        (offsetX * offsetX + offsetY * offsetY).toDouble()
                    ).toFloat() / sqrt(2f)
                    val adjustedScore = rawScore - cropPenalty - offsetDistance * OFFSET_PENALTY

                    if (adjustedScore > localBestAdj) {
                        localBestAdj = adjustedScore
                        localBestRaw = rawScore
                        localBestX = offsetX
                        localBestY = offsetY
                    }
                }
            }
            return Triple(localBestAdj, localBestX, localBestY)
        }

        // Phase 1: Coarse Search
        val coarseZoomStep = 0.1f
        val coarseOffsetsX = floatArrayOf(-0.66f, 0f, 0.66f)
        val coarseOffsetsY = floatArrayOf(-0.66f, 0f, 0.66f)
        
        class Candidate(val zoom: Float, val score: Float)
        val coarseCandidates = mutableListOf<Candidate>()

        var zCoarse = minBound
        while (zCoarse <= maxBound + 0.0001f) {
            val (adjScore, _, _) = evaluate(zCoarse, coarseOffsetsX, coarseOffsetsY)
            coarseCandidates.add(Candidate(zCoarse, adjScore))
            zCoarse += coarseZoomStep
        }

        // Phase 2: Fine Search around top candidates
        coarseCandidates.sortByDescending { it.score }
        val topCandidates = coarseCandidates.take(3).map { it.zoom }

        val fineZoomStep = 0.01f
        val fineOffsets = OFFSET_STEPS
        val evaluatedZooms = mutableSetOf<Int>()

        for (baseZoom in topCandidates) {
            var zFine = (baseZoom - 0.06f).coerceAtLeast(minBound)
            val zEnd = (baseZoom + 0.06f).coerceAtMost(maxBound)
            
            while (zFine <= zEnd + 0.0001f) {
                val zoomKey = (zFine * 1000).roundToInt()
                if (evaluatedZooms.add(zoomKey)) {
                    val (adjScore, offX, offY) = evaluate(zFine, fineOffsets, fineOffsets)
                    if (adjScore > bestAdjustedScore) {
                        bestAdjustedScore = adjScore
                        bestRawScore = adjScore
                        bestZoom = zFine
                        bestOffsetX = offX
                        bestOffsetY = offY
                    }
                }
                zFine += fineZoomStep
            }
        }

        Log.d(
            TAG,
            "Translation-aware zoom=$bestZoom raw=$bestRawScore adjusted=$bestAdjustedScore offset=($bestOffsetX,$bestOffsetY)"
        )
        return bestZoom.coerceIn(minBound, maxBound)
    }

    /**
     * Builds a (width+1) x (height+1) summed-area table of the luminance so any
     * axis-aligned box average can be computed in constant time.
     */
    private fun integralLuminance(pixels: IntArray, width: Int, height: Int): DoubleArray {
        val stride = width + 1
        val integral = DoubleArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0.0
            val srcRow = y * width
            val dstRow = (y + 1) * stride
            val prevRow = y * stride
            for (x in 0 until width) {
                rowSum += luminance(pixels[srcRow + x]).toDouble()
                integral[dstRow + x + 1] = integral[prevRow + x + 1] + rowSum
            }
        }
        return integral
    }

    /**
     * Samples the given region into a GRID_SIZE x GRID_SIZE grid where every
     * cell is the average luminance of the pixel block it covers (area
     * downsampling). This is deterministic and strongly suppresses noise
     * compared to picking single nearest-neighbor pixels.
     */
    private fun sampleRegion(
        integral: DoubleArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        x0: Int,
        y0: Int,
        width: Int,
        height: Int
    ): FloatArray {
        val stride = bitmapWidth + 1
        val values = FloatArray(GRID_SIZE * GRID_SIZE)

        for (gy in 0 until GRID_SIZE) {
            var ys = gridEdge(y0, height, gy, bitmapHeight)
            var ye = gridEdge(y0, height, gy + 1, bitmapHeight)
            if (ye <= ys) {
                ys = ys.coerceIn(0, bitmapHeight - 1)
                ye = ys + 1
            }
            for (gx in 0 until GRID_SIZE) {
                var xs = gridEdge(x0, width, gx, bitmapWidth)
                var xe = gridEdge(x0, width, gx + 1, bitmapWidth)
                if (xe <= xs) {
                    xs = xs.coerceIn(0, bitmapWidth - 1)
                    xe = xs + 1
                }
                val sum = integral[ye * stride + xe] -
                    integral[ys * stride + xe] -
                    integral[ye * stride + xs] +
                    integral[ys * stride + xs]
                val area = (xe - xs) * (ye - ys)
                values[gy * GRID_SIZE + gx] = (sum / area).toFloat()
            }
        }
        return values
    }

    private fun gridEdge(origin: Int, extent: Int, gridIndex: Int, limit: Int): Int {
        return (origin + (extent.toLong() * gridIndex / GRID_SIZE).toInt()).coerceIn(0, limit)
    }

    /**
     * Central-difference gradient magnitude on the sampled grid. Correlating
     * edge structure instead of raw brightness makes the match robust against
     * exposure/gain differences between the two frames.
     */
    private fun gradientMagnitude(values: FloatArray): FloatArray {
        val out = FloatArray(values.size)
        for (y in 0 until GRID_SIZE) {
            val ym = (y - 1).coerceAtLeast(0)
            val yp = min(y + 1, GRID_SIZE - 1)
            for (x in 0 until GRID_SIZE) {
                val xm = (x - 1).coerceAtLeast(0)
                val xp = min(x + 1, GRID_SIZE - 1)
                val dx = (values[y * GRID_SIZE + xp] - values[y * GRID_SIZE + xm]) * 0.5f
                val dy = (values[yp * GRID_SIZE + x] - values[ym * GRID_SIZE + x]) * 0.5f
                out[y * GRID_SIZE + x] = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            }
        }
        return out
    }

    private fun luminance(color: Int): Float {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return red * 0.299f + green * 0.587f + blue * 0.114f
    }

    private class PatchStats(val mean: Float, val variance: Double) {
        companion object {
            fun of(values: FloatArray): PatchStats {
                var sum = 0.0
                for (value in values) {
                    sum += value
                }
                val mean = (sum / values.size).toFloat()
                var variance = 0.0
                for (value in values) {
                    val delta = (value - mean).toDouble()
                    variance += delta * delta
                }
                return PatchStats(mean, variance)
            }
        }
    }

    private fun zncc(
        valuesA: FloatArray,
        statsA: PatchStats,
        valuesB: FloatArray
    ): Float {
        if (statsA.variance <= 0.0) {
            return 0f
        }
        var sumB = 0.0
        for (value in valuesB) {
            sumB += value
        }
        val meanB = (sumB / valuesB.size).toFloat()

        var crossSum = 0.0
        var varianceB = 0.0
        for (i in valuesA.indices) {
            val deltaA = (valuesA[i] - statsA.mean).toDouble()
            val deltaB = (valuesB[i] - meanB).toDouble()
            crossSum += deltaA * deltaB
            varianceB += deltaB * deltaB
        }

        val denominator = sqrt(statsA.variance * varianceB)
        return if (denominator > 0.0) {
            (crossSum / denominator).toFloat()
        } else {
            0f
        }
    }
}
