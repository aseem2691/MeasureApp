package com.example.measureapp.ar

import android.util.Log
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Rectangle Auto-Detection for AR measurement
 * 
 * Detects rectangular surfaces automatically from ARCore plane polygons
 * Features:
 * - Identifies 4-corner rectangles from plane vertices
 * - Validates 90° angles (within tolerance)
 * - Calculates all 4 sides + area instantly
 * - Provides corner positions for visual overlay
 * 
 * This is a signature iOS Measure feature for measuring doors, screens, frames, etc.
 */
data class DetectedRectangle(
    val corners: List<Position>, // 4 corners in order: TL, TR, BR, BL
    val sides: List<Float>, // 4 side lengths in meters: top, right, bottom, left
    val area: Float, // Area in square meters
    val confidence: Float // 0-1, how confident this is a rectangle
)

class RectangleDetector {
    
    companion object {
        private const val TAG = "RectangleDetector"
        private const val ANGLE_TOLERANCE = 15f // Degrees tolerance for 90° angles
        private const val MIN_SIDE_LENGTH = 0.05f // 5cm minimum side
        private const val MAX_SIDE_LENGTH = 2.0f // 2m maximum side (filters out large plane boundaries)
        private const val MIN_CONFIDENCE = 0.7f // Minimum confidence to report
    }
    
    /**
     * Attempt to detect a rectangle from an ARCore plane
     * Returns null if no valid rectangle found
     */
    fun detectRectangle(plane: Plane): DetectedRectangle? {
        // Get plane polygon vertices
        val polygon = plane.polygon
        if (polygon == null || polygon.capacity() < 8) { // Need at least 4 vertices (x,z pairs)
            return null
        }
        
        // Extract vertices as 2D points (x, z) - y is height
        val vertices = mutableListOf<Pair<Float, Float>>()
        polygon.rewind()
        while (polygon.hasRemaining() && vertices.size < 100) { // Safety limit
            if (polygon.remaining() >= 2) {
                val x = polygon.get()
                val z = polygon.get()
                vertices.add(Pair(x, z))
            }
        }
        
        Log.d(TAG, "Plane has ${vertices.size} vertices")
        
        // Need exactly 4 vertices for a rectangle, or find the bounding box
        if (vertices.size < 4) return null
        
        // Find convex hull or bounding box
        val rectangle = if (vertices.size == 4) {
            // Already has 4 vertices, check if it's a rectangle
            validateRectangle(plane, vertices)
        } else {
            // Find minimum area rectangle from polygon
            findMinimumAreaRectangle(plane, vertices)
        }
        
        return rectangle
    }
    
    /**
     * Validate if 4 vertices form a proper rectangle
     */
    private fun validateRectangle(plane: Plane, vertices: List<Pair<Float, Float>>): DetectedRectangle? {
        if (vertices.size != 4) return null
        
        // Convert 2D vertices to 3D positions using plane pose
        val centerPose = plane.centerPose
        val corners3D = vertices.map { (x, z) ->
            val localPose = Pose.makeTranslation(x, 0f, z)
            val worldPose = centerPose.compose(localPose)
            Position(worldPose.tx(), worldPose.ty(), worldPose.tz())
        }
        
        // Calculate side lengths
        val sides = listOf(
            distance(corners3D[0], corners3D[1]), // Top
            distance(corners3D[1], corners3D[2]), // Right
            distance(corners3D[2], corners3D[3]), // Bottom
            distance(corners3D[3], corners3D[0])  // Left
        )
        
        // Validate side lengths
        if (sides.any { it < MIN_SIDE_LENGTH || it > MAX_SIDE_LENGTH }) {
            Log.d(TAG, "Side lengths out of range: $sides")
            return null
        }
        
        // Check if opposite sides are similar (rectangle property)
        val topBottomRatio = sides[0] / sides[2]
        val leftRightRatio = sides[3] / sides[1]
        if (abs(topBottomRatio - 1.0f) > 0.2f || abs(leftRightRatio - 1.0f) > 0.2f) {
            Log.d(TAG, "Opposite sides not similar: top/bottom=$topBottomRatio, left/right=$leftRightRatio")
            return null
        }
        
        // Check angles (should be close to 90°)
        val angles = listOf(
            angleBetweenVectors(corners3D[3], corners3D[0], corners3D[1]), // TL
            angleBetweenVectors(corners3D[0], corners3D[1], corners3D[2]), // TR
            angleBetweenVectors(corners3D[1], corners3D[2], corners3D[3]), // BR
            angleBetweenVectors(corners3D[2], corners3D[3], corners3D[0])  // BL
        )
        
        val angleConfidence = angles.map { angle ->
            val deviation = abs(angle - 90f)
            if (deviation < ANGLE_TOLERANCE) 1.0f - (deviation / ANGLE_TOLERANCE) else 0f
        }.average().toFloat()
        
        if (angleConfidence < MIN_CONFIDENCE) {
            Log.d(TAG, "Angles not close to 90°: $angles, confidence=$angleConfidence")
            return null
        }
        
        // Calculate area (simple rectangle area)
        val width = (sides[0] + sides[2]) / 2f
        val height = (sides[1] + sides[3]) / 2f
        val area = width * height
        
        Log.d(TAG, "✓ Rectangle detected: ${width}m x ${height}m = ${area}m²")
        
        return DetectedRectangle(
            corners = corners3D,
            sides = sides,
            area = area,
            confidence = angleConfidence
        )
    }
    
    /**
     * Find minimum area rectangle from arbitrary polygon
     * Uses rotating calipers algorithm (simplified)
     */
    private fun findMinimumAreaRectangle(plane: Plane, vertices: List<Pair<Float, Float>>): DetectedRectangle? {
        // Find axis-aligned bounding box as approximation
        val minX = vertices.minOf { it.first }
        val maxX = vertices.maxOf { it.first }
        val minZ = vertices.minOf { it.second }
        val maxZ = vertices.maxOf { it.second }
        
        // Create rectangle corners
        val rectVertices = listOf(
            Pair(minX, minZ), // Bottom-left
            Pair(maxX, minZ), // Bottom-right
            Pair(maxX, maxZ), // Top-right
            Pair(minX, maxZ)  // Top-left
        )
        
        return validateRectangle(plane, rectVertices)
    }
    
    // --- Helper Math Functions ---
    
    private fun distance(p1: Position, p2: Position): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val dz = p2.z - p1.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    /**
     * Calculate angle at vertex 'b' formed by vectors ba and bc
     * Returns angle in degrees
     */
    private fun angleBetweenVectors(a: Position, b: Position, c: Position): Float {
        // Vectors from b to a and b to c
        val ba = Position(a.x - b.x, a.y - b.y, a.z - b.z)
        val bc = Position(c.x - b.x, c.y - b.y, c.z - b.z)
        
        // Dot product and magnitudes
        val dot = ba.x * bc.x + ba.y * bc.y + ba.z * bc.z
        val magBa = sqrt(ba.x * ba.x + ba.y * ba.y + ba.z * ba.z)
        val magBc = sqrt(bc.x * bc.x + bc.y * bc.y + bc.z * bc.z)
        
        if (magBa == 0f || magBc == 0f) return 0f
        
        // Angle in radians then degrees
        val cosAngle = (dot / (magBa * magBc)).coerceIn(-1f, 1f)
        val angleRad = acos(cosAngle)
        return Math.toDegrees(angleRad.toDouble()).toFloat()
    }
}
