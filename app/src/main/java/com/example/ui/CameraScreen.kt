package com.example.ui

import android.content.Context
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.camera.CameraController
import com.example.camera.CameraLensDetails
import com.example.camera.ZoomableTextureView
import com.example.ui.theme.WiggleColors
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Live camera screen: viewfinder, zoom controls, calibration and shutter.
 *
 * All hardware handling lives in [CameraController]; this composable only renders state and
 * forwards intents. That separation is what makes the shutter deterministic — the button no
 * longer depends on half a dozen Compose state flags being set in the right order.
 */
@Composable
fun CameraScreen(
    uiState: WiggleUiState,
    viewModel: WiggleViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val controller = remember(context) { CameraController(context) }
    val views = remember(context) {
        CameraController.Views(
            textureA = ZoomableTextureView(context),
            textureB = ZoomableTextureView(context),
            previewA = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER },
            previewB = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        )
    }

    DisposableEffect(controller, lifecycleOwner) {
        controller.attach(views, lifecycleOwner)
        onDispose {
            views.textureA.cleanup()
            views.textureB.cleanup()
            controller.release()
        }
    }

    val status by controller.status.collectAsStateWithLifecycle()
    val countdown by controller.countdown.collectAsStateWithLifecycle()
    val burstProgress by controller.burstProgress.collectAsStateWithLifecycle()

    val request = remember(
        uiState.primaryLens,
        uiState.secondaryLenses,
        uiState.is4K,
        uiState.concurrentPreviewSupported
    ) {
        uiState.primaryLens?.let { primary ->
            CameraController.Request(
                primary = primary,
                secondaries = uiState.secondaryLenses.filter { it.id != primary.id },
                is4K = uiState.is4K,
                concurrentPreviewSupported = uiState.concurrentPreviewSupported
            )
        }
    }

    // The camera runs exactly while the screen is resumed. repeatOnLifecycle replaces the
    // hand rolled ON_RESUME observer, which could miss the event depending on when the
    // composable first ran and then never started the camera at all.
    LaunchedEffect(request, lifecycleOwner) {
        val activeRequest = request ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.start(activeRequest)
        }
    }

    // With auto alignment the secondary lens always streams its full field of view.
    val secondaryPreviewZoom = if (uiState.autoAlign) 1f else uiState.zoomB
    LaunchedEffect(uiState.zoomA, secondaryPreviewZoom, status.mode) {
        controller.applyZoom(uiState.zoomA, secondaryPreviewZoom)
    }

    var flashCount by remember { mutableIntStateOf(0) }

    fun startCapture() {
        val activeRequest = request ?: run {
            Toast.makeText(context, "Keine Linse ausgewählt", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            // null means the request was ignored because a capture is already running.
            val capture = controller.capture(activeRequest, burst = viewModel.burstFrames()) { lens ->
                viewModel.uiState.value.captureZoomFor(lens)
            } ?: return@launch
            if (capture.frames.isEmpty()) {
                Toast.makeText(context, "Aufnahme fehlgeschlagen", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Only the JPEGs were taken; alignment runs in the background and the shutter is
            // usable again immediately.
            flashCount++
            viewModel.enqueueCapture(context, capture)
        }
    }

    LaunchedEffect(uiState.captureTrigger) {
        if (uiState.captureTrigger > 0) startCapture()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Viewfinder(views = views, uiState = uiState, useTextureViews = status.usesTextureViews)

        val progress = burstProgress
        if (progress != null) {
            NightHoldOverlay(progress)
        } else {
            countdown?.let { CountdownOverlay(it) }
            if (uiState.nightMode) {
                NightBadge(
                    holdMs = controller.estimatedBurstMs(viewModel.nightFrames),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                )
            }
        }
        ShutterFlash(flashCount)

        ModeIndicator(
            simultaneous = status.simultaneous,
            night = uiState.nightMode,
            lensCount = uiState.lensCount,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        )

        NightToggle(
            enabled = uiState.nightMode,
            onToggle = { viewModel.setNightMode(context, !uiState.nightMode) },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        )

        ZoomPanel(
            uiState = uiState,
            viewModel = viewModel,
            onCalibrate = {
                val activeRequest = request
                if (activeRequest == null || activeRequest.secondaries.isEmpty()) {
                    Toast.makeText(context, "Keine Linsen zum Kalibrieren", Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch {
                        runCalibration(context, controller, activeRequest, viewModel)
                        // A CameraX capture may have reset the optical zoom of the preview.
                        controller.applyZoom(uiState.zoomA, secondaryPreviewZoom)
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 120.dp)
        )

        ShutterBar(
            busy = status.busy,
            night = uiState.nightMode,
            burst = burstProgress,
            pending = uiState.pendingJobs,
            onCapture = ::startCapture,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun Viewfinder(
    views: CameraController.Views,
    uiState: WiggleUiState,
    useTextureViews: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { views.textureA },
            modifier = Modifier.fillMaxSize().alpha(if (useTextureViews) 1f else 0f),
            update = { it.updateZoom(uiState.zoomA) }
        )
        AndroidView(
            factory = { views.previewA },
            modifier = Modifier.fillMaxSize().alpha(if (useTextureViews) 0f else 1f)
        )
    }

    // The secondary viewfinder must stay attached and drawn, otherwise its SurfaceTexture is
    // never produced and the simultaneous modes cannot start. It is kept large enough for
    // calibration to read a usable frame from it, but practically invisible.
    Box(
        modifier = Modifier
            .size(280.dp, 210.dp)
            .alpha(0.02f)
    ) {
        AndroidView(
            factory = { views.textureB },
            modifier = Modifier.fillMaxSize().alpha(if (useTextureViews) 1f else 0f),
            update = { it.updateZoom(uiState.zoomB) }
        )
        AndroidView(
            factory = { views.previewB },
            modifier = Modifier.fillMaxSize().alpha(if (useTextureViews) 0f else 1f)
        )
    }
}

@Composable
private fun CountdownOverlay(value: Int) {
    Box(
        modifier = Modifier.fillMaxSize().background(WiggleColors.Scrim),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (value == 0) "RUHIG HALTEN" else value.toString(),
                fontSize = if (value == 0) 30.sp else 64.sp,
                fontWeight = FontWeight.Black,
                color = WiggleColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Sequentieller Modus: die Linsen werden nacheinander aufgenommen",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = WiggleColors.Accent,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModeIndicator(simultaneous: Boolean, night: Boolean, lensCount: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(WiggleColors.OverlayStrong, RoundedCornerShape(12.dp))
            .border(1.dp, WiggleColors.Outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (simultaneous) WiggleColors.Accent else WiggleColors.Warning, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = (if (simultaneous) "SIMULTAN (2 Linsen)" else "SEQUENTIELL ($lensCount Linsen)") +
                    if (night) " · NACHT" else "",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = WiggleColors.TextPrimary,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun NightToggle(enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) NIGHT_BLUE else WiggleColors.OverlayStrong)
            .border(1.dp, if (enabled) NIGHT_BLUE else WiggleColors.Outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.NightsStay,
                contentDescription = null,
                tint = if (enabled) Color.Black else NIGHT_BLUE,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (enabled) "NACHT AN" else "NACHT",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = if (enabled) Color.Black else WiggleColors.TextPrimary,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/** Moon badge on the live preview while night mode is armed. */
@Composable
internal fun NightBadge(holdMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(WiggleColors.OverlayStrong, RoundedCornerShape(20.dp))
            .border(1.dp, NIGHT_BLUE, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.NightsStay,
            contentDescription = "Nachtmodus",
            tint = NIGHT_BLUE,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "NACHTMODUS · beim Auslösen ca. ${formatSeconds(holdMs)} ruhig halten",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = WiggleColors.TextPrimary
        )
    }
}

/**
 * Animated hold-still progress of a running burst: elapsed time against the measured
 * duration, but never ahead of the frames that actually arrived and never behind them.
 */
@Composable
private fun rememberBurstFraction(progress: CameraController.BurstProgress): Pair<Float, Long> {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(progress.startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(16)
        }
    }
    val elapsed = now - progress.startedAt
    val byTime = elapsed.toFloat() / progress.estimatedMs.coerceAtLeast(1)
    val lower = progress.done.toFloat() / progress.total
    val upper = (progress.done + 0.95f) / progress.total
    val fraction = byTime.coerceIn(lower, upper.coerceAtMost(1f))
    val remaining = (progress.estimatedMs - elapsed).coerceAtLeast(0)
    return fraction to remaining
}

/** Shown while a night burst is being taken; the preview stays visible underneath. */
@Composable
internal fun NightHoldOverlay(progress: CameraController.BurstProgress) {
    val (fraction, _) = rememberBurstFraction(progress)
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x55000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(WiggleColors.OverlayStrong, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            Icon(
                imageVector = Icons.Default.NightsStay,
                contentDescription = null,
                tint = NIGHT_BLUE,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "RUHIG HALTEN",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = WiggleColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Bild ${(progress.done + 1).coerceAtMost(progress.total)} von ${progress.total}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = NIGHT_BLUE
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction },
                color = NIGHT_BLUE,
                trackColor = WiggleColors.SurfaceMuted,
                modifier = Modifier.width(180.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Kleine Bewegungen gleicht die App aus",
                fontSize = 11.sp,
                color = WiggleColors.TextTertiary
            )
        }
    }
}

private val NIGHT_BLUE = Color(0xFF8AB4FF)

private fun formatSeconds(ms: Long): String = String.format(Locale.GERMANY, "%.1f s", ms / 1000.0)

@Composable
private fun ZoomPanel(
    uiState: WiggleUiState,
    viewModel: WiggleViewModel,
    onCalibrate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(WiggleColors.OverlaySoft, RoundedCornerShape(16.dp))
            .border(1.dp, WiggleColors.Outline, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        uiState.primaryLens?.let { lens ->
            LensZoomSlider(
                label = "ZOOM (HAUPTLINSE)",
                labelColor = WiggleColors.Accent,
                lens = lens,
                uiState = uiState,
                onZoomChange = { viewModel.setZoomForLens(lens.id, it) }
            )
        }

        if (uiState.autoAlign) {
            AutoAlignInfo(uiState.alignmentInfo)
        } else {
            uiState.secondaryLenses.forEachIndexed { index, lens ->
                LensZoomSlider(
                    label = "ZOOM (SEKUNDÄRLINSE ${index + 1})",
                    labelColor = WiggleColors.TextPrimary,
                    lens = lens,
                    uiState = uiState,
                    onZoomChange = { viewModel.setZoomForLens(lens.id, it) }
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CalibrationButton(
                isCalibrating = uiState.isCalibrating,
                isApplied = uiState.isAutoZoomApplied,
                onClick = onCalibrate
            )
        }
    }
}

@Composable
private fun AutoAlignInfo(info: String?) {
    Column {
        Text(
            text = "SEKUNDÄRLINSE: AUTO-AUSRICHTUNG",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = WiggleColors.TextPrimary
        )
        Text(
            text = info ?: "Skalierung, Rotation, Versatz und Farbe werden bei jeder Aufnahme gemessen",
            fontSize = 9.sp,
            color = WiggleColors.TextTertiary
        )
    }
}

@Composable
private fun LensZoomSlider(
    label: String,
    labelColor: Color,
    lens: CameraLensDetails,
    uiState: WiggleUiState,
    onZoomChange: (Float) -> Unit
) {
    val limits = uiState.zoomLimitsFor(lens)
    val zoom = uiState.zoomFor(lens)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label: ${String.format(Locale.US, "%.2fx", zoom)}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = labelColor
            )
            Text(text = lens.name, fontSize = 8.sp, color = Color.Gray)
        }
        Slider(
            value = zoom,
            onValueChange = onZoomChange,
            valueRange = limits.first..limits.second,
            modifier = Modifier.height(24.dp)
        )
    }
}

@Composable
private fun CalibrationButton(isCalibrating: Boolean, isApplied: Boolean, onClick: () -> Unit) {
    val background = when {
        isCalibrating -> WiggleColors.SurfaceMuted
        isApplied -> WiggleColors.Accent
        else -> WiggleColors.SurfaceElevated
    }
    val outline = when {
        isCalibrating -> WiggleColors.OutlineStrong
        isApplied -> WiggleColors.Accent
        else -> WiggleColors.Outline
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, outline, RoundedCornerShape(10.dp))
            .clickable(enabled = !isCalibrating, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isCalibrating) {
                CircularProgressIndicator(
                    color = WiggleColors.Accent,
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp
                )
            }
            Text(
                text = when {
                    isCalibrating -> "KALIBRIERE..."
                    isApplied -> "✓ KALIBRIERT"
                    else -> "⚡ LINSEN KALIBRIEREN"
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                color = when {
                    isCalibrating -> WiggleColors.TextTertiary
                    isApplied -> Color.Black
                    else -> WiggleColors.Accent
                },
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
internal fun ShutterBar(
    busy: Boolean,
    night: Boolean,
    burst: CameraController.BurstProgress?,
    pending: Int,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(WiggleColors.Background)
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .padding(6.dp)
                .border(
                    3.dp,
                    when {
                        burst != null -> WiggleColors.SurfaceMuted // the progress ring is the border now
                        night -> NIGHT_BLUE
                        else -> WiggleColors.TextPrimary
                    },
                    CircleShape
                )
                .clickable(enabled = !busy, onClick = onCapture),
            contentAlignment = Alignment.Center
        ) {
            when {
                burst != null -> {
                    // Night burst running: shutter blocked, ring fills while holding still.
                    val (fraction, remaining) = rememberBurstFraction(burst)
                    Box(modifier = Modifier.size(56.dp).background(WiggleColors.SurfaceMuted, CircleShape))
                    CircularProgressIndicator(
                        progress = { fraction },
                        color = NIGHT_BLUE,
                        trackColor = Color.Transparent,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(60.dp)
                    )
                    Text(
                        text = formatSeconds(remaining),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = WiggleColors.TextPrimary
                    )
                }

                busy -> {
                    Box(modifier = Modifier.size(56.dp).background(WiggleColors.SurfaceMuted, CircleShape))
                    CircularProgressIndicator(color = WiggleColors.Accent, modifier = Modifier.size(56.dp), strokeWidth = 3.dp)
                }

                night -> {
                    Box(
                        modifier = Modifier.size(56.dp).background(NIGHT_BLUE, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NightsStay,
                            contentDescription = "Nachtaufnahme",
                            tint = Color(0xFF0F1A33),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                else -> Box(modifier = Modifier.size(56.dp).background(WiggleColors.Accent, CircleShape))
            }
        }
        if (burst != null) {
            Text(
                text = "Nicht bewegen",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NIGHT_BLUE,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
            )
        }
        if (pending > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .background(WiggleColors.SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    color = WiggleColors.Accent,
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$pending in Arbeit",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = WiggleColors.TextPrimary
                )
            }
        }
    }
}

/** Short white flash so the user sees that the picture was taken. */
@Composable
private fun ShutterFlash(trigger: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        alpha.snapTo(0.7f)
        alpha.animateTo(0f, animationSpec = tween(durationMillis = 220))
    }
    if (alpha.value > 0f) {
        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = alpha.value)))
    }
}

/**
 * Takes one simultaneous capture with every lens at its full field of view and queues it to
 * be measured and stored as the lens pair calibration.
 */
private suspend fun runCalibration(
    context: Context,
    controller: CameraController,
    request: CameraController.Request,
    viewModel: WiggleViewModel
) {
    if (viewModel.uiState.value.isCalibrating) return
    viewModel.setCalibrating(true)
    val capture = try {
        controller.capture(request) { 1.0f }
    } catch (e: Exception) {
        android.util.Log.e("CameraScreen", "Calibration capture failed", e)
        null
    }
    when {
        capture == null -> {
            viewModel.setCalibrating(false)
            Toast.makeText(context, "Kamera beschäftigt – bitte erneut versuchen", Toast.LENGTH_SHORT).show()
        }
        capture.frames.size < 2 -> {
            viewModel.setCalibrating(false)
            Toast.makeText(context, "Kalibrierung fehlgeschlagen – Kamera nicht bereit", Toast.LENGTH_SHORT).show()
        }
        else -> viewModel.enqueueCalibration(context, capture)
    }
}
