package com.example.measureapp.domain.usecase

import com.example.measureapp.data.repository.MeasurementRepository
import javax.inject.Inject

/**
 * Use case for deleting measurements
 */
class DeleteMeasurementUseCase @Inject constructor(
    private val measurementRepository: MeasurementRepository
) {
    
    /**
     * Delete measurement by ID
     */
    suspend operator fun invoke(id: Long) {
        val measurement = measurementRepository.getMeasurementById(id)
        measurement?.let {
            measurementRepository.deleteMeasurement(it)
        }
    }
    
    /**
     * Delete all measurements
     */
    suspend fun deleteAll() {
        measurementRepository.deleteAllMeasurements()
    }
}
