package com.example.live

import com.example.align.WiggleAligner
import java.util.Locale

/**
 * Turns the live measurements into at most two short tips, shown like subtitles over the
 * viewfinder so the photographer never has to look away from the subject. Pure rules, no
 * Android dependencies.
 */
object LiveGuide {

    enum class Level { GOOD, OK, WARN, BAD, INFO }

    data class Tip(val emoji: String, val text: String, val level: Level)

    /** Why no fresh 3D measurement is available. */
    enum class Pause { MOVING, POWER_SAVE, UNSUPPORTED, BUSY }

    data class Inputs(
        /** Smoothed parallax spread in % of the width, null if not (yet) measured. */
        val depthPercent: Double? = null,
        /** The last measurement found too little structure to judge. */
        val lowStructure: Boolean = false,
        val pause: Pause? = null,
        /** Distance the main lens focuses at (usually the centre of the frame), metres. */
        val focusDistanceM: Double? = null,
        val exposureMs: Double? = null,
        val iso: Int? = null,
        val nightMode: Boolean = false,
        val landscape: Boolean = false
    )

    /** % of the width per m⁻¹ of inverse distance difference (16 mm baseline, f = 2950 px @ 3072 px). */
    const val SPREAD_PER_DIOPTER = 1.54

    const val TOO_CLOSE_FROM = 4.0
    const val DARK_EXPOSURE_MS = 50.0
    const val DARK_ISO = 1600
    const val MAX_TIPS = 2

    fun tips(inputs: Inputs): List<Tip> {
        val tips = mutableListOf<Tip>()
        if (inputs.landscape) {
            tips += Tip("📱", "Hochformat halten – quer wackelt es senkrecht", Level.WARN)
        }
        when (inputs.pause) {
            Pause.MOVING -> tips += Tip("✋", "Ruhig halten – dann wird die 3D-Wirkung gemessen", Level.WARN)
            Pause.POWER_SAVE -> if (inputs.depthPercent == null) {
                tips += Tip("🔋", "Energiesparmodus – 3D-Messung pausiert", Level.INFO)
            }
            else -> Unit
        }

        val depth = inputs.depthPercent
        when {
            inputs.pause == Pause.MOVING -> Unit
            depth == null && inputs.lowStructure ->
                tips += Tip("🔍", "Zu wenig Struktur – etwas mit Details in die Bildmitte", Level.INFO)
            depth == null -> Unit
            depth >= TOO_CLOSE_FROM ->
                tips += Tip("⚠️", "Sehr nah (${percent(depth)}) – etwas zurück, sonst reißen Kanten auf", Level.WARN)
            depth >= WiggleAligner.DepthRating.STRONG_FROM ->
                tips += Tip("✅", "3D stark · ${percent(depth)} – auslösen!", Level.GOOD)
            else -> {
                val weak = depth < WiggleAligner.DepthRating.WEAK_BELOW
                tips += if (weak) {
                    Tip("❌", "3D schwach · ${percent(depth)}", Level.BAD)
                } else {
                    Tip("👍", "3D mittel · ${percent(depth)}", Level.OK)
                }
                tips += improvement(depth, inputs.focusDistanceM)
            }
        }

        val dark = (inputs.exposureMs ?: 0.0) >= DARK_EXPOSURE_MS || (inputs.iso ?: 0) >= DARK_ISO
        if (dark && !inputs.nightMode) {
            tips += Tip("🌙", "Wenig Licht – Nachtmodus einschalten", Level.INFO)
        }
        return tips.take(MAX_TIPS)
    }

    /** What to change to get from [depth] to a strong effect. */
    internal fun improvement(depth: Double, focusM: Double?): Tip {
        if (focusM == null) {
            return Tip("👣", "Näher ran (0,5–1 m), weg vom Hintergrund", Level.INFO)
        }
        if (focusM > 1.0) {
            return Tip("👣", "Näher ran: Motiv ~${meters(focusM)}, ideal 0,5–1 m", Level.INFO)
        }
        // Subject close enough: then the background is too close behind it. Its distance
        // follows from 1/near − 1/far = spread / SPREAD_PER_DIOPTER.
        val inverseFar = 1.0 / focusM - depth / SPREAD_PER_DIOPTER
        val gap = if (inverseFar > 0) 1.0 / inverseFar - focusM else null
        return if (gap != null && gap < 3 * focusM) {
            Tip("🏞️", "Hintergrund zu nah (~${meters(gap)} dahinter)", Level.INFO)
        } else {
            Tip("🏞️", "Mehr Tiefe: etwas Nahes vor weitem Hintergrund", Level.INFO)
        }
    }

    private fun percent(value: Double) = String.format(Locale.GERMANY, "%.1f %%", value)

    private fun meters(value: Double) = if (value < 1.0) {
        "${(value * 100).toInt().coerceAtLeast(5) / 5 * 5} cm"
    } else {
        String.format(Locale.GERMANY, "%.1f m", value)
    }
}
