package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import com.example.align.FrameMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Camera2 session that streams two physical sub-cameras of the same logical camera at
 * once and captures a synchronised JPEG pair.
 *
 * This class is deliberately **single-use**: create it, [open] it, [capture] as often as
 * needed, [close] it, and throw it away. The previous implementation allowed restarting a
 * closed instance, which re-used an already terminated background thread and left the
 * shutter permanently dead. Failure is always reported through return values — a caller
 * that gets `false`/`null` can immediately fall back to another capture path instead of
 * waiting for a callback that never arrives.
 */
@RequiresApi(Build.VERSION_CODES.P)
class DualPhysicalSession(
    context: Context,
    private val logicalCameraId: String,
    private val physicalIdA: String?,
    private val physicalIdB: String?,
    is4K: Boolean
) {
    companion object {
        private const val TAG = "DualPhysicalSession"
        const val OPEN_TIMEOUT_MS = 4_000L
        const val CAPTURE_TIMEOUT_MS = 4_000L
        const val RESULT_GRACE_MS = 500L

        /** Extra time allowed per additional frame of a night burst. */
        const val BURST_FRAME_TIMEOUT_MS = 1_500L
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val backgroundThread = HandlerThread("WiggleCamera2").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    private val readerA: ImageReader
    private val readerB: ImageReader

    /** Sensor orientation of the logical camera, needed to rotate captured JPEGs upright. */
    val sensorOrientation: Int

    /** Widest zoom the hardware reports; the preview always streams the full field of view. */
    private val minZoomRatio: Float

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    @Volatile
    private var closed = false

    @Volatile
    private var pending: PendingCapture? = null

    /** Collects the JPEGs of one (burst) capture as they arrive from both readers. */
    private class PendingCapture(val count: Int, val onProgress: (Int) -> Unit) {
        val a = ArrayList<ByteArray>(count)
        val b = ArrayList<ByteArray>(count)
        val done = CompletableDeferred<Unit>()

        @Synchronized
        fun add(isA: Boolean, bytes: ByteArray) {
            val list = if (isA) a else b
            if (list.size >= count) return
            list += bytes
            onProgress(minOf(a.size, b.size))
            if (a.size >= count && b.size >= count) done.complete(Unit)
        }
    }

    init {
        val chars = runCatching { cameraManager.getCameraCharacteristics(logicalCameraId) }.getOrNull()
        sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        minZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1.0f
        } else {
            1.0f
        }

        val sizeA = jpegSize(physicalIdA ?: logicalCameraId, is4K)
        val sizeB = jpegSize(physicalIdB ?: logicalCameraId, is4K)
        Log.d(TAG, "JPEG sizes A=${sizeA.width}x${sizeA.height} B=${sizeB.width}x${sizeB.height}")

        // Three buffers: a burst keeps the HAL busy while earlier JPEGs are still being copied.
        readerA = ImageReader.newInstance(sizeA.width, sizeA.height, ImageFormat.JPEG, 3)
        readerB = ImageReader.newInstance(sizeB.width, sizeB.height, ImageFormat.JPEG, 3)
        readerA.setOnImageAvailableListener({ reader -> drain(reader, isA = true) }, backgroundHandler)
        readerB.setOnImageAvailableListener({ reader -> drain(reader, isA = false) }, backgroundHandler)
    }

    private fun drain(reader: ImageReader, isA: Boolean) {
        // acquireNextImage, not acquireLatestImage: during a burst several JPEGs can be queued
        // and every one of them is needed.
        while (true) {
            val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
            try {
                val target = pending ?: continue
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                target.add(isA, bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed reading image", e)
            } finally {
                runCatching { image.close() }
            }
        }
    }

    private fun jpegSize(cameraId: String, is4K: Boolean): Size {
        val sizes = runCatching {
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
        }.getOrNull()

        if (sizes.isNullOrEmpty()) {
            return if (is4K) Size(4032, 3024) else Size(1440, 1080)
        }

        val fourByThree = sizes.filter { size ->
            val aspect = size.width.toFloat() / size.height.toFloat()
            Math.abs(aspect - 4f / 3f) < 0.1f || Math.abs(aspect - 3f / 4f) < 0.1f
        }
        val candidates = fourByThree.ifEmpty { sizes.toList() }

        return if (is4K) {
            candidates.maxByOrNull { it.width * it.height }!!
        } else {
            candidates.filter { it.width <= 1920 && it.height <= 1920 }
                .maxByOrNull { it.width * it.height }
                ?: candidates.minByOrNull { it.width * it.height }!!
        }
    }

    /**
     * Opens the camera and configures the streaming session. Returns false on any failure
     * (camera busy, session rejected, timeout) so the caller can fall back immediately.
     */
    @SuppressLint("MissingPermission")
    suspend fun open(surfaceA: Surface, surfaceB: Surface): Boolean {
        if (closed) return false

        val camera = withTimeoutOrNull(OPEN_TIMEOUT_MS) { openDevice() }
        if (camera == null) {
            Log.e(TAG, "Camera $logicalCameraId did not open in time")
            return false
        }
        if (closed) {
            runCatching { camera.close() }
            return false
        }
        device = camera

        val configured = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            createSession(camera, surfaceA, surfaceB)
        }
        if (configured == null) {
            Log.e(TAG, "Capture session for $logicalCameraId was not configured")
            return false
        }
        if (closed) {
            runCatching { configured.close() }
            return false
        }
        session = configured

        return startPreview(camera, configured, surfaceA, surfaceB)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(): CameraDevice? = suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        fun finish(result: CameraDevice?) {
            if (!resumed.compareAndSet(false, true)) return
            // The open timeout may have elapsed already; the device would leak otherwise.
            if (!continuation.isActive) {
                result?.let { runCatching { it.close() } }
                return
            }
            continuation.resume(result)
        }
        try {
            cameraManager.openCamera(
                logicalCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (resumed.get() || closed) {
                            runCatching { camera.close() }
                            finish(null)
                        } else {
                            finish(camera)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Camera $logicalCameraId disconnected")
                        runCatching { camera.close() }
                        if (device === camera) device = null
                        finish(null)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera $logicalCameraId error $error")
                        runCatching { camera.close() }
                        if (device === camera) device = null
                        finish(null)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "openCamera($logicalCameraId) threw", e)
            finish(null)
        }
    }

    private suspend fun createSession(
        camera: CameraDevice,
        surfaceA: Surface,
        surfaceB: Surface
    ): CameraCaptureSession? = suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        fun finish(result: CameraCaptureSession?) {
            if (!resumed.compareAndSet(false, true)) return
            if (!continuation.isActive) {
                result?.let { runCatching { it.close() } }
                return
            }
            continuation.resume(result)
        }
        try {
            val outputs = listOf(
                outputConfig(surfaceA, physicalIdA),
                outputConfig(surfaceB, physicalIdB),
                outputConfig(readerA.surface, physicalIdA),
                outputConfig(readerB.surface, physicalIdB)
            )
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                { runnable -> backgroundHandler.post(runnable) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        finish(configured)
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        Log.e(TAG, "Session configuration failed for $logicalCameraId")
                        runCatching { configured.close() }
                        finish(null)
                    }
                }
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession threw", e)
            finish(null)
        }
    }

    /** Exposure and focus of the latest preview frame of the primary lens, for the live tips. */
    @Volatile
    var latestPreview: PreviewStats? = null
        private set

    /** True if LENS_FOCUS_DISTANCE of the primary lens is in real diopters. */
    val focusDistanceCalibrated: Boolean = runCatching {
        cameraManager.getCameraCharacteristics(physicalIdA ?: logicalCameraId)
            .get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
    }.getOrNull().let { it != null && it != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val primary = physicalIdA?.let { id -> physicalResult(result, id) } ?: result
            latestPreview = PreviewStats(
                exposureNs = primary.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = primary.get(CaptureResult.SENSOR_SENSITIVITY) ?: result.get(CaptureResult.SENSOR_SENSITIVITY),
                focusDiopters = (primary.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: result.get(CaptureResult.LENS_FOCUS_DISTANCE))
                    ?.takeIf { focusDistanceCalibrated },
                timestampMs = android.os.SystemClock.elapsedRealtime()
            )
        }
    }

    private fun physicalResult(result: TotalCaptureResult, id: String): CaptureResult? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.physicalCameraTotalResults[id]
        } else {
            @Suppress("DEPRECATION")
            result.physicalCameraResults[id]
        }

    private fun outputConfig(surface: Surface, physicalId: String?): OutputConfiguration =
        OutputConfiguration(surface).apply {
            if (physicalId != null && physicalId != logicalCameraId) {
                setPhysicalCameraId(physicalId)
            }
        }

    private fun startPreview(
        camera: CameraDevice,
        captureSession: CameraCaptureSession,
        surfaceA: Surface,
        surfaceB: Surface
    ): Boolean = try {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surfaceA)
            addTarget(surfaceB)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoomRatio)
            }
        }
        captureSession.setRepeatingRequest(builder.build(), previewCallback, backgroundHandler)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not start preview", e)
        false
    }

    /** One synchronised still capture: both JPEGs plus the capture result of each lens. */
    class DualCapture(
        val jpegA: ByteArray,
        val jpegB: ByteArray,
        val metadataA: FrameMetadata?,
        val metadataB: FrameMetadata?
    )

    /** Sensor orientation of a physical camera, falling back to the logical one. */
    fun sensorOrientationOf(physicalId: String?): Int {
        if (physicalId == null) return sensorOrientation
        return runCatching {
            cameraManager.getCameraCharacteristics(physicalId).get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: sensorOrientation
    }

    /**
     * Triggers one synchronised still capture. Returns null on failure or timeout; it never
     * suspends indefinitely.
     */
    suspend fun capture(): DualCapture? = captureBurst(1)?.firstOrNull()

    /**
     * Takes [count] synchronised still pairs back to back (night mode). Exposure and white
     * balance are locked for the burst so all frames can be averaged. [onProgress] reports
     * how many complete pairs have arrived. Returns null on failure or timeout.
     */
    suspend fun captureBurst(count: Int, onProgress: (Int) -> Unit = {}): List<DualCapture>? {
        if (closed || count < 1) return null
        val camera = device ?: return null
        val captureSession = session ?: return null

        val request = PendingCapture(count, onProgress)
        pending = request
        val results = arrayOfNulls<TotalCaptureResult>(count)
        try {
            val requests = (0 until count).map { index ->
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(readerA.surface)
                    addTarget(readerB.surface)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    if (count > 1) {
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                        set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoomRatio)
                    }
                    setTag(index)
                }.build()
            }
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
                    (r.tag as? Int)?.let { if (it in 0 until count) results[it] = total }
                }

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Still capture ${r.tag} failed, reason ${failure.reason}")
                    request.done.completeExceptionally(IllegalStateException("capture failed"))
                }
            }
            if (count == 1) {
                captureSession.capture(requests.first(), callback, backgroundHandler)
            } else {
                captureSession.captureBurst(requests, callback, backgroundHandler)
            }

            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS + (count - 1) * BURST_FRAME_TIMEOUT_MS) {
                request.done.await()
                // Results normally arrive before the JPEGs; never wait long for them.
                withTimeoutOrNull(RESULT_GRACE_MS) { while (results.any { it == null }) delay(20) }
                synchronized(request) {
                    (0 until count).map { i ->
                        val total = results[i]
                        DualCapture(
                            request.a[i],
                            request.b[i],
                            total?.let { metadataFor(it, physicalIdA) },
                            total?.let { metadataFor(it, physicalIdB) }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Still capture threw", e)
            return null
        } finally {
            pending = null
        }
    }

    private fun metadataFor(total: TotalCaptureResult, physicalId: String?): FrameMetadata? = runCatching {
        if (physicalId == null || physicalId == logicalCameraId) {
            return@runCatching LensMetadata.fromResult(logicalCameraId, total)
        }
        val physical: CaptureResult? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            total.physicalCameraTotalResults[physicalId]
        } else {
            @Suppress("DEPRECATION")
            total.physicalCameraResults[physicalId]
        }
        physical?.let { LensMetadata.fromResult(physicalId, it) }
    }.onFailure { Log.w(TAG, "No physical result for $physicalId", it) }.getOrNull()

    fun close() {
        if (closed) return
        closed = true
        pending?.done?.cancel()
        pending = null
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { readerA.setOnImageAvailableListener(null, null) }
        runCatching { readerB.setOnImageAvailableListener(null, null) }
        runCatching { readerA.close() }
        runCatching { readerB.close() }
        backgroundThread.quitSafely()
    }
}
