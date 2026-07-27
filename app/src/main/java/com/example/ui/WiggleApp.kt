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
import androidx.compose.ui.platform.LocalLifecycleOwner
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

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E2430),
                    modifier = Modifier.clickable {
                        Toast.makeText(context, "Dual Camera Calibration Locked", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF00FFCC), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "STEREO ACTIVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            if (cameraPermissionState.status.isGranted) {
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
    
    var isCapturing by remember { mutableStateOf(false) }
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

    DisposableEffect(uiState.primaryLens, uiState.secondaryLens, isResumed) {
        val primary = uiState.primaryLens
        val secondary = uiState.secondaryLens

        if (isResumed && primary != null && secondary != null) {
            val logicalIdA = primary.parentLogicalId ?: primary.id
            val logicalIdB = secondary.parentLogicalId ?: secondary.id

            if (logicalIdA == logicalIdB && primary.id != secondary.id && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                usingDualManager = true
                
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val logicalChars = cameraManager.getCameraCharacteristics(logicalIdA)
                val sensorOrient = logicalChars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                
                textureViewA.sensorOrientation = sensorOrient
                textureViewB.sensorOrientation = sensorOrient

                val initDualManager = { stA: android.graphics.SurfaceTexture, stB: android.graphics.SurfaceTexture ->
                    dualManager?.stop()
                    val manager = com.example.util.DualCameraManager(
                        context = context,
                        logicalCameraId = logicalIdA,
                        physicalIdA = primary.id,
                        physicalIdB = secondary.id,
                        surfaceA = android.view.Surface(stA),
                        surfaceB = android.view.Surface(stB),
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
                        st.setDefaultBufferSize(1440, 1080)
                        textureViewB.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st2: android.graphics.SurfaceTexture, width2: Int, height2: Int) {
                                st2.setDefaultBufferSize(1440, 1080)
                                initDualManager(st, st2)
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                        if (textureViewB.surfaceTexture != null) {
                            textureViewB.surfaceTexture!!.setDefaultBufferSize(1440, 1080)
                            initDualManager(st, textureViewB.surfaceTexture!!)
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                
                // If they are already available (recomposition)
                if (textureViewA.surfaceTexture != null && textureViewB.surfaceTexture != null) {
                    textureViewA.surfaceTexture!!.setDefaultBufferSize(1440, 1080)
                    textureViewB.surfaceTexture!!.setDefaultBufferSize(1440, 1080)
                    initDualManager(textureViewA.surfaceTexture!!, textureViewB.surfaceTexture!!)
                }
            } else {
                usingDualManager = false
                // CameraX Fallback for separate logical cameras
                try {
                    val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                    cameraProvider.unbindAll()

                    val builderA = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    if (primary.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                        androidx.camera.camera2.interop.Camera2Interop.Extender(builderA).setPhysicalCameraId(primary.id)
                    }
                    val capA = builderA.build()

                    val builderB = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    if (secondary.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                        androidx.camera.camera2.interop.Camera2Interop.Extender(builderB).setPhysicalCameraId(secondary.id)
                    }
                    val capB = builderB.build()

                    activeCaptureA = capA
                    activeCaptureB = capB

                    val cameraSelectorA = findCameraSelector(cameraProvider, logicalIdA)
                    val previewBuilderA = Preview.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3).apply {
                        if (primary.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                            androidx.camera.camera2.interop.Camera2Interop.Extender(this).setPhysicalCameraId(primary.id)
                        }
                    }.build()

                    try {
                        val cameraA = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelectorA, previewBuilderA, capA)
                        previewBuilderA.setSurfaceProvider(previewViewA.surfaceProvider)
                        cameraControlA = cameraA.cameraControl
                    } catch (e: Exception) { Log.e("CameraBinding", "Failed A", e) }

                    if (logicalIdA != logicalIdB) {
                        val cameraSelectorB = findCameraSelector(cameraProvider, logicalIdB)
                        val previewBuilderB = Preview.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3).apply {
                            if (secondary.parentLogicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                                androidx.camera.camera2.interop.Camera2Interop.Extender(this).setPhysicalCameraId(secondary.id)
                            }
                        }.build()

                        try {
                            val cameraB = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelectorB, previewBuilderB, capB)
                            previewBuilderB.setSurfaceProvider(previewViewB.surfaceProvider)
                            cameraControlB = cameraB.cameraControl
                        } catch (e: Exception) { Log.e("CameraBinding", "Failed B", e) }
                    } else {
                        // We cannot bind B if it's the exact same logical camera and uses CameraX.
                        // We'll just leave B empty if DualCameraManager didn't catch it
                        Log.e("CameraBinding", "Cannot bind two previews to same logical camera in CameraX fallback.")
                    }

                } catch (e: Exception) {
                    Log.e("CameraBinding", "Failed fallback binding", e)
                }
            }
        }
        
        onDispose {
            dualManager?.stop()
            dualManager = null
        }
    }
    
    val executeCapture = remember(usingDualManager, dualManager, activeCaptureA, activeCaptureB) {
        {
            if (!isCapturing && !uiState.isCapturing) {
                isCapturing = true
                if (usingDualManager && dualManager != null) {
                    dualManager?.takePicture()
                } else {
                    val cA = activeCaptureA
                    val cB = activeCaptureB
                    
                    if (cA != null && cB != null) {
                        var bitmapA: Bitmap? = null
                        var bitmapB: Bitmap? = null
                        
                        val checkComplete = {
                            if (bitmapA != null && bitmapB != null) {
                                onCapture(bitmapA, bitmapB)
                                isCapturing = false
                            }
                        }

                        cA.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                            val matrix = android.graphics.Matrix()
                            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
                            
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val tempBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            
                            val crop = image.cropRect
                            val scaleX = tempBitmap.width.toFloat() / image.width.toFloat()
                            val scaleY = tempBitmap.height.toFloat() / image.height.toFloat()
                            
                            // Account for potential rounding errors
                            val cw = (crop.width() * scaleX).toInt().coerceAtMost(tempBitmap.width)
                            val ch = (crop.height() * scaleY).toInt().coerceAtMost(tempBitmap.height)
                            val cx = (crop.left * scaleX).toInt().coerceAtMost(tempBitmap.width - cw)
                            val cy = (crop.top * scaleY).toInt().coerceAtMost(tempBitmap.height - ch)
                            
                            matrix.postScale(tempBitmap.width.toFloat() / cw, tempBitmap.height.toFloat() / ch)
                            
                            bitmapA = android.graphics.Bitmap.createBitmap(
                                tempBitmap, cx, cy, cw, ch, matrix, true
                            )
                            image.close()
                            checkComplete()
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("Wiggle", "Capture A failed", exception)
                            isCapturing = false
                        }
                    })
                    
                    cB.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                            val matrix = android.graphics.Matrix()
                            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
                            
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val tempBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            
                            val crop = image.cropRect
                            val scaleX = tempBitmap.width.toFloat() / image.width.toFloat()
                            val scaleY = tempBitmap.height.toFloat() / image.height.toFloat()
                            
                            val cw = (crop.width() * scaleX).toInt().coerceAtMost(tempBitmap.width)
                            val ch = (crop.height() * scaleY).toInt().coerceAtMost(tempBitmap.height)
                            val cx = (crop.left * scaleX).toInt().coerceAtMost(tempBitmap.width - cw)
                            val cy = (crop.top * scaleY).toInt().coerceAtMost(tempBitmap.height - ch)
                            
                            matrix.postScale(tempBitmap.width.toFloat() / cw, tempBitmap.height.toFloat() / ch)
                            
                            bitmapB = android.graphics.Bitmap.createBitmap(
                                tempBitmap, cx, cy, cw, ch, matrix, true
                            )
                            image.close()
                            checkComplete()
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("Wiggle", "Capture B failed", exception)
                            isCapturing = false
                        }
                    })
                    } else {
                        Toast.makeText(context, "Kameras nicht bereit", Toast.LENGTH_SHORT).show()
                        isCapturing = false
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
        
        // Settings Overlay / Lens Selection Top Left
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xD90D0F12), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF232A38), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = "LENS CONFIGURATION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                var expandedA by remember { mutableStateOf(false) }
                Column(modifier = Modifier.clickable { expandedA = true }.fillMaxWidth(0.6f).padding(vertical = 4.dp)) {
                    Text(text = "LENS A", fontSize = 10.sp, color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold)
                    Text(
                        text = uiState.primaryLens?.name ?: "Unknown",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
                DropdownMenu(expanded = expandedA, onDismissRequest = { expandedA = false }) {
                    uiState.availableLenses.forEach { lens ->
                        DropdownMenuItem(
                            text = { Text(lens.name, fontSize = 12.sp) },
                            onClick = {
                                viewModel.setPrimaryLens(lens)
                                expandedA = false
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth(0.6f).height(1.dp).background(Color(0xFF232A38)))
                Spacer(modifier = Modifier.height(4.dp))
                
                var expandedB by remember { mutableStateOf(false) }
                Column(modifier = Modifier.clickable { expandedB = true }.fillMaxWidth(0.6f).padding(vertical = 4.dp)) {
                    Text(text = "LENS B", fontSize = 10.sp, color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold)
                    Text(
                        text = uiState.secondaryLens?.name ?: "Unknown",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
                DropdownMenu(expanded = expandedB, onDismissRequest = { expandedB = false }) {
                    uiState.availableLenses.forEach { lens ->
                        DropdownMenuItem(
                            text = { Text(lens.name, fontSize = 12.sp) },
                            onClick = {
                                viewModel.setSecondaryLens(lens)
                                expandedB = false
                            }
                        )
                    }
                }
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
                    text = "Hauptlinse live",
                    fontSize = 8.sp,
                    color = Color.LightGray
                )
            }
        }
        
        // Zoom Controls Bottom Left & Right
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        text = "ZOOM A: ${String.format(java.util.Locale.US, "%.1fx", uiState.zoomA)}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FFCC)
                    )
                    Slider(
                        value = uiState.zoomA,
                        onValueChange = { viewModel.setZoomA(it) },
                        valueRange = 1f..5f,
                        steps = 39,
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "ZOOM B: ${String.format(java.util.Locale.US, "%.2fx", uiState.zoomB)}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    if (uiState.isAutoZoomApplied) Color(0xFF00FFCC) else Color(0xFF232A38),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    val w = textureViewA.width
                                    val h = textureViewA.height
                                    val bmpA = if (w > 0 && h > 0) textureViewA.getBitmap(w, h) else textureViewA.bitmap
                                    val bmpB = if (w > 0 && h > 0) textureViewB.getBitmap(w, h) else textureViewB.bitmap
                                    viewModel.autoCalibrateFOV(bmpA, bmpB)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "AUTO",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                color = if (uiState.isAutoZoomApplied) Color.Black else Color.White
                            )
                        }
                    }
                    Slider(
                        value = uiState.zoomB,
                        onValueChange = { viewModel.setZoomB(it) },
                        valueRange = 1f..3f,
                        modifier = Modifier.height(24.dp)
                    )
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
    sensorOrientation: Int = 90
) {
    val matrix = android.graphics.Matrix()
    if (viewWidth == 0f || viewHeight == 0f) return

    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    // We assume the camera buffer is generally a 4:3 landscape stream like 1440x1080
    val bufferWidth = 1440f
    val bufferHeight = 1080f

    // 1. Un-stretch to actual stream ratio
    matrix.postScale(bufferWidth / viewWidth, bufferHeight / viewHeight, centerX, centerY)
    
    // 2. Rotate it based on sensor orientation
    matrix.postRotate(sensorOrientation.toFloat(), centerX, centerY)
    
    // 3. Center crop to fill the container view without distortion
    val rotatedWidth = if (sensorOrientation % 180 != 0) bufferHeight else bufferWidth
    val rotatedHeight = if (sensorOrientation % 180 != 0) bufferWidth else bufferHeight
    val scaleFill = maxOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
    matrix.postScale(scaleFill, scaleFill, centerX, centerY)
    
    // 4. Apply manual/auto zoom crop
    matrix.postScale(zoom, zoom, centerX, centerY)
    
    textureView.setTransform(matrix)
}

class ZoomableTextureView(context: Context) : android.view.TextureView(context) {
    var currentZoom: Float = 1f
    var sensorOrientation: Int = 90
        set(value) {
            field = value
            if (lastWidth > 0 && lastHeight > 0) {
                updateTextureViewTransform(this, lastWidth, lastHeight, currentZoom, field)
            }
        }
    var lastWidth: Float = 0f
    var lastHeight: Float = 0f

    init {
        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            lastWidth = (right - left).toFloat()
            lastHeight = (bottom - top).toFloat()
            updateTextureViewTransform(this, lastWidth, lastHeight, currentZoom, sensorOrientation)
        }
    }

    fun updateZoom(newZoom: Float) {
        currentZoom = newZoom
        if (lastWidth > 0 && lastHeight > 0) {
            updateTextureViewTransform(this, lastWidth, lastHeight, currentZoom, sensorOrientation)
        }
    }
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
    var currentFrameIndex by remember { mutableStateOf(0) }
    
    // Read files
    val bitmapA = remember(capture.imageAPath) { WiggleProcessor.loadBitmap(capture.imageAPath) }
    val bitmapB = remember(capture.imageBPath) { WiggleProcessor.loadBitmap(capture.imageBPath) }

    // Playback loop controller (A -> B)
    LaunchedEffect(Unit) {
        while (true) {
            val delayMs = 250L // 4 fps simple toggle
            delay(delayMs)
            currentFrameIndex = (currentFrameIndex + 1) % 2
        }
    }

    val activeDisplayBitmap = when (currentFrameIndex) {
        0 -> bitmapA
        else -> bitmapB
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Player screen area
        Box(
            modifier = Modifier
                .weight(1f)
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
                val frameLabel = when (currentFrameIndex) {
                    0 -> "CAM A"
                    else -> "CAM B"
                }
                Text(
                    text = "$frameLabel",
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
            // Title descriptor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "3D WIGGLE CONTROLLER",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
                
                Text(
                    "SAVED TO GALLERY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FFCC)
                )
            }

            Text(
                "Mache mehr daraus mit KI!",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                capture.prompt,
                fontSize = 12.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("KI Prompt", capture.prompt)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Prompt kopiert!", android.widget.Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Prompt für Gemini (KI) kopieren", color = Color.Black, fontWeight = FontWeight.Bold)
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
            model = item.imageAPath,
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
