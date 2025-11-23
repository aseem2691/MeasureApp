package com.example.measureapp.data.models

import com.google.ar.core.Anchor

/**
 * Represents a single measurement point in 3D space
 */
data class MeasurementPoint(
    val position: Vector3,
    val anchor: Anchor? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Represents a measurement line between two points
 */
data class MeasurementLine(
    val id: Long,
    val startPoint: MeasurementPoint,
    val endPoint: MeasurementPoint,
    val distanceMeters: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Get midpoint of the line for label placement
     */
    fun getMidpoint(): Vector3 {
        return Vector3(
            x = (startPoint.position.x + endPoint.position.x) / 2f,
            y = (startPoint.position.y + endPoint.position.y) / 2f,
            z = (startPoint.position.z + endPoint.position.z) / 2f
        )
    }

    /**
     * Get direction vector of the line
     */
    fun getDirection(): Vector3 {
        return endPoint.position - startPoint.position
    }
}

/**
 * Represents a detected rectangle in AR space
 */
data class Rectangle(
    val corners: List<Vector3>,
    val width: Float,
    val height: Float,
    val area: Float,
    val confidence: Float,
    val isHorizontal: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    init {
        require(corners.size == 4) { "Rectangle must have exactly 4 corners" }
        require(confidence in 0f..1f) { "Confidence must be between 0 and 1" }
    }

    /**
     * Get center point of the rectangle
     */
    fun getCenter(): Vector3 {
        val sumX = corners.sumOf { it.x.toDouble() }.toFloat()
        val sumY = corners.sumOf { it.y.toDouble() }.toFloat()
        val sumZ = corners.sumOf { it.z.toDouble() }.toFloat()
        return Vector3(sumX / 4f, sumY / 4f, sumZ / 4f)
    }

    /**
     * Check if the rectangle is stable enough to be accepted
     */
    fun isStable(): Boolean {
        return confidence >= 0.7f
    }
}

/**
 * Represents measurement type
 */
enum class MeasurementType {
    POINT_TO_POINT,
    RECTANGLE,
    PERSON_HEIGHT,
    PATH,
    AREA,
    LEVEL
}

/**
 * Represents a complete measurement with metadata
 */
data class Measurement(
    val id: Long,
    val type: MeasurementType,
    val points: List<MeasurementPoint>,
    val lines: List<MeasurementLine> = emptyList(),
    val rectangle: Rectangle? = null,
    val value: Float, // Primary measurement value in meters
    val unit: UnitType,
    val label: String = "",
    val imageUri: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
) {
    /**
     * Get formatted display value based on unit
     */
    fun getFormattedValue(): String {
        return unit.formatDistance(value)
    }

    /**
     * Get formatted area if applicable
     */
    fun getFormattedArea(): String? {
        return rectangle?.let { unit.formatArea(it.area) }
    }
}

/**
 * Represents level/tilt data from sensors
 */
data class LevelData(
    val pitch: Float, // Rotation around X axis (degrees)
    val roll: Float,  // Rotation around Z axis (degrees)
    val isLevel: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Check if device is level within tolerance
     */
    fun isLevelWithinTolerance(tolerance: Float = 0.5f): Boolean {
        return kotlin.math.abs(pitch) < tolerance && kotlin.math.abs(roll) < tolerance
    }

    /**
     * Get combined tilt magnitude
     */
    fun getTiltMagnitude(): Float {
        return kotlin.math.sqrt(pitch * pitch + roll * roll)
    }
}
