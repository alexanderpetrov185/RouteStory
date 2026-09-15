package com.alexander.routestory.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    val tracks: StateFlow<List<Track>> = repository.observeTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteTrack(id: Long) {
        viewModelScope.launch { repository.deleteTrack(id) }
    }
}