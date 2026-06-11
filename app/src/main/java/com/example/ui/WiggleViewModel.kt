package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL

data class CameraLensDetails(
    val id: String,
    val name: String,
    val focalLength: Float?,
    val isDefaultUltraWide: Boolean = false,
    val parentLogicalId: String? = null
)

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
    val autoZoomRatio: Float = 1.0f,
    val isAutoZoomApplied: Boolean = false,
    val isCalibrating: Boolean = false,
    val lensCount: Int = 2,
    val showSettings: Boolean = false,
    val is4K: Boolean = true
)

class WiggleViewModel(private val repository: WiggleRepository) : ViewModel() {
    private val TAG = "WiggleViewModel"

    val capturesList: StateFlow<List<WiggleCapture>> = repository.allCaptures
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(WiggleUiState())
    val uiState: StateFlow<WiggleUiState> = _uiState.asStateFlow()

    fun initCameraDiscovery(context: Context) {
        viewModelScope.launch {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val lenses = mutableListOf<CameraLensDetails>()
                var concurrentCapable = false

                // Check concurrent camera preview support in Android 11+ (API 30)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val concurrentSets = cameraManager.concurrentCameraIds
                    if (concurrentSets.isNotEmpty()) {
                        concurrentCapable = true
                        Log.d(TAG, "Device supports concurrent physical camera streams!")
                    }
                }

                for (id in cameraManager.cameraIdList) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            val physicalIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                chars.physicalCameraIds
                            } else {
                                emptySet<String>()
                            }

                            if (physicalIds.isNotEmpty()) {
                                Log.d(TAG, "Camera $id has physical camera IDs: $physicalIds")
                                // Add parent logical camera ID as an option too!
                                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val mainFocal = focalLengths?.firstOrNull()
                                val isUltraWide = mainFocal != null && mainFocal < 3.0f
                                lenses.add(
                                    CameraLensDetails(
                                        id = id,
                                        name = "Auto Smart Multi-Lens #$id",
                                        focalLength = mainFocal,
                                        isDefaultUltraWide = isUltraWide,
                                        parentLogicalId = null
                                    )
                                )

                                // Add each individual physical lens
                                for (pId in physicalIds) {
                                    try {
                                        val pChars = cameraManager.getCameraCharacteristics(pId)
                                        val pFocal = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                                        val isPUltraWide = pFocal != null && ((mainFocal != null && pFocal < mainFocal * 0.8f) || pFocal < 3.0f)
                                        val label = when {
                                            isPUltraWide -> "Phys. Lens #$pId - Ultra-Wide (~0.5x)"
                                            pFocal != null && mainFocal != null && pFocal > mainFocal * 1.5f -> "Phys. Lens #$pId - Telephoto (~2x+)"
                                            pFocal != null && mainFocal == null && pFocal >= 8.0f -> "Phys. Lens #$pId - Telephoto (~2x+)"
                                            else -> "Phys. Lens #$pId - Wide Main (1x)"
                                        }
                                        lenses.add(
                                            CameraLensDetails(
                                                id = pId,
                                                name = label,
                                                focalLength = pFocal,
                                                isDefaultUltraWide = isPUltraWide,
                                                parentLogicalId = id
                                            )
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error querying physical lens $pId", e)
                                    }
                                }
                            } else {
                                // Simple individual back camera
                                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val mainFocal = focalLengths?.firstOrNull()
                                val isUltraWide = mainFocal != null && mainFocal < 3.0f
                                val label = when {
                                    isUltraWide -> "Camera #$id - Ultra-Wide (~0.5x)"
                                    mainFocal != null && mainFocal >= 8.0f -> "Camera #$id - Telephoto (~2x+)"
                                    else -> "Camera #$id - Standard Wide (1x)"
                                }
                                lenses.add(
                                    CameraLensDetails(
                                        id = id,
                                        name = label,
                                        focalLength = mainFocal,
                                        isDefaultUltraWide = isUltraWide,
                                        parentLogicalId = null
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking characteristics of camera $id", e)
                    }
                }

                if (lenses.isEmpty()) {
                    // Fallback mock definitions for testing/emulators where physical descriptors might be omitted
                    lenses.add(CameraLensDetails("0", "Cam 0 - Standard (1x)", 4.25f, false, null))
                    lenses.add(CameraLensDetails("1", "Cam 1 - Ultra-Wide (0.5x)", 1.85f, true, null))
                }

                // Load saved settings
                val savedPrimaryId = com.example.data.SettingsManager.getPrimaryLensId(context)
                val savedSecondaryIds = com.example.data.SettingsManager.getSecondaryLensIds(context)
                val savedLensCount = com.example.data.SettingsManager.getLensCount(context)
                val savedZoomLimits = com.example.data.SettingsManager.getZoomLimits(context)
                val savedIs4K = com.example.data.SettingsManager.getIs4K(context)

                var primary = lenses.firstOrNull { it.id == savedPrimaryId }
                if (primary == null) {
                    primary = lenses.firstOrNull { !it.isDefaultUltraWide && it.parentLogicalId != null }
                        ?: lenses.firstOrNull { !it.isDefaultUltraWide }
                        ?: lenses.firstOrNull()
                }

                // Load secondary lenses based on saved settings or default
                val secondaryLenses = mutableListOf<CameraLensDetails>()
                if (savedSecondaryIds.isNotEmpty()) {
                    savedSecondaryIds.forEach { id ->
                        lenses.firstOrNull { it.id == id }?.let { secondaryLenses.add(it) }
                    }
                }
                
                // If secondary selection is empty, pick default
                if (secondaryLenses.isEmpty()) {
                    val defaultSec = lenses.firstOrNull { it.id != primary?.id && it.isDefaultUltraWide && it.parentLogicalId != null }
                        ?: lenses.firstOrNull { it.id != primary?.id && it.isDefaultUltraWide }
                        ?: lenses.firstOrNull { it.id != primary?.id }
                        ?: lenses.firstOrNull()
                    if (defaultSec != null) {
                        secondaryLenses.add(defaultSec)
                    }
                }

                // Adjust secondary list to fit the requested lensCount - 1
                val neededSecondaries = (savedLensCount - 1).coerceIn(1, 3)
                while (secondaryLenses.size < neededSecondaries) {
                    val candidate = lenses.firstOrNull { it.id != primary?.id && !secondaryLenses.any { s -> s.id == it.id } }
                    if (candidate != null) {
                        secondaryLenses.add(candidate)
                    } else {
                        break
                    }
                }
                while (secondaryLenses.size > neededSecondaries) {
                    secondaryLenses.removeAt(secondaryLenses.size - 1)
                }

                val secondary = secondaryLenses.firstOrNull()

                // Default zoom limits per lens if not configured
                val zoomLimitsMap = savedZoomLimits.toMutableMap()
                lenses.forEach { lens ->
                    if (!zoomLimitsMap.containsKey(lens.id)) {
                        zoomLimitsMap[lens.id] = Pair(1.0f, 3.0f)
                    }
                }

                // Initialize zoom values
                val zoomMap = mutableMapOf<String, Float>()
                lenses.forEach { lens ->
                    zoomMap[lens.id] = 1.0f
                }

                _uiState.value = _uiState.value.copy(
                    availableLenses = lenses,
                    primaryLens = primary,
                    secondaryLens = secondary,
                    secondaryLenses = secondaryLenses,
                    lensCount = savedLensCount,
                    zoomLimitsMap = zoomLimitsMap,
                    zoomMap = zoomMap,
                    is4K = savedIs4K,
                    concurrentPreviewSupported = concurrentCapable,
                    previewStateMessage = if (concurrentCapable) "Dual Cameras Active" else "Single Viewfinder (Smart Fallback Capture)"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed discovering cameras", e)
            }
        }
    }

    fun setCalibrating(calibrating: Boolean) {
        _uiState.value = _uiState.value.copy(isCalibrating = calibrating)
    }

    fun setIs4K(context: Context, is4K: Boolean) {
        viewModelScope.launch {
            com.example.data.SettingsManager.saveIs4K(context, is4K)
            _uiState.value = _uiState.value.copy(is4K = is4K)
        }
    }

    suspend fun calculateVisualZoomMatch(
        bmpA: Bitmap,
        bmpB: Bitmap,
        minZoom: Float,
        maxZoom: Float
    ): Float = withContext(Dispatchers.Default) {
        Log.d(TAG, "Visual zoom match: A=${bmpA.width}x${bmpA.height}, B=${bmpB.width}x${bmpB.height}, range=[$minZoom, $maxZoom]")
        VisualZoomMatcher.calculate(bmpA, bmpB, minZoom, maxZoom)
    }

    fun applyCalibrationResults(results: Map<String, List<Float>>) {
        val currentState = _uiState.value
        val newZoomMap = currentState.zoomMap.toMutableMap()
        
        var firstSecondaryZoom = currentState.zoomB

        for ((lensId, zooms) in results) {
            if (zooms.isNotEmpty()) {
                // Use the median of the passes: unlike the mean it is robust
                // against a single outlier pass (e.g. a frame captured before
                // auto-exposure had fully converged).
                val sorted = zooms.sorted()
                val medianZoom = if (sorted.size % 2 == 1) {
                    sorted[sorted.size / 2]
                } else {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
                }
                val limits = currentState.zoomLimitsMap[lensId] ?: Pair(1.0f, 3.0f)
                val clamped = medianZoom.coerceIn(limits.first, limits.second)
                
                newZoomMap[lensId] = clamped
                Log.d(TAG, "Applied median visual ZNCC calibration for lens $lensId: $clamped (from $zooms)")
                
                if (lensId == currentState.secondaryLens?.id) {
                    firstSecondaryZoom = clamped
                }
            }
        }

        _uiState.value = currentState.copy(
            zoomMap = newZoomMap,
            zoomB = firstSecondaryZoom,
            isAutoZoomApplied = true,
            isCalibrating = false
        )
    }

    fun toggleSettings() {
        _uiState.value = _uiState.value.copy(showSettings = !_uiState.value.showSettings)
    }

    fun setLensCount(context: Context, count: Int) {
        val primary = _uiState.value.primaryLens
        val lenses = _uiState.value.availableLenses
        
        val secondaryLenses = _uiState.value.secondaryLenses.toMutableList()
        val neededSecondaries = (count - 1).coerceIn(1, 3)
        
        while (secondaryLenses.size < neededSecondaries) {
            val candidate = lenses.firstOrNull { it.id != primary?.id && !secondaryLenses.any { s -> s.id == it.id } }
            if (candidate != null) {
                secondaryLenses.add(candidate)
            } else {
                break
            }
        }
        while (secondaryLenses.size > neededSecondaries) {
            secondaryLenses.removeAt(secondaryLenses.size - 1)
        }

        _uiState.value = _uiState.value.copy(
            lensCount = count,
            secondaryLenses = secondaryLenses,
            secondaryLens = secondaryLenses.firstOrNull()
        )
        
        com.example.data.SettingsManager.saveLensCount(context, count)
        com.example.data.SettingsManager.saveSecondaryLensIds(context, secondaryLenses.map { it.id })
    }

    fun updatePrimaryLens(context: Context, lens: CameraLensDetails) {
        val secondaryLenses = _uiState.value.secondaryLenses.toMutableList()
        secondaryLenses.removeAll { it.id == lens.id }
        
        val lenses = _uiState.value.availableLenses
        val neededSecondaries = (_uiState.value.lensCount - 1).coerceIn(1, 3)
        
        while (secondaryLenses.size < neededSecondaries) {
            val candidate = lenses.firstOrNull { it.id != lens.id && !secondaryLenses.any { s -> s.id == it.id } }
            if (candidate != null) {
                secondaryLenses.add(candidate)
            } else {
                break
            }
        }

        _uiState.value = _uiState.value.copy(
            primaryLens = lens,
            secondaryLenses = secondaryLenses,
            secondaryLens = secondaryLenses.firstOrNull()
        )
        
        com.example.data.SettingsManager.savePrimaryLensId(context, lens.id)
        com.example.data.SettingsManager.saveSecondaryLensIds(context, secondaryLenses.map { it.id })

        if (_uiState.value.isAutoZoomApplied) {
            _uiState.value = _uiState.value.copy(isAutoZoomApplied = false)
        }
    }

    fun updateSecondaryLenses(context: Context, newSecondaries: List<CameraLensDetails>) {
        _uiState.value = _uiState.value.copy(
            secondaryLenses = newSecondaries,
            secondaryLens = newSecondaries.firstOrNull()
        )
        com.example.data.SettingsManager.saveSecondaryLensIds(context, newSecondaries.map { it.id })
        if (_uiState.value.isAutoZoomApplied) {
            _uiState.value = _uiState.value.copy(isAutoZoomApplied = false)
        }
    }

    fun setZoomForLens(lensId: String, zoom: Float) {
        val newZoomMap = _uiState.value.zoomMap.toMutableMap()
        newZoomMap[lensId] = zoom
        
        val isPrimary = _uiState.value.primaryLens?.id == lensId
        val firstSecondaryId = _uiState.value.secondaryLens?.id
        
        _uiState.value = _uiState.value.copy(
            zoomMap = newZoomMap,
            zoomA = if (isPrimary) zoom else _uiState.value.zoomA,
            zoomB = if (lensId == firstSecondaryId) zoom else _uiState.value.zoomB,
            isAutoZoomApplied = false
        )
    }

    fun setZoomLimitsForLens(context: Context, lensId: String, min: Float, max: Float) {
        val newLimits = _uiState.value.zoomLimitsMap.toMutableMap()
        newLimits[lensId] = Pair(min, max)
        
        val newZoomMap = _uiState.value.zoomMap.toMutableMap()
        val currentZoom = newZoomMap[lensId] ?: 1.0f
        if (currentZoom < min) {
            newZoomMap[lensId] = min
        } else if (currentZoom > max) {
            newZoomMap[lensId] = max
        }

        _uiState.value = _uiState.value.copy(
            zoomLimitsMap = newLimits,
            zoomMap = newZoomMap,
            zoomA = if (_uiState.value.primaryLens?.id == lensId) (newZoomMap[lensId] ?: 1.0f) else _uiState.value.zoomA,
            zoomB = if (_uiState.value.secondaryLens?.id == lensId) (newZoomMap[lensId] ?: 1.0f) else _uiState.value.zoomB
        )
        com.example.data.SettingsManager.saveZoomLimits(context, newLimits)
    }

    fun setPrimaryLens(lens: CameraLensDetails) {
        _uiState.value = _uiState.value.copy(primaryLens = lens, isAutoZoomApplied = false)
    }

    fun setSecondaryLens(lens: CameraLensDetails) {
        _uiState.value = _uiState.value.copy(secondaryLens = lens, isAutoZoomApplied = false)
    }

    fun setZoomA(zoom: Float) {
        _uiState.value = _uiState.value.copy(zoomA = zoom, isAutoZoomApplied = false)
        val pId = _uiState.value.primaryLens?.id
        if (pId != null) {
            val newZoomMap = _uiState.value.zoomMap.toMutableMap()
            newZoomMap[pId] = zoom
            _uiState.value = _uiState.value.copy(zoomMap = newZoomMap)
        }
    }

    fun setZoomB(zoom: Float) {
        _uiState.value = _uiState.value.copy(zoomB = zoom, isAutoZoomApplied = false)
        val sId = _uiState.value.secondaryLens?.id
        if (sId != null) {
            val newZoomMap = _uiState.value.zoomMap.toMutableMap()
            newZoomMap[sId] = zoom
            _uiState.value = _uiState.value.copy(zoomMap = newZoomMap)
        }
    }

    fun triggerCapture() {
        _uiState.value = _uiState.value.copy(captureTrigger = _uiState.value.captureTrigger + 1)
    }

    fun selectCapture(capture: WiggleCapture?) {
        _uiState.value = _uiState.value.copy(selectedCapture = capture)
    }

    fun deleteCapture(context: Context, capture: WiggleCapture) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                capture.getImagePathList().forEach { path ->
                    java.io.File(path).delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting internal raw cache files", e)
            }
            repository.deleteCapture(capture.id)
            if (_uiState.value.selectedCapture?.id == capture.id) {
                _uiState.value = _uiState.value.copy(selectedCapture = null)
            }
        }
    }

    fun performMultiCapture(context: Context, bitmaps: List<Bitmap>) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(isCapturing = true)
            try {
                if (bitmaps.isEmpty()) {
                    Log.e(TAG, "Empty bitmaps list supplied. Cannot perform capture.")
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isCapturing = false)
                    }
                    return@launch
                }

                val timestamp = System.currentTimeMillis()
                val savedPaths = mutableListOf<String>()

                bitmaps.forEachIndexed { index, bitmap ->
                    val fileName = "cam_${index}_$timestamp.jpg"
                    val path = WiggleProcessor.saveToInternalFiles(context, bitmap, fileName) ?: ""
                    if (path.isNotEmpty()) {
                        savedPaths.add(path)
                    }
                    WiggleProcessor.saveToGallery(context, bitmap, "Wiggle_${timestamp}_Frame_$index")
                }

                val imagePathsString = savedPaths.joinToString(",")

                val newCapture = WiggleCapture(
                    imagePaths = imagePathsString,
                    timestamp = timestamp
                )

                val insertId = repository.insertCapture(newCapture)
                val savedCapture = newCapture.copy(id = insertId)

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        selectedCapture = savedCapture,
                        isCapturing = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing multi stereoscopic frame capture", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isCapturing = false)
                }
            }
        }
    }

    fun performDualCapture(context: Context, bitmapA: Bitmap?, bitmapB: Bitmap?) {
        val list = mutableListOf<Bitmap>()
        if (bitmapA != null) list.add(bitmapA)
        if (bitmapB != null) list.add(bitmapB)
        performMultiCapture(context, list)
    }
}

class WiggleViewModelFactory(private val repository: WiggleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WiggleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WiggleViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
