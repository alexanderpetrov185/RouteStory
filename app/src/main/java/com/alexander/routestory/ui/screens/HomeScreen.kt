package com.alexander.routestory.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.routestory.R
import com.alexander.routestory.domain.TrackingState
import com.alexander.routestory.ui.theme.BrandButtonContainer
import com.alexander.routestory.ui.theme.BrandButtonContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onStartTracking: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenPro: () -> Unit = {},
    onBuildRoute: () -> Unit = {},
    onOpenSavedRoutes: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    var dontAskAgain by remember { mutableStateOf(false) }
    /** Sprint 62: the single "Routes" entry opens a small choice dialog. */
    var routesDialog by remember { mutableStateOf(false) }

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
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val title = if (isPro) "RouteStory (Pro)"
                    else stringResource(R.string.app_name)
                    Text(text = title)
                }
            )
        }
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
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onStartTracking,
                    modifier = Modifier.fillMaxWidth(BUTTON_WIDTH_FRACTION).height(BUTTON_HEIGHT),
                    colors = homeButtonColors(),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    HomeButtonContent(
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp), tint = BrandButtonContent) },
                        label = stringResource(R.string.action_start)
                    )
                }
                Button(
                    onClick = onOpenHistory,
                    modifier = Modifier.fillMaxWidth(BUTTON_WIDTH_FRACTION).height(BUTTON_HEIGHT),
                    colors = homeButtonColors(),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    HomeButtonContent(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = BrandButtonContent
                            )
                        },
                        label = stringResource(R.string.history_button)
                    )
                }
                Button(
                    onClick = onOpenPro,
                    modifier = Modifier.fillMaxWidth(BUTTON_WIDTH_FRACTION).height(BUTTON_HEIGHT),
                    colors = homeButtonColors(),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    HomeButtonContent(
                        icon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(20.dp), tint = BrandButtonContent) },
                        label = stringResource(R.string.pro_title)
                    )
                }
                Button(
                    onClick = { routesDialog = true },
                    modifier = Modifier.fillMaxWidth(BUTTON_WIDTH_FRACTION).height(BUTTON_HEIGHT),
                    colors = homeButtonColors(),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    // Sprint 62: "Build route" and "Saved routes" became one "Routes" entry so the
                    // home list stays short.
                    HomeButtonContent(
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp), tint = BrandButtonContent) },
                        label = stringResource(R.string.home_routes)
                    )
                }
            }
        }
    }
    if (routesDialog) {
        AlertDialog(
            onDismissRequest = { routesDialog = false },
            title = { Text(stringResource(R.string.home_routes)) },
            text = { Text(stringResource(R.string.home_routes_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    routesDialog = false
                    onBuildRoute()
                }) { Text(stringResource(R.string.route_create)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    routesDialog = false
                    onOpenSavedRoutes()
                }) { Text(stringResource(R.string.route_open_saved)) }
            }
        )
    }
}

/** Buttons leave 12.5% of the width free on each side and are a bit taller than default. */
private const val BUTTON_WIDTH_FRACTION = 0.75f
private val BUTTON_HEIGHT = 56.dp

@Composable
private fun homeButtonColors() = ButtonDefaults.buttonColors(
    containerColor = BrandButtonContainer,
    contentColor = BrandButtonContent
)

/**
 * Fixed-width content row: the icon starts at the same x on every button, the whole block is
 * centered inside the button by Material3, and icon/label share one vertical centre line.
 * Sprint 62: the row may grow and the label may wrap instead of being clipped, so long Russian
 * strings ("Сохранённые маршруты") stay readable.
 */
@Composable
private fun HomeButtonContent(icon: @Composable () -> Unit, label: String) {
    Row(
        modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start,
            maxLines = 2
        )
    }
}