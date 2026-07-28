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
import kotlinx.coroutines.CompletableDeferred
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

    private class PendingCapture {
        val a = CompletableDeferred<ByteArray>()
        val b = CompletableDeferred<ByteArray>()
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

        readerA = ImageReader.newInstance(sizeA.width, sizeA.height, ImageFormat.JPEG, 2)
        readerB = ImageReader.newInstance(sizeB.width, sizeB.height, ImageFormat.JPEG, 2)
        readerA.setOnImageAvailableListener({ reader -> drain(reader, isA = true) }, backgroundHandler)
        readerB.setOnImageAvailableListener({ reader -> drain(reader, isA = false) }, backgroundHandler)
    }

    private fun drain(reader: ImageReader, isA: Boolean) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val target = pending ?: return
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            if (isA) target.a.complete(bytes) else target.b.complete(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading image", e)
        } finally {
            runCatching { image.close() }
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
        captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not start preview", e)
        false
    }

    /**
     * Triggers one synchronised still capture. Returns null on failure or timeout; it never
     * suspends indefinitely.
     */
    suspend fun capture(): Pair<ByteArray, ByteArray>? {
        if (closed) return null
        val camera = device ?: return null
        val captureSession = session ?: return null

        val request = PendingCapture()
        pending = request
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerA.surface)
                addTarget(readerB.surface)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoomRatio)
                }
            }
            captureSession.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Still capture failed, reason ${failure.reason}")
                        request.a.completeExceptionally(IllegalStateException("capture failed"))
                        request.b.completeExceptionally(IllegalStateException("capture failed"))
                    }
                },
                backgroundHandler
            )

            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                val a = request.a.await()
                val b = request.b.await()
                a to b
            }
        } catch (e: Exception) {
            Log.e(TAG, "Still capture threw", e)
            return null
        } finally {
            pending = null
        }
    }

    fun close() {
        if (closed) return
        closed = true
        pending?.let {
            it.a.cancel()
            it.b.cancel()
        }
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
