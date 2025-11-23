package com.example.measureapp.data.repository

import com.example.measureapp.data.local.dao.MeasurementDao
import com.example.measureapp.data.local.dao.PointDao
import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.local.entities.PointEntity
import com.example.measureapp.data.models.Measurement
import com.example.measureapp.data.models.MeasurementPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for measurement data operations
 * Handles database operations and data transformations
 */
@Singleton
class MeasurementRepository @Inject constructor(
    private val measurementDao: MeasurementDao,
    private val pointDao: PointDao
) {
    
    /**
     * Get all measurements as Flow
     */
    fun getAllMeasurements(): Flow<List<MeasurementEntity>> {
        return measurementDao.getAllMeasurements()
    }
    
    /**
     * Get measurements by project ID
     */
    fun getMeasurementsByProject(projectId: Long): Flow<List<MeasurementEntity>> {
        return measurementDao.getMeasurementsByProject(projectId)
    }
    
    /**
     * Get favorite measurements
     */
    fun getFavoriteMeasurements(): Flow<List<MeasurementEntity>> {
        return measurementDao.getFavoriteMeasurements()
    }
    
    /**
     * Get measurement by ID
     */
    suspend fun getMeasurementById(id: Long): MeasurementEntity? {
        return measurementDao.getMeasurementById(id)
    }
    
    /**
     * Get measurement with all its points
     */
    suspend fun getMeasurementWithPoints(id: Long): Pair<MeasurementEntity, List<PointEntity>>? {
        val measurement = measurementDao.getMeasurementById(id) ?: return null
        val points = pointDao.getPointsForMeasurement(id)
        return Pair(measurement, points)
    }
    
    /**
     * Save a new measurement with its points
     */
    suspend fun saveMeasurement(
        measurement: MeasurementEntity,
        points: List<MeasurementPoint>
    ): Long {
        // Insert measurement first to get ID
        val measurementId = measurementDao.insertMeasurement(measurement)
        
        // Convert and insert points
        if (points.isNotEmpty()) {
            val pointEntities = points.mapIndexed { index, point ->
                PointEntity(
                    measurementId = measurementId,
                    pointIndex = index,
                    x = point.position.x,
                    y = point.position.y,
                    z = point.position.z,
                    timestamp = System.currentTimeMillis()
                )
            }
            pointDao.insertPoints(pointEntities)
        }
        
        return measurementId
    }
    
    /**
     * Update existing measurement
     */
    suspend fun updateMeasurement(measurement: MeasurementEntity) {
        measurementDao.updateMeasurement(measurement)
    }
    
    /**
     * Delete measurement (points will cascade delete)
     */
    suspend fun deleteMeasurement(measurement: MeasurementEntity) {
        measurementDao.deleteMeasurement(measurement)
    }
    
    /**
     * Delete all measurements
     */
    suspend fun deleteAllMeasurements() {
        measurementDao.deleteAllMeasurements()
    }
    
    /**
     * Toggle favorite status
     */
    suspend fun toggleFavorite(id: Long, isFavorite: Boolean) {
        measurementDao.updateFavoriteStatus(id, isFavorite)
    }
    
    /**
     * Get measurement count
     */
    fun getMeasurementCount(): Flow<Int> {
        return getAllMeasurements().map { it.size }
    }
}
