package com.example.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.WiggleCapture
import com.example.util.WiggleProcessor
import com.example.ui.WiggleViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WiggleApp(viewModel: WiggleViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val captures by viewModel.capturesList.collectAsStateWithLifecycle()

    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)

    LaunchedEffect(cameraPermissionState.status.isGranted) {
        if (cameraPermissionState.status.isGranted) {
            viewModel.initCameraDiscovery(context)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0F1115) // Deep luxury photographic dark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0F1115))
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FilterFrames,
                        contentDescription = "3D Logo",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "WIGGLE-CAM 3D",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                IconButton(
                    onClick = { viewModel.toggleSettings() },
                    modifier = Modifier
                        .background(Color(0xFF1E2430), RoundedCornerShape(12.dp))
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Einstellungen",
                        tint = Color.White
                    )
                }
            }

            if (uiState.showSettings) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.toggleSettings() }
                )
            } else if (cameraPermissionState.status.isGranted) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        // 1. Dual Camera Workspace
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF161920))
                                .border(1.dp, Color(0xFF232A38), RoundedCornerShape(24.dp))
                        ) {
                            if (uiState.selectedCapture == null) {
                                // Camera Mode
                                PreviewAndControlLayout(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onCapture = { bA, bB ->
                                        viewModel.performDualCapture(context, bA, bB)
                                    }
                                )
                            } else {
                                // Wiggle Player Mode
                                WigglePlayerLayout(
                                    capture = uiState.selectedCapture!!,
                                    viewModel = viewModel,
                                    onCloseReview = { viewModel.selectCapture(null) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 3. Captures history / Database list
                        Text(
                            text = "GALLERY & CREATIONS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4E586E),
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )

                        if (captures.isEmpty()) {
                            EmptyGalleryState()
                        } else {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(captures) { item ->
                                    HistoryItemCard(
                                        item = item,
                                        isSelected = uiState.selectedCapture?.id == item.id,
                                        onSelect = { viewModel.selectCapture(item) },
                                        onDelete = { viewModel.deleteCapture(context, item) }
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else {
                // Request Permission view
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161920), RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0xFF232A38), RoundedCornerShape(24.dp))
                            .padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Camera",
                            tint = Color(0xFF00FFCC),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera Permission Required",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This application strictly requires dual core back camera permissions to perform dynamic alignment calibration and capture gorgeous stereoscopic depth effects.",
                            fontSize = 14.sp,
                            color = Color(0xFF8A94A6),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Grant Permission",
                                color = Color(0xFF0F1115),
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PreviewAndControlLayout(
    uiState: WiggleUiState,
    viewModel: WiggleViewModel,
    onCapture: (Bitmap?, Bitmap?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // For CameraX Fallbacks
    val previewViewA = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val previewViewB = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    var cameraControlA by remember { mutableStateOf<CameraControl?>(null) }
    var cameraControlB by remember { mutableStateOf<CameraControl?>(null) }
    
    var activeCaptureA by remember { mutableStateOf<ImageCapture?>(null) }
    var activeCaptureB by remember { mutableStateOf<ImageCapture?>(null) }
    var captureTimeoutJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var isCapturing by remember { mutableStateOf(false) }
    var cameraXPairReady by remember { mutableStateOf(false) }
    var cameraRestartToken by remember { mutableStateOf(0) }
    var stillCountdown by remember { mutableStateOf<Int?>(null) }
    val mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(context)

    // For DualCameraManager on API >= 28
    val textureViewA = remember { 
        com.example.ui.ZoomableTextureView(context)
    }
    val textureViewB = remember { 
        com.example.ui.ZoomableTextureView(context)
    }
    var dualManager by remember { mutableStateOf<com.example.util.DualCameraManager?>(null) }
    var usingDualManager by remember { mutableStateOf(false) }

    // 1. Zoom adjustment binders
    LaunchedEffect(uiState.zoomA, uiState.zoomB, cameraControlA, cameraControlB, dualManager, usingDualManager) {
        cameraControlA?.setZoomRatio(uiState.zoomA)
        cameraControlB?.setZoomRatio(uiState.zoomB)
        dualManager?.setZoom(uiState.zoomA, uiState.zoomB)
    }

    var isResumed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            isResumed = (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(uiState.primaryLens, uiState.secondaryLens, uiState.is4K, isResumed, cameraRestartToken) {
        val primary = uiState.primaryLens
        val secondary = uiState.secondaryLens

        if (isResumed && primary != null && secondary != null) {
            cameraXPairReady = false
            activeCaptureA = null
            activeCaptureB = null
            cameraControlA = null
            cameraControlB = null
            val logicalIdA = primary.parentLogicalId ?: primary.id
            val logicalIdB = secondary.parentLogicalId ?: secondary.id

            if (logicalIdA == logicalIdB && primary.id != secondary.id && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                usingDualManager = true
                
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val logicalChars = cameraManager.getCameraCharacteristics(logicalIdA)
                val sensorOrient = logicalChars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                
                val streamMap = logicalChars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val previewSizes = streamMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                val previewSize = previewSizes
                    ?.filter { s -> val r = s.width.toFloat() / s.height; Math.abs(r - 4f/3f) < 0.1f }
                    ?.filter { s -> s.width <= 1920 }  // Begrenzen für Preview-Performance
                    ?.maxByOrNull { it.width * it.height }
                    ?: android.util.Size(1440, 1080)

                val bufW = previewSize.width
                val bufH = previewSize.height

                textureViewA.sensorOrientation = sensorOrient
                textureViewB.sensorOrientation = sensorOrient
                textureViewA.bufferWidth = bufW.toFloat()
                textureViewA.bufferHeight = bufH.toFloat()
                textureViewB.bufferWidth = bufW.toFloat()
                textureViewB.bufferHeight = bufH.toFloat()

                val initDualManager = { stA: android.graphics.SurfaceTexture, stB: android.graphics.SurfaceTexture ->
                    dualManager?.stop()
                    val manager = com.example.util.DualCameraManager(
                        context = context,
                        logicalCameraId = logicalIdA,
                        physicalIdA = primary.id,
                        physicalIdB = secondary.id,
                        surfaceA = android.view.Surface(stA),
                        surfaceB = android.view.Surface(stB),
                        is4K = uiState.is4K,
                        onDualCapture = { bytesA, bytesB ->
                            try {
                                val bitmapA = android.graphics.BitmapFactory.decodeByteArray(bytesA, 0, bytesA.size)
                                val bitmapB = android.graphics.BitmapFactory.decodeByteArray(bytesB, 0, bytesB.size)
                                
                                val cropAndTransform = { bmp: android.graphics.Bitmap, zoom: Float ->
                                    val kw = (bmp.width / zoom).toInt()
                                    val kh = (bmp.height / zoom).toInt()
                                    val x = (bmp.width - kw) / 2
                                    val y = (bmp.height - kh) / 2
                                    val matrix = android.graphics.Matrix()
                                    matrix.postRotate(sensorOrient.toFloat())
                                    matrix.postScale(zoom, zoom)
                                    android.graphics.Bitmap.createBitmap(bmp, x, y, kw, kh, matrix, true)
                                }
                                
                                val aRot = cropAndTransform(bitmapA, viewModel.uiState.value.zoomA)
                                val bRot = cropAndTransform(bitmapB, viewModel.uiState.value.zoomB)
                                
                                onCapture(aRot, bRot)
                            } catch (e: Exception) {
                                android.util.Log.e("WiggleApp", "Error processing dual capture", e)
                            } finally {
                                isCapturing = false
                            }
                        },
                        onCaptureFailed = {
                            isCapturing = false
                        }
                    )
                    dualManager = manager
                    manager.start()
                }
                
                // wait for surface textures to be available
                textureViewA.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        st.setDefaultBufferSize(bufW, bufH)
                        textureViewB.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st2: android.graphics.SurfaceTexture, width2: Int, height2: Int) {
                                st2.setDefaultBufferSize(bufW, bufH)
                                initDualManager(st, st2)
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                        if (textureViewB.surfaceTexture != null) {
                            textureViewB.surfaceTexture!!.setDefaultBufferSize(bufW, bufH)
                            initDualManager(st, textureViewB.surfaceTexture!!)
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                
                // If they are already available (recomposition)
                if (textureViewA.surfaceTexture != null && textureViewB.surfaceTexture != null) {
                    textureViewA.surfaceTexture!!.setDefaultBufferSize(bufW, bufH)
                    textureViewB.surfaceTexture!!.setDefaultBufferSize(bufW, bufH)
                    initDualManager(textureViewA.surfaceTexture!!, textureViewB.surfaceTexture!!)
                }
            } else {
                usingDualManager = false
                // CameraX Fallback for separate logical cameras
                try {
                    val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                    cameraProvider.unbindAll()
                    val captureResolutionSelector = buildCaptureResolutionSelector(uiState.is4K)
                    val builderA = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(captureResolutionSelector)
                    if (primary.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                        androidx.camera.camera2.interop.Camera2Interop.Extender(builderA).setPhysicalCameraId(primary.id)
                    }
                    val capA = builderA.build()
 
                    val builderB = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(captureResolutionSelector)
                    if (secondary.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                        androidx.camera.camera2.interop.Camera2Interop.Extender(builderB).setPhysicalCameraId(secondary.id)
                    }
                    val capB = builderB.build()

                    val cameraSelectorA = findCameraSelector(cameraProvider, logicalIdA)
                    val previewBuilderA = Preview.Builder().setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy(
                                    androidx.camera.core.AspectRatio.RATIO_4_3,
                                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                                )
                            ).build()
                    ).apply {
                        if (primary.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                            androidx.camera.camera2.interop.Camera2Interop.Extender(this).setPhysicalCameraId(primary.id)
                        }
                    }.build()

                    try {
                        val cameraA = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelectorA, previewBuilderA, capA)
                        previewBuilderA.setSurfaceProvider(previewViewA.surfaceProvider)
                        cameraControlA = cameraA.cameraControl
                        activeCaptureA = capA
                    } catch (e: Exception) { Log.e("CameraBinding", "Failed A", e) }

                    if (logicalIdA != logicalIdB && uiState.concurrentPreviewSupported) {
                        val cameraSelectorB = findCameraSelector(cameraProvider, logicalIdB)
                        val previewBuilderB = Preview.Builder().setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(
                                    AspectRatioStrategy(
                                        androidx.camera.core.AspectRatio.RATIO_4_3,
                                        AspectRatioStrategy.FALLBACK_RULE_AUTO
                                    )
                                ).build()
                        ).apply {
                            if (secondary.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                                androidx.camera.camera2.interop.Camera2Interop.Extender(this).setPhysicalCameraId(secondary.id)
                            }
                        }.build()

                        try {
                            val cameraB = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelectorB, previewBuilderB, capB)
                            previewBuilderB.setSurfaceProvider(previewViewB.surfaceProvider)
                            cameraControlB = cameraB.cameraControl
                            activeCaptureB = capB
                            cameraXPairReady = true
                        } catch (e: Exception) { Log.e("CameraBinding", "Failed B", e) }
                    } else {
                        Log.d("CameraBinding", "Using sequential capture fallback for secondary lens.")
                    }

                } catch (e: Exception) {
                    Log.e("CameraBinding", "Failed fallback binding", e)
                }
            }
        }
        
        onDispose {
            dualManager?.stop()
            dualManager = null
            cameraXPairReady = false
            textureViewA.cleanup()
            textureViewB.cleanup()
        }
    }
    
    val coroutineScope = rememberCoroutineScope()

    suspend fun runStillCountdown() {
        for (count in 3 downTo 1) {
            stillCountdown = count
            delay(700)
        }
        stillCountdown = 0
        delay(180)
    }

    suspend fun captureSelectedLensesSequentially(): List<Bitmap> {
        val selectedLenses = mutableListOf<CameraLensDetails>()
        uiState.primaryLens?.let { selectedLenses.add(it) }
        if (uiState.lensCount <= 2) {
            uiState.secondaryLens?.let { selectedLenses.add(it) }
        } else {
            selectedLenses.addAll(uiState.secondaryLenses)
        }

        val capturedBitmaps = mutableListOf<Bitmap>()
        val uniqueLenses = selectedLenses.distinctBy { it.id }
        for ((index, lens) in uniqueLenses.withIndex()) {
            val limits = uiState.zoomLimitsMap[lens.id] ?: Pair(1f, 3f)
            val zoom = (uiState.zoomMap[lens.id] ?: 1f).coerceIn(limits.first, limits.second)
            val bitmap = try {
                if (index == 0 && lens.id == uiState.primaryLens?.id && activeCaptureA != null) {
                    val activePrimaryBitmap = try {
                        try {
                            cameraControlA?.setZoomRatio(zoom)?.await(context)
                        } catch (e: Exception) {
                            Log.w("WiggleApp", "Could not apply primary zoom before sequential capture", e)
                        }
                        captureCameraXBitmap(activeCaptureA!!, mainExecutor)
                    } catch (e: Exception) {
                        Log.w("WiggleApp", "Active primary capture failed; rebinding lens ${lens.id}", e)
                        null
                    }
                    activePrimaryBitmap ?: captureLensStillFrame(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewViewA,
                        lens = lens,
                        zoom = zoom,
                        is4K = uiState.is4K,
                        mainExecutor = mainExecutor,
                        settleDelayMs = 220L
                    )
                } else {
                    captureLensStillFrame(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewViewA,
                        lens = lens,
                        zoom = zoom,
                        is4K = uiState.is4K,
                        mainExecutor = mainExecutor,
                        settleDelayMs = if (index == 0) 220L else 140L
                    )
                }
            } catch (e: Exception) {
                Log.e("WiggleApp", "Sequential capture failed for lens ${lens.id}", e)
                null
            }

            if (bitmap != null) {
                capturedBitmaps.add(bitmap)
            } else {
                Log.e("WiggleApp", "Sequential capture returned no bitmap for lens ${lens.id}")
            }
        }
        return capturedBitmaps
    }

    val executeCapture = {
        if (!isCapturing && !uiState.isCapturing) {
            isCapturing = true

            val canUseDualManager = uiState.lensCount == 2 && usingDualManager && dualManager != null
            val canUseCameraXPair = uiState.lensCount == 2 && !usingDualManager && cameraXPairReady && activeCaptureA != null && activeCaptureB != null

            if (canUseDualManager) {
                val mgr = dualManager
                if (mgr != null) {
                    mgr.takePicture()
                    captureTimeoutJob?.cancel()
                    captureTimeoutJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(5000)
                        if (isCapturing) {
                            isCapturing = false
                            Toast.makeText(context, "Aufnahme Timeout", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    isCapturing = false
                    Toast.makeText(context, "Kamera nicht bereit", Toast.LENGTH_SHORT).show()
                }
            } else if (canUseCameraXPair) {
                val cA = activeCaptureA
                val cB = activeCaptureB

                if (cA != null && cB != null) {
                    var bitmapA: Bitmap? = null
                    var bitmapB: Bitmap? = null
                    val captureCounter = AtomicInteger(2)

                    captureTimeoutJob?.cancel()
                    captureTimeoutJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(5000)
                        if (isCapturing) {
                            isCapturing = false
                            Toast.makeText(context, "Aufnahme Timeout", Toast.LENGTH_SHORT).show()
                        }
                    }

                    val checkComplete = {
                        if (captureCounter.decrementAndGet() == 0) {
                            captureTimeoutJob?.cancel()
                            if (bitmapA != null && bitmapB != null) {
                                onCapture(bitmapA, bitmapB)
                            } else {
                                Toast.makeText(context, "Aufnahme fehlgeschlagen", Toast.LENGTH_SHORT).show()
                            }
                            isCapturing = false
                        }
                    }

                    cA.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                bitmapA = imageProxyToBitmap(image)
                            } finally {
                                image.close()
                                checkComplete()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("Wiggle", "Capture A failed", exception)
                            checkComplete()
                        }
                    })

                    cB.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                bitmapB = imageProxyToBitmap(image)
                            } finally {
                                image.close()
                                checkComplete()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("Wiggle", "Capture B failed", exception)
                            checkComplete()
                        }
                    })
                } else {
                    Toast.makeText(context, "Kameras nicht bereit", Toast.LENGTH_SHORT).show()
                    isCapturing = false
                }
            } else {
                coroutineScope.launch {
                    try {
                        runStillCountdown()
                        val capturedBitmaps = captureSelectedLensesSequentially()
                        if (capturedBitmaps.size >= 2) {
                            viewModel.performMultiCapture(context, capturedBitmaps)
                        } else {
                            Toast.makeText(context, "Aufnahme fehlgeschlagen", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("WiggleApp", "Sequential capture failed", e)
                        Toast.makeText(context, "Aufnahme fehlgeschlagen", Toast.LENGTH_SHORT).show()
                    } finally {
                        stillCountdown = null
                        isCapturing = false
                        cameraRestartToken += 1
                    }
                }
            }
        }
    }

    LaunchedEffect(uiState.captureTrigger) {
        if (uiState.captureTrigger > 0) {
            executeCapture()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Primary Screen Viewfinder (Camera A)
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { textureViewA }, 
                modifier = Modifier.fillMaxSize().alpha(if (usingDualManager) 1f else 0f),
                update = { view -> view.updateZoom(uiState.zoomA) }
            )
            AndroidView(
                factory = { previewViewA }, 
                modifier = Modifier.fillMaxSize().alpha(if (!usingDualManager) 1f else 0f)
            )
        }

        // Hidden Secondary Viewfinder (Camera B) - Must exist for SurfaceTexture to be active & available
        Box(
            modifier = Modifier
                .size(640.dp, 480.dp)
                .alpha(0.01f)
        ) {
            AndroidView(
                factory = { textureViewB }, 
                modifier = Modifier.fillMaxSize().alpha(if (usingDualManager) 1f else 0f),
                update = { view -> view.updateZoom(uiState.zoomB) }
            )
            AndroidView(
                factory = { previewViewB }, 
                modifier = Modifier.fillMaxSize().alpha(if (!usingDualManager) 1f else 0f)
            )
        }

        if (stillCountdown != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (stillCountdown == 0) "JETZT STILL" else stillCountdown.toString(),
                        fontSize = if (stillCountdown == 0) 30.sp else 64.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Bitte ganz ruhig halten",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FFCC),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        
        // Camera Mode Indicator Pill Top Left
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xD90D0F12), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF232A38), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val simultaneousReady = uiState.lensCount == 2 && ((usingDualManager && dualManager != null) || cameraXPairReady)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (simultaneousReady) Color(0xFF00FFCC) else Color(0xFFFFCC00), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (simultaneousReady) "SIMULTAN (2 Linsen)" else "SEQUENTIELL (${uiState.lensCount} Linsen)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Stereo Active indicator Top Right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "STEREO ACTIVE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FFCC)
                )
                Text(
                    text = "Kamera live",
                    fontSize = 8.sp,
                    color = Color.LightGray
                )
            }
        }
        
        // Dynamic Zoom Controls Bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 120.dp)
                .background(Color(0xCC0D0F12), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF232A38), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Primary Lens Slider
            val pLens = uiState.primaryLens
            if (pLens != null) {
                val pLimits = uiState.zoomLimitsMap[pLens.id] ?: Pair(1f, 3f)
                val pZoom = (uiState.zoomMap[pLens.id] ?: 1.0f).coerceIn(pLimits.first, pLimits.second)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ZOOM (HAUPTLINSE): ${String.format(java.util.Locale.US, "%.2fx", pZoom)}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FFCC)
                        )
                        Text(
                            text = pLens.name,
                            fontSize = 8.sp,
                            color = Color.Gray
                        )
                    }
                    Slider(
                        value = pZoom,
                        onValueChange = { viewModel.setZoomForLens(pLens.id, it) },
                        valueRange = pLimits.first..pLimits.second,
                        modifier = Modifier.height(24.dp)
                    )
                }
            }

            // Slider for each active secondary lens
            uiState.secondaryLenses.forEachIndexed { index, sLens ->
                val sLimits = uiState.zoomLimitsMap[sLens.id] ?: Pair(1f, 3f)
                val sZoom = (uiState.zoomMap[sLens.id] ?: 1.0f).coerceIn(sLimits.first, sLimits.second)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ZOOM (SEKUNDÄRLINSE ${index + 1}): ${String.format(java.util.Locale.US, "%.2fx", sZoom)}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = sLens.name,
                            fontSize = 8.sp,
                            color = Color.Gray
                        )
                    }
                    Slider(
                        value = sZoom,
                        onValueChange = { viewModel.setZoomForLens(sLens.id, it) },
                        valueRange = sLimits.first..sLimits.second,
                        modifier = Modifier.height(24.dp)
                    )
                }
            }

            // Auto-Zoom Calibration Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (uiState.isCalibrating) Color(0xFF2C3545)
                            else if (uiState.isAutoZoomApplied) Color(0xFF00FFCC)
                            else Color(0xFF1E2430)
                        )
                        .border(
                            1.dp,
                            if (uiState.isCalibrating) Color(0xFF3B485E)
                            else if (uiState.isAutoZoomApplied) Color(0xFF00FFCC)
                            else Color(0xFF232A38),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = !uiState.isCalibrating) {
                            coroutineScope.launch {
                                val freshState = viewModel.uiState.value
                                viewModel.setCalibrating(true)
                                var restartCameraAfterCalibration = false
                                try {
                                    kotlinx.coroutines.withTimeout(15_000L) {
                                        val results = mutableMapOf<String, MutableList<Float>>()
                                        val originalSecondary = freshState.secondaryLens
                                        val originalZoomB = freshState.zoomB
                                        val primaryLens = freshState.primaryLens
                                        val useSequentialCalibration = !usingDualManager && !cameraXPairReady
                                        restartCameraAfterCalibration = useSequentialCalibration
                                        
                                        // Loop through all secondary lenses
                                        for (lens in freshState.secondaryLenses) {
                                            if (freshState.secondaryLens?.id != lens.id) {
                                                viewModel.setSecondaryLens(lens)
                                                delay(if (useSequentialCalibration) 150 else 800)
                                            }
                                            
                                            val limits = freshState.zoomLimitsMap[lens.id] ?: Pair(1.0f, 3.0f)
                                            val minZoom = limits.first.coerceAtLeast(1.0f)
                                            val maxZoom = limits.second
                                            val passes = 3

                                            if (!useSequentialCalibration) {
                                                viewModel.setZoomB(minZoom)
                                                delay(400)
                                            }
                                            
                                            // Run visual matching multiple times and collect results
                                            for (i in 0 until passes) {
                                                val bmpPrimary = when {
                                                    usingDualManager -> textureViewA.getBitmap(640, 480)
                                                    useSequentialCalibration && primaryLens != null -> captureLensStillFrame(
                                                        context = context,
                                                        lifecycleOwner = lifecycleOwner,
                                                        previewView = previewViewA,
                                                        lens = primaryLens,
                                                        zoom = freshState.zoomMap[primaryLens.id] ?: 1f,
                                                        is4K = freshState.is4K,
                                                        mainExecutor = mainExecutor,
                                                        settleDelayMs = 250L
                                                    )
                                                    else -> previewViewA.bitmap
                                                }
                                                
                                                val bmpSecondary = when {
                                                    usingDualManager -> textureViewB.getBitmap(640, 480)
                                                    useSequentialCalibration -> captureLensStillFrame(
                                                        context = context,
                                                        lifecycleOwner = lifecycleOwner,
                                                        previewView = previewViewA,
                                                        lens = lens,
                                                        zoom = minZoom,
                                                        is4K = freshState.is4K,
                                                        mainExecutor = mainExecutor,
                                                        settleDelayMs = 250L
                                                    )
                                                    else -> previewViewB.bitmap
                                                }
                                                
                                                if (bmpPrimary != null && bmpSecondary != null && bmpSecondary.width > 50 && bmpSecondary.height > 50) {
                                                    val bestZoom = viewModel.calculateVisualZoomMatch(bmpPrimary, bmpSecondary, minZoom, maxZoom)
                                                    results.getOrPut(lens.id) { mutableListOf() }.add(bestZoom)
                                                } else {
                                                    android.util.Log.e("WiggleApp", "Cannot calibrate iteration $i for lens ${lens.id}: bmpPrimary=$bmpPrimary, bmpSecondary=$bmpSecondary")
                                                }
                                                
                                                if (i < passes - 1) {
                                                    delay(if (useSequentialCalibration) 120 else 100)
                                                }
                                            }
                                        }
                                        
                                        // Restore original secondary lens and zoom if we switched it
                                        if (originalSecondary != null && freshState.secondaryLens?.id != originalSecondary.id) {
                                            viewModel.setSecondaryLens(originalSecondary)
                                            viewModel.setZoomB(originalZoomB)
                                            delay(500)
                                        }
                                        
                                        if (results.isEmpty() || results.values.all { it.isEmpty() }) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(context, "Kalibrierung fehlgeschlagen – Kamera nicht bereit", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            viewModel.applyCalibrationResults(results)
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, "Kalibrierung Timeout", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("WiggleApp", "Error during ML calibration", e)
                                    viewModel.setCalibrating(false)
                                } finally {
                                    viewModel.setCalibrating(false)
                                    if (restartCameraAfterCalibration) {
                                        cameraRestartToken += 1
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (uiState.isCalibrating) {
                            CircularProgressIndicator(
                                color = Color(0xFF00FFCC),
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp
                            )
                        }
                        Text(
                            text = if (uiState.isCalibrating) "KALIBRIERE..." else if (uiState.isAutoZoomApplied) "✓ AUTO" else "⚡ AUTO KALIBRIERUNG",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = if (uiState.isCalibrating) Color(0xFF8A94A6) else if (uiState.isAutoZoomApplied) Color.Black else Color(0xFF00FFCC),
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        // Central physical Shutter button bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF0F1115))
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isCapturing || uiState.isCapturing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00FFCC), modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ALIGNING & INTERPOLATING...",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FFCC),
                        letterSpacing = 1.sp
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .padding(6.dp)
                        .border(3.dp, Color.White, CircleShape)
                        .clickable { executeCapture() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0xFF00FFCC), CircleShape)
                    )
                }
            }
        }
    }
}


fun updateTextureViewTransform(
    textureView: android.view.TextureView, 
    viewWidth: Float, 
    viewHeight: Float, 
    zoom: Float,
    sensorOrientation: Int = 90,
    bufferWidth: Float = 1440f,
    bufferHeight: Float = 1080f
) {
    if (viewWidth == 0f || viewHeight == 0f) return

    val matrix = android.graphics.Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    // 1. Keine zusätzliche Rotation der TextureView nötig, da die SurfaceTexture
    //    den Preview-Stream bereits intern an die native Geräteausrichtung (Portrait) anpasst.
    val rotation = 0f
    matrix.postRotate(rotation, centerX, centerY)

    // 2. Nach der internen SurfaceTexture-Rotation liegt der Buffer bereits im Hochformat vor.
    //    Daher sind Breite und Höhe vertauscht:
    val bufferPortraitWidth = bufferHeight
    val bufferPortraitHeight = bufferWidth

    // 3. Gleichmäßige Skalierung (Center-Crop) zur Beseitigung von Verzerrungen
    val scaleFill = maxOf(viewWidth / bufferPortraitWidth, viewHeight / bufferPortraitHeight)
    val scaleX = scaleFill * (bufferPortraitWidth / viewWidth)
    val scaleY = scaleFill * (bufferPortraitHeight / viewHeight)
    matrix.postScale(scaleX, scaleY, centerX, centerY)

    // 4. Manueller/Automatischer Zoom
    matrix.postScale(zoom, zoom, centerX, centerY)

    textureView.setTransform(matrix)
}

class ZoomableTextureView(context: Context) : android.view.TextureView(context) {
    var currentZoom: Float = 1f
    var sensorOrientation: Int = 90
        set(value) { field = value; applyTransform() }
    var bufferWidth: Float = 1440f
        set(value) { field = value; applyTransform() }
    var bufferHeight: Float = 1080f
        set(value) { field = value; applyTransform() }
    private var lastWidth: Float = 0f
    private var lastHeight: Float = 0f

    private val layoutListener = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        lastWidth = (right - left).toFloat()
        lastHeight = (bottom - top).toFloat()
        applyTransform()
    }

    init {
        addOnLayoutChangeListener(layoutListener)
    }

    private fun applyTransform() {
        if (lastWidth > 0 && lastHeight > 0) {
            updateTextureViewTransform(this, lastWidth, lastHeight, currentZoom, sensorOrientation, bufferWidth, bufferHeight)
        }
    }

    fun updateZoom(newZoom: Float) {
        currentZoom = newZoom
        applyTransform()
    }

    fun cleanup() {
        removeOnLayoutChangeListener(layoutListener)
    }
}

private fun buildCaptureResolutionSelector(is4K: Boolean): androidx.camera.core.resolutionselector.ResolutionSelector {
    val resolutionStrategy = if (is4K) {
        androidx.camera.core.resolutionselector.ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
    } else {
        androidx.camera.core.resolutionselector.ResolutionStrategy(
            android.util.Size(1920, 1440),
            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
        )
    }
    return androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            androidx.camera.core.resolutionselector.AspectRatioStrategy(
                androidx.camera.core.AspectRatio.RATIO_4_3,
                androidx.camera.core.resolutionselector.AspectRatioStrategy.FALLBACK_RULE_AUTO
            )
        )
        .setResolutionStrategy(resolutionStrategy)
        .build()
}

private suspend fun captureLensStillFrame(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    lens: CameraLensDetails,
    zoom: Float,
    is4K: Boolean,
    mainExecutor: Executor,
    settleDelayMs: Long
): Bitmap? {
    val cameraProvider = ProcessCameraProvider.getInstance(context).await(context)
    cameraProvider.unbindAll()

    val logicalId = lens.parentLogicalId ?: lens.id
    val imageCaptureBuilder = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(buildCaptureResolutionSelector(is4K))
    if (lens.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
        androidx.camera.camera2.interop.Camera2Interop.Extender(imageCaptureBuilder)
            .setPhysicalCameraId(lens.id)
    }
    val imageCapture = imageCaptureBuilder.build()

    val previewBuilder = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(
                        androidx.camera.core.AspectRatio.RATIO_4_3,
                        AspectRatioStrategy.FALLBACK_RULE_AUTO
                    )
                ).build()
        )
    val previewExtender = androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
    if (lens.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
        previewExtender.setPhysicalCameraId(lens.id)
    }

    // After a full unbind/rebind the auto-exposure and auto-white-balance loops
    // restart from scratch. Capturing a calibration frame before 3A has
    // converged yields frames with unpredictable brightness, which makes the
    // ZNCC zoom match non-deterministic across app starts. Monitor the preview
    // capture results and wait until AE + AWB report a stable state.
    val converged = kotlinx.coroutines.CompletableDeferred<Unit>()
    var stableFrames = 0
    previewExtender.setSessionCaptureCallback(
        object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: android.hardware.camera2.CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult
            ) {
                val aeState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                val awbState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE)
                val aeStable = aeState == null ||
                    aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED ||
                    aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                val awbStable = awbState == null ||
                    awbState == android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                    awbState == android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE_LOCKED
                if (aeStable && awbStable) {
                    stableFrames += 1
                    if (stableFrames >= AE_STABLE_FRAME_COUNT && !converged.isCompleted) {
                        converged.complete(Unit)
                    }
                } else {
                    stableFrames = 0
                }
            }
        }
    )
    val preview = previewBuilder.build()
    preview.setSurfaceProvider(previewView.surfaceProvider)

    val cameraSelector = findCameraSelector(cameraProvider, logicalId)
    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
    try {
        camera.cameraControl.setZoomRatio(zoom).await(context)
    } catch (e: Exception) {
        Log.w("WiggleApp", "Could not apply zoom before sequential lens capture", e)
    }
    val didConverge = kotlinx.coroutines.withTimeoutOrNull(AE_CONVERGENCE_TIMEOUT_MS) {
        converged.await()
    } != null
    if (!didConverge) {
        Log.w("WiggleApp", "3A did not converge within ${AE_CONVERGENCE_TIMEOUT_MS}ms for lens ${lens.id}; capturing anyway")
    }
    delay(settleDelayMs)

    return captureCameraXBitmap(imageCapture, mainExecutor)
}

// Number of consecutive preview frames that must report stable AE/AWB before a
// calibration frame is captured, plus a hard timeout so calibration never hangs
// on devices that do not report 3A states.
private const val AE_STABLE_FRAME_COUNT = 3
private const val AE_CONVERGENCE_TIMEOUT_MS = 2500L

private suspend fun captureCameraXBitmap(
    imageCapture: ImageCapture,
    mainExecutor: Executor
): Bitmap? {
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        imageCapture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    continuation.resumeWith(Result.success(imageProxyToBitmap(image)))
                } catch (e: Exception) {
                    continuation.resumeWith(Result.failure(e))
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWith(Result.failure(exception))
            }
        })
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val tempBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

    val crop = image.cropRect
    val scaleX = tempBitmap.width.toFloat() / image.width.toFloat()
    val scaleY = tempBitmap.height.toFloat() / image.height.toFloat()

    val cropWidth = (crop.width() * scaleX).toInt().coerceIn(1, tempBitmap.width)
    val cropHeight = (crop.height() * scaleY).toInt().coerceIn(1, tempBitmap.height)
    val cropX = (crop.left * scaleX).toInt().coerceIn(0, tempBitmap.width - cropWidth)
    val cropY = (crop.top * scaleY).toInt().coerceIn(0, tempBitmap.height - cropHeight)

    val matrix = android.graphics.Matrix()
    matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
    matrix.postScale(
        tempBitmap.width.toFloat() / cropWidth,
        tempBitmap.height.toFloat() / cropHeight
    )

    return android.graphics.Bitmap.createBitmap(
        tempBitmap,
        cropX,
        cropY,
        cropWidth,
        cropHeight,
        matrix,
        true
    )
}

private fun findCameraSelector(cameraProvider: ProcessCameraProvider, cameraId: String): CameraSelector {
    for (info in cameraProvider.availableCameraInfos) {
        val cid = androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId
        if (cid == cameraId) {
            return CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { it == info }
                }
                .build()
        }
    }
    return CameraSelector.DEFAULT_BACK_CAMERA
}

@Composable
fun WigglePlayerLayout(
    capture: com.example.data.WiggleCapture,
    viewModel: WiggleViewModel,
    onCloseReview: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Read multi-frame image paths
    val imagePaths = remember(capture.imagePaths) { capture.getImagePathList() }
    val loadedBitmaps = remember(imagePaths) {
        imagePaths.mapNotNull { WiggleProcessor.loadBitmap(it) }
    }

    var isPingPong by remember { mutableStateOf(true) }
    var delayTimeMs by remember { mutableStateOf(250f) } // Default 250ms (4 fps)
    
    var currentFrameIndex by remember { mutableStateOf(0) }
    var direction by remember { mutableStateOf(1) } // 1 for forward, -1 for backward

    // Multi-frame looping playback logic
    LaunchedEffect(loadedBitmaps, isPingPong, delayTimeMs) {
        if (loadedBitmaps.isEmpty()) return@LaunchedEffect
        
        while (true) {
            delay(delayTimeMs.toLong())
            
            if (isPingPong) {
                if (loadedBitmaps.size <= 1) {
                    currentFrameIndex = 0
                } else {
                    val nextIndex = currentFrameIndex + direction
                    if (nextIndex >= loadedBitmaps.size) {
                        direction = -1
                        currentFrameIndex = (loadedBitmaps.size - 2).coerceAtLeast(0)
                    } else if (nextIndex < 0) {
                        direction = 1
                        currentFrameIndex = 1.coerceAtMost(loadedBitmaps.size - 1)
                    } else {
                        currentFrameIndex = nextIndex
                    }
                }
            } else {
                currentFrameIndex = (currentFrameIndex + 1) % loadedBitmaps.size
            }
        }
    }

    val activeDisplayBitmap = loadedBitmaps.getOrNull(currentFrameIndex)
    var isExporting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Player screen area
        Box(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (activeDisplayBitmap != null) {
                Image(
                    bitmap = activeDisplayBitmap.asImageBitmap(),
                    contentDescription = "Holographic Parallax Feed",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Return / Close button overlay
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(Color(0x990D0F12), CircleShape)
                    .clickable { onCloseReview() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Loop Frame Number Indicator
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "LINSE ${currentFrameIndex + 1}/${loadedBitmaps.size}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FFCC)
                )
            }
        }

        // Live Controls Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161920))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "3D WIGGLE CONTROLLER",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Loop Mode Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Loop-Modus", fontSize = 13.sp, color = Color.LightGray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { isPingPong = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPingPong) Color(0xFF00FFCC) else Color(0xFF232A38)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Ping-Pong",
                            fontSize = 11.sp,
                            color = if (isPingPong) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { isPingPong = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isPingPong) Color(0xFF00FFCC) else Color(0xFF232A38)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Loop",
                            fontSize = 11.sp,
                            color = if (!isPingPong) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Speed Control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Geschwindigkeit", fontSize = 13.sp, color = Color.LightGray)
                Text("${delayTimeMs.toInt()} ms", fontSize = 13.sp, color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = delayTimeMs,
                onValueChange = { delayTimeMs = it },
                valueRange = 50f..800f,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Export Actions
            if (isExporting) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF00FFCC), modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Exportiere GIF...", fontSize = 11.sp, color = Color(0xFF00FFCC))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            isExporting = true
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                                try {
                                    val exportFrames = mutableListOf<Bitmap>()
                                    if (isPingPong && loadedBitmaps.size > 2) {
                                        exportFrames.addAll(loadedBitmaps)
                                        for (i in (loadedBitmaps.size - 2) downTo 1) {
                                            exportFrames.add(loadedBitmaps[i])
                                        }
                                    } else {
                                        exportFrames.addAll(loadedBitmaps)
                                    }
                                    
                                    val gifBytes = com.example.util.GifEncoder.encode(exportFrames, delayTimeMs.toInt())
                                    val savedUri = WiggleProcessor.saveGifToGallery(context, gifBytes, "Wiggle_${System.currentTimeMillis()}")
                                    
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        if (savedUri != null) {
                                            Toast.makeText(context, "GIF in Galerie gespeichert!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
                                        }
                                        isExporting = false
                                    }
                                } catch (e: Exception) {
                                    Log.e("WiggleApp", "Failed exporting GIF", e)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, "Fehler beim Exportieren", Toast.LENGTH_SHORT).show()
                                        isExporting = false
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Gif, contentDescription = "GIF", tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("GIF Exportieren", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("KI Prompt", capture.prompt)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Prompt kopiert!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232A38)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Prompt kopieren", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyGalleryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(Color(0xFF161920), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF232A38), RoundedCornerShape(16.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.PermCameraMic,
            contentDescription = "No images",
            tint = Color(0xFF3B4861),
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "No stereoscopic animations captured yet",
            fontSize = 12.sp,
            color = Color(0xFF8A94A6)
        )
        Text(
            "Tap shutter to generate first 3D wiggle photo!",
            fontSize = 10.sp,
            color = Color(0xFF4E586E)
        )
    }
}

@Composable
fun HistoryItemCard(
    item: com.example.data.WiggleCapture,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val df = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val formattedTime = remember(item.timestamp) { df.format(java.util.Date(item.timestamp)) }

    Box(
        modifier = Modifier
            .size(110.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF161920))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) Color(0xFF00FFCC) else Color(0xFF232A38),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onSelect() }
    ) {
        // Thumbnail loading
        AsyncImage(
            model = item.getThumbnailFile(),
            contentDescription = "Wiggle capture",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Delete top-right trigger
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .background(Color(0xCC0D0F12), CircleShape)
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = "Delete from history",
                tint = Color(0xFFFF4D4D),
                modifier = Modifier.size(14.dp)
            )
        }

        // Time indicator bottom-left
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color(0x990D0F12), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = formattedTime,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(context: Context): T {
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation_proc ->
        addListener({
            try {
                continuation_proc.resumeWith(Result.success(get()))
            } catch (e: Exception) {
                continuation_proc.resumeWith(Result.failure(e))
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }
}

// Border utility for consistent Material design 
object BoxDefaults {
    fun borderStrokeWithSecondary() = BorderStroke(1.dp, Color(0xFF232A38))
}
