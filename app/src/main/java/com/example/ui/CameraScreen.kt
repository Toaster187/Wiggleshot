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
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

private const val CALIBRATION_PASSES = 3
private const val CALIBRATION_TIMEOUT_MS = 15_000L
private const val CALIBRATION_PASS_DELAY_MS = 120L

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

    LaunchedEffect(uiState.zoomA, uiState.zoomB, status.mode) {
        controller.applyZoom(uiState.zoomA, uiState.zoomB)
    }

    fun startCapture() {
        val activeRequest = request ?: run {
            Toast.makeText(context, "Keine Linse ausgewählt", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            // null means the request was ignored because a capture is already running.
            val frames = controller.capture(activeRequest) { lens ->
                viewModel.uiState.value.zoomFor(lens)
            } ?: return@launch

            when {
                frames.size >= 2 -> viewModel.performMultiCapture(context, frames)
                frames.size == 1 ->
                    Toast.makeText(context, "Nur ein Frame erfasst – Aufnahme verworfen", Toast.LENGTH_SHORT).show()
                else ->
                    Toast.makeText(context, "Aufnahme fehlgeschlagen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(uiState.captureTrigger) {
        if (uiState.captureTrigger > 0) startCapture()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Viewfinder(views = views, uiState = uiState, useTextureViews = status.usesTextureViews)

        countdown?.let { CountdownOverlay(it) }

        ModeIndicator(
            simultaneous = status.simultaneous,
            lensCount = uiState.lensCount,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        )

        StereoIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))

        ZoomPanel(
            uiState = uiState,
            viewModel = viewModel,
            onCalibrate = {
                scope.launch { runCalibration(context, controller, viewModel) }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 120.dp)
        )

        ShutterBar(
            busy = status.busy || uiState.isCapturing,
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
                text = if (value == 0) "JETZT STILL" else value.toString(),
                fontSize = if (value == 0) 30.sp else 64.sp,
                fontWeight = FontWeight.Black,
                color = WiggleColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Bitte ganz ruhig halten",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = WiggleColors.Accent,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModeIndicator(simultaneous: Boolean, lensCount: Int, modifier: Modifier = Modifier) {
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
                text = if (simultaneous) "SIMULTAN (2 Linsen)" else "SEQUENTIELL ($lensCount Linsen)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = WiggleColors.TextPrimary,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun StereoIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(WiggleColors.Overlay, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text("STEREO ACTIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = WiggleColors.Accent)
            Text("Kamera live", fontSize = 8.sp, color = Color.LightGray)
        }
    }
}

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

        uiState.secondaryLenses.forEachIndexed { index, lens ->
            LensZoomSlider(
                label = "ZOOM (SEKUNDÄRLINSE ${index + 1})",
                labelColor = WiggleColors.TextPrimary,
                lens = lens,
                uiState = uiState,
                onZoomChange = { viewModel.setZoomForLens(lens.id, it) }
            )
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
                    isApplied -> "✓ AUTO"
                    else -> "⚡ AUTO KALIBRIERUNG"
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
private fun ShutterBar(busy: Boolean, onCapture: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(WiggleColors.Background)
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = WiggleColors.Accent, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ALIGNING & INTERPOLATING...",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = WiggleColors.Accent,
                    letterSpacing = 1.sp
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .padding(6.dp)
                    .border(3.dp, WiggleColors.TextPrimary, CircleShape)
                    .clickable(onClick = onCapture),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(56.dp).background(WiggleColors.Accent, CircleShape))
            }
        }
    }
}

/**
 * Measures the zoom factor at which each secondary lens visually matches the primary lens.
 *
 * Runs entirely against [CameraController.calibrationFrames], which knows how to obtain a
 * comparison pair in every mode. The previous version reached into preview views, texture
 * views and lens selection state from inside the composable and temporarily rebound the
 * secondary lens, which left the camera in an inconsistent state whenever it was cancelled.
 */
private suspend fun runCalibration(
    context: Context,
    controller: CameraController,
    viewModel: WiggleViewModel
) {
    val state = viewModel.uiState.value
    val primary = state.primaryLens
    if (primary == null || state.secondaryLenses.isEmpty()) {
        Toast.makeText(context, "Keine Linsen zum Kalibrieren", Toast.LENGTH_SHORT).show()
        return
    }

    viewModel.setCalibrating(true)
    val results = mutableMapOf<String, MutableList<Float>>()
    try {
        withTimeout(CALIBRATION_TIMEOUT_MS) {
            for (lens in state.secondaryLenses) {
                val limits = state.zoomLimitsFor(lens)
                val minZoom = limits.first.coerceAtLeast(1.0f)
                repeat(CALIBRATION_PASSES) { pass ->
                    val frames = controller.calibrationFrames(
                        primary = primary,
                        primaryZoom = state.zoomFor(primary),
                        secondary = lens,
                        secondaryZoom = minZoom,
                        is4K = state.is4K
                    )
                    if (frames == null) {
                        android.util.Log.w("CameraScreen", "No calibration frames for lens ${lens.id}")
                    } else {
                        results.getOrPut(lens.id) { mutableListOf() } +=
                            viewModel.calculateVisualZoomMatch(frames.first, frames.second, minZoom, limits.second)
                    }
                    if (pass < CALIBRATION_PASSES - 1) delay(CALIBRATION_PASS_DELAY_MS)
                }
            }
        }
        if (results.values.all { it.isEmpty() }) {
            Toast.makeText(context, "Kalibrierung fehlgeschlagen – Kamera nicht bereit", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.applyCalibrationResults(results)
        }
    } catch (e: TimeoutCancellationException) {
        Toast.makeText(context, "Kalibrierung Timeout", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.util.Log.e("CameraScreen", "Calibration failed", e)
        Toast.makeText(context, "Kalibrierung fehlgeschlagen", Toast.LENGTH_SHORT).show()
    } finally {
        viewModel.setCalibrating(false)
        controller.requestReconfigure()
    }
}
