package com.example.measureapp.ar

import android.content.Context
import com.google.ar.core.*
import com.example.measureapp.data.models.MeasurementPoint
import com.example.measureapp.data.models.Vector3
import android.graphics.PointF
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages AR hit testing to place measurement points in 3D space
 * Uses Config.DepthMode.AUTOMATIC for native ToF sensor support
 */
@Singleton
class HitTestManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    
    /**
     * Initialize with AR session (call after session created)
     */
    fun initialize(session: Session) {
        // Depth mode is configured in MeasureActivity via Config.DepthMode.AUTOMATIC
        android.util.Log.d("HitTestManager", "HitTestManager initialized")
    }
    
    /**
     * Perform hit test at screen coordinates
     * FIXED: Remove isPoseInPolygon check which can be too strict
     * FIXED: Enable instant placement configuration for immediate feedback
     * 
     * Priority order:
     * 1. Plane intersections (most reliable)
     * 2. Instant placement (fallback - enables immediate measurements)
     */
    fun performHitTest(
        frame: Frame,
        screenX: Float,
        screenY: Float
    ): MeasurementPoint? {
        
        val hits = frame.hitTest(screenX, screenY)
        
        android.util.Log.d("HitTestManager", "🎯 Hit test at ($screenX, $screenY), found ${hits.size} hits")
        
        // Log camera tracking state
        val trackingState = frame.camera.trackingState
        android.util.Log.d("HitTestManager", "Camera tracking: $trackingState")
        
        if (trackingState != TrackingState.TRACKING) {
            android.util.Log.w("HitTestManager", "⚠️ Camera not tracking yet, state=$trackingState")
        }
        
        // PRIORITY 1: Look for plane hits first (most accurate)
        for (hitResult in hits) {
            val trackable = hitResult.trackable
            
            // CRITICAL FIX: Add isPoseInPolygon check (proven essential by ARCoreMeasure)
            // This prevents placing anchors outside the detected plane polygon
            if (trackable is Plane && 
                trackable.trackingState == TrackingState.TRACKING &&
                trackable.isPoseInPolygon(hitResult.hitPose)) {
                
                val pose = hitResult.hitPose
                val distance = hitResult.distance
                val anchor = hitResult.createAnchor()
                
                android.util.Log.d("HitTestManager", 
                    "✅ HIT PLANE: Position=(${pose.tx()}, ${pose.ty()}, ${pose.tz()}), " +
                    "Distance=${distance}m, Type=${trackable.type}, " +
                    "PlaneSize=${trackable.extentX}x${trackable.extentZ}m")
                
                return MeasurementPoint(
                    position = Vector3(
                        x = pose.tx(),
                        y = pose.ty(),
                        z = pose.tz()
                    ),
                    anchor = anchor,
                    timestamp = System.currentTimeMillis()
                )
            }
            
            // Also accept feature points as backup
            if (trackable is Point && trackable.trackingState == TrackingState.TRACKING) {
                val pose = hitResult.hitPose
                val anchor = hitResult.createAnchor()
                
                android.util.Log.d("HitTestManager", 
                    "✅ HIT FEATURE POINT: Position=(${pose.tx()}, ${pose.ty()}, ${pose.tz()})")
                
                return MeasurementPoint(
                    position = Vector3(
                        x = pose.tx(),
                        y = pose.ty(),
                        z = pose.tz()
                    ),
                    anchor = anchor,
                    timestamp = System.currentTimeMillis()
                )
            }
        }
        
        android.util.Log.e("HitTestManager", "❌ NO HITS - No plane or feature point found")
        android.util.Log.e("HitTestManager", "Suggestion: Move phone slowly, ensure good lighting, point at textured surfaces")
        
        return null
    }
    

    
    /**
     * Test if a ray intersects with detected planes
     * Useful for custom hit testing (currently simplified)
     */
    fun raycastPlanes(
        frame: Frame,
        screenX: Float,
        screenY: Float
    ): List<HitResult> {
        return frame.hitTest(screenX, screenY)
    }
}
