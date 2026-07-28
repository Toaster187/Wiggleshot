package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.WiggleCapture
import com.example.ui.theme.WiggleColors
import com.example.util.GifEncoder
import com.example.util.WiggleProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PlayerScreen"
private const val MIN_FRAME_DELAY_MS = 50f
private const val MAX_FRAME_DELAY_MS = 800f

/** Plays back a captured wiggle and exports it as an animated GIF. */
@Composable
fun PlayerScreen(
    capture: WiggleCapture,
    onCloseReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val imagePaths = remember(capture.imagePaths) { capture.getImagePathList() }
    var frames by remember(imagePaths) { mutableStateOf<List<Bitmap>>(emptyList()) }

    // Decoding several multi megapixel JPEGs used to happen inline during composition, which
    // blocked the main thread and could exhaust the heap on 4K captures.
    LaunchedEffect(imagePaths) {
        frames = withContext(Dispatchers.IO) {
            imagePaths.mapNotNull { WiggleProcessor.loadBitmap(it) }
        }
    }

    var isPingPong by remember { mutableStateOf(true) }
    var frameDelayMs by remember { mutableFloatStateOf(250f) }
    var currentFrame by remember { mutableIntStateOf(0) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(frames, isPingPong, frameDelayMs) {
        if (frames.size <= 1) {
            currentFrame = 0
            return@LaunchedEffect
        }
        var direction = 1
        currentFrame = 0
        while (true) {
            delay(frameDelayMs.toLong())
            if (isPingPong) {
                if (currentFrame + direction !in frames.indices) direction = -direction
                currentFrame = (currentFrame + direction).coerceIn(frames.indices)
            } else {
                currentFrame = (currentFrame + 1) % frames.size
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            frames.getOrNull(currentFrame)?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Holographic Parallax Feed",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(WiggleColors.Overlay, CircleShape)
                    .clickable(onClick = onCloseReview),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = WiggleColors.TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(WiggleColors.Overlay, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "LINSE ${currentFrame + 1}/${frames.size.coerceAtLeast(1)}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = WiggleColors.Accent
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(WiggleColors.Surface)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "3D WIGGLE CONTROLLER",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = WiggleColors.TextPrimary,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Loop-Modus", fontSize = 13.sp, color = Color.LightGray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoopModeButton("Ping-Pong", isPingPong) { isPingPong = true }
                    LoopModeButton("Loop", !isPingPong) { isPingPong = false }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Geschwindigkeit", fontSize = 13.sp, color = Color.LightGray)
                Text(
                    "${frameDelayMs.toInt()} ms",
                    fontSize = 13.sp,
                    color = WiggleColors.Accent,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = frameDelayMs,
                onValueChange = { frameDelayMs = it },
                valueRange = MIN_FRAME_DELAY_MS..MAX_FRAME_DELAY_MS,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (isExporting) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = WiggleColors.Accent, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Exportiere GIF...", fontSize = 11.sp, color = WiggleColors.Accent)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (frames.isEmpty()) {
                                Toast.makeText(context, "Keine Frames geladen", Toast.LENGTH_SHORT).show()
                            } else {
                                isExporting = true
                                scope.launch {
                                    exportGif(context, frames, isPingPong, frameDelayMs.toInt())
                                    isExporting = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WiggleColors.Accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Gif, contentDescription = "GIF", tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("GIF Exportieren", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("KI Prompt", capture.prompt))
                            Toast.makeText(context, "Prompt kopiert!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WiggleColors.SurfaceMuted),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = WiggleColors.TextPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Prompt kopieren", color = WiggleColors.TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoopModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) WiggleColors.Accent else WiggleColors.SurfaceMuted
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) Color.Black else WiggleColors.TextPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}

private suspend fun exportGif(
    context: Context,
    frames: List<Bitmap>,
    pingPong: Boolean,
    delayMs: Int
) {
    val result = withContext(Dispatchers.Default) {
        runCatching {
            val exportFrames = if (pingPong && frames.size > 2) {
                frames + frames.subList(1, frames.size - 1).reversed()
            } else {
                frames
            }
            val gifBytes = GifEncoder.encode(exportFrames, delayMs)
            WiggleProcessor.saveGifToGallery(context, gifBytes, "Wiggle_${System.currentTimeMillis()}")
        }
    }

    val message = result.fold(
        onSuccess = { uri -> if (uri != null) "GIF in Galerie gespeichert!" else "Export fehlgeschlagen" },
        onFailure = { error ->
            Log.e(TAG, "Failed exporting GIF", error)
            "Fehler beim Exportieren"
        }
    )
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
