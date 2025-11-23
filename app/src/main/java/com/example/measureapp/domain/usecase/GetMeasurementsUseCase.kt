package com.example.measureapp.domain.usecase

import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.repository.MeasurementRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for retrieving measurements from database
 */
class GetMeasurementsUseCase @Inject constructor(
    private val measurementRepository: MeasurementRepository
) {
    
    /**
     * Get all measurements
     */
    operator fun invoke(): Flow<List<MeasurementEntity>> {
        return measurementRepository.getAllMeasurements()
    }
    
    /**
     * Get measurements by project
     */
    fun byProject(projectId: Long): Flow<List<MeasurementEntity>> {
        return measurementRepository.getMeasurementsByProject(projectId)
    }
    
    /**
     * Get favorite measurements
     */
    fun favorites(): Flow<List<MeasurementEntity>> {
        return measurementRepository.getFavoriteMeasurements()
    }
    
    /**
     * Get single measurement by ID
     */
    suspend fun byId(id: Long): MeasurementEntity? {
        return measurementRepository.getMeasurementById(id)
    }
}
