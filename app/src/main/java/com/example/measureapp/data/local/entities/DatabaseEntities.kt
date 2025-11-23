package com.example.measureapp.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.measureapp.data.models.MeasurementType
import com.example.measureapp.data.models.UnitType

/**
 * Room entity for storing measurements
 */
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: MeasurementType,
    val value: Float, // Primary measurement value in meters
    val unit: UnitType,
    val label: String = "",
    val imageUri: String? = null,
    val projectId: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    // Rectangle data (if applicable)
    val rectangleWidth: Float? = null,
    val rectangleHeight: Float? = null,
    val rectangleArea: Float? = null,
    val rectangleConfidence: Float? = null,
    val rectangleIsHorizontal: Boolean? = null
)

/**
 * Room entity for storing measurement points
 */
@Entity(
    tableName = "measurement_points",
    primaryKeys = ["measurementId", "pointIndex"]
)
data class PointEntity(
    val measurementId: Long,
    val pointIndex: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Room entity for storing projects/collections of measurements
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val thumbnailUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
