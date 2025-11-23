package com.example.measureapp.domain.calculator

import com.example.measureapp.data.models.Vector3
import kotlin.math.abs

/**
 * Calculator for area measurements
 */
class AreaCalculator {

    private val distanceCalculator = DistanceCalculator()

    /**
     * Calculate area of a rectangle given four corner points
     */
    fun calculateRectangleArea(corners: List<Vector3>): Float {
        if (corners.size != 4) return 0f

        val width = distanceCalculator.calculateDistance(corners[0], corners[1])
        val height = distanceCalculator.calculateDistance(corners[1], corners[2])
        return width * height
    }

    /**
     * Calculate area of a triangle using Heron's formula
     */
    fun calculateTriangleArea(p1: Vector3, p2: Vector3, p3: Vector3): Float {
        val a = distanceCalculator.calculateDistance(p1, p2)
        val b = distanceCalculator.calculateDistance(p2, p3)
        val c = distanceCalculator.calculateDistance(p3, p1)

        // Semi-perimeter
        val s = (a + b + c) / 2f

        // Heron's formula: Area = √(s(s-a)(s-b)(s-c))
        val area = kotlin.math.sqrt(s * (s - a) * (s - b) * (s - c))
        return if (area.isNaN()) 0f else area
    }

    /**
     * Calculate area of a polygon using the shoelace formula
     * Points should be in order (clockwise or counter-clockwise)
     */
    fun calculatePolygonArea(points: List<Vector3>): Float {
        if (points.size < 3) return 0f

        // Project onto XZ plane (horizontal) for area calculation
        var area = 0f
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].z
            area -= points[j].x * points[i].z
        }
        return abs(area) / 2f
    }

    /**
     * Calculate area of a circle
     */
    fun calculateCircleArea(radius: Float): Float {
        return Math.PI.toFloat() * radius * radius
    }

    /**
     * Calculate surface area of a rectangular box
     */
    fun calculateBoxSurfaceArea(width: Float, height: Float, depth: Float): Float {
        return 2f * (width * height + height * depth + depth * width)
    }

    /**
     * Calculate surface area of a cylinder
     */
    fun calculateCylinderSurfaceArea(radius: Float, height: Float): Float {
        return 2f * Math.PI.toFloat() * radius * (radius + height)
    }

    /**
     * Calculate surface area of a sphere
     */
    fun calculateSphereSurfaceArea(radius: Float): Float {
        return 4f * Math.PI.toFloat() * radius * radius
    }
}
