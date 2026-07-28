package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraXSupport"

/** Number of consecutive preview frames that must report stable AE/AWB before capturing. */
internal const val AE_STABLE_FRAME_COUNT = 3

/** Hard cap so a device that never reports 3A state cannot stall a capture. */
internal const val AE_CONVERGENCE_TIMEOUT_MS = 2_500L

/**
 * Suspending replacement for the blocking `ListenableFuture.get()` that used to run on the
 * main thread during composition.
 */
internal suspend fun <T> ListenableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            Executor { it.run() }
        )
    }

internal suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    ProcessCameraProvider.getInstance(context).awaitResult()

internal fun captureResolutionSelector(is4K: Boolean): ResolutionSelector {
    val strategy = if (is4K) {
        ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
    } else {
        ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
    }
    return ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .setResolutionStrategy(strategy)
        .build()
}

internal fun previewResolutionSelector(): ResolutionSelector =
    ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .build()

/**
 * Resolves the [CameraSelector] that opens exactly [cameraId]. Falls back to the default
 * back camera when the id is unknown, so binding never fails outright.
 */
internal fun findCameraSelector(provider: ProcessCameraProvider, cameraId: String): CameraSelector {
    for (info in provider.availableCameraInfos) {
        val id = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
        if (id == cameraId) {
            return CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { it == info } }
                .build()
        }
    }
    Log.w(TAG, "No camera with id $cameraId, using default back camera")
    return CameraSelector.DEFAULT_BACK_CAMERA
}

internal fun imageCaptureFor(lens: CameraLensDetails, is4K: Boolean): ImageCapture {
    val builder = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(captureResolutionSelector(is4K))
    if (lens.isPhysical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Camera2Interop.Extender(builder).setPhysicalCameraId(lens.id)
    }
    return builder.build()
}

/**
 * Builds a preview for [lens]. When [convergence] is provided it is completed once auto
 * exposure and auto white balance have been stable for [AE_STABLE_FRAME_COUNT] frames —
 * capturing before that yields frames with unpredictable brightness, which makes the
 * calibration match non-deterministic across app starts.
 */
internal fun previewFor(
    lens: CameraLensDetails,
    convergence: CompletableDeferred<Unit>? = null
): Preview {
    val builder = Preview.Builder().setResolutionSelector(previewResolutionSelector())
    val extender = Camera2Interop.Extender(builder)
    if (lens.isPhysical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        extender.setPhysicalCameraId(lens.id)
    }
    if (convergence != null) {
        var stableFrames = 0
        extender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (convergence.isCompleted) return
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                val aeStable = aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                val awbStable = awbState == null ||
                    awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                    awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
                if (aeStable && awbStable) {
                    stableFrames += 1
                    if (stableFrames >= AE_STABLE_FRAME_COUNT) {
                        convergence.complete(Unit)
                    }
                } else {
                    stableFrames = 0
                }
            }
        })
    }
    return builder.build()
}

/**
 * Takes a single picture. Returns null instead of throwing so a failing lens degrades the
 * capture to fewer frames rather than aborting it.
 */
internal suspend fun ImageCapture.captureBitmap(executor: Executor): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        try {
            takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        CameraImages.fromImageProxy(image)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed converting captured image", e)
                        null
                    } finally {
                        runCatching { image.close() }
                    }
                    if (continuation.isActive) continuation.resume(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "takePicture threw", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }
