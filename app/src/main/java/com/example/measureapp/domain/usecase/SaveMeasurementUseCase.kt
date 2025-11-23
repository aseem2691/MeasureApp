package com.example.measureapp.domain.usecase

import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.models.MeasurementPoint
import com.example.measureapp.data.repository.MeasurementRepository
import javax.inject.Inject

/**
 * Use case for saving measurements to database
 */
class SaveMeasurementUseCase @Inject constructor(
    private val measurementRepository: MeasurementRepository
) {
    
    /**
     * Save measurement with its points
     * Returns the ID of the saved measurement
     */
    suspend operator fun invoke(
        measurement: MeasurementEntity,
        points: List<MeasurementPoint>
    ): Long {
        return measurementRepository.saveMeasurement(measurement, points)
    }
    
    /**
     * Update existing measurement
     */
    suspend fun update(measurement: MeasurementEntity) {
        measurementRepository.updateMeasurement(measurement)
    }
}
