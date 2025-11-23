package com.example.measureapp.ar

import android.content.Context
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ArSessionManager - Manages ARCore session lifecycle OUTSIDE of Compose recomposition.
 * 
 * **KEY INSIGHT from working repos**: 
 * - StreetMeasure creates session in Activity.onResume(), NOT in Compose
 * - ARCoreMeasure uses AndroidView with GLSurfaceView lifecycle
 * - Both avoid Compose state-driven recomposition triggering session recreation
 * 
 * This manager provides:
 * 1. Session created ONCE and reused
 * 2. Lifecycle methods (pause/resume) tied to actual Android lifecycle
 * 3. No dependency on Compose recomposition
 */
class ArSessionManager(private val context: Context) {
    
    private val TAG = "ArSessionManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Session state - created once, reused forever
    private var _session: Session? = null
    val session: Session? get() = _session
    
    private var isSessionConfigured = false
    private var isPaused = true
    
    /**
     * Initialize ARCore session - called once from Activity/Fragment
     * NOT from Compose remember {} to avoid recomposition issues
     */
    fun createSession(): Boolean {
        if (_session != null) {
            Log.d(TAG, "✅ Session already exists, reusing...")
            return true
        }
        
        return try {
            Log.i(TAG, "🚀 Creating NEW ARCore session (should happen ONCE ONLY)")
            
            val session = Session(context)
            configureSession(session)
            
            _session = session
            isSessionConfigured = true
            isPaused = true
            
            Log.i(TAG, "✅ ARCore session created and configured successfully!")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create ARCore session", e)
            false
        }
    }
    
    /**
     * Configure session with proven settings from StreetMeasure and ARCoreMeasure
     */
    private fun configureSession(session: Session) {
        val config = session.config
        
        // From StreetMeasure: HORIZONTAL planes only for stability
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        Log.i(TAG, "✅ Plane finding: HORIZONTAL only (stable detection)")
        
        // From both repos: Instant placement DISABLED for accuracy
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        Log.i(TAG, "✅ Instant placement: DISABLED (accurate measurements)")
        
        // Update mode for real-time tracking
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        
        // Light estimation for better rendering
        config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
        
        // Focus mode for sharp edges
        config.focusMode = Config.FocusMode.AUTO
        
        // Depth configuration (if available)
        if (session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
            config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
            Log.i(TAG, "✅ Depth mode: RAW_DEPTH_ONLY (ToF sensor)")
        } else if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
            Log.i(TAG, "✅ Depth mode: AUTOMATIC (fallback)")
        } else {
            config.depthMode = Config.DepthMode.DISABLED
            Log.w(TAG, "⚠️ Depth mode: DISABLED (not supported)")
        }
        
        // Disable unused features for performance
        config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
        
        // Configure camera
        configureCameraConfig(session)
        
        // Apply configuration
        session.configure(config)
    }
    
    /**
     * Select best camera configuration
     */
    private fun configureCameraConfig(session: Session) {
        val cameraConfigFilter = com.google.ar.core.CameraConfigFilter(session)
        cameraConfigFilter.setFacingDirection(
            com.google.ar.core.CameraConfig.FacingDirection.BACK
        )
        
        val cameraConfigs = session.getSupportedCameraConfigs(cameraConfigFilter)
            .sortedByDescending { it.textureSize.width * it.textureSize.height }
        
        Log.i(TAG, "Found ${cameraConfigs.size} camera configs")
        
        if (cameraConfigs.isNotEmpty()) {
            // Log first 3 options
            cameraConfigs.take(3).forEachIndexed { index, config ->
                Log.i(TAG, "Camera[$index]: ${config.textureSize.width}x${config.textureSize.height}, " +
                        "FPS=${config.fpsRange.lower}-${config.fpsRange.upper}, " +
                        "Depth=${config.depthSensorUsage}")
            }
            
            // Select: Camera ID 0 (main camera) with highest resolution
            val selected = cameraConfigs.firstOrNull { it.cameraId == "0" } 
                ?: cameraConfigs.first()
            
            session.cameraConfig = selected
            Log.i(TAG, "✅ Selected camera: ${selected.textureSize.width}x${selected.textureSize.height}")
        }
    }
    
    /**
     * Resume session - called from Activity.onResume()
     */
    fun resume() {
        val session = _session ?: run {
            Log.w(TAG, "⚠️ Cannot resume - session not created")
            return
        }
        
        if (!isPaused) {
            Log.d(TAG, "Session already resumed")
            return
        }
        
        try {
            session.resume()
            isPaused = false
            Log.i(TAG, "✅ Session resumed")
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "❌ Camera not available", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to resume session", e)
        }
    }
    
    /**
     * Pause session - called from Activity.onPause()
     */
    fun pause() {
        val session = _session ?: return
        
        if (isPaused) {
            Log.d(TAG, "Session already paused")
            return
        }
        
        session.pause()
        isPaused = true
        Log.i(TAG, "✅ Session paused")
    }
    
    /**
     * Destroy session - called from Activity.onDestroy()
     */
    fun destroy() {
        scope.launch(Dispatchers.Default) {
            _session?.close()
            _session = null
            isSessionConfigured = false
            isPaused = true
            Log.i(TAG, "✅ Session destroyed")
        }
    }
    
    /**
     * Check if session is ready for use
     */
    fun isReady(): Boolean {
        return _session != null && isSessionConfigured && !isPaused
    }
}
