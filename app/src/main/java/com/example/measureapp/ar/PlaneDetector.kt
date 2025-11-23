package com.example.measureapp.ar

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Detects and tracks AR planes (surfaces) for measurement
 */
@Singleton
class PlaneDetector @Inject constructor() {
    
    private var cachedSession: com.google.ar.core.Session? = null
    
    /**
     * Get all currently tracked planes
     */
    fun getTrackedPlanes(frame: Frame): List<Plane> {
        // Cache session from camera to avoid package-private access issues
        if (cachedSession == null) {
            try {
                // Use reflection to access session if needed, or get it from camera
                cachedSession = frame.camera.trackingState.let { frame.camera.displayOrientedPose.let { null } }
            } catch (e: Exception) {
                return emptyList()
            }
        }
        
        // Get all trackables from the frame's associated AR session
        // Note: In production, session should be passed from the Activity/Fragment
        return try {
            val camera = frame.camera
            frame.getUpdatedTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get horizontal planes (floor, tables, etc.)
     */
    fun getHorizontalPlanes(frame: Frame): List<Plane> {
        return getTrackedPlanes(frame)
            .filter { 
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING || 
                it.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING 
            }
    }
    
    /**
     * Get vertical planes (walls, doors, etc.)
     */
    fun getVerticalPlanes(frame: Frame): List<Plane> {
        return getTrackedPlanes(frame)
            .filter { it.type == Plane.Type.VERTICAL }
    }
    
    /**
     * Find the largest horizontal plane (typically the floor)
     */
    fun findLargestHorizontalPlane(frame: Frame): Plane? {
        return getHorizontalPlanes(frame)
            .maxByOrNull { calculatePlaneArea(it) }
    }
    
    /**
     * Find the largest vertical plane (typically a wall)
     */
    fun findLargestVerticalPlane(frame: Frame): Plane? {
        return getVerticalPlanes(frame)
            .maxByOrNull { calculatePlaneArea(it) }
    }
    
    /**
     * Calculate approximate area of a plane using its polygon
     * Uses the shoelace formula
     */
    private fun calculatePlaneArea(plane: Plane): Float {
        val polygon = plane.polygon
        val limit = polygon.limit()
        if (limit < 6) return 0f // Need at least 3 points (x,z pairs)
        
        var area = 0f
        val numPoints = limit / 2
        
        for (i in 0 until numPoints) {
            val x1 = polygon.get(i * 2)
            val z1 = polygon.get(i * 2 + 1)
            val nextIndex = ((i + 1) % numPoints)
            val x2 = polygon.get(nextIndex * 2)
            val z2 = polygon.get(nextIndex * 2 + 1)
            
            area += (x1 * z2 - x2 * z1)
        }
        
        return abs(area) / 2f
    }
    
    /**
     * Check if enough planes have been detected for good tracking
     */
    fun hasEnoughPlanes(frame: Frame, minPlanes: Int = 1): Boolean {
        return getTrackedPlanes(frame).size >= minPlanes
    }
    
    /**
     * Get plane at a specific point (for measurement validation)
     */
    fun getPlaneAtPoint(frame: Frame, x: Float, y: Float): Plane? {
        val hits = frame.hitTest(x, y)
        return hits.firstOrNull { it.trackable is Plane }?.trackable as? Plane
    }
}
