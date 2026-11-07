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
 *  1. [AlignmentStrategy] measures the transform on the captured frames, seeded by the stored
 *     calibration of this lens pair and a rough scale hint from the Camera2 metadata, but
 *     always verified by a wide search, so a wrong starting value cannot win,
 *  2. the secondary frame is resampled into the primary frame's geometry.
 *
 * Then all frames are cropped to the area every lens covers (and to the primary zoom), and
 * the colours of the secondaries are matched to the primary.
 */
class WiggleAligner(context: Context) {

    data class Settings(
        val autoAlign: Boolean = true,
        val colorMatch: Boolean = true,
        /** Feed confident measurements back into the stored calibration. */
        val learnCalibration: Boolean = true,
        /** True if all frames were taken at the same instant (dual physical / CameraX pair). */
        val simultaneous: Boolean = true,
        val pivot: Pivot = Pivot.CENTER
    )

    /** Which depth stays still in the wiggle. */
    enum class Pivot(val label: String) {
        /** The subject in the image centre stays still; front and back move (classic wiggle). */
        CENTER("Bildmitte"),

        /** The dominant (usually far) background stays still, like the Pixel camera's lens switch. */
        BACKGROUND("Hintergrund")
    }

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
        val secondaryHeight: Int,
        /** True if the image analysis failed and a fallback transform was used. */
        val uncertain: Boolean = false,
        /** Confidence of each search the strategy ran. */
        val attempts: Map<String, Double> = emptyMap(),
        /** Full mapping incl. the perpendicular correction; null = [transform] only. */
        val field: WarpField? = null,
        val rectifiedPatches: Int = 0,
        /** Perpendicular misalignment before / after the correction (secondary px, rms). */
        val rectifyRmsBefore: Double? = null,
        val rectifyRmsAfter: Double? = null
    ) {
        fun mapX(x: Double, y: Double): Double = field?.mapX(x, y) ?: transform.mapX(x, y)
        fun mapY(x: Double, y: Double): Double = field?.mapY(x, y) ?: transform.mapY(x, y)

        fun summary(): String = String.format(
            Locale.GERMANY,
            "Zoom %.3f× · Rot %.2f° · Q %s · %s%s",
            equivalentZoom,
            transform.rotationDegrees,
            confidence?.let { String.format(Locale.GERMANY, "%.0f%%", it * 100) } ?: "–",
            if (uncertain) "unsicher (${source.label})" else source.label,
            if (rectifyRmsBefore != null && rectifyRmsAfter != null) {
                String.format(Locale.GERMANY, " · vertikal %.1f→%.1f px", rectifyRmsBefore, rectifyRmsAfter)
            } else if (field != null) {
                " · vertikal kalibriert"
            } else {
                ""
            }
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
            val field = report.field
            val warped = if (field != null && !field.isIdentity) {
                warpMesh(secondary.bitmap, field, crop)
            } else {
                warp(secondary.bitmap, report.transform, crop)
            }
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
     * Measures the lens pair on a fresh simultaneous capture and stores it as the new
     * calibration, replacing any previous one. Bitmaps are not modified.
     */
    fun calibrate(frames: List<CapturedFrame>): List<Report> {
        require(frames.size >= 2)
        val primary = frames.first()
        val factor = max(1, (primary.bitmap.width / WORKING_WIDTH.toDouble()).roundToInt())
        val gray = toGray(primary.bitmap, factor)
        return frames.drop(1).map { secondary ->
            val report = measure(primary, secondary, { gray }, factor, Settings(learnCalibration = false), ignoreStored = true)
            val global = report.global
            if (report.source == Source.IMAGE && global != null &&
                (report.confidence ?: 0.0) >= AlignmentStrategy.LEARN_CONFIDENCE
            ) {
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
        val toWorking = kp.toDouble() / ks
        val outcome = runCatching {
            AlignmentStrategy.run(
                primary = primary.toGrayImage(kp),
                secondary = secondary.toGrayImage(ks),
                calibration = null,
                scaleHint = expected * toWorking,
                freeRange = (expected * toWorking / FREE_RANGE)..(expected * toWorking * FREE_RANGE)
            )
        }.onFailure { Log.e(TAG, "measurePair failed", it) }.getOrNull() ?: return null
        val result = outcome.result ?: return null
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
        // The factory calibration describes the uncorrected optics; after the HAL's
        // distortion correction only its scale is a usable (rough) hint.
        val metadataPrior = metadataPrior(primary, secondary, geometryP, geometryS)
        val scaleHint = metadataPrior?.scale ?: focalRatioGuess(geometryP, geometryS, pw, sw)
        val storedEntry = if (ignoreStored) null else store.get(primary.lens.id, secondary.lens.id)
        val stored = if (storedEntry == null) {
            null
        } else {
            // The calibration describes full field of view frames; account for a zoom the
            // camera applied while capturing (centred digital crop, same output size).
            storedEntry.toPixels(pw, sw).let { full ->
                val unzoomPrimary = Similarity.of(1.0 / primary.appliedZoom, 0.0, 0.0, 0.0)
                    .anchoredAt(pw / 2.0, ph / 2.0, pw / 2.0, ph / 2.0)
                val zoomSecondary = Similarity.of(secondary.appliedZoom.toDouble(), 0.0, 0.0, 0.0)
                    .anchoredAt(sw / 2.0, sh / 2.0, sw / 2.0, sh / 2.0)
                zoomSecondary.compose(full.compose(unzoomPrimary))
            }
        }

        val unzoomed = primary.appliedZoom == 1f && secondary.appliedZoom == 1f
        // Direction from the primary to the secondary lens in the upright frame; parallax only
        // happens along it, so everything across it is lens geometry and gets rectified.
        val baseline = if (geometryP != null && geometryS != null) LensGeometry.baselineInImage(geometryP, geometryS) else null
        val (baselineX, baselineY) = baseline ?: (1.0 to 0.0)

        val expectedScale = stored?.scale ?: scaleHint ?: 0.5
        val secondaryFactor = max(1, (primaryFactor * expectedScale).roundToInt())
        val toWorking = primaryFactor.toDouble() / secondaryFactor

        var outcome: AlignmentStrategy.Outcome? = null
        try {
            outcome = AlignmentStrategy.run(
                primary = primaryGray(),
                secondary = toGray(secondary.bitmap, secondaryFactor),
                calibration = stored?.reexpress(primaryScale = primaryFactor.toDouble(), secondaryScale = secondaryFactor.toDouble()),
                scaleHint = scaleHint?.let { it * toWorking },
                // Centred on the expected working scale (~1), so no resolution is thrown away.
                freeRange = (expectedScale * toWorking / FREE_RANGE)..(expectedScale * toWorking * FREE_RANGE),
                rectify = unzoomed,
                baselineX = baselineX,
                baselineY = baselineY
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while aligning ${secondary.lens.id}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Alignment of ${secondary.lens.id} failed", e)
        }

        val toFull = { t: Similarity ->
            t.reexpress(primaryScale = 1.0 / primaryFactor, secondaryScale = 1.0 / secondaryFactor)
        }
        val result = outcome?.result
        val accepted = outcome?.accepted == true && result != null
        val measuredGlobal = result?.let { toFull(it.global) }
        val measuredPivot = result?.let { toFull(it.pivoted) }
        if (!accepted) {
            Log.w(TAG, "No reliable alignment for ${secondary.lens.id}: attempts=${outcome?.attempts} best=$result")
        }

        val (source, similarity) = when {
            accepted && settings.pivot == Pivot.BACKGROUND -> Source.IMAGE to measuredGlobal!!
            accepted -> Source.IMAGE to measuredPivot!!
            stored != null -> Source.CALIBRATION to stored
            scaleHint != null -> Source.METADATA to Similarity.centered(scaleHint, 0.0, pw.toDouble(), ph.toDouble(), sw.toDouble(), sh.toDouble())
            else -> Source.MANUAL to Similarity.centered(expectedScale, 0.0, pw.toDouble(), ph.toDouble(), sw.toDouble(), sh.toDouble())
        }

        // Perpendicular correction: measured on this capture if possible, else the one learned
        // for this lens pair (it is lens geometry and does not depend on the scene).
        val measuredField = if (accepted) {
            result.rectification?.let { r ->
                val f = if (settings.pivot == Pivot.BACKGROUND) r.global else r.pivoted
                f.reexpress(primaryScale = 1.0 / primaryFactor, secondaryScale = 1.0 / secondaryFactor)
            }
        } else {
            null
        }
        val field = when {
            measuredField != null -> measuredField
            unzoomed && storedEntry != null && source != Source.MANUAL -> storedEntry.field(similarity, pw, ph, sw)
            else -> null
        }
        val transform = field?.base ?: similarity

        // Only frames taken at the same instant describe the lenses; sequential frames also
        // contain the hand movement in between and must never become the calibration.
        if (accepted && unzoomed && settings.simultaneous && settings.learnCalibration &&
            result.confidence >= AlignmentStrategy.LEARN_CONFIDENCE
        ) {
            val globalField = result.rectification?.global
                ?.reexpress(primaryScale = 1.0 / primaryFactor, secondaryScale = 1.0 / secondaryFactor)
            store.update(primary.lens.id, secondary.lens.id, measuredGlobal!!, pw, sw, field = globalField)
        }

        return Report(
            primaryId = primary.lens.id,
            lensId = secondary.lens.id,
            source = source,
            transform = transform,
            global = if (accepted) measuredGlobal else null,
            prior = stored ?: metadataPrior,
            priorSource = when {
                stored != null -> Source.CALIBRATION
                metadataPrior != null -> Source.METADATA
                else -> null
            },
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
            secondaryHeight = sh,
            uncertain = !accepted,
            attempts = outcome?.attempts?.mapKeys { it.key.name }.orEmpty(),
            field = field,
            rectifiedPatches = result?.rectification?.patches ?: 0,
            rectifyRmsBefore = result?.rectification?.rmsBefore?.let { it * secondaryFactor },
            rectifyRmsAfter = result?.rectification?.rmsAfter?.let { it * secondaryFactor }
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
                    val sx = r.mapX(x, y)
                    val sy = r.mapY(x, y)
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

    /**
     * Resamples [source] through a non-linear [field]. drawBitmapMesh maps a regular grid over
     * the source bitmap to arbitrary output positions, so the inverse of the field is evaluated
     * at the grid vertices; the field is smooth, so a 48 column mesh is exact to < 0.1 px.
     */
    private fun warpMesh(source: Bitmap, field: WarpField, crop: Rect): Bitmap {
        val out = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        val meshW = MESH_COLUMNS
        val meshH = max(8, (MESH_COLUMNS * source.height.toDouble() / source.width).roundToInt())
        val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)
        var k = 0
        for (j in 0..meshH) {
            val sy = source.height * j.toDouble() / meshH
            for (i in 0..meshW) {
                val sx = source.width * i.toDouble() / meshW
                val (px, py) = field.inverse(sx, sy)
                verts[k++] = (px - crop.left).toFloat()
                verts[k++] = (py - crop.top).toFloat()
            }
        }
        Canvas(out).drawBitmapMesh(source, meshW, meshH, verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
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
        const val MESH_COLUMNS = 48

        /** Factor around the expected scale covered by the last-resort free search. */
        const val FREE_RANGE = 4.0
    }
}
