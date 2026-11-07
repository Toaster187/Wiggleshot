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
import androidx.annotation.RequiresApi
import com.example.align.FrameMetadata
import com.example.align.LensGeometry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Experiment that reads the lens matching out of the camera HAL.
 *
 * The Pixel's own camera pipeline switches from the ultra wide to the main lens at 1.0x and
 * has to make the ultra wide frame at 0.99x look exactly like the main frame at 1.0x — that
 * is the matching we want. Third party apps get the same behaviour from the logical camera:
 * setting `CONTROL_ZOOM_RATIO` below 1.0 makes the HAL crop and warp the ultra wide itself.
 *
 * The probe captures the logical stream at several zoom ratios. If the device allows it, the
 * raw streams of the physical lenses are captured **in the same request**, so the logical
 * frame and the raw ultra wide frame show the identical instant. Aligning the two yields the
 * HAL's ultra wide -> 1x transform without any hand shake in between.
 *
 * Results are recorded together with `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` and (Android
 * 15+) `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION`, the sensor area the HAL
 * actually read out.
 */
@RequiresApi(Build.VERSION_CODES.R)
class HalZoomProbe(
    context: Context,
    private val logicalId: String,
    private val physicalIds: List<String>
) {
    companion object {
        private const val TAG = "HalZoomProbe"
        private const val OPEN_TIMEOUT_MS = 4_000L
        private const val SHOT_TIMEOUT_MS = 5_000L
        private const val SETTLE_MS = 1_100L
        private const val MAX_JPEG_WIDTH = 2048
        val DEFAULT_ZOOMS = listOf(0.6f, 0.8f, 0.9f, 0.95f, 0.99f, 1.0f, 1.01f, 1.2f)
    }

    class Shot(
        val requestedZoom: Float,
        val logicalJpeg: ByteArray?,
        val physicalJpegs: Map<String, ByteArray>,
        val logical: FrameMetadata?,
        val physical: Map<String, FrameMetadata>,
        /** Android 15+: sensor region of the active physical camera, in its own coordinates. */
        val activePhysicalSensorCrop: LensGeometry.IntRect?
    )

    class Outcome(
        val shots: List<Shot>,
        val withPhysicalStreams: Boolean,
        val sensorOrientation: Int,
        val physicalOrientations: Map<String, Int>,
        val logicalJpegSize: Size,
        val physicalJpegSizes: Map<String, Size>,
        val error: String?,
        /** Step by step protocol, written to hal_probe.json. */
        val log: List<String> = emptyList()
    )

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("HalZoomProbe").apply { start() }
    private val handler = Handler(thread.looper)

    private class Pending(ids: List<String>) {
        val logical = CompletableDeferred<ByteArray>()
        val physical = ids.associateWith { CompletableDeferred<ByteArray>() }
    }

    @Volatile
    private var pending: Pending? = null

    suspend fun run(zooms: List<Float> = DEFAULT_ZOOMS, onProgress: (String) -> Unit = {}): Outcome {
        val chars = cameraManager.getCameraCharacteristics(logicalId)
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val physicalOrientations = physicalIds.associateWith { id ->
            runCatching { cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) }
                .getOrNull() ?: orientation
        }
        val logicalSize = jpegSize(logicalId)
        val physicalSizes = physicalIds.associateWith { jpegSize(it) }

        val logicalReader = ImageReader.newInstance(logicalSize.width, logicalSize.height, ImageFormat.JPEG, 2)
        val previewReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
        val physicalReaders = physicalSizes.mapValues { (_, size) ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        }
        previewReader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)
        logicalReader.setOnImageAvailableListener({ r -> deliver(r) { p, bytes -> p.logical.complete(bytes) } }, handler)
        physicalReaders.forEach { (id, reader) ->
            reader.setOnImageAvailableListener({ r -> deliver(r) { p, bytes -> p.physical[id]?.complete(bytes) } }, handler)
        }

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            onProgress("Öffne Kamera $logicalId …")
            device = openWithRetry() ?: return Outcome(
                emptyList(), false, orientation, physicalOrientations, logicalSize, physicalSizes,
                "Kamera ließ sich nicht öffnen (von anderer App belegt?)", listOf("open failed")
            )

            var withPhysical = physicalReaders.isNotEmpty()
            session = if (withPhysical) createSession(device, logicalReader, previewReader, physicalReaders) else null
            if (session == null) {
                withPhysical = false
                Log.w(TAG, "Logical + physical streams not supported together, probing logical stream only")
                session = createSession(device, logicalReader, previewReader, emptyMap())
            }
            if (session == null) {
                return Outcome(
                    emptyList(), false, orientation, physicalOrientations, logicalSize, physicalSizes,
                    "Session nicht konfigurierbar", listOf("session configuration failed")
                )
            }

            log.add("session: " + if (withPhysical) "logical + physical streams" else "logical stream only")

            suspend fun takeShot(target: CameraCaptureSession, zoom: Float, physical: Boolean): Shot? {
                val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewReader.surface)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                target.setRepeatingRequest(preview, null, handler)
                delay(SETTLE_MS)

                val p = Pending(if (physical) physicalIds else emptyList())
                pending = p
                val total = CompletableDeferred<TotalCaptureResult?>()
                val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(logicalReader.surface)
                    if (physical) physicalReaders.values.forEach { addTarget(it.surface) }
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                target.capture(still, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        total.complete(result)
                    }

                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        log.add("zoom $zoom: capture failed, reason ${failure.reason}")
                        total.complete(null)
                    }
                }, handler)

                try {
                    return withTimeoutOrNull<Shot?>(SHOT_TIMEOUT_MS) {
                        val result = total.await()
                        val logicalBytes = withTimeoutOrNull(SHOT_TIMEOUT_MS) { p.logical.await() }
                        val physicalBytes = p.physical.mapNotNull { (id, d) ->
                            withTimeoutOrNull(1_500) { d.await() }?.let { id to it }
                        }.toMap()
                        if (logicalBytes == null) {
                            log.add("zoom $zoom: no logical JPEG (result " + (if (result == null) "missing" else "ok") + ")")
                            return@withTimeoutOrNull null
                        }
                        if (physical && physicalBytes.size < physicalIds.size) {
                            log.add("zoom $zoom: only ${physicalBytes.size}/${physicalIds.size} physical JPEGs")
                        }
                        Shot(
                            requestedZoom = zoom,
                            logicalJpeg = logicalBytes,
                            physicalJpegs = physicalBytes,
                            logical = result?.let { LensMetadata.fromResult(logicalId, it) },
                            physical = result?.let { physicalMetadata(it) }.orEmpty(),
                            activePhysicalSensorCrop = result?.let { activeCrop(it) }
                        )
                    } ?: run<Shot?> {
                        log.add("zoom $zoom: timeout")
                        null
                    }
                } finally {
                    pending = null
                }
            }

            val shots = mutableListOf<Shot>()
            for ((index, zoom) in zooms.withIndex()) {
                onProgress("Zoom ${String.format(Locale.US, "%.2f", zoom)}× (${index + 1}/${zooms.size}) …")
                var shot = takeShot(session!!, zoom, withPhysical)
                if (withPhysical && (shot == null || shot.physicalJpegs.size < physicalIds.size)) {
                    // Some HALs configure logical + physical streams but cannot fill them in one
                    // request. Continue with the logical stream alone instead of giving up.
                    log.add("falling back to logical stream only")
                    withPhysical = false
                    runCatching { session.close() }
                    session = createSession(device, logicalReader, previewReader, emptyMap())
                    if (session == null) {
                        log.add("logical-only session could not be configured")
                        break
                    }
                    shot = takeShot(session, zoom, false)
                }
                if (shot != null) shots += shot
            }
            return Outcome(
                shots, withPhysical, orientation, physicalOrientations, logicalSize, physicalSizes,
                if (shots.isEmpty()) log.lastOrNull() ?: "keine Aufnahme" else null,
                log
            )
        } catch (e: Exception) {
            Log.e(TAG, "Probe failed", e)
            log.add("exception: $e")
            return Outcome(emptyList(), false, orientation, physicalOrientations, logicalSize, physicalSizes, e.message ?: e.javaClass.simpleName, log.toList())
        } finally {
            pending = null
            runCatching { session?.close() }
            runCatching { device?.close() }
            listOf(logicalReader, previewReader).plus(physicalReaders.values).forEach { runCatching { it.close() } }
            thread.quitSafely()
        }
    }

    private fun deliver(reader: ImageReader, sink: (Pending, ByteArray) -> Unit) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val target = pending ?: return
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            sink(target, bytes)
        } finally {
            runCatching { image.close() }
        }
    }

    private fun physicalMetadata(total: TotalCaptureResult): Map<String, FrameMetadata> {
        val results: Map<String, CaptureResult> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            total.physicalCameraTotalResults
        } else {
            @Suppress("DEPRECATION")
            total.physicalCameraResults
        }
        return results.mapValues { (id, r) -> LensMetadata.fromResult(id, r) }
    }

    private fun activeCrop(total: TotalCaptureResult): LensGeometry.IntRect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val rect = total.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION) ?: return null
        return LensGeometry.IntRect(rect.left, rect.top, rect.width(), rect.height())
    }

    private fun jpegSize(cameraId: String): Size {
        val sizes = runCatching {
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
        }.getOrNull().orEmpty()
        return sizes
            .filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.05f && it.width <= MAX_JPEG_WIDTH }
            .maxByOrNull { it.width * it.height }
            ?: Size(1600, 1200)
    }

    /** The camera screen may still be releasing the device; give it a few attempts. */
    private suspend fun openWithRetry(): CameraDevice? {
        repeat(4) { attempt ->
            withTimeoutOrNull(OPEN_TIMEOUT_MS) { open() }?.let { return it }
            Log.w(TAG, "Open attempt ${attempt + 1} failed")
            delay(600)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(): CameraDevice? = suspendCancellableCoroutine { continuation ->
        val done = AtomicBoolean(false)
        fun finish(device: CameraDevice?) {
            if (!done.compareAndSet(false, true)) return
            if (!continuation.isActive) {
                device?.let { runCatching { it.close() } }
                return
            }
            continuation.resume(device)
        }
        try {
            cameraManager.openCamera(logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = finish(camera)
                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    finish(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera error $error")
                    runCatching { camera.close() }
                    finish(null)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera threw", e)
            finish(null)
        }
    }

    private suspend fun createSession(
        device: CameraDevice,
        logicalReader: ImageReader,
        previewReader: ImageReader,
        physicalReaders: Map<String, ImageReader>
    ): CameraCaptureSession? = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val done = AtomicBoolean(false)
            fun finish(session: CameraCaptureSession?) {
                if (!done.compareAndSet(false, true)) return
                if (!continuation.isActive) {
                    session?.let { runCatching { it.close() } }
                    return
                }
                continuation.resume(session)
            }
            try {
                val outputs = mutableListOf(OutputConfiguration(previewReader.surface), OutputConfiguration(logicalReader.surface))
                physicalReaders.forEach { (id, reader) ->
                    outputs += OutputConfiguration(reader.surface).apply { setPhysicalCameraId(id) }
                }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    { runnable -> handler.post(runnable) },
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) = finish(session)
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            runCatching { session.close() }
                            finish(null)
                        }
                    }
                )
                device.createCaptureSession(config)
            } catch (e: Exception) {
                Log.w(TAG, "createCaptureSession threw", e)
                finish(null)
            }
        }
    }
}
