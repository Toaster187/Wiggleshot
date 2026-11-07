package com.example.align

import kotlin.math.abs

/**
 * Decides how [LensAligner] is run for one lens pair, so that a wrong starting value can
 * never decide the result.
 *
 * Lessons from the first Pixel 9 tests: the Camera2 factory calibration describes the
 * *uncorrected* optics, but the HAL distortion-corrects the ultra wide and zooms into it
 * while doing so. A prior built from `LENS_INTRINSIC_CALIBRATION` was therefore ~16 % off in
 * scale and had a wrong optical centre (which turns into a vertical offset in portrait).
 * A narrow search around such a prior finds nothing, and the old code then fell back to the
 * prior itself — the "height difference" between the wiggle frames.
 *
 * Now:
 *  1. A stored calibration (measured earlier on real simultaneous frames) is tried first
 *     with a narrow, fast search. It is only accepted if the match is clearly good.
 *  2. Otherwise a **wide search** runs: scale only loosely bounded around a hint (metadata or
 *     calibration scale, ±40 %), translation free, no optical centre assumed.
 *  3. The candidate with the best confidence wins. The caller never silently uses an
 *     unverified prior; if nothing reaches [MIN_CONFIDENCE] the report says so.
 */
object AlignmentStrategy {

    /** Below this the match is considered failed (real good pairs score ~0.45-0.8). */
    const val MIN_CONFIDENCE = 0.25

    /** A calibration-seeded match at least this good skips the wide search. */
    const val FAST_PATH_CONFIDENCE = 0.4

    /** Measurements this good may update the stored calibration. */
    const val LEARN_CONFIDENCE = 0.4

    const val MAX_ROTATION_DEGREES = 3.0

    /** Relative scale range of the wide search around a hint. */
    private const val HINT_RANGE = 1.4

    enum class Path { CALIBRATION, WIDE_HINT, WIDE_FREE }

    class Outcome(
        val result: LensAligner.Result?,
        val path: Path?,
        /** Confidence of each attempt, for diagnostics. */
        val attempts: Map<Path, Double>
    ) {
        val accepted: Boolean
            get() = result != null && result.confidence >= MIN_CONFIDENCE &&
                abs(result.global.rotationDegrees) < MAX_ROTATION_DEGREES
    }

    /**
     * All transforms in working pixels. [calibration] is a full transform (narrow search),
     * [scaleHint] only a rough scale (wide search); either may be null.
     */
    fun run(
        primary: GrayImage,
        secondary: GrayImage,
        calibration: Similarity?,
        scaleHint: Double?,
        freeRange: ClosedFloatingPointRange<Double> = 0.2..5.0
    ): Outcome {
        val attempts = linkedMapOf<Path, Double>()
        var best: LensAligner.Result? = null
        var bestPath: Path? = null

        fun consider(path: Path, result: LensAligner.Result?) {
            if (result == null) return
            attempts[path] = result.confidence
            if (best == null || result.confidence > best!!.confidence) {
                best = result
                bestPath = path
            }
        }

        if (calibration != null) {
            val narrow = LensAligner.Options(priorScaleTolerance = 0.04, priorTranslationFraction = 0.06)
            consider(Path.CALIBRATION, LensAligner.align(primary, secondary, calibration, narrow))
            val current = best
            if (current != null && current.confidence >= FAST_PATH_CONFIDENCE &&
                abs(current.global.rotationDegrees) < MAX_ROTATION_DEGREES
            ) {
                return Outcome(current, bestPath, attempts)
            }
        }

        val hint = scaleHint ?: calibration?.scale
        if (hint != null && hint.isFinite() && hint > 0) {
            val wide = LensAligner.Options(minScale = hint / HINT_RANGE, maxScale = hint * HINT_RANGE)
            consider(Path.WIDE_HINT, LensAligner.align(primary, secondary, null, wide))
        }
        if (best == null || best!!.confidence < MIN_CONFIDENCE) {
            val free = LensAligner.Options(minScale = freeRange.start, maxScale = freeRange.endInclusive)
            consider(Path.WIDE_FREE, LensAligner.align(primary, secondary, null, free))
        }
        return Outcome(best, bestPath, attempts)
    }
}
