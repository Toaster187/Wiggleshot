package com.example.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A single selectable lens. Either a logical camera (parentLogicalId == null) or one
 * physical sub-camera of a logical multi-camera (parentLogicalId != null).
 */
data class CameraLensDetails(
    val id: String,
    val name: String,
    val focalLength: Float?,
    val isDefaultUltraWide: Boolean = false,
    val parentLogicalId: String? = null
) {
    /** The camera id that must actually be opened to use this lens. */
    val logicalId: String get() = parentLogicalId ?: id

    val isPhysical: Boolean get() = parentLogicalId != null
}

data class LensDiscoveryResult(
    val lenses: List<CameraLensDetails>,
    val concurrentPreviewSupported: Boolean
)

/**
 * Enumerates the back-facing lenses of the device. Runs off the main thread because
 * [CameraManager.getCameraCharacteristics] can take tens of milliseconds per camera and
 * used to be executed during composition.
 */
object LensDiscovery {
    private const val TAG = "LensDiscovery"

    suspend fun discover(context: Context): LensDiscoveryResult = withContext(Dispatchers.IO) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = mutableListOf<CameraLensDetails>()
        var concurrentCapable = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            concurrentCapable = runCatching { cameraManager.concurrentCameraIds.isNotEmpty() }
                .getOrDefault(false)
        }

        val cameraIds = runCatching { cameraManager.cameraIdList }.getOrDefault(emptyArray())
        for (id in cameraIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                    continue
                }
                val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { chars.physicalCameraIds }.getOrDefault(emptySet())
                } else {
                    emptySet()
                }
                val mainFocal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

                if (physicalIds.isEmpty()) {
                    lenses += CameraLensDetails(
                        id = id,
                        name = labelForStandalone(id, mainFocal),
                        focalLength = mainFocal,
                        isDefaultUltraWide = mainFocal != null && mainFocal < 3.0f,
                        parentLogicalId = null
                    )
                    continue
                }

                // The logical camera itself stays selectable (auto lens switching).
                lenses += CameraLensDetails(
                    id = id,
                    name = "Auto Smart Multi-Lens #$id",
                    focalLength = mainFocal,
                    isDefaultUltraWide = mainFocal != null && mainFocal < 3.0f,
                    parentLogicalId = null
                )

                for (physicalId in physicalIds) {
                    try {
                        val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                        val focal = physicalChars
                            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.firstOrNull()
                        val ultraWide = focal != null &&
                            ((mainFocal != null && focal < mainFocal * 0.8f) || focal < 3.0f)
                        lenses += CameraLensDetails(
                            id = physicalId,
                            name = labelForPhysical(physicalId, focal, mainFocal, ultraWide),
                            focalLength = focal,
                            isDefaultUltraWide = ultraWide,
                            parentLogicalId = id
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping physical lens $physicalId", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping camera $id", e)
            }
        }

        if (lenses.isEmpty()) {
            // Emulators frequently omit physical descriptors entirely.
            lenses += CameraLensDetails("0", "Cam 0 - Standard (1x)", 4.25f, false, null)
            lenses += CameraLensDetails("1", "Cam 1 - Ultra-Wide (0.5x)", 1.85f, true, null)
        }

        LensDiscoveryResult(lenses, concurrentCapable)
    }

    private fun labelForStandalone(id: String, focal: Float?): String = when {
        focal != null && focal < 3.0f -> "Camera #$id - Ultra-Wide (~0.5x)"
        focal != null && focal >= 8.0f -> "Camera #$id - Telephoto (~2x+)"
        else -> "Camera #$id - Standard Wide (1x)"
    }

    private fun labelForPhysical(
        id: String,
        focal: Float?,
        mainFocal: Float?,
        ultraWide: Boolean
    ): String = when {
        ultraWide -> "Phys. Lens #$id - Ultra-Wide (~0.5x)"
        focal != null && mainFocal != null && focal > mainFocal * 1.5f -> "Phys. Lens #$id - Telephoto (~2x+)"
        focal != null && mainFocal == null && focal >= 8.0f -> "Phys. Lens #$id - Telephoto (~2x+)"
        else -> "Phys. Lens #$id - Wide Main (1x)"
    }
}
