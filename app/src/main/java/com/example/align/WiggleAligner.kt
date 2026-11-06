package com.example.align

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraManager
import android.util.Log
import com.example.camera.CameraLensDetails
import com.example.camera.CapturedFrame
import com.example.camera.LensMetadata
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the raw frames of one capture into wiggle frames that line up.
 *
 * For every secondary lens:
 *  1. a **prior** is taken from the stored calibration of this lens pair, or else from the
 *     factory calibration in the Camera2 metadata (focal lengths and optical centres),
 *  2. [LensAligner] measures the actual transform on the captured frames,
 *  3. the secondary frame is resampled into the primary frame's geometry.
 *
 * Then all frames are cropped to the area every lens covers (and to the primary zoom), and
 * the colours of the secondaries are matched to the primary.
 */
class WiggleAligner(context: Context) {

    data class Settings(
        val autoAlign: Boolean = true,
        val colorMatch: Boolean = true,
        /** Feed confident measurements back into the stored calibration. */
        val learnCalibration: Boolean = true
    )

    enum class Source(val label: String) {
        IMAGE("Bildanalyse"),
        CALIBRATION("gespeicherte Kalibrierung"),
        METADATA("Kamera-Metadaten"),
        MANUAL("manueller Zoom")
    }

    data class Report(
        val primaryId: String,
        val lensId: String,
        val source: Source,
        /** Final transform, full resolution primary px -> secondary px (pivot applied). */
        val transform: Similarity,
        /** Global (depth averaged) fit, if the image analysis succeeded. */
        val global: Similarity?,
        val prior: Similarity?,
        val priorSource: Source?,
        val confidence: Double?,
        val pivotShiftPx: Double,
        /** How much the secondary has to be magnified to match the primary (e.g. 2.0 for 0.5x). */
        val equivalentZoom: Double,
        val colorCorrection: Int,
        val elapsedMs: Long,
        val metadataRollDegrees: Double?,
        val baselineMm: Double?,
        val primaryWidth: Int,
        val primaryHeight: Int,
        val secondaryWidth: Int,
        val secondaryHeight: Int
    ) {
        fun summary(): String = String.format(
            Locale.GERMANY,
            "Zoom %.3f× · Rot %.2f° · Q %s · %s",
            equivalentZoom,
            transform.rotationDegrees,
            confidence?.let { String.format(Locale.GERMANY, "%.0f%%", it * 100) } ?: "–",
            source.label
        )
    }

    class Output(val frames: List<Bitmap>, val reports: List<Report>, val crop: Rect)

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val store = CalibrationStore(context)

    /**
     * Aligns [frames] (primary first). Input bitmaps are consumed (recycled or reused).
     * [zoomFor] is the zoom the user selected per lens; only the primary's is used when
     * auto alignment is on.
     */
    fun process(
        frames: List<CapturedFrame>,
        zoomFor: (CameraLensDetails) -> Float,
        settings: Settings
    ): Output {
        require(frames.isNotEmpty())
        val primary = frames.first()
        val pw = primary.bitmap.width
        val ph = primary.bitmap.height
        val remainingPrimaryZoom = (zoomFor(primary.lens) / primary.appliedZoom).coerceAtLeast(1f)

        val primaryFactor = max(1, (pw / WORKING_WIDTH.toDouble()).roundToInt())
        val primaryGray by lazy { toGray(primary.bitmap, primaryFactor) }

        val reports = frames.drop(1).map { secondary ->
            if (settings.autoAlign) {
                measure(primary, secondary, { primaryGray }, primaryFactor, settings)
            } else {
                manual(primary, secondary, zoomFor)
            }
        }

        val crop = commonCrop(pw, ph, reports, remainingPrimaryZoom)
        val outFrames = mutableListOf<Bitmap>()
        val primaryOut = if (crop.left == 0 && crop.top == 0 && crop.width() == pw && crop.height() == ph) {
            primary.bitmap
        } else {
            Bitmap.createBitmap(primary.bitmap, crop.left, crop.top, crop.width(), crop.height()).also {
                if (it !== primary.bitmap) primary.bitmap.recycle()
            }
        }
        outFrames += primaryOut

        val finalReports = frames.drop(1).mapIndexed { index, secondary ->
            val report = reports[index]
            val warped = warp(secondary.bitmap, report.transform, crop)
            secondary.bitmap.recycle()
            var correction = 0
            if (settings.colorMatch) {
                correction = matchColors(primaryOut, warped)
            }
            outFrames += warped
            report.copy(colorCorrection = correction)
        }
        finalReports.forEach { Log.d(TAG, "Lens ${it.lensId}: ${it.summary()} transform=${it.transform}") }
        return Output(outFrames, finalReports, crop)
    }

    /**
     * Measures the lens pair on a fresh capture and stores it as the new calibration,
     * replacing any previous one. Bitmaps are not modified.
     */
    fun calibrate(frames: List<CapturedFrame>): List<Report> {
        require(frames.size >= 2)
        val primary = frames.first()
        val factor = max(1, (primary.bitmap.width / WORKING_WIDTH.toDouble()).roundToInt())
        val gray = toGray(primary.bitmap, factor)
        return frames.drop(1).map { secondary ->
            val report = measure(primary, secondary, { gray }, factor, Settings(learnCalibration = false), ignoreStored = true)
            val global = report.global
            if (report.source == Source.IMAGE && global != null) {
                store.update(
                    primary.lens.id, secondary.lens.id, global,
                    primary.bitmap.width, secondary.bitmap.width, replace = true
                )
            }
            report
        }
    }

    class PairMeasurement(val global: Similarity, val pivoted: Similarity, val confidence: Double)

    /**
     * Measures the transform between two arbitrary upright bitmaps (full resolution pixels),
     * used by the diagnostics. [prior] is in full resolution pixels too.
     */
    fun measurePair(primary: Bitmap, secondary: Bitmap, prior: Similarity?, fallbackScale: Double = 0.5): PairMeasurement? {
        val kp = max(1, (primary.width / WORKING_WIDTH.toDouble()).roundToInt())
        val expected = prior?.scale ?: fallbackScale
        val ks = max(1, (kp * expected).roundToInt())
        val options = if (prior != null) {
            LensAligner.Options()
        } else {
            val w = expected * kp / ks
            LensAligner.Options(minScale = w / 2.2, maxScale = w * 2.2)
        }
        val result = runCatching {
            LensAligner.align(
                primary.toGrayImage(kp),
                secondary.toGrayImage(ks),
                prior?.reexpress(primaryScale = kp.toDouble(), secondaryScale = ks.toDouble()),
                options
            )
        }.onFailure { Log.e(TAG, "measurePair failed", it) }.getOrNull() ?: return null
        val toFull = { t: Similarity -> t.reexpress(primaryScale = 1.0 / kp, secondaryScale = 1.0 / ks) }
        return PairMeasurement(toFull(result.global), toFull(result.pivoted), result.confidence)
    }

    /** Metadata (factory calibration) prior between two frames, full resolution pixels. */
    fun metadataPriorFor(primary: CapturedFrame, secondary: CapturedFrame): Similarity? = metadataPrior(
        primary, secondary,
        LensMetadata.geometry(cameraManager, primary.lens.id),
        LensMetadata.geometry(cameraManager, secondary.lens.id)
    )

    fun clearCalibration() = store.clear()

    fun storedCalibration(primaryId: String, secondaryId: String): CalibrationStore.Entry? =
        store.get(primaryId, secondaryId)

    // ------------------------------------------------------------------------------------

    private fun measure(
        primary: CapturedFrame,
        secondary: CapturedFrame,
        primaryGray: () -> GrayImage,
        primaryFactor: Int,
        settings: Settings,
        ignoreStored: Boolean = false
    ): Report {
        val start = System.nanoTime()
        val pw = primary.bitmap.width
        val ph = primary.bitmap.height
        val sw = secondary.bitmap.width
        val sh = secondary.bitmap.height

        val geometryP = LensMetadata.geometry(cameraManager, primary.lens.id)
        val geometryS = LensMetadata.geometry(cameraManager, secondary.lens.id)
        val metadataPrior = metadataPrior(primary, secondary, geometryP, geometryS)
        val stored = if (ignoreStored) {
            null
        } else {
            // The calibration describes full field of view frames; account for a zoom the
            // camera applied while capturing (centred digital crop, same output size).
            store.get(primary.lens.id, secondary.lens.id)?.toPixels(pw, sw)?.let { full ->
                val unzoomPrimary = Similarity.of(1.0 / primary.appliedZoom, 0.0, 0.0, 0.0)
                    .anchoredAt(pw / 2.0, ph / 2.0, pw / 2.0, ph / 2.0)
                val zoomSecondary = Similarity.of(secondary.appliedZoom.toDouble(), 0.0, 0.0, 0.0)
                    .anchoredAt(sw / 2.0, sh / 2.0, sw / 2.0, sh / 2.0)
                zoomSecondary.compose(full.compose(unzoomPrimary))
            }
        }
        val prior = stored ?: metadataPrior
        val priorSource = when {
            stored != null -> Source.CALIBRATION
            metadataPrior != null -> Source.METADATA
            else -> null
        }

        val expectedScale = prior?.scale ?: focalRatioGuess(geometryP, geometryS, pw, sw) ?: 0.5
        val secondaryFactor = max(1, (primaryFactor * expectedScale).roundToInt())

        var result: LensAligner.Result? = null
        try {
            val p = primaryGray()
            val s = toGray(secondary.bitmap, secondaryFactor)
            val options = if (prior != null) {
                // A stored calibration is precise; the metadata prior may use another
                // principal point convention, so search a wider window around it.
                if (priorSource == Source.CALIBRATION) {
                    LensAligner.Options(priorScaleTolerance = 0.04, priorTranslationFraction = 0.06)
                } else {
                    LensAligner.Options(priorScaleTolerance = 0.07, priorTranslationFraction = 0.12)
                }
            } else {
                val w = expectedScale * primaryFactor / secondaryFactor
                LensAligner.Options(minScale = w / 2.2, maxScale = w * 2.2)
            }
            val workingPrior = prior?.reexpress(primaryScale = primaryFactor.toDouble(), secondaryScale = secondaryFactor.toDouble())
            result = LensAligner.align(p, s, workingPrior, options)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while aligning ${secondary.lens.id}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Alignment of ${secondary.lens.id} failed", e)
        }

        val toFull = { t: Similarity ->
            t.reexpress(primaryScale = 1.0 / primaryFactor, secondaryScale = 1.0 / secondaryFactor)
        }
        val measuredGlobal = result?.let { toFull(it.global) }
        val measured = result?.let { toFull(it.pivoted) }
        val accepted = result != null && measured != null && measuredGlobal != null &&
            result.confidence >= MIN_CONFIDENCE &&
            abs(measured.rotationDegrees) < MAX_ROTATION_DEGREES &&
            (prior == null || abs(ln(measured.scale / prior.scale)) < MAX_SCALE_DEVIATION)
        if (result != null && !accepted) {
            Log.w(TAG, "Rejected alignment for ${secondary.lens.id}: $result (prior $prior)")
        }

        val (source, transform) = when {
            accepted -> Source.IMAGE to measured
            prior != null -> priorSource!! to prior
            else -> Source.MANUAL to Similarity.centered(expectedScale, 0.0, pw.toDouble(), ph.toDouble(), sw.toDouble(), sh.toDouble())
        }

        val unzoomed = primary.appliedZoom == 1f && secondary.appliedZoom == 1f
        if (accepted && unzoomed && settings.learnCalibration && result.confidence >= LEARN_CONFIDENCE) {
            store.update(primary.lens.id, secondary.lens.id, measuredGlobal, pw, sw)
        }

        return Report(
            primaryId = primary.lens.id,
            lensId = secondary.lens.id,
            source = source,
            transform = transform,
            global = if (accepted) measuredGlobal else null,
            prior = prior,
            priorSource = priorSource,
            confidence = result?.confidence,
            pivotShiftPx = if (accepted) result.pivotShift * primaryFactor else 0.0,
            equivalentZoom = sw / (pw * transform.scale),
            colorCorrection = 0,
            elapsedMs = (System.nanoTime() - start) / 1_000_000,
            metadataRollDegrees = if (geometryP != null && geometryS != null) LensGeometry.relativeRollDegrees(geometryP, geometryS) else null,
            baselineMm = if (geometryP != null && geometryS != null) LensGeometry.baselineMm(geometryP, geometryS) else null,
            primaryWidth = pw,
            primaryHeight = ph,
            secondaryWidth = sw,
            secondaryHeight = sh
        )
    }

    /** Legacy behaviour: centre crop the secondary by its manually chosen zoom. */
    private fun manual(
        primary: CapturedFrame,
        secondary: CapturedFrame,
        zoomFor: (CameraLensDetails) -> Float
    ): Report {
        val pw = primary.bitmap.width
        val ph = primary.bitmap.height
        val sw = secondary.bitmap.width
        val sh = secondary.bitmap.height
        val primaryZoom = zoomFor(primary.lens) / primary.appliedZoom
        val secondaryZoom = (zoomFor(secondary.lens) / secondary.appliedZoom).coerceAtLeast(0.1f)
        // The secondary crop sw/zs must show what the primary crop pw/zp shows.
        val scale = sw * primaryZoom / (pw * secondaryZoom.toDouble())
        val transform = Similarity.centered(scale, 0.0, pw.toDouble(), ph.toDouble(), sw.toDouble(), sh.toDouble())
        return Report(
            primaryId = primary.lens.id,
            lensId = secondary.lens.id,
            source = Source.MANUAL,
            transform = transform,
            global = null,
            prior = null,
            priorSource = null,
            confidence = null,
            pivotShiftPx = 0.0,
            equivalentZoom = sw / (pw * scale),
            colorCorrection = 0,
            elapsedMs = 0,
            metadataRollDegrees = null,
            baselineMm = null,
            primaryWidth = pw,
            primaryHeight = ph,
            secondaryWidth = sw,
            secondaryHeight = sh
        )
    }

    private fun metadataPrior(
        primary: CapturedFrame,
        secondary: CapturedFrame,
        geometryP: LensGeometry?,
        geometryS: LensGeometry?
    ): Similarity? {
        if (geometryP == null || geometryS == null) return null
        val pp = geometryP.projection(
            primary.bitmap.width, primary.bitmap.height,
            cropRegion = primary.metadata?.cropRegion,
            dynamicIntrinsics = primary.metadata?.intrinsics,
            digitalZoom = primary.appliedZoom.toDouble(),
            rotationDegrees = primary.rotationDegrees
        ) ?: return null
        val ps = geometryS.projection(
            secondary.bitmap.width, secondary.bitmap.height,
            cropRegion = secondary.metadata?.cropRegion,
            dynamicIntrinsics = secondary.metadata?.intrinsics,
            digitalZoom = secondary.appliedZoom.toDouble(),
            rotationDegrees = secondary.rotationDegrees
        ) ?: return null
        val prior = ps.priorFrom(pp)
        // Physically meaningful lens pairs differ by less than ~10x.
        return prior.takeIf { it.scale in 0.08..12.0 }
    }

    private fun focalRatioGuess(p: LensGeometry?, s: LensGeometry?, pw: Int, sw: Int): Double? {
        val fp = p?.focalLengthMm ?: return null
        val fs = s?.focalLengthMm ?: return null
        val wp = p.sensorWidthMm ?: return null
        val ws = s.sensorWidthMm ?: return null
        // Field of view ratio from focal length and sensor width, in output pixels.
        val ratio = (fs / ws) / (fp / wp) * sw / pw.toDouble()
        return ratio.takeIf { it.isFinite() && it in 0.05..20.0 }
    }

    /**
     * Largest centred crop of the primary frame (same aspect ratio) that every secondary
     * covers completely, further limited by the primary zoom.
     */
    private fun commonCrop(pw: Int, ph: Int, reports: List<Report>, primaryZoom: Float): Rect {
        val maxFraction = 1.0 / primaryZoom
        fun fits(fraction: Double): Boolean {
            val hw = pw * fraction / 2
            val hh = ph * fraction / 2
            val cx = pw / 2.0
            val cy = ph / 2.0
            return reports.all { r ->
                listOf(cx - hw to cy - hh, cx + hw to cy - hh, cx - hw to cy + hh, cx + hw to cy + hh).all { (x, y) ->
                    val sx = r.transform.mapX(x, y)
                    val sy = r.transform.mapY(x, y)
                    sx >= 0.0 && sy >= 0.0 && sx <= r.secondaryWidth && sy <= r.secondaryHeight
                }
            }
        }
        var fraction = maxFraction
        if (!fits(fraction)) {
            var lo = 0.2
            var hi = maxFraction
            repeat(24) {
                val mid = (lo + hi) / 2
                if (fits(mid)) lo = mid else hi = mid
            }
            fraction = if (fits(lo)) lo else maxFraction
        }
        val w = min(pw, (pw * fraction).roundToInt()) and 1.inv()
        val h = min(ph, (ph * fraction).roundToInt()) and 1.inv()
        val left = (pw - w) / 2
        val top = (ph - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** Resamples [source] into the primary geometry, restricted to [crop]. */
    private fun warp(source: Bitmap, transform: Similarity, crop: Rect): Bitmap {
        val out = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        val inv = transform.inverse() // secondary px -> primary px
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    inv.a.toFloat(), (-inv.b).toFloat(), inv.tx.toFloat(),
                    inv.b.toFloat(), inv.a.toFloat(), inv.ty.toFloat(),
                    0f, 0f, 1f
                )
            )
            postTranslate(-crop.left.toFloat(), -crop.top.toFloat())
        }
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return out
    }

    /** Matches [target]'s colours to [reference] in place; returns the largest LUT change. */
    private fun matchColors(reference: Bitmap, target: Bitmap): Int {
        val sampleW = min(COLOR_SAMPLE_WIDTH, reference.width)
        val sampleH = max(1, (reference.height * sampleW.toDouble() / reference.width).roundToInt())
        val refSmall = Bitmap.createScaledBitmap(reference, sampleW, sampleH, true)
        val tgtSmall = Bitmap.createScaledBitmap(target, sampleW, sampleH, true)
        val refPixels = IntArray(sampleW * sampleH)
        val tgtPixels = IntArray(sampleW * sampleH)
        refSmall.getPixels(refPixels, 0, sampleW, 0, 0, sampleW, sampleH)
        tgtSmall.getPixels(tgtPixels, 0, sampleW, 0, 0, sampleW, sampleH)
        if (refSmall !== reference) refSmall.recycle()
        if (tgtSmall !== target) tgtSmall.recycle()

        val luts = ColorMatcher.compute(refPixels, tgtPixels)
        val w = target.width
        val rows = 64
        val strip = IntArray(w * rows)
        var y = 0
        while (y < target.height) {
            val n = min(rows, target.height - y)
            target.getPixels(strip, 0, w, 0, y, w, n)
            luts.applyInPlace(strip, 0, w * n)
            target.setPixels(strip, 0, w, 0, y, w, n)
            y += n
        }
        return luts.maxCorrection()
    }

    private fun toGray(bitmap: Bitmap, factor: Int): GrayImage = bitmap.toGrayImage(factor)

    private companion object {
        const val TAG = "WiggleAligner"
        const val WORKING_WIDTH = 1024
        const val COLOR_SAMPLE_WIDTH = 384
        const val MIN_CONFIDENCE = 0.2
        const val LEARN_CONFIDENCE = 0.4
        const val MAX_ROTATION_DEGREES = 3.0
        /** Maximum |ln(scale / prior)|, i.e. about 12 %. */
        const val MAX_SCALE_DEVIATION = 0.12
    }
}
