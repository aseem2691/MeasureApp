package com.example.measureapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.measureapp.data.models.UnitType
import com.example.measureapp.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val unitType: StateFlow<UnitType> = preferencesRepository.unitType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UnitType.METRIC)

    val hapticEnabled: StateFlow<Boolean> = preferencesRepository.hapticFeedbackEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val soundEnabled: StateFlow<Boolean> = preferencesRepository.soundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoSaveEnabled: StateFlow<Boolean> = preferencesRepository.autoSaveEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val personDetectionEnabled: StateFlow<Boolean> = preferencesRepository.personDetectionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val rectangleDetectionEnabled: StateFlow<Boolean> = preferencesRepository.rectangleDetectionEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setUnitType(type: UnitType) {
        viewModelScope.launch { preferencesRepository.setUnitType(type) }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setHapticFeedback(enabled) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setSoundEnabled(enabled) }
    }

    fun setAutoSave(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoSave(enabled) }
    }

    fun setPersonDetection(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setPersonDetection(enabled) }
    }

    fun setRectangleDetection(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setRectangleDetection(enabled) }
    }
}
