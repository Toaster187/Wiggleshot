package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraLensDetails
import com.example.camera.LensDiscovery
import com.example.data.SettingsManager
import com.example.data.WiggleCapture
import com.example.data.WiggleRepository
import com.example.util.VisualZoomMatcher
import com.example.util.WiggleProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Fallback zoom range for lenses the user has not configured explicitly. */
val DEFAULT_ZOOM_LIMITS: Pair<Float, Float> = 1.0f to 3.0f

data class WiggleUiState(
    val selectedCapture: WiggleCapture? = null,
    val isCapturing: Boolean = false,
    val availableLenses: List<CameraLensDetails> = emptyList(),
    val primaryLens: CameraLensDetails? = null,
    val secondaryLens: CameraLensDetails? = null,
    val secondaryLenses: List<CameraLensDetails> = emptyList(),
    val zoomA: Float = 1.0f,
    val zoomB: Float = 1.0f,
    val zoomMap: Map<String, Float> = emptyMap(),
    val zoomLimitsMap: Map<String, Pair<Float, Float>> = emptyMap(),
    val concurrentPreviewSupported: Boolean = false,
    val previewStateMessage: String = "",
    val captureTrigger: Int = 0,
    val isAutoZoomApplied: Boolean = false,
    val isCalibrating: Boolean = false,
    val lensCount: Int = 2,
    val showSettings: Boolean = false,
    val is4K: Boolean = true
) {
    fun zoomLimitsFor(lens: CameraLensDetails): Pair<Float, Float> =
        zoomLimitsMap[lens.id] ?: DEFAULT_ZOOM_LIMITS

    /** The zoom to use for [lens], always inside its configured limits. */
    fun zoomFor(lens: CameraLensDetails): Float {
        val limits = zoomLimitsFor(lens)
        return (zoomMap[lens.id] ?: 1.0f).coerceIn(limits.first, limits.second)
    }
}

class WiggleViewModel(private val repository: WiggleRepository) : ViewModel() {

    private companion object {
        const val TAG = "WiggleViewModel"
        const val MAX_SECONDARY_LENSES = 3
    }

    val capturesList: StateFlow<List<WiggleCapture>> = repository.allCaptures
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(WiggleUiState())
    val uiState: StateFlow<WiggleUiState> = _uiState.asStateFlow()

    private var discoveryStarted = false

    /**
     * Enumerates the device's lenses once and restores the saved lens selection. Guarded
     * against repeat invocations because the permission effect re-fires on every
     * configuration change while the ViewModel survives.
     */
    fun initCameraDiscovery(context: Context) {
        if (discoveryStarted) return
        discoveryStarted = true

        viewModelScope.launch {
            try {
                val discovery = LensDiscovery.discover(context)
                val lenses = discovery.lenses

                val savedPrimaryId = SettingsManager.getPrimaryLensId(context)
                val savedSecondaryIds = SettingsManager.getSecondaryLensIds(context)
                val savedLensCount = SettingsManager.getLensCount(context)
                val savedZoomLimits = SettingsManager.getZoomLimits(context)
                val savedIs4K = SettingsManager.getIs4K(context)

                val primary = lenses.firstOrNull { it.id == savedPrimaryId }
                    ?: lenses.firstOrNull { !it.isDefaultUltraWide && it.isPhysical }
                    ?: lenses.firstOrNull { !it.isDefaultUltraWide }
                    ?: lenses.firstOrNull()

                val secondaries = resolveSecondaries(
                    lenses = lenses,
                    primary = primary,
                    preferredIds = savedSecondaryIds,
                    lensCount = savedLensCount
                )

                val zoomLimits = savedZoomLimits.toMutableMap()
                lenses.forEach { zoomLimits.putIfAbsent(it.id, DEFAULT_ZOOM_LIMITS) }

                _uiState.update { state ->
                    state.copy(
                        availableLenses = lenses,
                        primaryLens = primary,
                        secondaryLens = secondaries.firstOrNull(),
                        secondaryLenses = secondaries,
                        lensCount = savedLensCount,
                        zoomLimitsMap = zoomLimits,
                        zoomMap = lenses.associate { it.id to 1.0f },
                        is4K = savedIs4K,
                        concurrentPreviewSupported = discovery.concurrentPreviewSupported,
                        previewStateMessage = if (discovery.concurrentPreviewSupported) {
                            "Dual Cameras Active"
                        } else {
                            "Single Viewfinder (Smart Fallback Capture)"
                        }
                    )
                }
            } catch (e: Exception) {
                discoveryStarted = false
                Log.e(TAG, "Failed discovering cameras", e)
            }
        }
    }

    /**
     * Picks the secondary lenses: preferred ids first, then any remaining lens, trimmed to
     * the requested lens count.
     */
    private fun resolveSecondaries(
        lenses: List<CameraLensDetails>,
        primary: CameraLensDetails?,
        preferredIds: List<String>,
        lensCount: Int
    ): List<CameraLensDetails> {
        val needed = (lensCount - 1).coerceIn(1, MAX_SECONDARY_LENSES)
        val selected = preferredIds
            .mapNotNull { id -> lenses.firstOrNull { it.id == id } }
            .filter { it.id != primary?.id }
            .distinctBy { it.id }
            .toMutableList()

        if (selected.isEmpty()) {
            val default = lenses.firstOrNull { it.id != primary?.id && it.isDefaultUltraWide && it.isPhysical }
                ?: lenses.firstOrNull { it.id != primary?.id && it.isDefaultUltraWide }
                ?: lenses.firstOrNull { it.id != primary?.id }
            if (default != null) selected += default
        }

        fillUp(selected, lenses, primary?.id, needed)
        return selected.take(needed)
    }

    private fun fillUp(
        selected: MutableList<CameraLensDetails>,
        lenses: List<CameraLensDetails>,
        excludeId: String?,
        needed: Int
    ) {
        while (selected.size < needed) {
            val candidate = lenses.firstOrNull { lens ->
                lens.id != excludeId && selected.none { it.id == lens.id }
            } ?: break
            selected += candidate
        }
    }

    fun setCalibrating(calibrating: Boolean) {
        _uiState.update { it.copy(isCalibrating = calibrating) }
    }

    fun setIs4K(context: Context, is4K: Boolean) {
        SettingsManager.saveIs4K(context, is4K)
        _uiState.update { it.copy(is4K = is4K) }
    }

    suspend fun calculateVisualZoomMatch(
        bmpA: Bitmap,
        bmpB: Bitmap,
        minZoom: Float,
        maxZoom: Float
    ): Float = withContext(Dispatchers.Default) {
        VisualZoomMatcher.calculate(bmpA, bmpB, minZoom, maxZoom)
    }

    /**
     * Applies the calibration measurements. The median of the passes is used because, unlike
     * the mean, it is robust against a single outlier pass (e.g. a frame captured before
     * auto exposure had converged).
     */
    fun applyCalibrationResults(results: Map<String, List<Float>>) {
        _uiState.update { state ->
            val zoomMap = state.zoomMap.toMutableMap()
            var secondaryZoom = state.zoomB

            for ((lensId, zooms) in results) {
                if (zooms.isEmpty()) continue
                val limits = state.zoomLimitsMap[lensId] ?: DEFAULT_ZOOM_LIMITS
                val clamped = median(zooms).coerceIn(limits.first, limits.second)
                zoomMap[lensId] = clamped
                Log.d(TAG, "Calibrated lens $lensId to ${clamped}x (from $zooms)")
                if (lensId == state.secondaryLens?.id) secondaryZoom = clamped
            }

            state.copy(
                zoomMap = zoomMap,
                zoomB = secondaryZoom,
                isAutoZoomApplied = true,
                isCalibrating = false
            )
        }
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun setLensCount(context: Context, count: Int) {
        val state = _uiState.value
        val secondaries = resolveSecondaries(
            lenses = state.availableLenses,
            primary = state.primaryLens,
            preferredIds = state.secondaryLenses.map { it.id },
            lensCount = count
        )

        _uiState.update {
            it.copy(
                lensCount = count,
                secondaryLenses = secondaries,
                secondaryLens = secondaries.firstOrNull()
            )
        }
        SettingsManager.saveLensCount(context, count)
        SettingsManager.saveSecondaryLensIds(context, secondaries.map { it.id })
    }

    fun updatePrimaryLens(context: Context, lens: CameraLensDetails) {
        val state = _uiState.value
        val secondaries = resolveSecondaries(
            lenses = state.availableLenses,
            primary = lens,
            preferredIds = state.secondaryLenses.map { it.id },
            lensCount = state.lensCount
        )

        _uiState.update {
            it.copy(
                primaryLens = lens,
                secondaryLenses = secondaries,
                secondaryLens = secondaries.firstOrNull(),
                isAutoZoomApplied = false
            )
        }
        SettingsManager.savePrimaryLensId(context, lens.id)
        SettingsManager.saveSecondaryLensIds(context, secondaries.map { it.id })
    }

    fun updateSecondaryLenses(context: Context, newSecondaries: List<CameraLensDetails>) {
        _uiState.update {
            it.copy(
                secondaryLenses = newSecondaries,
                secondaryLens = newSecondaries.firstOrNull(),
                isAutoZoomApplied = false
            )
        }
        SettingsManager.saveSecondaryLensIds(context, newSecondaries.map { it.id })
    }

    fun setZoomForLens(lensId: String, zoom: Float) {
        _uiState.update { state ->
            state.copy(
                zoomMap = state.zoomMap + (lensId to zoom),
                zoomA = if (state.primaryLens?.id == lensId) zoom else state.zoomA,
                zoomB = if (state.secondaryLens?.id == lensId) zoom else state.zoomB,
                isAutoZoomApplied = false
            )
        }
    }

    fun setZoomLimitsForLens(context: Context, lensId: String, min: Float, max: Float) {
        _uiState.update { state ->
            val clamped = (state.zoomMap[lensId] ?: 1.0f).coerceIn(min, max)
            state.copy(
                zoomLimitsMap = state.zoomLimitsMap + (lensId to (min to max)),
                zoomMap = state.zoomMap + (lensId to clamped),
                zoomA = if (state.primaryLens?.id == lensId) clamped else state.zoomA,
                zoomB = if (state.secondaryLens?.id == lensId) clamped else state.zoomB
            )
        }
        SettingsManager.saveZoomLimits(context, _uiState.value.zoomLimitsMap)
    }

    /** Triggered by the hardware volume keys. */
    fun triggerCapture() {
        _uiState.update { it.copy(captureTrigger = it.captureTrigger + 1) }
    }

    fun selectCapture(capture: WiggleCapture?) {
        _uiState.update { it.copy(selectedCapture = capture) }
    }

    fun deleteCapture(context: Context, capture: WiggleCapture) {
        viewModelScope.launch(Dispatchers.IO) {
            capture.getImagePathList().forEach { path ->
                runCatching { File(path).delete() }
                    .onFailure { Log.w(TAG, "Could not delete $path", it) }
            }
            repository.deleteCapture(capture.id)
            _uiState.update { state ->
                if (state.selectedCapture?.id == capture.id) state.copy(selectedCapture = null) else state
            }
        }
    }

    /** Persists the frames of one capture and opens it in the player. */
    fun performMultiCapture(context: Context, bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) {
            Log.e(TAG, "No bitmaps supplied, nothing to save")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isCapturing = true) }
            try {
                val timestamp = System.currentTimeMillis()
                val savedPaths = bitmaps.mapIndexedNotNull { index, bitmap ->
                    val path = WiggleProcessor.saveToInternalFiles(context, bitmap, "cam_${index}_$timestamp.jpg")
                    WiggleProcessor.saveToGallery(context, bitmap, "Wiggle_${timestamp}_Frame_$index")
                    path?.takeIf { it.isNotEmpty() }
                }

                if (savedPaths.isEmpty()) {
                    Log.e(TAG, "Could not persist any frame of this capture")
                    return@launch
                }

                val capture = WiggleCapture(imagePaths = savedPaths.joinToString(","), timestamp = timestamp)
                val insertId = repository.insertCapture(capture)
                _uiState.update { it.copy(selectedCapture = capture.copy(id = insertId)) }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving capture", e)
            } finally {
                _uiState.update { it.copy(isCapturing = false) }
            }
        }
    }
}

class WiggleViewModelFactory(private val repository: WiggleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(WiggleViewModel::class.java)) { "Unknown ViewModel class" }
        @Suppress("UNCHECKED_CAST")
        return WiggleViewModel(repository) as T
    }
}
