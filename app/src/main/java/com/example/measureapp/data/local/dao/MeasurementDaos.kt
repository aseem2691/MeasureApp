package com.example.measureapp.data.local.dao

import androidx.room.*
import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.local.entities.PointEntity
import com.example.measureapp.data.local.entities.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for measurement operations
 */
@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun getAllMeasurements(): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun getMeasurementById(id: Long): MeasurementEntity?

    @Query("SELECT * FROM measurements WHERE projectId = :projectId ORDER BY timestamp DESC")
    fun getMeasurementsByProject(projectId: Long): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE isFavorite = 1 ORDER BY timestamp DESC")
    fun getFavoriteMeasurements(): Flow<List<MeasurementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurement(measurement: MeasurementEntity): Long

    @Update
    suspend fun updateMeasurement(measurement: MeasurementEntity)

    @Delete
    suspend fun deleteMeasurement(measurement: MeasurementEntity)

    @Query("DELETE FROM measurements")
    suspend fun deleteAllMeasurements()

    @Query("UPDATE measurements SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean)
}

/**
 * DAO for measurement points
 */
@Dao
interface PointDao {
    @Query("SELECT * FROM measurement_points WHERE measurementId = :measurementId ORDER BY pointIndex")
    suspend fun getPointsForMeasurement(measurementId: Long): List<PointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<PointEntity>)

    @Query("DELETE FROM measurement_points WHERE measurementId = :measurementId")
    suspend fun deletePointsForMeasurement(measurementId: Long)
}

/**
 * DAO for projects
 */
@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("UPDATE projects SET updatedAt = :timestamp WHERE id = :id")
    suspend fun updateProjectTimestamp(id: Long, timestamp: Long = System.currentTimeMillis())
}
