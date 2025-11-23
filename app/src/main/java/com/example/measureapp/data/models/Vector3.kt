package com.example.measureapp.data.models

import kotlin.math.sqrt

/**
 * Represents a 3D vector/point in space
 */
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    /**
     * Calculate the length (magnitude) of this vector
     */
    fun length(): Float {
        return sqrt(x * x + y * y + z * z)
    }

    /**
     * Calculate dot product with another vector
     */
    fun dot(other: Vector3): Float {
        return x * other.x + y * other.y + z * other.z
    }

    /**
     * Add another vector to this vector
     */
    operator fun plus(other: Vector3): Vector3 {
        return Vector3(x + other.x, y + other.y, z + other.z)
    }

    /**
     * Subtract another vector from this vector
     */
    operator fun minus(other: Vector3): Vector3 {
        return Vector3(x - other.x, y - other.y, z - other.z)
    }

    /**
     * Multiply this vector by a scalar
     */
    operator fun times(scalar: Float): Vector3 {
        return Vector3(x * scalar, y * scalar, z * scalar)
    }

    /**
     * Divide this vector by a scalar
     */
    operator fun div(scalar: Float): Vector3 {
        return Vector3(x / scalar, y / scalar, z / scalar)
    }

    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
        val ONE = Vector3(1f, 1f, 1f)
        val UP = Vector3(0f, 1f, 0f)
        val DOWN = Vector3(0f, -1f, 0f)
        val FORWARD = Vector3(0f, 0f, 1f)
        val BACK = Vector3(0f, 0f, -1f)
        val RIGHT = Vector3(1f, 0f, 0f)
        val LEFT = Vector3(-1f, 0f, 0f)
    }
}
