package com.alexander.routestory.ui.screens

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.routestory.billing.BillingManager
import com.alexander.routestory.data.ProRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProViewModel @Inject constructor(
    private val billingManager: BillingManager,
    private val proRepository: ProRepository
) : ViewModel() {

    val isPro: StateFlow<Boolean> = proRepository.isPro
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val billingAvailable: StateFlow<Boolean> = billingManager.billingAvailable

    private val _purchaseResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val purchaseResult: SharedFlow<Boolean> = _purchaseResult.asSharedFlow()

    init {
        billingManager.connect { }
    }

    fun buy(activity: Activity) {
        billingManager.purchase(activity) { ok -> _purchaseResult.tryEmit(ok) }
    }

    fun activateDemo() {
        viewModelScope.launch { proRepository.setPro(true) }
    }
}