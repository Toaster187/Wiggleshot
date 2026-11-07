package com.example.live

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import com.example.align.LensGeometry
import com.example.align.LiveDepth
import com.example.align.Similarity
import com.example.align.toGrayImage
import com.example.camera.CameraController
import com.example.camera.LensMetadata
import com.example.camera.ZoomableTextureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Live tips while framing: about once a second the preview frames of both lenses are read
 * (through the GPU, the camera session is not touched) and the 3D strength is measured on
 * them. No measurement while the phone moves fast (gyroscope) or in battery saver; the
 * cheap tips (orientation, light) keep working.
 */
class LiveAssistant(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _tips = MutableStateFlow<List<LiveGuide.Tip>>(emptyList())
    val tips: StateFlow<List<LiveGuide.Tip>> = _tips.asStateFlow()

    /** Duration of the last measurement, for diagnostics. */
    @Volatile
    var lastMeasurementMs: Long = 0
        private set

    @Volatile private var lastMovingAt = 0L
    @Volatile private var gravityX = 0f
    @Volatile private var gravityY = 9.81f

    fun startSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopSensors() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                if (sqrt(x * x + y * y + z * z) > MOVING_RAD_PER_S) lastMovingAt = SystemClock.elapsedRealtime()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Low pass: gravity only.
                gravityX += (event.values[0] - gravityX) * 0.2f
                gravityY += (event.values[1] - gravityY) * 0.2f
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Runs until cancelled. Must be called on the main thread (the preview frames are read
     * from the TextureViews there); the measurement itself runs in the background.
     */
    suspend fun run(
        controller: CameraController,
        textureA: ZoomableTextureView,
        textureB: ZoomableTextureView,
        primaryId: String,
        secondaryId: String,
        nightMode: () -> Boolean
    ) {
        val geometryP = LensMetadata.geometry(cameraManager, primaryId)
        val geometryS = LensMetadata.geometry(cameraManager, secondaryId)
        val (baselineX, baselineY) = if (geometryP != null && geometryS != null) {
            LensGeometry.baselineInImage(geometryP, geometryS) ?: (1.0 to 0.0)
        } else {
            1.0 to 0.0
        }

        var primaryBitmap: Bitmap? = null
        var secondaryBitmap: Bitmap? = null
        var transform: Similarity? = null
        var fullConfidence = 0.0
        var fastRuns = 0
        var smoothed: Double? = null
        var lowStructure = false
        var lastMeasureAt = 0L
        var interval = MEASURE_INTERVAL_MS

        try {
            while (coroutineContext.isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val status = controller.status.value
                val pause = when {
                    !status.usesTextureViews -> LiveGuide.Pause.UNSUPPORTED
                    powerManager.isPowerSaveMode -> LiveGuide.Pause.POWER_SAVE
                    now - lastMovingAt < MOVING_HOLD_MS -> LiveGuide.Pause.MOVING
                    status.busy -> LiveGuide.Pause.BUSY
                    else -> null
                }
                if (pause == LiveGuide.Pause.MOVING) {
                    // The scene changes while the phone moves; start the average afresh.
                    smoothed = null
                    lowStructure = false
                }

                if (pause == null && now - lastMeasureAt >= interval &&
                    textureA.isAvailable && textureB.isAvailable && textureA.bufferHeight > 0f
                ) {
                    lastMeasureAt = now
                    // The SurfaceTexture delivers the stream upright (portrait).
                    val aspect = textureA.bufferWidth / textureA.bufferHeight
                    val pw = PRIMARY_WIDTH
                    val sw = minOf(SECONDARY_WIDTH, textureB.bufferHeight.toInt())
                    val pBitmap = primaryBitmap?.takeIf { it.width == pw } ?: createBitmap(pw, (pw * aspect).roundToInt()).also { primaryBitmap = it }
                    val sBitmap = secondaryBitmap?.takeIf { it.width == sw } ?: createBitmap(sw, (sw * aspect).roundToInt()).also { secondaryBitmap = it }
                    val grabbed = runCatching {
                        textureA.getBitmap(pBitmap)
                        textureB.getBitmap(sBitmap)
                    }.isSuccess

                    if (grabbed) {
                        val previous = transform
                        val useFast = previous != null && fastRuns < FAST_RUNS_PER_FULL
                        val measurement = withContext(Dispatchers.Default) {
                            runCatching {
                                val p = pBitmap.toGrayImage(1)
                                val s = sBitmap.toGrayImage(1)
                                var m = if (useFast && previous != null) LiveDepth.measureFast(p, s, previous, baselineX, baselineY) else null
                                if (m == null || m.confidence < fullConfidence * FAST_MIN_RELATIVE_CONFIDENCE) {
                                    // First run, scheduled refresh, or the fast path lost the match.
                                    m = LiveDepth.measure(p, s, previous, if (previous == null) scaleGuess(geometryP, geometryS, pw, sw) else null, baselineX, baselineY)
                                        ?.also { full -> if (full.accepted) fullConfidence = full.confidence }
                                    fastRuns = 0
                                } else {
                                    fastRuns++
                                }
                                m
                            }.onFailure { Log.w(TAG, "Live measurement failed", it) }.getOrNull()
                        }
                        lastMeasurementMs = SystemClock.elapsedRealtime() - now
                        // Keep the analysis at most ~25 % of one core even on a slow phone.
                        interval = max(MEASURE_INTERVAL_MS, lastMeasurementMs * 4)

                        if (measurement != null && measurement.accepted) transform = measurement.transform
                        val depth = measurement?.depthPercent
                        lowStructure = depth == null
                        smoothed = when {
                            depth == null -> smoothed
                            smoothed == null -> depth
                            // Big changes (new subject) are taken over at once, small ones averaged.
                            abs(depth - smoothed) > 0.5 * max(depth, smoothed) -> depth
                            else -> smoothed + SMOOTHING * (depth - smoothed)
                        }
                        if (depth == null && measurement?.accepted != true) transform = null
                        Log.d(TAG, "live depth=${depth?.let { "%.2f".format(it) }} conf=${measurement?.confidence?.let { "%.2f".format(it) }} ${lastMeasurementMs}ms")
                    }
                }

                val stats = controller.previewStats()
                _tips.value = LiveGuide.tips(
                    LiveGuide.Inputs(
                        depthPercent = if (pause == LiveGuide.Pause.UNSUPPORTED) null else smoothed,
                        lowStructure = lowStructure && smoothed == null,
                        pause = pause,
                        focusDistanceM = stats?.focusDiopters?.let { if (it > 0.05f) 1.0 / it else null },
                        exposureMs = stats?.exposureNs?.let { it / 1e6 },
                        iso = stats?.iso,
                        nightMode = nightMode(),
                        landscape = abs(gravityX) > abs(gravityY) * 1.3f && abs(gravityX) > 5f
                    )
                )
            }
        } finally {
            _tips.value = emptyList()
            primaryBitmap?.recycle()
            secondaryBitmap?.recycle()
        }
    }

    /** Rough primary → secondary pixel scale from focal lengths and sensor widths. */
    private fun scaleGuess(p: LensGeometry?, s: LensGeometry?, pw: Int, sw: Int): Double? {
        val fp = p?.focalLengthMm ?: return null
        val fs = s?.focalLengthMm ?: return null
        val wp = p.sensorWidthMm ?: return null
        val ws = s.sensorWidthMm ?: return null
        return ((fs / ws) / (fp / wp) * sw / pw.toDouble()).takeIf { it.isFinite() && it in 0.05..20.0 }
    }

    private companion object {
        const val TAG = "LiveAssistant"
        const val TICK_MS = 250L
        const val MEASURE_INTERVAL_MS = 1000L

        /** Preview frame widths for the measurement (portrait). */
        const val PRIMARY_WIDTH = 480
        const val SECONDARY_WIDTH = 1080

        /** Above this rotation speed the phone counts as moving (~30°/s). */
        const val MOVING_RAD_PER_S = 0.5f
        const val MOVING_HOLD_MS = 600L

        /** Every so many fast runs the transform is measured afresh. */
        const val FAST_RUNS_PER_FULL = 5
        const val FAST_MIN_RELATIVE_CONFIDENCE = 0.8
        const val SMOOTHING = 0.5
    }
}
