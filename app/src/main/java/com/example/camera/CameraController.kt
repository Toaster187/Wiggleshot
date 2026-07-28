package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns every piece of camera hardware state.
 *
 * All camera setup used to live inside a ~200 line `DisposableEffect` in the camera screen,
 * driven by five Compose state variables that could change in any order. That made start-up
 * order dependent and regularly left the app in a state where the shutter did nothing.
 *
 * This class replaces that with three guarantees:
 *
 *  1. **One owner, one lock.** Configuration and capture are serialised through a single
 *     [Mutex], so a reconfiguration can never interleave with a capture.
 *  2. **Bounded operations.** Every hardware wait has a timeout. Nothing can hang forever,
 *     so [busy] always returns to false and the shutter always becomes usable again.
 *  3. **A capture always has a path.** If the simultaneous modes are not available or fail,
 *     [capture] transparently falls back to sequential single-lens captures, which only need
 *     CameraX and work from a cold start.
 *
 * Instances must be driven from the main thread; [start] and [capture] are main-safe.
 */
class CameraController(context: Context) {

    companion object {
        private const val TAG = "CameraController"
        private const val SURFACE_TIMEOUT_MS = 3_000L

        /**
         * Budget for one capture. Sequential captures rebind the camera per lens, so the
         * budget grows with the number of lenses instead of using one fixed value that either
         * aborts legitimate four lens captures or leaves the shutter blocked for too long.
         */
        private const val CAPTURE_TIMEOUT_BASE_MS = 8_000L
        private const val CAPTURE_TIMEOUT_PER_LENS_MS = 5_000L

        /**
         * CameraX closes the camera device asynchronously after `unbindAll`. Opening the same
         * hardware with Camera2 right away is rejected with CAMERA_IN_USE, which used to leave
         * the app with a dead shutter after switching lens modes.
         */
        private const val CAMERAX_RELEASE_DELAY_MS = 350L

        private const val PREVIEW_SETTLE_MS = 220L
        private const val SEQUENTIAL_SETTLE_MS = 140L
        private const val CALIBRATION_PREVIEW_SIZE = 640
    }

    enum class Mode {
        /** Nothing bound yet. */
        NONE,

        /** Two physical sub-cameras of one logical camera, captured in a single request. */
        DUAL_PHYSICAL,

        /** Two independent logical cameras bound through CameraX at the same time. */
        CAMERAX_PAIR,

        /** One live lens; additional frames are captured one after another. */
        SEQUENTIAL
    }

    data class Request(
        val primary: CameraLensDetails,
        val secondaries: List<CameraLensDetails>,
        val is4K: Boolean,
        val concurrentPreviewSupported: Boolean
    ) {
        val lenses: List<CameraLensDetails> = (listOf(primary) + secondaries).distinctBy { it.id }
    }

    data class Status(
        val mode: Mode = Mode.NONE,
        val ready: Boolean = false,
        val busy: Boolean = false
    ) {
        /** True while the Camera2 texture views hold the live preview. */
        val usesTextureViews: Boolean get() = mode == Mode.DUAL_PHYSICAL

        /** True when both frames of a wiggle are captured at the exact same instant. */
        val simultaneous: Boolean get() = ready && (mode == Mode.DUAL_PHYSICAL || mode == Mode.CAMERAX_PAIR)
    }

    /** The four views the camera can render into. Supplied once by the camera screen. */
    class Views(
        val textureA: ZoomableTextureView,
        val textureB: ZoomableTextureView,
        val previewA: PreviewView,
        val previewB: PreviewView
    )

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * Decoding a 4K JPEG takes well over a frame budget, so capture callbacks are delivered
     * off the main thread instead of on `ContextCompat.getMainExecutor`.
     */
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WiggleImageDecode")
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _countdown = MutableStateFlow<Int?>(null)

    /** Non-null while the "hold still" countdown before a sequential capture is running. */
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()

    /** Serialises configuration against capture. */
    private val lock = Mutex()

    private val reconfigureRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var views: Views? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var request: Request? = null

    private var provider: ProcessCameraProvider? = null
    private var dualSession: DualPhysicalSession? = null
    private var dualSurfaces: List<Surface> = emptyList()

    private var cameraXBound = false
    private var captureA: ImageCapture? = null
    private var captureB: ImageCapture? = null
    private var controlA: CameraControl? = null
    private var controlB: CameraControl? = null

    fun attach(views: Views, lifecycleOwner: LifecycleOwner) {
        this.views = views
        this.lifecycleOwner = lifecycleOwner
    }

    /**
     * Runs the camera for as long as the caller's coroutine is alive. Cancel it (e.g. when
     * the screen is no longer resumed) and every resource is released again.
     */
    suspend fun start(request: Request) {
        try {
            this.request = request
            configure(request)
            reconfigureRequests.collect { configure(request) }
        } finally {
            withContext(NonCancellable) { lock.withLock { teardown() } }
        }
    }

    /** Releases everything immediately; used when the camera screen leaves composition. */
    fun release() {
        teardown()
        views = null
        lifecycleOwner = null
        request = null
        imageExecutor.shutdown()
    }

    // ---------------------------------------------------------------- configuration

    private suspend fun configure(request: Request) = lock.withLock {
        _status.value = _status.value.copy(mode = Mode.NONE, ready = false)
        val hadCameraXBindings = teardown()

        val views = views ?: return@withLock
        val owner = lifecycleOwner ?: return@withLock
        val secondary = request.secondaries.singleOrNull()

        val started = when {
            secondary != null && canUseDualPhysical(request.primary, secondary) -> {
                if (hadCameraXBindings) delay(CAMERAX_RELEASE_DELAY_MS)
                startDualPhysical(request, secondary, views)
            }

            secondary != null && canUseCameraXPair(request, secondary) ->
                startCameraXPair(request, secondary, views, owner)

            else -> false
        }

        if (!started) {
            startSequential(request, views, owner)
        }
    }

    private fun canUseDualPhysical(primary: CameraLensDetails, secondary: CameraLensDetails): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            primary.logicalId == secondary.logicalId &&
            primary.id != secondary.id

    private fun canUseCameraXPair(request: Request, secondary: CameraLensDetails): Boolean =
        request.concurrentPreviewSupported && request.primary.logicalId != secondary.logicalId

    private suspend fun startDualPhysical(
        request: Request,
        secondary: CameraLensDetails,
        views: Views
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false

        val session = DualPhysicalSession(
            context = appContext,
            logicalCameraId = request.primary.logicalId,
            physicalIdA = request.primary.id,
            physicalIdB = secondary.id,
            is4K = request.is4K
        )

        val bufferSize = previewBufferSize(request.primary.logicalId)
        listOf(views.textureA, views.textureB).forEach { view ->
            view.sensorOrientation = session.sensorOrientation
            view.bufferWidth = bufferSize.width.toFloat()
            view.bufferHeight = bufferSize.height.toFloat()
        }

        val textureA = views.textureA.awaitSurfaceTexture(bufferSize.width, bufferSize.height, SURFACE_TIMEOUT_MS)
        val textureB = views.textureB.awaitSurfaceTexture(bufferSize.width, bufferSize.height, SURFACE_TIMEOUT_MS)
        if (textureA == null || textureB == null) {
            Log.w(TAG, "Preview surfaces not available, falling back")
            session.close()
            return false
        }

        val surfaces = listOf(Surface(textureA), Surface(textureB))
        if (!session.open(surfaces[0], surfaces[1])) {
            session.close()
            surfaces.forEach { runCatching { it.release() } }
            return false
        }

        dualSession = session
        dualSurfaces = surfaces
        _status.value = _status.value.copy(mode = Mode.DUAL_PHYSICAL, ready = true)
        Log.d(TAG, "Dual physical session ready (${request.primary.id} + ${secondary.id})")
        return true
    }

    private suspend fun startCameraXPair(
        request: Request,
        secondary: CameraLensDetails,
        views: Views,
        owner: LifecycleOwner
    ): Boolean {
        val provider = obtainProvider() ?: return false
        provider.unbindAll()

        val boundA = bindPreviewAndCapture(provider, owner, request.primary, request.is4K, views.previewA)
        if (boundA == null) return false
        captureA = boundA.capture
        controlA = boundA.control

        val boundB = bindPreviewAndCapture(provider, owner, secondary, request.is4K, views.previewB)
        if (boundB == null) {
            Log.w(TAG, "Second camera could not be bound, using sequential capture")
            return false
        }
        captureB = boundB.capture
        controlB = boundB.control

        _status.value = _status.value.copy(mode = Mode.CAMERAX_PAIR, ready = true)
        Log.d(TAG, "CameraX pair ready (${request.primary.id} + ${secondary.id})")
        return true
    }

    private suspend fun startSequential(request: Request, views: Views, owner: LifecycleOwner) {
        val provider = obtainProvider()
        if (provider == null) {
            _status.value = _status.value.copy(mode = Mode.SEQUENTIAL, ready = false)
            return
        }
        provider.unbindAll()
        captureA = null
        captureB = null
        controlA = null
        controlB = null

        val bound = bindPreviewAndCapture(provider, owner, request.primary, request.is4K, views.previewA)
        captureA = bound?.capture
        controlA = bound?.control
        _status.value = _status.value.copy(mode = Mode.SEQUENTIAL, ready = bound != null)
        Log.d(TAG, "Sequential mode ready=${bound != null} (${request.primary.id})")
    }

    private class Bound(val capture: ImageCapture, val control: CameraControl)

    private fun bindPreviewAndCapture(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        lens: CameraLensDetails,
        is4K: Boolean,
        previewView: PreviewView
    ): Bound? = try {
        val preview = previewFor(lens)
        val imageCapture = imageCaptureFor(lens, is4K)
        val camera = provider.bindToLifecycle(
            owner,
            findCameraSelector(provider, lens.logicalId),
            preview,
            imageCapture
        )
        preview.setSurfaceProvider(previewView.surfaceProvider)
        cameraXBound = true
        Bound(imageCapture, camera.cameraControl)
    } catch (e: Exception) {
        Log.e(TAG, "Could not bind lens ${lens.id}", e)
        null
    }

    private suspend fun obtainProvider(): ProcessCameraProvider? =
        provider ?: runCatching { awaitCameraProvider(appContext) }
            .onFailure { Log.e(TAG, "CameraX provider unavailable", it) }
            .getOrNull()
            ?.also { provider = it }

    private fun previewBufferSize(logicalId: String): Size {
        val sizes = runCatching {
            cameraManager.getCameraCharacteristics(logicalId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
        }.getOrNull()

        return sizes
            ?.filter { Math.abs(it.width.toFloat() / it.height - 4f / 3f) < 0.1f }
            ?.filter { it.width <= 1920 }
            ?.maxByOrNull { it.width * it.height }
            ?: Size(1440, 1080)
    }

    /** Releases every camera resource. Returns true if CameraX use cases were bound. */
    private fun teardown(): Boolean {
        dualSession?.close()
        dualSession = null
        dualSurfaces.forEach { runCatching { it.release() } }
        dualSurfaces = emptyList()
        runCatching { provider?.unbindAll() }
        captureA = null
        captureB = null
        controlA = null
        controlB = null
        val hadBindings = cameraXBound
        cameraXBound = false
        return hadBindings
    }

    // --------------------------------------------------------------------- zoom

    /** Applies the optical zoom of the CameraX paths. Camera2 zoom is applied when cropping. */
    suspend fun applyZoom(primaryZoom: Float, secondaryZoom: Float) {
        runCatching { controlA?.setZoomRatio(primaryZoom)?.awaitResult() }
        runCatching { controlB?.setZoomRatio(secondaryZoom)?.awaitResult() }
    }

    // ------------------------------------------------------------------ capturing

    /**
     * Captures one wiggle and returns the frames in lens order.
     *
     * Returns an empty list when the capture produced nothing, and null when the request was
     * ignored because another capture or a reconfiguration is running. The whole operation is
     * time boxed, so the shutter can never stay stuck.
     */
    suspend fun capture(request: Request, zoomFor: (CameraLensDetails) -> Float): List<Bitmap>? {
        if (!lock.tryLock()) {
            Log.d(TAG, "Capture ignored, camera busy")
            return null
        }
        var needsReconfigure = false
        return try {
            _status.value = _status.value.copy(busy = true)
            withTimeoutOrNull(CAPTURE_TIMEOUT_BASE_MS + request.lenses.size * CAPTURE_TIMEOUT_PER_LENS_MS) {
                captureDual(request, zoomFor)
                    ?: capturePair(request, zoomFor)
                    ?: run {
                        needsReconfigure = true
                        captureSequential(request, zoomFor)
                    }
            }.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            emptyList()
        } finally {
            _countdown.value = null
            _status.value = _status.value.copy(busy = false)
            lock.unlock()
            // Sequential capture unbinds every use case; rebuild the live preview.
            if (needsReconfigure) reconfigureRequests.tryEmit(Unit)
        }
    }

    private suspend fun captureDual(
        request: Request,
        zoomFor: (CameraLensDetails) -> Float
    ): List<Bitmap>? {
        val session = dualSession ?: return null
        val secondary = request.secondaries.singleOrNull() ?: return null
        val pair = session.capture() ?: run {
            Log.w(TAG, "Dual capture returned nothing, falling back")
            return null
        }
        val rotation = session.sensorOrientation
        val frames = withContext(Dispatchers.Default) {
            listOfNotNull(
                CameraImages.fromJpeg(pair.first, rotation, zoomFor(request.primary)),
                CameraImages.fromJpeg(pair.second, rotation, zoomFor(secondary))
            )
        }
        return frames.takeIf { it.size == 2 }
    }

    private suspend fun capturePair(
        request: Request,
        zoomFor: (CameraLensDetails) -> Float
    ): List<Bitmap>? {
        val a = captureA ?: return null
        val b = captureB ?: return null
        val secondary = request.secondaries.singleOrNull() ?: return null

        applyZoom(zoomFor(request.primary), zoomFor(secondary))

        val frames = coroutineScope {
            listOf(
                async { a.captureBitmap(imageExecutor) },
                async { b.captureBitmap(imageExecutor) }
            ).awaitAll()
        }
        return frames.filterNotNull().takeIf { it.size == 2 }
    }

    private suspend fun captureSequential(
        request: Request,
        zoomFor: (CameraLensDetails) -> Float
    ): List<Bitmap> {
        runCountdown()

        val frames = mutableListOf<Bitmap>()
        request.lenses.forEachIndexed { index, lens ->
            val zoom = zoomFor(lens)
            val bitmap = if (index == 0) {
                captureFromLiveCamera(lens, zoom) ?: captureStillFrame(lens, zoom, request.is4K, PREVIEW_SETTLE_MS)
            } else {
                captureStillFrame(lens, zoom, request.is4K, SEQUENTIAL_SETTLE_MS)
            }
            if (bitmap != null) {
                frames += bitmap
            } else {
                Log.e(TAG, "No frame captured for lens ${lens.id}")
            }
        }
        return frames
    }

    /** Fast path: the primary lens is already streaming, so no rebind is needed. */
    private suspend fun captureFromLiveCamera(lens: CameraLensDetails, zoom: Float): Bitmap? {
        val imageCapture = captureA ?: return null
        if (request?.primary?.id != lens.id) return null
        runCatching { controlA?.setZoomRatio(zoom)?.awaitResult() }
        return imageCapture.captureBitmap(imageExecutor)
    }

    /**
     * Binds [lens] on its own, waits for auto exposure and white balance to settle and takes
     * a single frame. Used for the second and further lenses of a sequential capture and for
     * calibration.
     */
    private suspend fun captureStillFrame(
        lens: CameraLensDetails,
        zoom: Float,
        is4K: Boolean,
        settleDelayMs: Long
    ): Bitmap? {
        val owner = lifecycleOwner ?: return null
        val views = views ?: return null
        val provider = obtainProvider() ?: return null

        return try {
            provider.unbindAll()
            captureA = null
            captureB = null
            controlA = null
            controlB = null

            val convergence = CompletableDeferred<Unit>()
            val preview = previewFor(lens, convergence)
            val imageCapture = imageCaptureFor(lens, is4K)
            val camera = provider.bindToLifecycle(
                owner,
                findCameraSelector(provider, lens.logicalId),
                preview,
                imageCapture
            )
            preview.setSurfaceProvider(views.previewA.surfaceProvider)
            cameraXBound = true

            runCatching { camera.cameraControl.setZoomRatio(zoom).awaitResult() }

            if (withTimeoutOrNull(AE_CONVERGENCE_TIMEOUT_MS) { convergence.await() } == null) {
                Log.w(TAG, "3A did not converge for lens ${lens.id}, capturing anyway")
            }
            delay(settleDelayMs)
            imageCapture.captureBitmap(imageExecutor)
        } catch (e: Exception) {
            Log.e(TAG, "Still frame capture failed for lens ${lens.id}", e)
            null
        }
    }

    private suspend fun runCountdown() {
        for (count in 3 downTo 1) {
            _countdown.value = count
            delay(700)
        }
        _countdown.value = 0
        delay(180)
    }

    // ---------------------------------------------------------------- calibration

    /**
     * Grabs one comparison pair for the auto zoom calibration, using whichever source the
     * current mode provides. Returns null when no usable frames are available.
     */
    suspend fun calibrationFrames(
        primary: CameraLensDetails,
        primaryZoom: Float,
        secondary: CameraLensDetails,
        secondaryZoom: Float,
        is4K: Boolean
    ): Pair<Bitmap, Bitmap>? {
        val views = views ?: return null
        val pair = when (_status.value.mode) {
            Mode.DUAL_PHYSICAL -> {
                views.textureA.bitmapOrNull() to views.textureB.bitmapOrNull()
            }

            Mode.CAMERAX_PAIR -> {
                views.previewA.bitmap to views.previewB.bitmap
            }

            else -> lock.withLock {
                captureStillFrame(primary, primaryZoom, is4K, PREVIEW_SETTLE_MS) to
                    captureStillFrame(secondary, secondaryZoom, is4K, PREVIEW_SETTLE_MS)
            }
        }

        val first = pair.first ?: return null
        val second = pair.second ?: return null
        if (first.width < 50 || first.height < 50 || second.width < 50 || second.height < 50) return null
        return first to second
    }

    /** Rebuilds the live preview after calibration destroyed the bindings. */
    fun requestReconfigure() {
        reconfigureRequests.tryEmit(Unit)
    }

    private fun ZoomableTextureView.bitmapOrNull(): Bitmap? =
        if (isAvailable) getBitmap(CALIBRATION_PREVIEW_SIZE, CALIBRATION_PREVIEW_SIZE * 3 / 4) else null
}
