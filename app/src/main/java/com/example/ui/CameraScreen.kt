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
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
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
import com.example.live.LiveGuide
import com.example.live.LiveAssistant
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.Crossfade
import com.example.camera.ZoomableTextureView
import com.example.ui.theme.WiggleColors
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Live camera screen: a large viewfinder with the live 3D tips on top (subtitle style), and
 * one row with calibration, night mode, shutter, tips and settings below it.
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
    val assistant = remember(context) { LiveAssistant(context) }
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
    val tips by assistant.tips.collectAsStateWithLifecycle()

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

    // Live tips: sensors and the ~1/s measurement run only while the screen is resumed.
    // Night or flash mode: the "too dark" tip is not needed.
    val nightModeState = rememberUpdatedState(uiState.nightMode || uiState.flashMode)
    LaunchedEffect(request, lifecycleOwner) {
        val activeRequest = request ?: return@LaunchedEffect
        val secondary = activeRequest.secondaries.firstOrNull() ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            assistant.startSensors()
            try {
                assistant.run(controller, views.textureA, views.textureB, activeRequest.primary.id, secondary.id) {
                    nightModeState.value
                }
            } finally {
                assistant.stopSensors()
            }
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
            val capture = controller.capture(
                activeRequest,
                burst = viewModel.burstFrames(),
                flash = viewModel.uiState.value.flashMode
            ) { lens ->
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

    fun startCalibration() {
        val activeRequest = request
        if (activeRequest == null || activeRequest.secondaries.isEmpty()) {
            Toast.makeText(context, "Keine Linsen zum Kalibrieren", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCalibration(context, controller, activeRequest, viewModel)
            // A CameraX capture may have reset the optical zoom of the preview.
            controller.applyZoom(uiState.zoomA, secondaryPreviewZoom)
        }
    }

    LaunchedEffect(uiState.captureTrigger) {
        if (uiState.captureTrigger > 0) startCapture()
    }

    // Portrait aspect of the preview stream (the SurfaceTexture delivers it upright).
    val aspect = if (views.textureA.bufferWidth > 0f) views.textureA.bufferHeight / views.textureA.bufferWidth else 0.75f

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Exactly the stream's aspect ratio: nothing is cropped, so what the live
            // measurement sees is what the viewfinder shows.
            Box(modifier = Modifier.aspectRatio(aspect)) {
                Viewfinder(views = views, uiState = uiState, useTextureViews = status.usesTextureViews, aspect = aspect)

                val progress = burstProgress
                if (progress != null) {
                    NightHoldOverlay(progress)
                } else {
                    countdown?.let { CountdownOverlay(it) }
                    LiveTips(
                        tips = tips,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                    if (uiState.nightMode) {
                        NightBadge(
                            holdMs = controller.estimatedBurstMs(viewModel.nightFrames),
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                        )
                    }
                    if (uiState.flashMode) {
                        FlashBadge(Modifier.align(Alignment.BottomStart).padding(12.dp))
                    }
                }
                if (uiState.pendingJobs > 0) {
                    PendingBadge(uiState.pendingJobs, Modifier.align(Alignment.BottomEnd).padding(12.dp))
                }
                ShutterFlash(flashCount)
            }
        }

        ShutterBar(
            busy = status.busy,
            night = uiState.nightMode,
            flash = uiState.flashMode,
            burst = burstProgress,
            isCalibrating = uiState.isCalibrating,
            isCalibrated = uiState.isAutoZoomApplied,
            onCapture = ::startCapture,
            onCalibrate = ::startCalibration,
            onCycleLight = { viewModel.cycleLightMode(context) },
            onOpenGuide = viewModel::toggleGuide,
            onOpenSettings = viewModel::toggleSettings
        )
    }
}

@Composable
private fun Viewfinder(
    views: CameraController.Views,
    uiState: WiggleUiState,
    useTextureViews: Boolean,
    aspect: Float
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
    // never produced and the simultaneous modes cannot start. Same aspect ratio as the
    // stream, so a frame read from it for the live measurement is never cropped; practically
    // invisible.
    Box(
        modifier = Modifier
            .width(150.dp)
            .aspectRatio(aspect)
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

/** Live tips, subtitle style: short lines, coloured by meaning, readable on any image. */
@Composable
internal fun LiveTips(tips: List<LiveGuide.Tip>, modifier: Modifier = Modifier) {
    Crossfade(targetState = tips, animationSpec = tween(250), modifier = modifier, label = "tips") { shown ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            shown.forEach { tip ->
                Text(
                    text = "${tip.emoji}  ${tip.text}",
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = tipColor(tip.level),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color(0xB3000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}

private fun tipColor(level: LiveGuide.Level): Color = when (level) {
    LiveGuide.Level.GOOD -> Color(0xFF69F0AE)
    LiveGuide.Level.OK -> Color(0xFFFFE082)
    LiveGuide.Level.WARN -> Color(0xFFFFB74D)
    LiveGuide.Level.BAD -> Color(0xFFFF8A80)
    LiveGuide.Level.INFO -> Color(0xFF9AD8FF)
}

@Composable
private fun PendingBadge(pending: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(WiggleColors.OverlayStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(color = WiggleColors.Accent, modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "$pending in Arbeit", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = WiggleColors.TextPrimary)
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
            text = "Nacht · ${formatSeconds(holdMs)} ruhig halten",
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
private val FLASH_YELLOW = Color(0xFFFFD54F)

/** Badge on the live preview while flash mode is armed. */
@Composable
internal fun FlashBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(WiggleColors.OverlayStrong, RoundedCornerShape(20.dp))
            .border(1.dp, FLASH_YELLOW, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.FlashOn,
            contentDescription = "Blitz",
            tint = FLASH_YELLOW,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Blitz · Einweg-Look",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = WiggleColors.TextPrimary
        )
    }
}

private fun formatSeconds(ms: Long): String = String.format(Locale.GERMANY, "%.1f s", ms / 1000.0)

/** Small round button with a label below, for the row next to the shutter. */
@Composable
private fun SideButton(
    label: String,
    enabled: Boolean = true,
    background: Color = WiggleColors.SurfaceElevated,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp).alpha(if (enabled) 1f else 0.4f)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { content() }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = WiggleColors.TextSecondary, maxLines = 1)
    }
}

@Composable
internal fun ShutterBar(
    busy: Boolean,
    night: Boolean,
    flash: Boolean,
    burst: CameraController.BurstProgress?,
    isCalibrating: Boolean,
    isCalibrated: Boolean,
    onCapture: () -> Unit,
    onCalibrate: () -> Unit,
    onCycleLight: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val idle = burst == null && !busy
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(WiggleColors.Background)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SideButton(
            label = when {
                isCalibrating -> "Misst…"
                isCalibrated -> "Kalibriert"
                else -> "Kalibrieren"
            },
            enabled = idle && !isCalibrating,
            onClick = onCalibrate
        ) {
            if (isCalibrating) {
                CircularProgressIndicator(color = WiggleColors.Accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = if (isCalibrated) Icons.Default.CheckCircle else Icons.Default.CenterFocusStrong,
                    contentDescription = "Linsen kalibrieren",
                    tint = WiggleColors.Accent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        // Light mode, cycling normal → night → flash: the two exclude each other.
        SideButton(
            label = when {
                night -> "Nacht"
                flash -> "Blitz"
                else -> "Licht"
            },
            enabled = burst == null && !busy,
            background = when {
                night -> NIGHT_BLUE
                flash -> FLASH_YELLOW
                else -> WiggleColors.SurfaceElevated
            },
            onClick = onCycleLight
        ) {
            Icon(
                imageVector = when {
                    night -> Icons.Default.NightsStay
                    flash -> Icons.Default.FlashOn
                    else -> Icons.Default.FlashOff
                },
                contentDescription = "Licht: normal, Nacht oder Blitz",
                tint = when {
                    night -> Color(0xFF0F1A33)
                    flash -> Color(0xFF2B2000)
                    else -> WiggleColors.TextSecondary
                },
                modifier = Modifier.size(22.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(76.dp)
                .padding(4.dp)
                .border(
                    3.dp,
                    when {
                        burst != null -> WiggleColors.SurfaceMuted // the progress ring is the border now
                        night -> NIGHT_BLUE
                        flash -> FLASH_YELLOW
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
                    Box(modifier = Modifier.size(60.dp).background(WiggleColors.SurfaceMuted, CircleShape))
                    CircularProgressIndicator(
                        progress = { fraction },
                        color = NIGHT_BLUE,
                        trackColor = Color.Transparent,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = formatSeconds(remaining),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = WiggleColors.TextPrimary
                    )
                }

                busy -> {
                    Box(modifier = Modifier.size(60.dp).background(WiggleColors.SurfaceMuted, CircleShape))
                    CircularProgressIndicator(color = WiggleColors.Accent, modifier = Modifier.size(60.dp), strokeWidth = 3.dp)
                }

                flash -> {
                    Box(
                        modifier = Modifier.size(60.dp).background(FLASH_YELLOW, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = "Blitzaufnahme",
                            tint = Color(0xFF2B2000),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                night -> {
                    Box(
                        modifier = Modifier.size(60.dp).background(NIGHT_BLUE, CircleShape),
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

                else -> Box(modifier = Modifier.size(60.dp).background(WiggleColors.Accent, CircleShape))
            }
        }

        SideButton(label = "3D-Tipps", onClick = onOpenGuide) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = "3D-Tipps",
                tint = WiggleColors.TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
        SideButton(label = "Optionen", enabled = burst == null, onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Einstellungen",
                tint = WiggleColors.TextPrimary,
                modifier = Modifier.size(22.dp)
            )
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
