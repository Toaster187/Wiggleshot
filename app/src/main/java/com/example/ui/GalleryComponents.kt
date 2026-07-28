package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PermCameraMic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.WiggleCapture
import com.example.ui.theme.WiggleColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EmptyGalleryState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(WiggleColors.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, WiggleColors.Outline, RoundedCornerShape(16.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.PermCameraMic,
            contentDescription = "No images",
            tint = WiggleColors.IconMuted,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "No stereoscopic animations captured yet",
            fontSize = 12.sp,
            color = WiggleColors.TextTertiary
        )
        Text(
            text = "Tap shutter to generate first 3D wiggle photo!",
            fontSize = 10.sp,
            color = WiggleColors.TextMuted
        )
    }
}

@Composable
fun HistoryItemCard(
    item: WiggleCapture,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(item.timestamp) { formatter.format(Date(item.timestamp)) }

    Box(
        modifier = Modifier
            .size(110.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(WiggleColors.Surface)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) WiggleColors.Accent else WiggleColors.Outline,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onSelect)
    ) {
        AsyncImage(
            model = item.getThumbnailFile(),
            contentDescription = "Wiggle capture",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .background(WiggleColors.OverlaySoft, CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = "Delete from history",
                tint = WiggleColors.Danger,
                modifier = Modifier.size(14.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(WiggleColors.Overlay, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = formattedTime,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = WiggleColors.TextPrimary
            )
        }
    }
}
