package com.example.align

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Night mode: merges the burst frames of one lens into a single low-noise frame.
 *
 * Nobody holds a phone still to the millimetre, so every frame is first aligned to the
 * reference with [LensAligner] (shift, rotation and the tiny scale change of focus
 * breathing). The frames are then averaged robustly: wherever a frame still differs from the
 * reference by much more than the noise — something moved, the TV picture changed, or the
 * hand moved so much that parallax shows — that frame gets no weight there, so moving parts
 * stay sharp instead of turning into ghosts. Averaging N frames lowers the noise by up to √N.
 *
 * Memory stays bounded: frames are decoded one at a time and merged into the reference in
 * row strips; motion is judged at quarter resolution.
 */
class BurstMerger {

    class Result(
        val bitmap: Bitmap,
        /** Frames that contributed (including the reference). */
        val used: Int,
        val total: Int,
        val referenceIndex: Int,
        /** Average share of the frame area where a frame was rejected as motion. */
        val rejectedFraction: Double,
        val elapsedMs: Long
    ) {
        override fun toString() = "Burst(used $used/$total, ref $referenceIndex, motion %.0f%%, ${elapsedMs}ms)".format(rejectedFraction * 100)
    }

    /**
     * [decode] returns burst frame i (0 until [count]) upright; all frames must have the
     * same size. Returns null if not even the reference could be decoded.
     */
    fun merge(count: Int, reference: Int, decode: (Int) -> Bitmap?): Result? {
        val start = System.nanoTime()
        val ref = decode(reference) ?: return null
        val acc = if (ref.isMutable && ref.config == Bitmap.Config.ARGB_8888) {
            ref
        } else {
            ref.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== ref) ref.recycle() }
        }
        val w = acc.width
        val h = acc.height
        val alignFactor = max(1, (w / ALIGN_WIDTH.toDouble()).roundToInt())
        val refGray = acc.toGrayImage(alignFactor)
        val refMotion = acc.toGrayImage(MOTION_FACTOR).gaussianBlur(1.0)
        val mw = refMotion.width
        val mh = refMotion.height
        val weightSum = FloatArray(mw * mh) { 1f }

        var used = 1
        var rejected = 0.0
        for (index in 0 until count) {
            if (index == reference) continue
            val frame = decode(index) ?: continue
            if (frame.width != w || frame.height != h) {
                frame.recycle()
                continue
            }
            val transform = align(refGray, frame.toGrayImage(alignFactor), alignFactor)
            if (transform == null) {
                Log.w(TAG, "Burst frame $index could not be aligned, skipped")
                frame.recycle()
                continue
            }
            val warped = warp(frame, transform, w, h)
            frame.recycle()

            val weights = motionWeights(refMotion, warped.toGrayImage(MOTION_FACTOR).gaussianBlur(1.0))
            rejected += weights.count { it < 0.5f }.toDouble() / weights.size
            accumulate(acc, warped, weights, weightSum, mw, mh)
            warped.recycle()
            for (i in weightSum.indices) weightSum[i] += weights[i]
            used++
        }
        val others = (count - 1).coerceAtLeast(1)
        return Result(acc, used, count, reference, rejected / others, (System.nanoTime() - start) / 1_000_000)
    }

    /** Reference px -> frame px in full resolution, or null if the frames do not match. */
    private fun align(refGray: GrayImage, frameGray: GrayImage, factor: Int): Similarity? {
        val result = LensAligner.align(
            refGray, frameGray, Similarity.IDENTITY,
            LensAligner.Options(
                priorScaleTolerance = 0.015,
                priorTranslationFraction = 0.08,
                alignPivot = false,
                coarseWidth = 80
            )
        ) ?: return null
        val t = result.global
        if (result.confidence < MIN_ALIGN_CONFIDENCE || abs(t.scale - 1) > 0.03 || abs(t.rotationDegrees) > 3.0) {
            Log.w(TAG, "Burst frame rejected: $result")
            return null
        }
        return t.reexpress(primaryScale = 1.0 / factor, secondaryScale = 1.0 / factor)
    }

    private fun warp(source: Bitmap, transform: Similarity, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val inv = transform.inverse()
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    inv.a.toFloat(), (-inv.b).toFloat(), inv.tx.toFloat(),
                    inv.b.toFloat(), inv.a.toFloat(), inv.ty.toFloat(),
                    0f, 0f, 1f
                )
            )
        }
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun accumulate(acc: Bitmap, frame: Bitmap, weights: FloatArray, weightSum: FloatArray, mw: Int, mh: Int) {
        val w = acc.width
        val h = acc.height
        val rows = STRIP_ROWS
        val a = IntArray(w * rows)
        val f = IntArray(w * rows)
        var y0 = 0
        while (y0 < h) {
            val n = min(rows, h - y0)
            acc.getPixels(a, 0, w, 0, y0, w, n)
            frame.getPixels(f, 0, w, 0, y0, w, n)
            for (dy in 0 until n) {
                val my = min(mh - 1, (y0 + dy) / MOTION_FACTOR)
                for (x in 0 until w) {
                    val i = dy * w + x
                    val fp = f[i]
                    if ((fp ushr 24) == 0) continue // outside the frame after the warp
                    val cell = my * mw + min(mw - 1, x / MOTION_FACTOR)
                    val wf = weights[cell]
                    if (wf <= 0f) continue
                    val wa = weightSum[cell]
                    val norm = 1f / (wa + wf)
                    val ap = a[i]
                    val r = (((ap shr 16) and 0xFF) * wa + ((fp shr 16) and 0xFF) * wf) * norm
                    val g = (((ap shr 8) and 0xFF) * wa + ((fp shr 8) and 0xFF) * wf) * norm
                    val b = ((ap and 0xFF) * wa + (fp and 0xFF) * wf) * norm
                    a[i] = (0xFF shl 24) or ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)
                }
            }
            acc.setPixels(a, 0, w, 0, y0, w, n)
            y0 += n
        }
    }

    companion object {
        private const val TAG = "BurstMerger"
        private const val ALIGN_WIDTH = 640
        private const val MOTION_FACTOR = 4
        private const val STRIP_ROWS = 64
        private const val MIN_ALIGN_CONFIDENCE = 0.3

        /**
         * Per motion cell weight of a frame against the reference: 1 where they agree within
         * the noise, falling to 0 beyond ~4 noise levels, then eroded so the rejection also
         * covers the borders of moving objects.
         */
        fun motionWeights(reference: GrayImage, frame: GrayImage): FloatArray {
            val n = reference.data.size
            val diff = FloatArray(n) { frame.data[it] - reference.data[it] }
            val sorted = diff.copyOf().also { it.sort() }
            val median = sorted[n / 2]
            val deviations = FloatArray(n) { abs(diff[it] - median) }.also { it.sort() }
            val sigma = 1.4826f * deviations[n / 2] + 0.5f
            val limit = 4f * sigma
            val raw = FloatArray(n) { i ->
                val q = (diff[i] - median) / limit
                val v = 1f - q * q
                if (v <= 0f) 0f else v * v
            }
            // Erode (3x3 minimum) so ghosts do not survive at the edges of moving objects.
            val w = reference.width
            val h = reference.height
            val out = FloatArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                var m = 1f
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val yy = (y + dy).coerceIn(0, h - 1)
                    m = min(m, raw[yy * w + xx])
                }
                out[y * w + x] = m
            }
            return out
        }

        /** Sharpness score (variance of the Laplacian of the luma) to pick the burst reference. */
        fun sharpness(bitmap: Bitmap): Double {
            val g = bitmap.toGrayImage(1)
            var sum = 0.0
            var sum2 = 0.0
            var n = 0
            for (y in 1 until g.height - 1) for (x in 1 until g.width - 1) {
                val l = 4 * g[x, y] - g[x - 1, y] - g[x + 1, y] - g[x, y - 1] - g[x, y + 1]
                sum += l
                sum2 += l * l
                n++
            }
            if (n == 0) return 0.0
            val mean = sum / n
            return sum2 / n - mean * mean
        }
    }
}
