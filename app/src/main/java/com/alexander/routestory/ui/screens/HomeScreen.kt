package com.alexander.routestory.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.routestory.R
import com.alexander.routestory.domain.TrackingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onStartTracking: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenPro: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dontAskAgain by remember { mutableStateOf(false) }

    // Ask about an interrupted ride as soon as the app opens.
    LaunchedEffect(Unit) { viewModel.checkDraft() }

    if (state.draftAvailable && state.state == TrackingState.IDLE) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.draft_found_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.draft_found_text))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontAskAgain = !dontAskAgain }
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.draft_dont_ask),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontAskAgain) {
                        viewModel.setDiscardConfirmed(true)
                    }
                    viewModel.resumeDraft()
                    onStartTracking()
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (dontAskAgain) {
                        viewModel.setDiscardConfirmed(true)
                    }
                    viewModel.discardDraft()
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.app_name)) }) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Thematic backdrop: dark slate card with a route polyline (free, no assets).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(height = 172.dp, width = 0.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val route = MaterialTheme.colorScheme.primary
                    val routeSoft = route.copy(alpha = 0.35f)
                    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stepX = size.width / 6f
                        val stepY = size.height / 4f
                        var gx = stepX
                        while (gx < size.width) {
                            drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 2f)
                            gx += stepX
                        }
                        var gy = stepY
                        while (gy < size.height) {
                            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 2f)
                            gy += stepY
                        }
                        val path = Path().apply {
                            moveTo(size.width * 0.12f, size.height * 0.78f)
                            cubicTo(
                                size.width * 0.30f, size.height * 0.30f,
                                size.width * 0.45f, size.height * 0.90f,
                                size.width * 0.62f, size.height * 0.45f
                            )
                            cubicTo(
                                size.width * 0.72f, size.height * 0.25f,
                                size.width * 0.80f, size.height * 0.45f,
                                size.width * 0.88f, size.height * 0.30f
                            )
                        }
                        drawPath(path, routeSoft, style = Stroke(width = 14f, cap = StrokeCap.Round))
                        drawPath(path, route, style = Stroke(width = 7f, cap = StrokeCap.Round))
                        drawCircle(Color.White, radius = 16f, center = Offset(size.width * 0.12f, size.height * 0.78f))
                        drawCircle(route, radius = 10f, center = Offset(size.width * 0.12f, size.height * 0.78f))
                        drawCircle(Color.White, radius = 18f, center = Offset(size.width * 0.88f, size.height * 0.30f))
                        drawCircle(Color(0xFFE53935), radius = 11f, center = Offset(size.width * 0.88f, size.height * 0.30f))
                    }
                }
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp
                    )
                )
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onStartTracking,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.action_start))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpenHistory,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.history_button))
                    }
                    OutlinedButton(
                        onClick = onOpenPro,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.pro_title))
                    }
                }
            }
        }
    }
}