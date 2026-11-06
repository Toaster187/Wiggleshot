package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.os.Build
import com.example.align.WiggleAligner
import com.example.camera.CameraLensDetails
import com.example.camera.CapturedFrame
import com.example.camera.LensDiscovery
import com.example.data.SettingsManager
import com.example.data.WiggleCapture
import com.example.data.WiggleRepository
import com.example.diagnostics.DiagnosticsExporter
import com.example.diagnostics.HalProbeRunner
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
    val is4K: Boolean = true,
    /** Secondary lenses are aligned automatically instead of using the manual zoom. */
    val autoAlign: Boolean = true,
    val colorMatch: Boolean = true,
    val diagnosticsMode: Boolean = false,
    /** Human readable result of the last alignment / calibration, shown on the camera screen. */
    val alignmentInfo: String? = null,
    /** One-shot message for a toast; cleared by [WiggleViewModel.consumeMessage]. */
    val message: String? = null,
    val diagnosticsBusy: Boolean = false,
    val diagnosticsStatus: String? = null
) {
    fun zoomLimitsFor(lens: CameraLensDetails): Pair<Float, Float> =
        zoomLimitsMap[lens.id] ?: DEFAULT_ZOOM_LIMITS

    /** The zoom to use for [lens], always inside its configured limits. */
    fun zoomFor(lens: CameraLensDetails): Float {
        val limits = zoomLimitsFor(lens)
        return (zoomMap[lens.id] ?: 1.0f).coerceIn(limits.first, limits.second)
    }

    /**
     * Zoom the camera should apply while capturing. With auto alignment the secondaries are
     * captured with their full field of view; the aligner then crops them precisely.
     */
    fun captureZoomFor(lens: CameraLensDetails): Float =
        if (autoAlign && lens.id != primaryLens?.id) 1.0f else zoomFor(lens)
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
    private var aligner: WiggleAligner? = null

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
                val savedAutoAlign = SettingsManager.getAutoAlign(context)
                val savedColorMatch = SettingsManager.getColorMatch(context)
                val savedDiagnostics = SettingsManager.getDiagnosticsMode(context)

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
                        autoAlign = savedAutoAlign,
                        colorMatch = savedColorMatch,
                        diagnosticsMode = savedDiagnostics,
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

    fun setAutoAlign(context: Context, value: Boolean) {
        SettingsManager.saveAutoAlign(context, value)
        _uiState.update { it.copy(autoAlign = value) }
    }

    fun setColorMatch(context: Context, value: Boolean) {
        SettingsManager.saveColorMatch(context, value)
        _uiState.update { it.copy(colorMatch = value) }
    }

    fun setDiagnosticsMode(context: Context, value: Boolean) {
        SettingsManager.saveDiagnosticsMode(context, value)
        _uiState.update { it.copy(diagnosticsMode = value) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun aligner(context: Context): WiggleAligner =
        aligner ?: WiggleAligner(context.applicationContext).also { aligner = it }

    /**
     * Measures the current lens pair on [frames] and stores the result as calibration. The
     * frames are only used for the measurement and recycled afterwards.
     */
    fun calibrate(context: Context, frames: List<CapturedFrame>) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isCalibrating = true) }
            try {
                if (frames.size < 2) {
                    _uiState.update { it.copy(message = "Kalibrierung braucht zwei Linsen") }
                    return@launch
                }
                val reports = aligner(context).calibrate(frames)
                if (_uiState.value.diagnosticsMode) {
                    DiagnosticsExporter.saveCapture(context, frames, reports, DiagnosticsExporter.newFolder(context, "calibration"))
                }
                val ok = reports.all { it.source == WiggleAligner.Source.IMAGE }
                val info = reports.joinToString("\n") { it.summary() }
                if (!_uiState.value.autoAlign) {
                    // Manual mode: the measured magnification becomes the secondary zoom.
                    val state = _uiState.value
                    val primaryZoom = state.primaryLens?.let(state::zoomFor) ?: 1f
                    reports.filter { it.source == WiggleAligner.Source.IMAGE }.forEach { r ->
                        val limits = state.zoomLimitsMap[r.lensId] ?: DEFAULT_ZOOM_LIMITS
                        setZoomForLens(r.lensId, (r.equivalentZoom * primaryZoom).toFloat().coerceIn(limits.first, limits.second))
                    }
                }
                _uiState.update {
                    it.copy(
                        isAutoZoomApplied = ok,
                        alignmentInfo = info,
                        message = if (ok) {
                            "Kalibriert: $info"
                        } else {
                            "Kalibrierung unsicher (zu wenig Struktur?) – bitte auf eine detailreiche Szene richten"
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Calibration failed", e)
                _uiState.update { it.copy(message = "Kalibrierung fehlgeschlagen") }
            } finally {
                frames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                _uiState.update { it.copy(isCalibrating = false) }
            }
        }
    }

    fun resetCalibration(context: Context) {
        aligner(context).clearCalibration()
        _uiState.update {
            it.copy(isAutoZoomApplied = false, alignmentInfo = null, message = "Kalibrierung zurückgesetzt")
        }
    }

    fun exportCameraInfo(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(diagnosticsBusy = true, diagnosticsStatus = "Exportiere Kamera-Metadaten …") }
            val path = runCatching { DiagnosticsExporter.exportCameraInfo(context) }.getOrNull()
            val text = path?.let { "Gespeichert in $it" } ?: "Export fehlgeschlagen"
            _uiState.update { it.copy(diagnosticsBusy = false, diagnosticsStatus = text, message = text) }
        }
    }

    /** Runs the HAL zoom experiment (see [HalProbeRunner]). The camera screen must be closed. */
    fun runHalProbe(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _uiState.update { it.copy(message = "HAL-Test benötigt Android 11+") }
            return
        }
        if (_uiState.value.diagnosticsBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(diagnosticsBusy = true, diagnosticsStatus = "Starte HAL-Test …") }
            val summary = try {
                HalProbeRunner.run(context.applicationContext, _uiState.value.availableLenses) { progress ->
                    _uiState.update { it.copy(diagnosticsStatus = progress) }
                }.text
            } catch (e: Exception) {
                Log.e(TAG, "HAL probe failed", e)
                "HAL-Test fehlgeschlagen: ${e.message}"
            }
            _uiState.update { it.copy(diagnosticsBusy = false, diagnosticsStatus = summary) }
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

    /**
     * Aligns the raw frames of one capture, persists the result and opens it in the player.
     * The frames' bitmaps are consumed.
     */
    fun processCapture(context: Context, frames: List<CapturedFrame>) {
        if (frames.isEmpty()) {
            Log.e(TAG, "No frames supplied, nothing to save")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isCapturing = true) }
            try {
                val state = _uiState.value
                val output = aligner(context).process(
                    frames = frames,
                    zoomFor = state::zoomFor,
                    settings = WiggleAligner.Settings(autoAlign = state.autoAlign, colorMatch = state.colorMatch)
                )
                if (state.diagnosticsMode) {
                    DiagnosticsExporter.saveCapture(context, frames, output.reports)
                }
                val info = output.reports.joinToString("\n") { it.summary() }.ifEmpty { null }
                _uiState.update { it.copy(alignmentInfo = info ?: it.alignmentInfo) }
                saveFrames(context, output.frames)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory processing capture", e)
                _uiState.update { it.copy(message = "Zu wenig Speicher – bitte HD-Modus verwenden") }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing capture", e)
                _uiState.update { it.copy(message = "Verarbeitung fehlgeschlagen") }
            } finally {
                _uiState.update { it.copy(isCapturing = false) }
            }
        }
    }

    private suspend fun saveFrames(context: Context, bitmaps: List<Bitmap>) {
        val timestamp = System.currentTimeMillis()
        val savedPaths = bitmaps.mapIndexedNotNull { index, bitmap ->
            val path = WiggleProcessor.saveToInternalFiles(context, bitmap, "cam_${index}_$timestamp.jpg")
            WiggleProcessor.saveToGallery(context, bitmap, "Wiggle_${timestamp}_Frame_$index")
            path?.takeIf { it.isNotEmpty() }
        }
        bitmaps.forEach { it.recycle() }

        if (savedPaths.isEmpty()) {
            Log.e(TAG, "Could not persist any frame of this capture")
            _uiState.update { it.copy(message = "Speichern fehlgeschlagen") }
            return
        }

        val capture = WiggleCapture(imagePaths = savedPaths.joinToString(","), timestamp = timestamp)
        val insertId = repository.insertCapture(capture)
        _uiState.update { it.copy(selectedCapture = capture.copy(id = insertId)) }
    }
}

class WiggleViewModelFactory(private val repository: WiggleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(WiggleViewModel::class.java)) { "Unknown ViewModel class" }
        @Suppress("UNCHECKED_CAST")
        return WiggleViewModel(repository) as T
    }
}
