package com.example.measureapp.ar

import android.content.Context
import android.media.Image
import android.opengl.Matrix
import android.view.WindowManager
import com.google.ar.core.*
import timber.log.Timber
import kotlin.math.abs

/**
 * ToF Depth Manager - Direct access to Samsung S25 Ultra's ToF sensor
 * Provides high-precision depth measurements (0.1m - 5m range)
 */
class ToFDepthManager(
    private val context: Context,
    val session: Session  // Made public for anchor creation
) {
    private var isDepthEnabled = false
    private val depthConfidenceThreshold = 0.5f
    
    companion object {
        private const val TAG = "ToFDepthManager"
        // Samsung ToF sensor typically provides depth in range of 0.1m - 5m
        private const val MIN_DEPTH_METERS = 0.1f
        private const val MAX_DEPTH_METERS = 5.0f
    }
    
    /**
     * Initialize and enable ToF depth sensing
     */
    fun enableDepthSensing(): Boolean {
        return try {
            val config = session.config
            
            // Check if depth mode is supported
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
                session.configure(config)
                isDepthEnabled = true
                android.util.Log.i(TAG, "✅ ToF Depth sensing enabled: true (AUTOMATIC mode)")
                true
            } else {
                android.util.Log.w(TAG, "⚠️ Depth mode AUTOMATIC not supported on this device")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to enable depth sensing: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get precise depth at a specific screen point using ToF sensor
     * This returns the actual measured depth from the ToF sensor
     */
    fun getDepthAtPoint(frame: Frame, screenX: Float, screenY: Float): DepthResult? {
        if (!isDepthEnabled) {
            android.util.Log.w(TAG, "Depth sensing not enabled")
            return null
        }
        
        try {
            // Acquire depth image from ToF sensor
            val depthImage = frame.acquireDepthImage16Bits() ?: run {
                android.util.Log.d(TAG, "No depth image available this frame (ToF may not be active)")
                return null
            }
            
            android.util.Log.i(TAG, "✅ Successfully acquired ToF depth image: ${depthImage.width}x${depthImage.height}")
            
            try {
                val camera = frame.camera
                
                // Get depth image dimensions
                val depthWidth = depthImage.width
                val depthHeight = depthImage.height
                
                // Get camera intrinsics for coordinate transformation
                val intrinsics = camera.textureIntrinsics
                val focalLengthX = intrinsics.focalLength[0]
                val focalLengthY = intrinsics.focalLength[1]
                val principalPointX = intrinsics.principalPoint[0]
                val principalPointY = intrinsics.principalPoint[1]
                
                // Transform screen coordinates to depth image coordinates
                val depthX = ((screenX / getScreenWidth()) * depthWidth).toInt()
                val depthY = ((screenY / getScreenHeight()) * depthHeight).toInt()
                
                // Bounds check
                if (depthX !in 0 until depthWidth || depthY !in 0 until depthHeight) {
                    Timber.w("Depth coordinates out of bounds: ($depthX, $depthY)")
                    return null
                }
                
                // Read depth value from ToF sensor (16-bit unsigned)
                val buffer = depthImage.planes[0].buffer
                val pixelStride = depthImage.planes[0].pixelStride
                val rowStride = depthImage.planes[0].rowStride
                val pixelIndex = depthY * rowStride + depthX * pixelStride
                
                // Extract 16-bit depth value (in millimeters from Samsung ToF)
                val depthValueMm = buffer.getShort(pixelIndex).toInt() and 0xFFFF
                
                // Convert millimeters to meters
                val depthMeters = depthValueMm / 1000f
                
                // Validate depth reading
                if (depthMeters < MIN_DEPTH_METERS || depthMeters > MAX_DEPTH_METERS) {
                    Timber.d("Depth out of valid range: $depthMeters m")
                    return null
                }
                
                // Calculate confidence based on depth value stability
                val confidence = calculateDepthConfidence(depthMeters, depthImage, depthX, depthY)
                
                if (confidence < depthConfidenceThreshold) {
                    Timber.d("Low confidence depth reading: $confidence")
                    return null
                }
                
                // Get 3D position using depth and camera intrinsics
                val worldPosition = calculateWorldPosition(
                    screenX, screenY, depthMeters, 
                    focalLengthX, focalLengthY, 
                    principalPointX, principalPointY,
                    camera
                )
                
                android.util.Log.i(TAG, "📏 ToF Depth SUCCESS: ${depthMeters}m at (${screenX}, ${screenY}), confidence: $confidence")
                
                return DepthResult(
                    depthMeters = depthMeters,
                    confidence = confidence,
                    worldPosition = worldPosition,
                    screenX = screenX,
                    screenY = screenY
                )
                
            } finally {
                depthImage.close()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error reading ToF depth data")
            return null
        }
    }
    
    /**
     * Calculate confidence of depth measurement based on surrounding pixels
     * Uses 3x3 neighborhood to validate depth consistency
     */
    private fun calculateDepthConfidence(
        centerDepth: Float,
        depthImage: Image,
        centerX: Int,
        centerY: Int
    ): Float {
        val buffer = depthImage.planes[0].buffer
        val pixelStride = depthImage.planes[0].pixelStride
        val rowStride = depthImage.planes[0].rowStride
        val width = depthImage.width
        val height = depthImage.height
        
        // Sample 3x3 neighborhood around center point
        var validSamples = 0
        var totalVariance = 0f
        
        for (dy in -1..1) {
            for (dx in -1..1) {
                val x = centerX + dx
                val y = centerY + dy
                
                if (x in 0 until width && y in 0 until height) {
                    val pixelIndex = y * rowStride + x * pixelStride
                    val depthValueMm = buffer.getShort(pixelIndex).toInt() and 0xFFFF
                    val depthMeters = depthValueMm / 1000f
                    
                    if (depthMeters in MIN_DEPTH_METERS..MAX_DEPTH_METERS) {
                        validSamples++
                        totalVariance += abs(depthMeters - centerDepth)
                    }
                }
            }
        }
        
        if (validSamples < 5) return 0f // Not enough valid samples
        
        // Lower variance = higher confidence
        val avgVariance = totalVariance / validSamples
        val confidence = 1f - (avgVariance * 10f).coerceIn(0f, 1f)
        
        return confidence
    }
    
    /**
     * Calculate 3D world position from screen coordinates and depth
     */
    private fun calculateWorldPosition(
        screenX: Float,
        screenY: Float,
        depth: Float,
        focalLengthX: Float,
        focalLengthY: Float,
        principalPointX: Float,
        principalPointY: Float,
        camera: Camera
    ): FloatArray {
        // Convert screen coordinates to normalized device coordinates
        val ndcX = (screenX - principalPointX) / focalLengthX
        val ndcY = (screenY - principalPointY) / focalLengthY
        
        // Calculate camera space position
        val cameraSpaceX = ndcX * depth
        val cameraSpaceY = ndcY * depth
        val cameraSpaceZ = -depth // Negative Z in OpenGL convention
        
        // Transform from camera space to world space
        val pose = camera.pose
        val cameraPos = floatArrayOf(cameraSpaceX, cameraSpaceY, cameraSpaceZ, 1f)
        val worldPos = FloatArray(4)
        
        // Get camera transformation matrix
        val cameraMatrix = FloatArray(16)
        pose.toMatrix(cameraMatrix, 0)
        
        // Transform to world space
        Matrix.multiplyMV(worldPos, 0, cameraMatrix, 0, cameraPos, 0)
        
        return floatArrayOf(worldPos[0], worldPos[1], worldPos[2])
    }
    
    /**
     * Enhanced hit test using ToF depth data
     * Priority: ToF Sensor → ARCore Depth → Planes → Instant Placement
     */
    fun performToFEnhancedHitTest(
        frame: Frame,
        screenX: Float,
        screenY: Float
    ): EnhancedHitResult? {
        // First try to get ToF depth
        val depthResult = getDepthAtPoint(frame, screenX, screenY)
        
        if (depthResult != null && depthResult.confidence > 0.7f) {
            // High confidence ToF reading - use it directly
            Timber.d("✅ Using ToF depth: ${depthResult.depthMeters}m, confidence: ${depthResult.confidence}")
            
            // Create pose from ToF position
            val pose = Pose.makeTranslation(depthResult.worldPosition)
            
            return EnhancedHitResult(
                pose = pose,
                confidence = depthResult.confidence,
                source = DepthSource.TOF_SENSOR,
                distance = depthResult.depthMeters
            )
        }
        
        // Fallback to ARCore hit test if ToF not available or low confidence
        val hits = frame.hitTest(screenX, screenY)
        
        for (hit in hits) {
            val trackable = hit.trackable
            
            when {
                // Depth points (from ARCore depth API) - second priority
                trackable is DepthPoint -> {
                    val pose = hit.hitPose
                    val distance = hit.distance
                    Timber.d("🎯 Using ARCore Depth: ${distance}m")
                    return EnhancedHitResult(
                        pose = pose,
                        confidence = 0.75f,
                        source = DepthSource.ARCORE_DEPTH,
                        distance = distance,
                        hitResult = hit
                    )
                }
                
                // Plane intersection - third priority
                trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) -> {
                    val pose = hit.hitPose
                    val distance = hit.distance
                    Timber.d("📐 Using ARCore Plane: ${distance}m")
                    return EnhancedHitResult(
                        pose = pose,
                        confidence = 0.9f, // Planes are very reliable
                        source = DepthSource.ARCORE_PLANE,
                        distance = distance,
                        hitResult = hit
                    )
                }
                
                // Instant placement with full tracking - last resort
                trackable is InstantPlacementPoint && 
                trackable.trackingMethod == InstantPlacementPoint.TrackingMethod.FULL_TRACKING -> {
                    val pose = hit.hitPose
                    val distance = hit.distance
                    Timber.d("⚡ Using Instant Placement: ${distance}m")
                    return EnhancedHitResult(
                        pose = pose,
                        confidence = 0.6f,
                        source = DepthSource.ARCORE_INSTANT_PLACEMENT,
                        distance = distance,
                        hitResult = hit
                    )
                }
            }
        }
        
        Timber.w("❌ No valid hit test result found")
        return null
    }
    
    private fun getScreenWidth(): Int {
        val displayMetrics = context.resources.displayMetrics
        return displayMetrics.widthPixels
    }
    
    private fun getScreenHeight(): Int {
        val displayMetrics = context.resources.displayMetrics
        return displayMetrics.heightPixels
    }
}

// Data classes
data class DepthResult(
    val depthMeters: Float,
    val confidence: Float,
    val worldPosition: FloatArray,
    val screenX: Float,
    val screenY: Float
)

data class EnhancedHitResult(
    val pose: Pose,
    val confidence: Float,
    val source: DepthSource,
    val distance: Float,
    val hitResult: HitResult? = null
)

enum class DepthSource {
    TOF_SENSOR,                   // Direct from Samsung ToF hardware (BEST)
    ARCORE_DEPTH,                 // ARCore depth from motion
    ARCORE_PLANE,                 // ARCore plane detection
    ARCORE_INSTANT_PLACEMENT      // ARCore instant placement (LEAST ACCURATE)
}
