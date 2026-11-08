package com.example.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.Log
import com.example.align.FrameMetadata
import com.example.align.LensGeometry
import java.util.concurrent.ConcurrentHashMap

/** Reads the factory calibration and per frame metadata out of Camera2. */
object LensMetadata {
    private const val TAG = "LensMetadata"
    private val cache = ConcurrentHashMap<String, LensGeometry>()

    fun geometry(cameraManager: CameraManager, cameraId: String): LensGeometry? {
        cache[cameraId]?.let { return it }
        return runCatching { fromCharacteristics(cameraId, cameraManager.getCameraCharacteristics(cameraId)) }
            .onFailure { Log.w(TAG, "No characteristics for $cameraId", it) }
            .getOrNull()
            ?.also { cache[cameraId] = it }
    }

    fun fromCharacteristics(cameraId: String, chars: CameraCharacteristics): LensGeometry? {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val preCorrection = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: active
        val intrinsics = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val distortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.get(CameraCharacteristics.LENS_DISTORTION)
        } else {
            @Suppress("DEPRECATION") chars.get(CameraCharacteristics.LENS_RADIAL_DISTORTION)
        }
        return LensGeometry(
            cameraId = cameraId,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            activeArray = LensGeometry.IntRect(active.left, active.top, active.width(), active.height()),
            preCorrectionArray = LensGeometry.IntRect(
                preCorrection.left, preCorrection.top, preCorrection.width(), preCorrection.height()
            ),
            intrinsics = intrinsics,
            focalLengthMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
            sensorWidthMm = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width,
            pixelArrayWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width,
            distortion = distortion,
            poseRotation = chars.get(CameraCharacteristics.LENS_POSE_ROTATION),
            poseTranslation = chars.get(CameraCharacteristics.LENS_POSE_TRANSLATION),
            poseReference = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) chars.get(CameraCharacteristics.LENS_POSE_REFERENCE) else null,
            apertureF = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        )
    }

    /** Extracts the values of one capture result that belong to [cameraId]. */
    fun fromResult(cameraId: String, result: CaptureResult): FrameMetadata {
        val crop = result.get(CaptureResult.SCALER_CROP_REGION)
        return FrameMetadata(
            cameraId = cameraId,
            intrinsics = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION),
            distortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) result.get(CaptureResult.LENS_DISTORTION) else null,
            cropRegion = crop?.let { LensGeometry.IntRect(it.left, it.top, it.width(), it.height()) },
            focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY),
            distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                result.get(CaptureResult.DISTORTION_CORRECTION_MODE)
            } else {
                null
            },
            timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP),
            activePhysicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            } else {
                null
            },
            zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) result.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        )
    }
}
