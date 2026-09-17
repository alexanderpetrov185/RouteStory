package com.alexander.routestory.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    // Ask about an interrupted ride as soon as the app opens.
    LaunchedEffect(Unit) { viewModel.checkDraft() }

    if (state.draftAvailable && state.state == TrackingState.IDLE) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.draft_found_title)) },
            text = { Text(stringResource(R.string.draft_found_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resumeDraft()
                    onStartTracking()
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::discardDraft) {
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
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = stringResource(R.string.ready_to_work))
                Button(onClick = onStartTracking) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(text = stringResource(R.string.action_start))
                }
                OutlinedButton(onClick = onOpenHistory) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Text(text = stringResource(R.string.history_button))
                }
                OutlinedButton(onClick = onOpenPro) {
                    Icon(Icons.Filled.Star, contentDescription = null)
                    Text(text = stringResource(R.string.pro_title))
                }
            }
        }
    }
}