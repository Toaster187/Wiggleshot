package com.example.data

import android.content.Context
import android.util.Log

object SettingsManager {
    private const val PREFS_NAME = "wiggle_settings"
    private const val KEY_LENS_COUNT = "lens_count"
    private const val KEY_PRIMARY_LENS_ID = "primary_lens_id"
    private const val KEY_SECONDARY_LENS_IDS = "secondary_lens_ids"
    private const val KEY_ZOOM_LIMITS = "zoom_limits" // Format: lensId:min:max,lensId:min:max
    private const val KEY_IS_4K = "is_4k"
    private const val KEY_AUTO_ALIGN = "auto_align"
    private const val KEY_COLOR_MATCH = "color_match"
    private const val KEY_DIAGNOSTICS = "diagnostics_mode"
    private const val KEY_PIVOT = "pivot"
    private const val KEY_DENOISE = "denoise_secondary"

    private const val TAG = "SettingsManager"

    fun getIs4K(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_4K, true)
    }

    fun saveIs4K(context: Context, is4K: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_4K, is4K).apply()
    }

    /** Align secondary lenses automatically (scale, rotation, offset) instead of manual zoom. */
    fun getAutoAlign(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_ALIGN, true)

    fun saveAutoAlign(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_ALIGN, value).apply()

    fun getColorMatch(context: Context): Boolean = prefs(context).getBoolean(KEY_COLOR_MATCH, true)

    fun saveColorMatch(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_COLOR_MATCH, value).apply()

    /** Save raw frames, metadata and alignment reports of every capture to Downloads. */
    fun getDiagnosticsMode(context: Context): Boolean = prefs(context).getBoolean(KEY_DIAGNOSTICS, false)

    fun saveDiagnosticsMode(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DIAGNOSTICS, value).apply()

    fun getPivot(context: Context): String = prefs(context).getString(KEY_PIVOT, "CENTER") ?: "CENTER"

    fun savePivot(context: Context, value: String) = prefs(context).edit().putString(KEY_PIVOT, value).apply()

    fun getDenoise(context: Context): String = prefs(context).getString(KEY_DENOISE, "AUTO") ?: "AUTO"

    fun saveDenoise(context: Context, value: String) = prefs(context).edit().putString(KEY_DENOISE, value).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLensCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LENS_COUNT, 2)
    }

    fun saveLensCount(context: Context, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LENS_COUNT, count).apply()
    }

    fun getPrimaryLensId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PRIMARY_LENS_ID, "") ?: ""
    }

    fun savePrimaryLensId(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PRIMARY_LENS_ID, id).apply()
    }

    fun getSecondaryLensIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SECONDARY_LENS_IDS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",")
    }

    fun saveSecondaryLensIds(context: Context, ids: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SECONDARY_LENS_IDS, ids.joinToString(",")).apply()
    }

    fun getZoomLimits(context: Context): Map<String, Pair<Float, Float>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ZOOM_LIMITS, "") ?: ""
        val result = mutableMapOf<String, Pair<Float, Float>>()
        if (raw.isNotEmpty()) {
            try {
                raw.split(",").forEach { item ->
                    val parts = item.split(":")
                    if (parts.size == 3) {
                        val lensId = parts[0]
                        val min = parts[1].toFloatOrNull() ?: 1.0f
                        val max = parts[2].toFloatOrNull() ?: 3.0f
                        result[lensId] = Pair(min, max)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing zoom limits", e)
            }
        }
        return result
    }

    fun saveZoomLimits(context: Context, limits: Map<String, Pair<Float, Float>>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = limits.entries.joinToString(",") { "${it.key}:${it.value.first}:${it.value.second}" }
        prefs.edit().putString(KEY_ZOOM_LIMITS, raw).apply()
    }
}
