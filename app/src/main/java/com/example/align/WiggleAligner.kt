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
import kotlin.math.ceil
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
        val pivot: Pivot = Pivot.SUBJECT,
        val denoise: Denoise = Denoise.AUTO,
        /** Give the ultra wide the main lens' depth of field ([LensBlur]). */
        val depthOfField: Boolean = true,
        /** In-between views between the two lenses ([ViewSynthesis]), 0 = off. */
        val inBetweenFrames: Int = 2,
        /** Taken with the flash: cheap disposable camera look ([FlashLook]). */
        val flashLook: Boolean = false
    )

    /** Noise reduction of magnified secondary frames ([NoiseReducer]). */
    enum class Denoise(val label: String, val strength: Double) {
        OFF("Aus", 0.0),

        /** Only frames that are magnified (the 0.5x lens), default strength. */
        AUTO("Auto", 1.0),
        STRONG("Stark", 1.8)
    }

    /**
     * How strong the depth effect of a capture is. With the Pixel 9's 16 mm baseline the
     * spread is about 1.5 % x (1/near - 1/far) with distances in metres.
     */
    enum class DepthRating(val label: String, val tip: String?) {
        WEAK("schwach", "3D-Effekt schwach – Motiv näher ran (0,5–1 m) und Abstand zum Hintergrund vergrößern"),
        MEDIUM("mittel", null),
        STRONG("stark", null);

        companion object {
            fun of(percent: Double) = when {
                percent < WEAK_BELOW -> WEAK
                percent < STRONG_FROM -> MEDIUM
                else -> STRONG
            }

            const val WEAK_BELOW = 0.6
            const val STRONG_FROM = 1.6
        }
    }

    /** Which depth stays still in the wiggle. */
    enum class Pivot(val label: String) {
        /**
         * The subject the main lens focused on stays still and the scene turns around it:
         * the background moves one way, anything nearer the other (classic wiggle).
         */
        SUBJECT("Motiv"),

        /** The depth in the image centre stays still. */
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
        val rectifyRmsAfter: Double? = null,
        /** Noise reduction applied to this frame (null = none). */
        val noiseSigma: Double? = null,
        val speckPixels: Int = 0,
        val denoiseMs: Long = 0,
        /** Strength of the 3D effect: depth parallax spread in % of the frame width. */
        val depthPercent: Double? = null,
        /** Shift that put the pivot depth at rest (primary px along the baseline). */
        val depthPivotShiftPx: Double = 0.0,
        /** True if the depth the main lens focused on was found. */
        val focusFound: Boolean? = null,
        /** Largest blur added to match the main lens' depth of field (output px). */
        val dofMaxBlurPx: Double? = null,
        val inBetweenFrames: Int = 0,
        /** Time of the depth map, blur and in-between views. */
        val depthMs: Long = 0
    ) {
        fun mapX(x: Double, y: Double): Double = field?.mapX(x, y) ?: transform.mapX(x, y)
        fun mapY(x: Double, y: Double): Double = field?.mapY(x, y) ?: transform.mapY(x, y)

        val depthRating: DepthRating? get() = depthPercent?.let { DepthRating.of(it) }

        fun summary(): String = String.format(
            Locale.GERMANY,
            "%sZoom %.3f× · Rot %.2f° · Q %s · %s%s",
            depthPercent?.let { String.format(Locale.GERMANY, "3D %s (%.1f %%) · ", DepthRating.of(it).label, it) } ?: "",
            equivalentZoom,
            transform.rotationDegrees,
            confidence?.let { String.format(Locale.GERMANY, "%.0f%%", it * 100) } ?: "–",
            if (uncertain) "unsicher (${source.label})" else source.label,
            (if (inBetweenFrames > 0) " · $inBetweenFrames Zwischenbilder" else "") +
                (dofMaxBlurPx?.let { String.format(Locale.GERMANY, " · Tiefenschärfe ≤%.0f px", it) } ?: "") +
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
        val primaryOut = if (crop.left == 0 && crop.top == 0 && crop.width() == pw && crop.height() == ph) {
            primary.bitmap
        } else {
            Bitmap.createBitmap(primary.bitmap, crop.left, crop.top, crop.width(), crop.height()).also {
                if (it !== primary.bitmap) primary.bitmap.recycle()
            }
        }
        // Output at most MAX_OUTPUT_DIMENSION: the ultra wide only has ~1300 px across the
        // main frame's field of view, so larger frames only make the two lenses look more
        // different (one sharp, one soft).
        val outFactor = max(1, ceil(max(crop.width(), crop.height()) / MAX_OUTPUT_DIMENSION.toDouble()).toInt())

        // Depth map before the final warp: it decides which depth stays still (pivot).
        val depth = if (frames.size == 2 && settings.autoAlign && settings.simultaneous) {
            runCatching { depthPrepass(primaryOut, frames[1], reports[0], crop, outFactor, settings) }
                .onFailure { Log.e(TAG, "Depth analysis failed", it) }
                .getOrNull()
        } else {
            null
        }
        val adjustedReports = if (depth != null && depth.pivot != 0.0) {
            listOf(reports[0].shiftedAlongX(depth.pivot * depth.cropPerMap)) + reports.drop(1)
        } else {
            reports
        }

        val warpedFrames = mutableListOf<Bitmap>()
        val finalReports = frames.drop(1).mapIndexed { index, secondary ->
            val report = adjustedReports[index]
            // Magnified frames (the 0.5x lens) are denoised at their native resolution first,
            // only on the part that ends up in the output.
            val denoise = settings.denoise != Denoise.OFF &&
                (settings.denoise == Denoise.STRONG || report.equivalentZoom >= DENOISE_MIN_ZOOM)
            val region = if (denoise) denoisedRegion(secondary.bitmap, report, crop, settings.denoise.strength) else null
            val source = region?.bitmap ?: secondary.bitmap
            val ox = region?.left?.toDouble() ?: 0.0
            val oy = region?.top?.toDouble() ?: 0.0
            val field = report.field
            val warped = if (field != null && !field.isIdentity) {
                warpMesh(source, field.withBase(field.base.copy(tx = field.base.tx - ox, ty = field.base.ty - oy)), crop)
            } else {
                warp(source, report.transform.copy(tx = report.transform.tx - ox, ty = report.transform.ty - oy), crop)
            }
            region?.bitmap?.recycle()
            secondary.bitmap.recycle()
            var correction = 0
            if (settings.colorMatch) {
                correction = matchColors(primaryOut, warped)
            }
            warpedFrames += warped
            report.copy(
                colorCorrection = correction,
                noiseSigma = region?.stats?.mottleSigma,
                speckPixels = region?.stats?.speckPixels ?: 0,
                denoiseMs = region?.stats?.elapsedMs ?: 0
            )
        }.toMutableList()

        var primaryFinal = downscale(primaryOut, outFactor)
        val secondaryFinal = warpedFrames.map { downscale(it, outFactor) }.toMutableList()
        val focusDiopters = primary.metadata?.focusDistanceDiopters?.toDouble()
        val inBetween = mutableListOf<Bitmap>()
        var flashLit = false
        if (depth != null && secondaryFinal.size == 1) {
            val finished = runCatching { finishDepth(primaryFinal, secondaryFinal[0], depth, settings, focusDiopters) }
                .onFailure { Log.e(TAG, "Depth effects failed", it) }
                .getOrNull()
            if (finished != null) {
                secondaryFinal[0].recycle()
                secondaryFinal[0] = finished.secondary
                finished.primary?.let { replacement ->
                    primaryFinal.recycle()
                    primaryFinal = replacement
                }
                flashLit = settings.flashLook
                inBetween += finished.inBetween
                finalReports[0] = finalReports[0].copy(
                    depthPivotShiftPx = depth.pivot * depth.cropPerMap,
                    focusFound = depth.focus != null,
                    dofMaxBlurPx = finished.maxBlur,
                    inBetweenFrames = finished.inBetween.size,
                    depthMs = depth.elapsedMs + finished.elapsedMs
                )
            } else {
                finalReports[0] = finalReports[0].copy(
                    depthPivotShiftPx = depth.pivot * depth.cropPerMap,
                    focusFound = depth.focus != null,
                    depthMs = depth.elapsedMs
                )
            }
        }
        val outFrames = mutableListOf(primaryFinal)
        outFrames += inBetween
        outFrames += secondaryFinal
        if (settings.flashLook) {
            runCatching { styleFlash(outFrames, withDepth = flashLit) }.onFailure { Log.e(TAG, "Flash look failed", it) }
        }
        finalReports.forEach { Log.d(TAG, "Lens ${it.lensId}: ${it.summary()} transform=${it.transform}") }
        return Output(outFrames, finalReports, crop)
    }

    /** Disparity maps of a pair, measured before the final warp, and the chosen pivot. */
    private class DepthPrepass(
        val primary: DisparityMap,
        val secondary: DisparityMap,
        /** Disparity (map px) that stays still in the wiggle, relative to the warp used. */
        val pivot: Double,
        /** Disparity of the focus plane (map px), null if not found. */
        val focus: Double?,
        /** Crop (input) px per map px, and output px per map px. */
        val cropPerMap: Double,
        val outPerMap: Double,
        val blurRatio: Double,
        val ownBlurRatio: Double,
        val elapsedMs: Long
    )

    private class DepthFinish(
        /** Replacement for the primary frame, null if unchanged. */
        val primary: Bitmap?,
        val secondary: Bitmap,
        val inBetween: List<Bitmap>,
        val maxBlur: Double?,
        val elapsedMs: Long
    )

    private fun depthPrepass(
        primaryOut: Bitmap,
        secondary: CapturedFrame,
        report: Report,
        crop: Rect,
        outFactor: Int,
        settings: Settings
    ): DepthPrepass? {
        if (report.uncertain || report.source != Source.IMAGE) return null
        val needed = settings.pivot != Pivot.BACKGROUND || settings.depthOfField || settings.inBetweenFrames > 0 || settings.flashLook
        if (!needed) return null
        val start = System.nanoTime()
        val geometryP = LensMetadata.geometry(cameraManager, report.primaryId)
        val geometryS = LensMetadata.geometry(cameraManager, report.lensId)
        val baseline = if (geometryP != null && geometryS != null) LensGeometry.baselineInImage(geometryP, geometryS) else null
        // Parallax must run along the image x axis (portrait frames of the Pixel 9).
        if (baseline != null && abs(baseline.second) > abs(baseline.first)) return null

        val mapFactor = outFactor * MAP_DOWNSCALE
        val pMap = primaryOut.toGrayImage(mapFactor)
        val mw = pMap.width
        val mh = pMap.height
        // The secondary, resampled into the primary's geometry at map resolution.
        val sFactor = max(1, (report.transform.scale * mapFactor).roundToInt())
        val sGray = secondary.bitmap.toGrayImage(sFactor)
        val sMap = GrayImage(mw, mh)
        for (my in 0 until mh) for (mx in 0 until mw) {
            val xp = crop.left + (mx + 0.5) * mapFactor
            val yp = crop.top + (my + 0.5) * mapFactor
            sMap.data[my * mw + mx] = sGray.sample(report.mapX(xp, yp) / sFactor, report.mapY(xp, yp) / sFactor)
        }
        val maps = StereoMatcher.matchPair(pMap, sMap, max(4, (mw * MAX_DISPARITY_FRACTION).roundToInt()))
        if (maps.primary.width != mw || maps.primary.reliableFraction() < 0.04) {
            Log.w(TAG, "Depth map not reliable (${maps.primary.reliableFraction()})")
            return null
        }
        val focus = FocusFinder.focusDisparity(pMap, sMap, maps.primary)
        val center = maps.primary.median(0.33, 0.67, 0.33, 0.67) ?: 0.0
        val pivot = when (settings.pivot) {
            Pivot.SUBJECT -> focus ?: center
            Pivot.CENTER -> center
            Pivot.BACKGROUND -> 0.0
        }
        val baselineMm = if (geometryP != null && geometryS != null) LensGeometry.baselineMm(geometryP, geometryS) else null
        val b = baselineMm?.takeIf { it > 1.0 } ?: DEFAULT_BASELINE_MM
        val ratio = (geometryP?.apertureDiameterMm ?: DEFAULT_MAIN_APERTURE_MM) / b
        val own = (geometryS?.apertureDiameterMm ?: DEFAULT_UW_APERTURE_MM) / b
        Log.d(TAG, "Depth: focus=$focus center=$center pivot=$pivot map ${mw}x$mh reliable=${"%.2f".format(maps.primary.reliableFraction())}")
        return DepthPrepass(
            primary = maps.primary,
            secondary = maps.secondary,
            pivot = pivot,
            focus = focus,
            cropPerMap = mapFactor.toDouble(),
            outPerMap = MAP_DOWNSCALE.toDouble(),
            blurRatio = ratio,
            ownBlurRatio = own,
            elapsedMs = (System.nanoTime() - start) / 1_000_000
        )
    }

    private fun finishDepth(
        primary: Bitmap,
        secondary: Bitmap,
        depth: DepthPrepass,
        settings: Settings,
        focusDiopters: Double?
    ): DepthFinish? {
        val start = System.nanoTime()
        val w = secondary.width
        val h = secondary.height
        if (primary.width != w || primary.height != h) return null
        // Maps relative to the pivot, in the frames as they are now (ultra wide shifted).
        val dP = depth.primary.minus(depth.pivot)
        val dS = depth.secondary.reReferenced(depth.pivot, depth.pivot)
        val scale = w.toDouble() / dP.width
        val focus = (depth.focus ?: depth.pivot) - depth.pivot
        val sPixels0 = IntArray(w * h).also { secondary.getPixels(it, 0, w, 0, 0, w, h) }
        var sPixels = sPixels0
        var pPixels: IntArray? = null
        fun primaryPixels(): IntArray = pPixels ?: IntArray(w * h).also { primary.getPixels(it, 0, w, 0, 0, w, h); pPixels = it }

        var maxBlur: Double? = null
        if (settings.depthOfField && depth.focus != null) {
            LensBlur.apply(sPixels, w, h, dS, scale, focus, depth.blurRatio, depth.ownBlurRatio)?.let { (pixels, stats) ->
                sPixels = pixels
                maxBlur = stats.maxDiameter
            }
        }
        // Flash light falls off with distance; done before the in-between views so every
        // frame is lit the same way.
        var primaryChanged = false
        if (settings.flashLook) {
            val diopters = focusDiopters?.takeIf { it > 0.05 } ?: DEFAULT_FLASH_FOCUS_DIOPTERS
            if (sPixels === sPixels0) sPixels = sPixels0.copyOf()
            FlashLook.falloff(primaryPixels(), w, h, dP, scale, focus, diopters)
            FlashLook.falloff(sPixels, w, h, dS, scale, focus, diopters)
            primaryChanged = true
        }
        val views = mutableListOf<Bitmap>()
        val count = settings.inBetweenFrames.coerceIn(0, 4)
        if (count > 0) {
            val occP = ViewSynthesis.occlusion(dP, dS, 1.0)
            val occS = ViewSynthesis.occlusion(dS, dP, -1.0)
            for (i in 1..count) {
                val t = i / (count + 1.0)
                val view = ViewSynthesis.render(t, primaryPixels(), sPixels, w, h, dP, dS, occP, occS, scale)
                views += Bitmap.createBitmap(view, w, h, Bitmap.Config.ARGB_8888)
            }
        }
        if (sPixels === sPixels0 && views.isEmpty() && !primaryChanged) return null
        val finalSecondary = Bitmap.createBitmap(sPixels, w, h, Bitmap.Config.ARGB_8888)
        val finalPrimary = if (primaryChanged) Bitmap.createBitmap(primaryPixels(), w, h, Bitmap.Config.ARGB_8888) else null
        return DepthFinish(finalPrimary, finalSecondary, views, maxBlur, (System.nanoTime() - start) / 1_000_000)
    }

    /** The disposable camera look on every frame (different grain per frame, like film). */
    private fun styleFlash(frames: MutableList<Bitmap>, withDepth: Boolean) {
        for (i in frames.indices) {
            val b = frames[i]
            val w = b.width
            val h = b.height
            val pixels = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
            if (!withDepth) FlashLook.falloffWithoutDepth(pixels, w, h)
            val styled = FlashLook.style(pixels, w, h, seed = 17L + i)
            b.recycle()
            frames[i] = Bitmap.createBitmap(styled, w, h, Bitmap.Config.ARGB_8888)
        }
    }

    /** Integer factor area downscale (keeps the two frames equally sharp, no aliasing). */
    private fun downscale(source: Bitmap, factor: Int): Bitmap {
        if (factor <= 1) return source
        val w = source.width / factor
        val h = source.height / factor
        val row = IntArray(source.width * factor)
        val out = IntArray(w * h)
        val n = factor * factor
        for (y in 0 until h) {
            source.getPixels(row, 0, source.width, 0, y * factor, source.width, factor)
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0 until factor) {
                    val base = dy * source.width + x * factor
                    for (dx in 0 until factor) {
                        val p = row[base + dx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        source.recycle()
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The report with its transform moved by [shift] primary px along +x (pivot change). */
    private fun Report.shiftedAlongX(shift: Double): Report {
        val t = transform
        val moved = t.copy(tx = t.tx + t.a * shift, ty = t.ty + t.b * shift)
        return copy(transform = moved, field = field?.withBase(moved))
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
                    primary.lens.id,
                    calibrationKey(secondary.lens.id, secondary.bitmap.width, secondary.bitmap.height),
                    global,
                    primary.bitmap.width,
                    secondary.bitmap.width,
                    replace = true,
                    field = report.field
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

    fun storedCalibration(primaryId: String, secondaryId: String, secondaryWidth: Int, secondaryHeight: Int): CalibrationStore.Entry? =
        store.get(primaryId, calibrationKey(secondaryId, secondaryWidth, secondaryHeight))

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
        val secondaryKey = calibrationKey(secondary.lens.id, sw, sh)
        val storedEntry = if (ignoreStored) null else store.get(primary.lens.id, secondaryKey)
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
            store.update(primary.lens.id, secondaryKey, measuredGlobal!!, pw, sw, field = globalField)
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
            rectifyRmsAfter = result?.rectification?.rmsAfter?.let { it * secondaryFactor },
            // working secondary px -> full secondary px -> primary px -> % of the width
            depthPercent = if (accepted) result.depthSpread?.let { it * secondaryFactor / transform.scale / pw * 100 } else null
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

    private class DenoisedRegion(val bitmap: Bitmap, val left: Int, val top: Int, val stats: NoiseReducer.Stats)

    /**
     * Copies the part of [source] that [report] maps into [crop], denoises it and returns it
     * with its offset; null if that part is empty.
     */
    private fun denoisedRegion(source: Bitmap, report: Report, crop: Rect, strength: Double): DenoisedRegion? = try {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val steps = 16
        for (i in 0..steps) for (edge in 0 until 4) {
            val t = i.toDouble() / steps
            val (x, y) = when (edge) {
                0 -> crop.left + t * crop.width() to crop.top.toDouble()
                1 -> crop.left + t * crop.width() to crop.bottom.toDouble()
                2 -> crop.left.toDouble() to crop.top + t * crop.height()
                else -> crop.right.toDouble() to crop.top + t * crop.height()
            }
            val sx = report.mapX(x, y)
            val sy = report.mapY(x, y)
            minX = min(minX, sx); maxX = max(maxX, sx); minY = min(minY, sy); maxY = max(maxY, sy)
        }
        val left = (minX - DENOISE_MARGIN).toInt().coerceIn(0, source.width - 1)
        val top = (minY - DENOISE_MARGIN).toInt().coerceIn(0, source.height - 1)
        val right = (maxX + DENOISE_MARGIN).toInt().coerceIn(left + 1, source.width)
        val bottom = (maxY + DENOISE_MARGIN).toInt().coerceIn(top + 1, source.height)
        val w = right - left
        val h = bottom - top
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, left, top, w, h)
        val stats = NoiseReducer.denoiseInStrips(pixels, w, h, strength)
        Log.d(TAG, "Denoised ${w}x$h: sigma=%.2f specks=${stats.speckPixels} ${stats.elapsedMs}ms".format(stats.mottleSigma))
        DenoisedRegion(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888), left, top, stats)
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory while denoising", e)
        null
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

        /** Secondary frames magnified at least this much are denoised in AUTO mode. */
        const val DENOISE_MIN_ZOOM = 1.3

        /** Context around the used region: guided filter radius plus interpolation. */
        const val DENOISE_MARGIN = 24.0

        /**
         * Calibrations are kept per secondary stream size: on the Pixel 9 the 0.5x lens shows
         * a different field of view in HD (1920x1440) than in 4K (4032x3024).
         */
        fun calibrationKey(lensId: String, width: Int, height: Int) = "$lensId@${width}x$height"

        /** Factor around the expected scale covered by the last-resort free search. */
        const val FREE_RANGE = 4.0

        /** Longest edge of the output frames. */
        const val MAX_OUTPUT_DIMENSION = 2048

        /** Depth maps have a quarter of the output resolution. */
        const val MAP_DOWNSCALE = 4
        const val MAX_DISPARITY_FRACTION = 0.07

        /** Pixel 9 values if the metadata lacks them. */
        const val DEFAULT_BASELINE_MM = 16.0
        const val DEFAULT_MAIN_APERTURE_MM = 6.9 / 1.68
        const val DEFAULT_UW_APERTURE_MM = 2.02 / 1.7

        /** Assumed subject distance for the flash falloff if the focus distance is unknown (~0.8 m). */
        const val DEFAULT_FLASH_FOCUS_DIOPTERS = 1.25
    }
}
