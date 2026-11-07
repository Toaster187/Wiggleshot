package com.example.align

import android.content.Context
import android.util.Log
import java.util.Locale
import kotlin.math.max

/**
 * Persists the measured transform between two lenses.
 *
 * The transform is stored resolution independent: coordinates are divided by the frame
 * width, so a calibration measured in 4K also applies to HD captures of the same lenses.
 * Every confident capture refines it (running mean for the first captures, then an
 * exponential moving average), so the prior gets better the more the app is used and is a
 * reliable fallback for scenes without texture (sky, white wall).
 */
class CalibrationStore(context: Context) {

    data class Entry(val normalized: Similarity, val samples: Int, val updatedAt: Long) {
        /** Transform in pixels of a [primaryWidth] wide primary and [secondaryWidth] wide secondary frame. */
        fun toPixels(primaryWidth: Int, secondaryWidth: Int): Similarity =
            normalized.reexpress(primaryScale = 1.0 / primaryWidth, secondaryScale = 1.0 / secondaryWidth)
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(primaryId: String, secondaryId: String): Entry? {
        val raw = prefs.getString(key(primaryId, secondaryId), null) ?: return null
        return runCatching {
            val parts = raw.split(';')
            Entry(
                normalized = Similarity(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble()),
                samples = parts[4].toInt(),
                updatedAt = parts[5].toLong()
            )
        }.onFailure { Log.w(TAG, "Corrupt calibration '$raw'", it) }.getOrNull()
    }

    /** Adds one measurement (in pixels) and returns the updated entry. */
    fun update(
        primaryId: String,
        secondaryId: String,
        measured: Similarity,
        primaryWidth: Int,
        secondaryWidth: Int,
        replace: Boolean = false
    ): Entry {
        val normalized = measured.reexpress(
            primaryScale = primaryWidth.toDouble(),
            secondaryScale = secondaryWidth.toDouble()
        )
        val old = if (replace) null else get(primaryId, secondaryId)
        val entry = if (old == null) {
            Entry(normalized, 1, System.currentTimeMillis())
        } else {
            val alpha = max(MIN_ALPHA, 1.0 / (old.samples + 1))
            fun mix(o: Double, n: Double) = o + alpha * (n - o)
            Entry(
                Similarity(
                    mix(old.normalized.a, normalized.a),
                    mix(old.normalized.b, normalized.b),
                    mix(old.normalized.tx, normalized.tx),
                    mix(old.normalized.ty, normalized.ty)
                ),
                old.samples + 1,
                System.currentTimeMillis()
            )
        }
        val n = entry.normalized
        prefs.edit()
            .putString(
                key(primaryId, secondaryId),
                String.format(Locale.US, "%.9f;%.9f;%.9f;%.9f;%d;%d", n.a, n.b, n.tx, n.ty, entry.samples, entry.updatedAt)
            )
            .apply()
        return entry
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun all(): Map<String, Entry> = prefs.all.keys.mapNotNull { k ->
        val ids = k.removePrefix("pair_").split("__")
        if (ids.size != 2) null else get(ids[0], ids[1])?.let { k to it }
    }.toMap()

    private fun key(primaryId: String, secondaryId: String) = "pair_${primaryId}__$secondaryId"

    private companion object {
        const val TAG = "CalibrationStore"
        // v2: calibrations of the first version could come from sequential frames or a
        // wrong metadata fallback and are discarded.
        const val PREFS = "lens_calibration_v2"
        const val MIN_ALPHA = 0.2
    }
}
