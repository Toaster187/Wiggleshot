package com.example.util

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * ML-based Perceptual Image Matcher
 *
 * Uses multiple feature extraction techniques from computer vision / machine learning
 * to compare images robustly, invariant to:
 * - Different camera color responses & white balance
 * - Brightness and exposure differences
 * - Sensor noise patterns
 * - Vignetting and lens-specific quirks
 *
 * Features:
 * 1. Sobel edge magnitude maps → structural matching via ZNCC
 * 2. Spatial Gradient Orientation Histograms (HOG-inspired) → shape matching
 * 3. Multi-scale structural analysis → coarse structure invariance
 * 4. Local Contrast Patterns (LBP-inspired) → texture matching
 */
object PerceptualImageMatcher {
    private const val TAG = "PerceptualMatcher"

    // Comparison resolution – 96x96 balances speed and accuracy well
    private const val CMP_SIZE = 96

    // Number of gradient orientation bins (10° each)
    private const val ORIENT_BINS = 36

    // Spatial grid for HOG: 4x4 = 16 cells, each with 36 bins = 576-dim feature
    private const val SPATIAL_CELLS = 4

    /**
     * Compute perceptual similarity between two images.
     *
     * @param imgA Primary camera reference image
     * @param imgB Secondary camera image (possibly at a specific zoom crop)
     * @return Similarity score from 0.0 (no match) to 1.0 (perfect match)
     */
    fun computeSimilarity(imgA: Bitmap, imgB: Bitmap): Float {
        var centerA: Bitmap? = null
        var centerB: Bitmap? = null
        var a: Bitmap? = null
        var b: Bitmap? = null
        var halfA: Bitmap? = null
        var halfB: Bitmap? = null
        try {
            // Extract center 60% to focus on shared FOV overlap
            centerA = extractCenter(imgA, 0.6f)
            centerB = extractCenter(imgB, 0.6f)

            // Resize to common comparison resolution
            a = Bitmap.createScaledBitmap(centerA, CMP_SIZE, CMP_SIZE, true)
            b = Bitmap.createScaledBitmap(centerB, CMP_SIZE, CMP_SIZE, true)

            val pixA = IntArray(CMP_SIZE * CMP_SIZE)
            val pixB = IntArray(CMP_SIZE * CMP_SIZE)
            a.getPixels(pixA, 0, CMP_SIZE, 0, 0, CMP_SIZE, CMP_SIZE)
            b.getPixels(pixB, 0, CMP_SIZE, 0, 0, CMP_SIZE, CMP_SIZE)

            // Convert to grayscale float arrays
            val grayA = toGrayscale(pixA)
            val grayB = toGrayscale(pixB)

            // ─── Feature 1: Edge-based structural matching ───
            val blurA = gaussianBlur5x5(grayA, CMP_SIZE, CMP_SIZE)
            val blurB = gaussianBlur5x5(grayB, CMP_SIZE, CMP_SIZE)

            val edgesA = sobelMagnitude(blurA, CMP_SIZE, CMP_SIZE)
            val edgesB = sobelMagnitude(blurB, CMP_SIZE, CMP_SIZE)
            val edgeScore = zncc(edgesA, edgesB)

            // ─── Feature 2: Spatial Gradient Orientation Histograms (HOG-like) ───
            val hogA = spatialGradientHistogram(blurA, CMP_SIZE, CMP_SIZE)
            val hogB = spatialGradientHistogram(blurB, CMP_SIZE, CMP_SIZE)
            val hogScore = cosineSimilarity(hogA, hogB)

            // ─── Feature 3: Multi-scale edge matching (half resolution) ───
            val halfSize = CMP_SIZE / 2
            halfA = Bitmap.createScaledBitmap(a, halfSize, halfSize, true)
            halfB = Bitmap.createScaledBitmap(b, halfSize, halfSize, true)
            val hPixA = IntArray(halfSize * halfSize)
            val hPixB = IntArray(halfSize * halfSize)
            halfA.getPixels(hPixA, 0, halfSize, 0, 0, halfSize, halfSize)
            halfB.getPixels(hPixB, 0, halfSize, 0, 0, halfSize, halfSize)
            val hBlurA = gaussianBlur5x5(toGrayscale(hPixA), halfSize, halfSize)
            val hBlurB = gaussianBlur5x5(toGrayscale(hPixB), halfSize, halfSize)
            val hEdgesA = sobelMagnitude(hBlurA, halfSize, halfSize)
            val hEdgesB = sobelMagnitude(hBlurB, halfSize, halfSize)
            val halfEdgeScore = zncc(hEdgesA, hEdgesB)

            // ─── Feature 4: Local Contrast Patterns (LBP-inspired) ───
            val lcpA = localContrastPattern(blurA, CMP_SIZE, CMP_SIZE)
            val lcpB = localContrastPattern(blurB, CMP_SIZE, CMP_SIZE)
            val lcpScore = zncc(lcpA, lcpB)

            // ─── Weighted fusion ───
            // Weights determined empirically for cross-camera FOV matching
            val combined = edgeScore * 0.35f +
                           hogScore * 0.25f +
                           halfEdgeScore * 0.20f +
                           lcpScore * 0.20f

            return combined.coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(TAG, "Error computing perceptual similarity", e)
            return 0f
        } finally {
            if (centerA != null && centerA != imgA) centerA.recycle()
            if (centerB != null && centerB != imgB) centerB.recycle()
            if (a != null && a != centerA) a.recycle()
            if (b != null && b != centerB) b.recycle()
            if (halfA != null && halfA != a) halfA.recycle()
            if (halfB != null && halfB != b) halfB.recycle()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Image preprocessing
    // ════════════════════════════════════════════════════════════════

    /** Extract center fraction of a bitmap */
    private fun extractCenter(bmp: Bitmap, fraction: Float): Bitmap {
        val cropW = (bmp.width * fraction).toInt().coerceAtLeast(1)
        val cropH = (bmp.height * fraction).toInt().coerceAtLeast(1)
        val x = (bmp.width - cropW) / 2
        val y = (bmp.height - cropH) / 2
        return Bitmap.createBitmap(bmp, x, y, cropW, cropH)
    }

    /** Convert ARGB pixel array to grayscale float array [0-255] */
    private fun toGrayscale(pixels: IntArray): FloatArray {
        return FloatArray(pixels.size) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            r * 0.299f + g * 0.587f + b * 0.114f
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Gaussian Blur (5x5 kernel for better noise suppression)
    // ════════════════════════════════════════════════════════════════

    /** Apply 5x5 Gaussian blur with sigma ≈ 1.0 */
    private fun gaussianBlur5x5(src: FloatArray, w: Int, h: Int): FloatArray {
        // Separable 5-tap Gaussian kernel: [1, 4, 6, 4, 1] / 16
        val kernel = floatArrayOf(1f / 16f, 4f / 16f, 6f / 16f, 4f / 16f, 1f / 16f)
        val temp = FloatArray(w * h)
        val dst = FloatArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (k in -2..2) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    sum += src[y * w + sx] * kernel[k + 2]
                }
                temp[y * w + x] = sum
            }
        }
        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (k in -2..2) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    sum += temp[sy * w + x] * kernel[k + 2]
                }
                dst[y * w + x] = sum
            }
        }
        return dst
    }

    // ════════════════════════════════════════════════════════════════
    // Sobel Edge Detection
    // ════════════════════════════════════════════════════════════════

    /**
     * Compute Sobel edge magnitude.
     * Returns the gradient magnitude at each pixel (invariant to brightness/color).
     */
    private fun sobelMagnitude(gray: FloatArray, w: Int, h: Int): FloatArray {
        val mag = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                // Sobel X kernel:  [-1  0  1]    Sobel Y kernel:  [-1 -2 -1]
                //                  [-2  0  2]                     [ 0  0  0]
                //                  [-1  0  1]                     [ 1  2  1]
                val i00 = gray[(y - 1) * w + (x - 1)]
                val i10 = gray[(y - 1) * w + x]
                val i20 = gray[(y - 1) * w + (x + 1)]
                val i01 = gray[y * w + (x - 1)]
                val i21 = gray[y * w + (x + 1)]
                val i02 = gray[(y + 1) * w + (x - 1)]
                val i12 = gray[(y + 1) * w + x]
                val i22 = gray[(y + 1) * w + (x + 1)]

                val gx = -i00 + i20 - 2 * i01 + 2 * i21 - i02 + i22
                val gy = -i00 - 2 * i10 - i20 + i02 + 2 * i12 + i22

                mag[y * w + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return mag
    }

    /**
     * Compute Sobel gradient direction (angle in radians [0, 2π)).
     * Used for HOG feature extraction.
     */
    private fun sobelDirection(gray: FloatArray, w: Int, h: Int): FloatArray {
        val dir = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i00 = gray[(y - 1) * w + (x - 1)]
                val i10 = gray[(y - 1) * w + x]
                val i20 = gray[(y - 1) * w + (x + 1)]
                val i01 = gray[y * w + (x - 1)]
                val i21 = gray[y * w + (x + 1)]
                val i02 = gray[(y + 1) * w + (x - 1)]
                val i12 = gray[(y + 1) * w + x]
                val i22 = gray[(y + 1) * w + (x + 1)]

                val gx = -i00 + i20 - 2 * i01 + 2 * i21 - i02 + i22
                val gy = -i00 - 2 * i10 - i20 + i02 + 2 * i12 + i22

                var angle = atan2(gy, gx)
                if (angle < 0) angle += (2 * PI).toFloat()
                dir[y * w + x] = angle
            }
        }
        return dir
    }

    // ════════════════════════════════════════════════════════════════
    // HOG-like Gradient Orientation Histograms
    // ════════════════════════════════════════════════════════════════

    /**
     * Compute spatial gradient orientation histogram (HOG-inspired).
     *
     * Divides the image into a SPATIAL_CELLS × SPATIAL_CELLS grid.
     * For each cell, creates a gradient orientation histogram with ORIENT_BINS bins.
     * Gradient magnitude is used as the vote weight.
     *
     * This is a key ML feature: it captures the shape/structure of the scene
     * independent of color, brightness, or noise.
     */
    private fun spatialGradientHistogram(gray: FloatArray, w: Int, h: Int): FloatArray {
        val mag = sobelMagnitude(gray, w, h)
        val dir = sobelDirection(gray, w, h)

        val totalBins = SPATIAL_CELLS * SPATIAL_CELLS * ORIENT_BINS
        val histogram = FloatArray(totalBins)

        val cellW = w / SPATIAL_CELLS
        val cellH = h / SPATIAL_CELLS

        for (cy in 0 until SPATIAL_CELLS) {
            for (cx in 0 until SPATIAL_CELLS) {
                val cellOffset = (cy * SPATIAL_CELLS + cx) * ORIENT_BINS
                val startX = cx * cellW
                val startY = cy * cellH
                val endX = if (cx == SPATIAL_CELLS - 1) w - 1 else startX + cellW
                val endY = if (cy == SPATIAL_CELLS - 1) h - 1 else startY + cellH

                for (y in startY until endY) {
                    for (x in startX until endX) {
                        val idx = y * w + x
                        val m = mag[idx]
                        if (m < 1f) continue // Skip very weak gradients (noise)

                        val angle = dir[idx]
                        val bin = ((angle / (2 * PI).toFloat()) * ORIENT_BINS).toInt()
                            .coerceIn(0, ORIENT_BINS - 1)

                        // Vote with magnitude as weight
                        histogram[cellOffset + bin] += m
                    }
                }
            }
        }

        // L2-normalize the full descriptor for scale invariance
        var norm = 0f
        for (v in histogram) norm += v * v
        norm = sqrt(norm) + 1e-6f
        for (i in histogram.indices) histogram[i] /= norm

        return histogram
    }

    // ════════════════════════════════════════════════════════════════
    // Local Contrast Patterns (LBP-inspired)
    // ════════════════════════════════════════════════════════════════

    /**
     * Compute Local Contrast Patterns – inspired by Local Binary Patterns (LBP).
     *
     * For each pixel, computes the sum of sign differences with its 8 neighbors.
     * This captures local texture patterns independent of absolute brightness.
     * It's a simplified LBP that's fast and effective for cross-camera matching.
     */
    private fun localContrastPattern(gray: FloatArray, w: Int, h: Int): FloatArray {
        val lcp = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = gray[y * w + x]
                var pattern = 0f

                // 8-connected neighborhood
                val neighbors = floatArrayOf(
                    gray[(y - 1) * w + (x - 1)],
                    gray[(y - 1) * w + x],
                    gray[(y - 1) * w + (x + 1)],
                    gray[y * w + (x - 1)],
                    gray[y * w + (x + 1)],
                    gray[(y + 1) * w + (x - 1)],
                    gray[(y + 1) * w + x],
                    gray[(y + 1) * w + (x + 1)]
                )

                for (n in neighbors) {
                    // Soft sign function – more robust than hard threshold
                    val diff = n - center
                    pattern += diff / (abs(diff) + 10f) // Sigmoid-like normalization
                }
                lcp[y * w + x] = pattern
            }
        }
        return lcp
    }

    // ════════════════════════════════════════════════════════════════
    // Similarity Metrics
    // ════════════════════════════════════════════════════════════════

    /**
     * Zero-Mean Normalized Cross-Correlation.
     * Score in [-1, 1] where 1 = identical structure.
     */
    private fun zncc(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f

        var sumA = 0.0
        var sumB = 0.0
        for (i in a.indices) {
            sumA += a[i]
            sumB += b[i]
        }
        val meanA = sumA / a.size
        val meanB = sumB / b.size

        var cross = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in a.indices) {
            val dA = a[i] - meanA
            val dB = b[i] - meanB
            cross += dA * dB
            varA += dA * dA
            varB += dB * dB
        }

        val den = sqrt(varA * varB)
        return if (den > 0) (cross / den).toFloat() else 0f
    }

    /**
     * Cosine similarity between two feature vectors.
     * Score in [-1, 1] where 1 = identical direction.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val den = sqrt(normA * normB)
        return if (den > 0) (dot / den).toFloat() else 0f
    }
}
