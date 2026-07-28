package com.example.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterFrames
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.WiggleColors
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Top level layout: header, the active screen (settings / camera / player) and the gallery
 * strip. All camera work lives in [CameraScreen], all playback in [PlayerScreen].
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WiggleApp(viewModel: WiggleViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val captures by viewModel.capturesList.collectAsStateWithLifecycle()

    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    val permissionGranted = cameraPermissionState.status.isGranted

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) viewModel.initCameraDiscovery(context)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = WiggleColors.Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(WiggleColors.Background)
        ) {
            HeaderBar(onOpenSettings = viewModel::toggleSettings)

            when {
                uiState.showSettings -> SettingsScreen(
                    viewModel = viewModel,
                    onBack = viewModel::toggleSettings
                )

                permissionGranted -> Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(WiggleColors.Surface)
                            .border(1.dp, WiggleColors.Outline, RoundedCornerShape(24.dp))
                    ) {
                        val selected = uiState.selectedCapture
                        if (selected == null) {
                            CameraScreen(uiState = uiState, viewModel = viewModel)
                        } else {
                            PlayerScreen(
                                capture = selected,
                                onCloseReview = { viewModel.selectCapture(null) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "GALLERY & CREATIONS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = WiggleColors.TextMuted,
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
                            items(captures, key = { it.id }) { item ->
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

                else -> PermissionRequest(
                    onRequest = cameraPermissionState::launchPermissionRequest
                )
            }
        }
    }
}

@Composable
private fun HeaderBar(onOpenSettings: () -> Unit) {
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
                tint = WiggleColors.Accent,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "WIGGLE-CAM 3D",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = WiggleColors.TextPrimary,
                letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .background(WiggleColors.SurfaceElevated, RoundedCornerShape(12.dp))
                .size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Einstellungen",
                tint = WiggleColors.TextPrimary
            )
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
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
                .background(WiggleColors.Surface, RoundedCornerShape(24.dp))
                .border(1.dp, WiggleColors.Outline, RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = "Camera",
                tint = WiggleColors.Accent,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Camera Permission Required",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = WiggleColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This application strictly requires dual core back camera permissions to " +
                    "perform dynamic alignment calibration and capture gorgeous stereoscopic depth effects.",
                fontSize = 14.sp,
                color = WiggleColors.TextTertiary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = WiggleColors.Accent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Grant Permission",
                    color = WiggleColors.Background,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
