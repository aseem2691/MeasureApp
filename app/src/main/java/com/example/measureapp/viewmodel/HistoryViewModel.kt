package com.example.measureapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.models.UnitType
import com.example.measureapp.data.repository.PreferencesRepository
import com.example.measureapp.domain.usecase.DeleteMeasurementUseCase
import com.example.measureapp.domain.usecase.GetMeasurementsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val getMeasurementsUseCase: GetMeasurementsUseCase,
    private val deleteMeasurementUseCase: DeleteMeasurementUseCase,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val measurements: StateFlow<List<MeasurementEntity>> = getMeasurementsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unitType: StateFlow<UnitType> = preferencesRepository.unitType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UnitType.METRIC)

    fun deleteMeasurement(id: Long) {
        viewModelScope.launch {
            deleteMeasurementUseCase(id)
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            deleteMeasurementUseCase.deleteAll()
        }
    }
}
