package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: WiggleViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .padding(horizontal = 20.dp)
            .verticalScroll(scrollState)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(Color(0xFF1E2430), RoundedCornerShape(12.dp))
                    .size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "EINSTELLUNGEN",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Divider(color = Color(0xFF232A38), thickness = 1.dp)
        Spacer(modifier = Modifier.height(20.dp))

        // 1. Linsenanzahl-Auswahl
        Text(
            text = "LINSENANZAHL",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4E586E),
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(2, 3, 4).forEach { count ->
                val isSelected = uiState.lensCount == count
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) Color(0xFF00FFCC) else Color(0xFF161920))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF00FFCC) else Color(0xFF232A38),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { viewModel.setLensCount(context, count) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = when (count) {
                                2 -> Icons.Default.LooksTwo
                                3 -> Icons.Default.Looks3
                                else -> Icons.Default.Looks4
                            },
                            contentDescription = "$count Lenses",
                            tint = if (isSelected) Color.Black else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$count Linsen",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.Black else Color.White
                        )
                    }
                }
            }
        }

        // Info-Box bezüglich des sequentiellen Captures bei 3+ Linsen
        AnimatedVisibility(
            visible = uiState.lensCount > 2,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .background(Color(0x3300FFCC), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x6600FFCC), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Multi-Kamera-Modus aktiv",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Hinweis: Android-Geräte erlauben maximal 2 gleichzeitige Live-Kamerastreams. Bei 3 oder 4 Linsen nimmt die App die zusätzlichen Frames nacheinander (sequentiell) in Sekundenbruchteilen auf. Für optimale Ergebnisse halte das Handy ruhig und fotografiere unbewegte Motive.",
                            fontSize = 12.sp,
                            color = Color(0xFFC4CFDD),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Foto-Qualität
        Text(
            text = "FOTO-QUALITÄT",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4E586E),
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val is4K = uiState.is4K
            
            // 4K Option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (is4K) Color(0xFF00FFCC) else Color(0xFF161920))
                    .border(
                        width = 1.dp,
                        color = if (is4K) Color(0xFF00FFCC) else Color(0xFF232A38),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { viewModel.setIs4K(context, true) }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "4K ULTRA",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = if (is4K) Color.Black else Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Maximale Auflösung",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (is4K) Color(0xFF1E2430) else Color.Gray
                    )
                }
            }

            // HD Option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (!is4K) Color(0xFF00FFCC) else Color(0xFF161920))
                    .border(
                        width = 1.dp,
                        color = if (!is4K) Color(0xFF00FFCC) else Color(0xFF232A38),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { viewModel.setIs4K(context, false) }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "HD 1080P",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = if (!is4K) Color.Black else Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Kompaktere Dateien",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (!is4K) Color(0xFF1E2430) else Color.Gray
                    )
                }
            }
        }

        // 3. Primärlinse auswählen
        Text(
            text = "PRIMÄRLINSE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4E586E),
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        var primaryExpanded by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161920), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF232A38), RoundedCornerShape(16.dp))
                .clickable { primaryExpanded = true }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.primaryLens?.name ?: "Bitte wählen...",
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Dropdown",
                    tint = Color.White
                )
            }

            DropdownMenu(
                expanded = primaryExpanded,
                onDismissRequest = { primaryExpanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(Color(0xFF161920))
                    .border(1.dp, Color(0xFF232A38), RoundedCornerShape(8.dp))
            ) {
                uiState.availableLenses.forEach { lens ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = lens.name,
                                color = if (uiState.primaryLens?.id == lens.id) Color(0xFF00FFCC) else Color.White,
                                fontWeight = if (uiState.primaryLens?.id == lens.id) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            viewModel.updatePrimaryLens(context, lens)
                            primaryExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 4. Sekundärlinsen auswählen
        val maxSelectable = (uiState.lensCount - 1).coerceAtLeast(1)
        Text(
            text = "SEKUNDÄRLINSEN (WÄHLE $maxSelectable)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4E586E),
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161920), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF232A38), RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            val candidates = uiState.availableLenses.filter { it.id != uiState.primaryLens?.id }
            if (candidates.isEmpty()) {
                Text(
                    text = "Keine weiteren Linsen verfügbar",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                candidates.forEach { lens ->
                    val isChecked = uiState.secondaryLenses.any { it.id == lens.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val current = uiState.secondaryLenses.toMutableList()
                                if (isChecked) {
                                    current.removeAll { it.id == lens.id }
                                } else {
                                    if (current.size < maxSelectable) {
                                        current.add(lens)
                                    } else {
                                        // Swap last or do nothing
                                        if (maxSelectable == 1) {
                                            current.clear()
                                            current.add(lens)
                                        }
                                    }
                                }
                                viewModel.updateSecondaryLenses(context, current)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = null, // Handled by row click
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF00FFCC),
                                checkmarkColor = Color.Black,
                                uncheckedColor = Color.Gray
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = lens.name,
                                fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal
                            )
                            lens.focalLength?.let {
                                Text(
                                    text = "Brennweite: ${it}mm",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 5. Zoom-Limits pro Linse (Akkordeon)
        Text(
            text = "INDIVIDUELLE ZOOM-LIMITS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4E586E),
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        uiState.availableLenses.forEach { lens ->
            var isExpanded by remember { mutableStateOf(false) }
            val limits = uiState.zoomLimitsMap[lens.id] ?: Pair(1.0f, 3.0f)
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .background(Color(0xFF161920), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF232A38), RoundedCornerShape(16.dp))
            ) {
                // Header (Click to expand)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = lens.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Bereich: ${String.format("%.1fx", limits.first)} - ${String.format("%.1fx", limits.second)}",
                            fontSize = 12.sp,
                            color = Color(0xFF00FFCC),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle",
                        tint = Color.White
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)
                    ) {
                        Divider(color = Color(0xFF232A38), modifier = Modifier.padding(bottom = 16.dp))
                        
                        // Min Zoom Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Minimaler Zoom",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = String.format("%.1fx", limits.first),
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = limits.first.coerceIn(1.0f, 10.0f),
                            onValueChange = { raw ->
                                val snapped = (Math.round(raw * 2) / 2.0f).coerceIn(1.0f, 10.0f)
                                viewModel.setZoomLimitsForLens(context, lens.id, snapped, maxOf(snapped + 0.5f, limits.second))
                            },
                            valueRange = 1.0f..10.0f,
                            steps = 17,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Max Zoom Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Maximaler Zoom",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = String.format("%.1fx", limits.second),
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = limits.second.coerceIn(1.0f, 10.0f),
                            onValueChange = { raw ->
                                val snapped = (Math.round(raw * 2) / 2.0f).coerceIn(1.0f, 10.0f)
                                viewModel.setZoomLimitsForLens(context, lens.id, minOf(snapped - 0.5f, limits.first), snapped)
                            },
                            valueRange = 1.0f..10.0f,
                            steps = 17
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}
