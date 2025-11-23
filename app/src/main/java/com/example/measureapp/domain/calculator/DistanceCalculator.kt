package com.example.measureapp.domain.calculator

import com.example.measureapp.data.models.Vector3
import com.google.ar.core.Pose
import kotlin.math.sqrt

/**
 * Calculator for distance measurements in AR space
 */
class DistanceCalculator {

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
     * Calculate distance between two ARCore Poses
     */
    fun calculateDistance(pose1: Pose, pose2: Pose): Float {
        val p1 = Vector3(pose1.tx(), pose1.ty(), pose1.tz())
        val p2 = Vector3(pose2.tx(), pose2.ty(), pose2.tz())
        return calculateDistance(p1, p2)
    }

    /**
     * Calculate horizontal distance (ignoring Y axis)
     */
    fun calculateHorizontalDistance(p1: Vector3, p2: Vector3): Float {
        val dx = p2.x - p1.x
        val dz = p2.z - p1.z
        return sqrt(dx * dx + dz * dz)
    }

    /**
     * Calculate vertical distance (Y axis only)
     */
    fun calculateVerticalDistance(p1: Vector3, p2: Vector3): Float {
        return kotlin.math.abs(p2.y - p1.y)
    }

    /**
     * Calculate distance with depth correction using ToF data
     * @param rawDistance Measured distance from anchors
     * @param confidence Depth confidence value (0-1)
     * @param depthValue Depth value from ToF sensor
     */
    fun calculateCorrectedDistance(
        rawDistance: Float,
        confidence: Float,
        depthValue: Float
    ): Float {
        // If confidence is low, use raw distance
        if (confidence < 0.5f) return rawDistance

        // Weighted average between raw distance and depth value
        val weight = confidence
        return rawDistance * (1 - weight) + depthValue * weight
    }

    /**
     * Calculate perimeter of a polygon
     */
    fun calculatePerimeter(points: List<Vector3>): Float {
        if (points.size < 2) return 0f

        var perimeter = 0f
        for (i in 0 until points.size - 1) {
            perimeter += calculateDistance(points[i], points[i + 1])
        }
        // Close the polygon
        perimeter += calculateDistance(points.last(), points.first())
        return perimeter
    }

    /**
     * Calculate total path length through multiple points
     */
    fun calculatePathLength(points: List<Vector3>): Float {
        if (points.size < 2) return 0f

        var length = 0f
        for (i in 0 until points.size - 1) {
            length += calculateDistance(points[i], points[i + 1])
        }
        return length
    }

    /**
     * Calculate midpoint between two points
     */
    fun calculateMidpoint(p1: Vector3, p2: Vector3): Vector3 {
        return Vector3(
            x = (p1.x + p2.x) / 2f,
            y = (p1.y + p2.y) / 2f,
            z = (p1.z + p2.z) / 2f
        )
    }

    /**
     * Calculate center point of multiple points
     */
    fun calculateCentroid(points: List<Vector3>): Vector3? {
        if (points.isEmpty()) return null

        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f

        points.forEach { point ->
            sumX += point.x
            sumY += point.y
            sumZ += point.z
        }

        val count = points.size.toFloat()
        return Vector3(sumX / count, sumY / count, sumZ / count)
    }
}
