package com.example.measureapp.domain.usecase

import com.example.measureapp.data.models.Vector3
import com.example.measureapp.domain.calculator.DistanceCalculator
import javax.inject.Inject

/**
 * Use case for calculating distance between two points in 3D space
 */
class CalculateDistanceUseCase @Inject constructor(
    private val distanceCalculator: DistanceCalculator
) {
    
    /**
     * Calculate distance between two points
     */
    operator fun invoke(point1: Vector3, point2: Vector3): Float {
        return distanceCalculator.calculateDistance(point1, point2)
    }
    
    /**
     * Calculate distance with depth correction
     * Note: This is a simplified version. Full implementation would use ToF data
     */
    fun calculateWithDepth(
        point1: Vector3,
        point2: Vector3,
        @Suppress("UNUSED_PARAMETER") depth1: Float,
        @Suppress("UNUSED_PARAMETER") depth2: Float
    ): Float {
        // For MVP, just return basic distance
        // TODO: Implement actual depth correction using ToF data
        return distanceCalculator.calculateDistance(point1, point2)
    }
    
    /**
     * Calculate total perimeter of connected points
     */
    fun calculatePerimeter(points: List<Vector3>): Float {
        return distanceCalculator.calculatePerimeter(points)
    }
}
