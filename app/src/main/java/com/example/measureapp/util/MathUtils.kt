package com.example.measureapp.util

import com.example.measureapp.data.models.Vector3
import kotlin.math.*

/**
 * Math utilities for AR measurements and calculations
 */
object MathUtils {

    /**
     * Calculate Euclidean distance between two 3D points
     */
    fun calculateDistance(p1: Vector3, p2: Vector3): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val dz = p2.z - p1.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Calculate midpoint between two 3D points
     */
    fun calculateMidpoint(p1: Vector3, p2: Vector3): Vector3 {
        return Vector3(
            x = (p1.x + p2.x) / 2f,
            y = (p1.y + p2.y) / 2f,
            z = (p1.z + p2.z) / 2f
        )
    }

    /**
     * Calculate area of a rectangle given four corner points
     */
    fun calculateRectangleArea(corners: List<Vector3>): Float {
        if (corners.size != 4) return 0f
        
        val width = calculateDistance(corners[0], corners[1])
        val height = calculateDistance(corners[1], corners[2])
        return width * height
    }

    /**
     * Calculate angle between two 3D vectors in degrees
     */
    fun calculateAngleDegrees(v1: Vector3, v2: Vector3): Float {
        val dot = v1.dot(v2)
        val magnitudeProduct = v1.length() * v2.length()
        if (magnitudeProduct == 0f) return 0f
        
        val cosAngle = dot / magnitudeProduct
        val clampedCos = cosAngle.coerceIn(-1f, 1f)
        return Math.toDegrees(acos(clampedCos).toDouble()).toFloat()
    }

    /**
     * Check if a line is approximately perpendicular to another
     * @param threshold Angle threshold in degrees (default 15°)
     */
    fun isPerpendicular(v1: Vector3, v2: Vector3, threshold: Float = 15f): Boolean {
        val angle = calculateAngleDegrees(v1, v2)
        return abs(angle - 90f) < threshold
    }

    /**
     * Normalize a vector to unit length
     */
    fun normalize(v: Vector3): Vector3 {
        val length = v.length()
        return if (length > 0f) {
            Vector3(v.x / length, v.y / length, v.z / length)
        } else {
            v
        }
    }

    /**
     * Calculate cross product of two vectors
     */
    fun crossProduct(v1: Vector3, v2: Vector3): Vector3 {
        return Vector3(
            x = v1.y * v2.z - v1.z * v2.y,
            y = v1.z * v2.x - v1.x * v2.z,
            z = v1.x * v2.y - v1.y * v2.x
        )
    }

    /**
     * Check if four points form a valid rectangle
     * @param tolerance Tolerance for angle deviation from 90° (default 15°)
     */
    fun isValidRectangle(corners: List<Vector3>, tolerance: Float = 15f): Boolean {
        if (corners.size != 4) return false

        // Check if opposite sides are parallel and equal
        val side1 = Vector3(
            corners[1].x - corners[0].x,
            corners[1].y - corners[0].y,
            corners[1].z - corners[0].z
        )
        val side2 = Vector3(
            corners[2].x - corners[1].x,
            corners[2].y - corners[1].y,
            corners[2].z - corners[1].z
        )
        val side3 = Vector3(
            corners[3].x - corners[2].x,
            corners[3].y - corners[2].y,
            corners[3].z - corners[2].z
        )
        val side4 = Vector3(
            corners[0].x - corners[3].x,
            corners[0].y - corners[3].y,
            corners[0].z - corners[3].z
        )

        // Check if all angles are approximately 90 degrees
        return isPerpendicular(side1, side2, tolerance) &&
               isPerpendicular(side2, side3, tolerance) &&
               isPerpendicular(side3, side4, tolerance) &&
               isPerpendicular(side4, side1, tolerance)
    }

    /**
     * Clamp a value between min and max
     */
    fun clamp(value: Float, min: Float, max: Float): Float {
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }

    /**
     * Linear interpolation between two values
     */
    fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + fraction * (end - start)
    }

    /**
     * Check if a value is within tolerance of zero
     */
    fun isNearZero(value: Float, tolerance: Float = 0.001f): Boolean {
        return abs(value) < tolerance
    }
}
