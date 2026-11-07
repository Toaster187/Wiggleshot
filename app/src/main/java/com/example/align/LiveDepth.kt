package com.example.align

import kotlin.math.abs

/**
 * Measures the 3D strength on two preview frames, for the live tips while framing.
 *
 * Same measurement as after a capture (parallax spread of the rectification patches along
 * the baseline), just on preview resolution. The lens transform hardly changes from one
 * second to the next, so the previous result seeds a narrow, fast search; only the first
 * measurement (or one after the scene changed completely) runs the wide search.
 */
object LiveDepth {

    class Measurement(
        /** Parallax spread in percent of the primary width, null if not measurable. */
        val depthPercent: Double?,
        val confidence: Double,
        val patches: Int,
        /** Primary px -> secondary px of the frames that were measured; seeds the next run. */
        val transform: Similarity,
        val elapsedMs: Long
    ) {
        val accepted: Boolean get() = confidence >= AlignmentStrategy.MIN_CONFIDENCE
    }

    /** Below this many usable patches the scene has too little structure to judge. */
    const val MIN_PATCHES = 30

    /**
     * Full measurement: aligns the frames (seeded by [previous], the transform of the last
     * accepted measurement on frames of the same sizes, or a rough [scaleHint]) and measures
     * the parallax.
     */
    fun measure(
        primary: GrayImage,
        secondary: GrayImage,
        previous: Similarity?,
        scaleHint: Double?,
        baselineX: Double,
        baselineY: Double
    ): Measurement? {
        val start = System.nanoTime()
        val expected = previous?.scale ?: scaleHint ?: 1.0
        val outcome = AlignmentStrategy.run(
            primary = primary,
            secondary = secondary,
            calibration = previous,
            scaleHint = scaleHint ?: previous?.scale,
            freeRange = (expected / FREE_RANGE)..(expected * FREE_RANGE),
            rectify = true,
            baselineX = baselineX,
            baselineY = baselineY
        )
        val result = outcome.result ?: return null
        // depthSpread exists whenever enough patches matched, also when no perpendicular
        // correction was needed (then there is no rectification).
        val patches = result.rectification?.patches ?: if (result.depthSpread != null) MIN_PATCHES else 0
        val depth = if (outcome.accepted) {
            result.depthSpread?.let { it / result.global.scale / primary.width * 100 }
        } else {
            null
        }
        return Measurement(
            depthPercent = depth?.let { abs(it) },
            confidence = result.confidence,
            patches = patches,
            transform = result.global,
            elapsedMs = (System.nanoTime() - start) / 1_000_000
        )
    }

    /**
     * Fast measurement with a known [transform]: only the parallax patches, no alignment.
     * About a third of the time of [measure]; the caller re-runs [measure] now and then and
     * whenever this one stops matching well.
     */
    fun measureFast(
        primary: GrayImage,
        secondary: GrayImage,
        transform: Similarity,
        baselineX: Double,
        baselineY: Double
    ): Measurement? {
        val start = System.nanoTime()
        val result = LensAligner.measureDepth(primary, secondary, transform, baselineX, baselineY) ?: return null
        val depth = if (result.confidence >= AlignmentStrategy.MIN_CONFIDENCE && result.patches >= MIN_PATCHES) {
            result.depthSpread?.let { abs(it) / transform.scale / primary.width * 100 }
        } else {
            null
        }
        return Measurement(depth, result.confidence, result.patches, transform, (System.nanoTime() - start) / 1_000_000)
    }

    private const val FREE_RANGE = 2.5
}
