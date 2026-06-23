package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
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

@RequiresApi(Build.VERSION_CODES.P)
class DualCameraManager(
    private val context: Context,
    private val logicalCameraId: String,
    private val physicalIdA: String?,
    private val physicalIdB: String?,
    private val surfaceA: Surface,
    private val surfaceB: Surface,
    private val is4K: Boolean = true,
    private val onDualCapture: (ByteArray, ByteArray) -> Unit,
    private val onCaptureFailed: (() -> Unit)? = null
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val sizeA: Size
    private val sizeB: Size

    private val imageReaderA: ImageReader
    private val imageReaderB: ImageReader

    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)
    private val mainExecutor = context.mainExecutor

    private var bytesA: ByteArray? = null
    private var bytesB: ByteArray? = null
    
    private var zoomA: Float = 1.0f
    private var zoomB: Float = 1.0f

    private var minZoomRatio: Float = 1.0f

    init {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val chars = cameraManager.getCameraCharacteristics(logicalCameraId)
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    minZoomRatio = zoomRange.lower
                    Log.d("DualCameraManager", "Min zoom ratio supported: $minZoomRatio")
                }
            }
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Failed to get zoom range", e)
        }

        sizeA = get43Size(physicalIdA ?: logicalCameraId)
        sizeB = get43Size(physicalIdB ?: logicalCameraId)
        
        Log.d("DualCameraManager", "Initializing ImageReaders with 4:3 sizes: sizeA=${sizeA.width}x${sizeA.height}, sizeB=${sizeB.width}x${sizeB.height}")

        imageReaderA = ImageReader.newInstance(sizeA.width, sizeA.height, ImageFormat.JPEG, 2)
        imageReaderB = ImageReader.newInstance(sizeB.width, sizeB.height, ImageFormat.JPEG, 2)

        imageReaderA.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                bytesA = ByteArray(buffer.capacity()).apply { buffer.get(this) }
                image.close()
                checkCaptureComplete()
            }
        }, backgroundHandler)

        imageReaderB.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                bytesB = ByteArray(buffer.capacity()).apply { buffer.get(this) }
                image.close()
                checkCaptureComplete()
            }
        }, backgroundHandler)
    }

    private fun get43Size(cameraId: String): Size {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (sizes != null && sizes.isNotEmpty()) {
                val fourThirdsSizes = sizes.filter { size ->
                    val aspect = size.width.toFloat() / size.height.toFloat()
                    val diff = Math.abs(aspect - (4.0f / 3.0f))
                    val diffInv = Math.abs(aspect - (3.0f / 4.0f))
                    diff < 0.1 || diffInv < 0.1
                }
                
                val candidates = if (fourThirdsSizes.isNotEmpty()) fourThirdsSizes else sizes.toList()
                
                if (!is4K) {
                    val hdCandidates = candidates.filter { it.width <= 1920 && it.height <= 1920 }
                    if (hdCandidates.isNotEmpty()) {
                        return hdCandidates.maxByOrNull { it.width * it.height }!!
                    }
                    return candidates.minByOrNull { it.width * it.height }!!
                } else {
                    return candidates.maxByOrNull { it.width * it.height }!!
                }
            }
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error finding 4:3 size for camera $cameraId", e)
        }
        return if (is4K) Size(4032, 3024) else Size(1440, 1080)
    }

    private var captureTimeoutRunnable: Runnable? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun checkCaptureComplete() {
        if (bytesA != null && bytesB != null) {
            val a = bytesA!!
            val b = bytesB!!
            bytesA = null
            bytesB = null
            captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            mainExecutor.execute {
                onDualCapture(a, b)
            }
        }
    }

    private var isClosed = false

    @SuppressLint("MissingPermission")
    fun start() {
        isClosed = false
        try {
            cameraManager.openCamera(logicalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (isClosed) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    
                    try {
                        val configSurfaceA = OutputConfiguration(surfaceA)
                        if (physicalIdA != null && physicalIdA != logicalCameraId) {
                            configSurfaceA.setPhysicalCameraId(physicalIdA)
                        }

                        val configSurfaceB = OutputConfiguration(surfaceB)
                        if (physicalIdB != null && physicalIdB != logicalCameraId) {
                            configSurfaceB.setPhysicalCameraId(physicalIdB)
                        }

                        val configReaderA = OutputConfiguration(imageReaderA.surface)
                        if (physicalIdA != null && physicalIdA != logicalCameraId) {
                            configReaderA.setPhysicalCameraId(physicalIdA)
                        }

                        val configReaderB = OutputConfiguration(imageReaderB.surface)
                        if (physicalIdB != null && physicalIdB != logicalCameraId) {
                            configReaderB.setPhysicalCameraId(physicalIdB)
                        }
                        
                        val outputs = listOf(configSurfaceA, configSurfaceB, configReaderA, configReaderB)
                        
                        val sessionConfig = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            outputs,
                            mainExecutor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (isClosed) {
                                        session.close()
                                        return
                                    }
                                    captureSession = session
                                    startPreview()
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e("DualCameraManager", "Failed to configure session")
                                }
                            }
                        )
                        camera.createCaptureSession(sessionConfig)
                    } catch (e: Exception) {
                        Log.e("DualCameraManager", "Error setting up session", e)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { 
                    camera.close() 
                    if (cameraDevice == camera) {
                        cameraDevice = null
                    }
                }
                override fun onError(camera: CameraDevice, error: Int) { 
                    camera.close() 
                    if (cameraDevice == camera) {
                        cameraDevice = null
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error opening camera", e)
        }
    }

    private fun startPreview() {
        if (isClosed) return
        try {
            val device = cameraDevice ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surfaceA)
            builder.addTarget(surfaceB)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoomRatio)
            }
            
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error starting preview", e)
        }
    }

    fun setZoom(zA: Float, zB: Float) {
        zoomA = zA
        zoomB = zB
        // We do not re-apply startPreview here anymore because zoom is only visual/post-processed.
        // The camera streams full FOV constantly.
    }

    fun takePicture() {
        if (isClosed) { onCaptureFailed?.invoke(); return }
        try {
            val device = cameraDevice ?: run { onCaptureFailed?.invoke(); return }
            val session = captureSession ?: run { onCaptureFailed?.invoke(); return }
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReaderA.surface)
            builder.addTarget(imageReaderB.surface)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoomRatio)
            }
            
            // Basic quality settings
            builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            
            captureSession?.capture(builder.build(), null, backgroundHandler)
            
            captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            captureTimeoutRunnable = Runnable {
                if (bytesA == null || bytesB == null) {
                    Log.e("DualCameraManager", "Capture timeout - one or both cameras failed to deliver frames")
                    bytesA = null
                    bytesB = null
                    onCaptureFailed?.invoke()
                }
            }
            mainHandler.postDelayed(captureTimeoutRunnable!!, 3000)
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error taking picture", e)
            onCaptureFailed?.invoke()
        }
    }

    fun stop() {
        isClosed = true
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bytesA = null
        bytesB = null
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReaderA.close()
            imageReaderB.close()
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error stopping camera", e)
        }
        backgroundThread.quitSafely()
    }
}
