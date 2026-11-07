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

    data class Entry(
        val normalized: Similarity,
        val samples: Int,
        val updatedAt: Long,
        /** [WarpField] coefficients divided by the secondary width, or null. */
        val fieldNormalized: DoubleArray? = null,
        val normalX: Double = 0.0,
        val normalY: Double = 1.0
    ) {
        /** Transform in pixels of a [primaryWidth] wide primary and [secondaryWidth] wide secondary frame. */
        fun toPixels(primaryWidth: Int, secondaryWidth: Int): Similarity =
            normalized.reexpress(primaryScale = 1.0 / primaryWidth, secondaryScale = 1.0 / secondaryWidth)

        /** Stored perpendicular correction for frames of these sizes, on top of [base]. */
        fun field(base: Similarity, primaryWidth: Int, primaryHeight: Int, secondaryWidth: Int): WarpField? =
            fieldNormalized?.let { c ->
                WarpField(base, normalX, normalY, DoubleArray(WarpField.TERMS) { c[it] * secondaryWidth }, primaryWidth.toDouble(), primaryHeight.toDouble())
            }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(primaryId: String, secondaryId: String): Entry? {
        val raw = prefs.getString(key(primaryId, secondaryId), null) ?: return null
        return runCatching {
            val parts = raw.split(';')
            val field = if (parts.size >= 8 + WarpField.TERMS) {
                DoubleArray(WarpField.TERMS) { parts[8 + it].toDouble() }
            } else {
                null
            }
            Entry(
                normalized = Similarity(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble()),
                samples = parts[4].toInt(),
                updatedAt = parts[5].toLong(),
                fieldNormalized = field,
                normalX = parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0,
                normalY = parts.getOrNull(7)?.toDoubleOrNull() ?: 1.0
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
        replace: Boolean = false,
        field: WarpField? = null
    ): Entry {
        // Coefficients are secondary pixels over normalised primary coordinates, so dividing by
        // the secondary width makes them resolution independent (like the similarity).
        val fieldNormalized = field?.takeIf { !it.isIdentity }?.let { f ->
            DoubleArray(WarpField.TERMS) { f.coefficients[it] / secondaryWidth }
        }
        val normalized = measured.reexpress(
            primaryScale = primaryWidth.toDouble(),
            secondaryScale = secondaryWidth.toDouble()
        )
        val old = if (replace) null else get(primaryId, secondaryId)
        val entry = if (old == null) {
            Entry(normalized, 1, System.currentTimeMillis(), fieldNormalized, field?.normalX ?: 0.0, field?.normalY ?: 1.0)
        } else {
            val alpha = max(MIN_ALPHA, 1.0 / (old.samples + 1))
            fun mix(o: Double, n: Double) = o + alpha * (n - o)
            val oldField = old.fieldNormalized
            val mixedField = when {
                fieldNormalized == null -> oldField
                oldField == null -> fieldNormalized
                else -> DoubleArray(WarpField.TERMS) { mix(oldField[it], fieldNormalized[it]) }
            }
            Entry(
                Similarity(
                    mix(old.normalized.a, normalized.a),
                    mix(old.normalized.b, normalized.b),
                    mix(old.normalized.tx, normalized.tx),
                    mix(old.normalized.ty, normalized.ty)
                ),
                old.samples + 1,
                System.currentTimeMillis(),
                mixedField,
                field?.normalX ?: old.normalX,
                field?.normalY ?: old.normalY
            )
        }
        val n = entry.normalized
        val fieldPart = entry.fieldNormalized?.joinToString(";", prefix = ";") { String.format(Locale.US, "%.10f", it) } ?: ""
        prefs.edit()
            .putString(
                key(primaryId, secondaryId),
                String.format(
                    Locale.US, "%.9f;%.9f;%.9f;%.9f;%d;%d;%.6f;%.6f",
                    n.a, n.b, n.tx, n.ty, entry.samples, entry.updatedAt, entry.normalX, entry.normalY
                ) + fieldPart
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
        // v3: keyed per secondary stream size (see WiggleAligner.calibrationKey).
        const val PREFS = "lens_calibration_v3"
        const val MIN_ALPHA = 0.2
    }
}
