package com.alexander.routestory.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.routestory.BuildConfig
import com.alexander.routestory.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProScreen(onBack: () -> Unit, viewModel: ProViewModel = hiltViewModel()) {
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val billingAvailable by viewModel.billingAvailable.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.purchaseResult.collect { ok ->
            snackbarHostState.showSnackbar(
                context.getString(if (ok) R.string.pro_active else R.string.export_error)
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pro_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isPro) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.pro_active),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            } else {
                BenefitRow(stringResource(R.string.pro_benefit_watermark))
                BenefitRow(stringResource(R.string.pro_benefit_duration))
                BenefitRow(stringResource(R.string.pro_benefit_photos))
                Button(
                    onClick = { (context as? Activity)?.let { viewModel.buy(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = billingAvailable
                ) {
                    Text(stringResource(R.string.action_buy_pro))
                }
                if (!billingAvailable && BuildConfig.DEBUG) {
                    OutlinedButton(
                        onClick = { viewModel.activateDemo() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_activate_demo))
                    }
                }
            }
        }
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(text = text, modifier = Modifier.padding(start = 12.dp))
    }
}