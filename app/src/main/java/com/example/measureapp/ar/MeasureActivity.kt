package com.example.measureapp.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.measureapp.R
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import java.util.EnumSet

class MeasureActivity : AppCompatActivity() {

    private val TAG = "MeasureActivity"
    private val CAMERA_PERMISSION_CODE = 1001

    private lateinit var sceneView: ARSceneView
    private lateinit var overlayView: OverlayView
    private lateinit var promptText: TextView
    private lateinit var addButton: Button
    private lateinit var doneButton: Button
    private lateinit var undoButton: Button
    private lateinit var clearButton: Button

    private lateinit var measurementManager: MeasurementManager
    private lateinit var reticle: ReticleNode
    private var lastHitResult: com.google.ar.core.HitResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Find views
        sceneView = findViewById(R.id.scene_view)
        // Connect lifecycle
        sceneView.lifecycle = this.lifecycle
        
        overlayView = findViewById(R.id.overlay_view)
        promptText = findViewById(R.id.prompt_text)
        addButton = findViewById(R.id.add_button)
        doneButton = findViewById(R.id.done_button)
        undoButton = findViewById(R.id.undo_button)
        clearButton = findViewById(R.id.clear_button)

        measurementManager = MeasurementManager(this, sceneView) { measurementText ->
            runOnUiThread {
                promptText.text = measurementText
                // Update live label in overlay
                overlayView.liveLabelText = measurementText
            }
        }
        
        // Connect overlay to manager
        overlayView.measurementManager = measurementManager
        
        promptText.text = "Move to start"

        // Check Camera Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }

        // Configure AR Scene - Optimized for S25+ surface detection
        sceneView.configureSession { session, config ->
            // CRITICAL: Enable both horizontal AND vertical for better detection
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            
            // Better lighting estimation for indoor/outdoor
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            
            // Enable Cloud Anchors for better persistence and tracking
            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
            
            // Optimize tracking for measurement accuracy
            // This helps maintain anchor positions when moving camera
            
            // CRITICAL for S25+: AUTO focus is essential for feature tracking
            config.focusMode = Config.FocusMode.AUTO
            
            // Enable Depth API for better edge detection on S25+ ToF sensor
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
                Log.d(TAG, "Depth mode enabled: AUTOMATIC")
            } else {
                config.depthMode = Config.DepthMode.DISABLED
                Log.d(TAG, "Depth mode not supported, using DISABLED")
            }
        }
        
        // Enable plane visualization for better surface detection feedback
        sceneView.planeRenderer.apply {
            isEnabled = true
            isVisible = true
            // Make planes more visible - white dots show detected surfaces
            isShadowReceiver = false
        }
        
        // Initialize Professional 3D Reticle AFTER SceneView is configured
        reticle = ReticleNode(sceneView)
        sceneView.addChildNode(reticle)
        Log.d(TAG, "Professional Reticle initialized and added to scene")
        
        // Initial prompt
        promptText.text = "Move phone to detect surface"

        sceneView.onSessionFailed = { exception ->
            Log.e(TAG, "AR Session failed", exception)
            Toast.makeText(this, "AR Session failed: ${exception.message}", Toast.LENGTH_LONG).show()
        }
        
        // Continuous Hit Testing with Rubber Banding
        sceneView.onSessionUpdated = { session, frame ->
            val camera = frame.camera
            if (camera.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                val centerX = sceneView.width / 2f
                val centerY = sceneView.height / 2f
                
                // 1. Perform Hit Test with EDGE DETECTION priority
                // Priority: Plane (inside) > DepthPoint (edges!) > Point > Plane (outside)
                val hits = frame.hitTest(centerX, centerY)
                
                // Log what we're seeing for debugging
                if (hits.isNotEmpty()) {
                    val types = hits.map { hit ->
                        when (hit.trackable) {
                            is com.google.ar.core.Plane -> "Plane"
                            is com.google.ar.core.DepthPoint -> "DepthPoint"
                            is com.google.ar.core.Point -> "Point"
                            else -> "Unknown"
                        }
                    }.distinct()
                    Log.d(TAG, "Hit types available: ${types.joinToString()}")
                }
                
                // PRIORITY 1: DepthPoint (ToF sensor edges) - MOST ACCURATE for edges
                var hitResult = hits.firstOrNull { hit ->
                    hit.trackable is com.google.ar.core.DepthPoint
                }
                if (hitResult != null) Log.d(TAG, "Using DepthPoint (ToF edge) ✓")
                
                // PRIORITY 2: Horizontal planes (laptop screens, tables)
                if (hitResult == null) {
                    hitResult = hits.firstOrNull { hit ->
                        val trackable = hit.trackable
                        if (trackable is com.google.ar.core.Plane && 
                            trackable.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                            // Check if plane is reasonably horizontal (laptop screen, table)
                            val normal = trackable.centerPose.getTransformedAxis(1, 1.0f)
                            val upDot = normal[1] // Y component - 1.0 = horizontal up, -1.0 = horizontal down
                            kotlin.math.abs(upDot) > 0.7f // Prefer surfaces within 45° of horizontal
                        } else false
                    }
                    if (hitResult != null) Log.d(TAG, "Using horizontal Plane hit")
                }
                
                // PRIORITY 3: Any tracked plane
                if (hitResult == null) {
                    hitResult = hits.firstOrNull { hit ->
                        val trackable = hit.trackable
                        trackable is com.google.ar.core.Plane && 
                        trackable.trackingState == com.google.ar.core.TrackingState.TRACKING
                    }
                    if (hitResult != null) Log.d(TAG, "Using any Plane hit")
                }
                
                // Oriented surface points
                if (hitResult == null) {
                    hitResult = hits.firstOrNull { hit ->
                        hit.trackable is com.google.ar.core.Point
                    }
                    if (hitResult != null) Log.d(TAG, "Using Point")
                }
                
                if (hitResult == null) {
                    Log.d(TAG, "No valid hit found from ${hits.size} hits")
                }
                
                // Validate distance from camera (max 10m for better range)
                val validHitResult = hitResult?.let { hit ->
                    val hitPose = hit.hitPose
                    val cameraPose = camera.pose
                    val dx = hitPose.tx() - cameraPose.tx()
                    val dy = hitPose.ty() - cameraPose.ty()
                    val dz = hitPose.tz() - cameraPose.tz()
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    // Only log distance occasionally to reduce spam
                    if (frame.timestamp % 30L == 0L) {
                        Log.d(TAG, "Hit distance: ${distance}m")
                    }
                    if (distance <= 10.0f && distance >= 0.1f) hit else null // Allow 10cm to 10m
                }
                
                lastHitResult = validHitResult
                
                // Monitor tracking quality
                if (frame.timestamp % 60L == 0L) {
                    val trackingState = camera.trackingState
                    val trackingReason = camera.trackingFailureReason
                    if (trackingState != com.google.ar.core.TrackingState.TRACKING) {
                        Log.w(TAG, "Tracking quality: $trackingState, reason: $trackingReason")
                    }
                }
                
                // 2. UPDATE THE MANAGER - This performs smart hit testing and updates rubber band
                measurementManager.onUpdate(validHitResult)
                
                // 3. GET SMART HIT RESULT for reticle visualization
                val smartHit = measurementManager.getCurrentSmartHit()
                val smartPose = smartHit.getPose()
                
                // 4. UPDATE PROFESSIONAL RETICLE
                val reticleState = when (smartHit) {
                    is SmartHit.None -> ReticleNode.State.SEARCHING
                    is SmartHit.SnappedVertex, is SmartHit.SnappedEdge -> ReticleNode.State.SNAPPED
                    is SmartHit.Surface -> ReticleNode.State.TRACKING
                }
                
                if (smartPose != null) {
                    reticle.update(smartPose, reticleState)
                    Log.d(TAG, "Reticle updated with pose at (${smartPose.tx()}, ${smartPose.ty()}, ${smartPose.tz()})")
                } else {
                    // No surface detected - show reticle 1m in front of camera
                    val cameraPose = camera.pose
                    val forwardPose = cameraPose.compose(Pose.makeTranslation(0f, 0f, -1.0f))
                    reticle.update(forwardPose, ReticleNode.State.SEARCHING)
                    Log.d(TAG, "No hit - showing reticle in SEARCHING mode")
                }
                reticle.smoothUpdate(0.016f) // ~60 FPS
                
                // 5. Update overlay for 3D label rendering
                overlayView.arCamera = camera
                overlayView.postInvalidate()
                
                // 6. Monitor tracking quality and warn user
                val trackingQuality = when (camera.trackingState) {
                    com.google.ar.core.TrackingState.TRACKING -> {
                        // Check if we have good feature points
                        val limitedTracking = camera.trackingFailureReason != com.google.ar.core.TrackingFailureReason.NONE
                        if (limitedTracking) "LIMITED" else "GOOD"
                    }
                    com.google.ar.core.TrackingState.PAUSED -> "PAUSED"
                    else -> "POOR"
                }
                
                // 7. Update UI based on smart hit state and tracking quality
                runOnUiThread {
                    val isSnapped = smartHit.isSnapped()
                    
                    if (validHitResult != null && trackingQuality == "GOOD") {
                        addButton.isEnabled = true
                        addButton.alpha = 1.0f
                        
                        // Update prompt only if not currently measuring
                        if (!measurementManager.hasStartedMeasurement) {
                            promptText.text = when (smartHit) {
                                is SmartHit.SnappedVertex -> "Tap + to snap to vertex"
                                is SmartHit.SnappedEdge -> "Tap + to snap to edge"
                                else -> "Tap + to start"
                            }
                        }
                    } else if (trackingQuality == "LIMITED" || trackingQuality == "POOR") {
                        addButton.isEnabled = false
                        addButton.alpha = 0.3f
                        promptText.text = "⚠️ Move slowly for better tracking"
                    } else {
                        addButton.isEnabled = true 
                        addButton.alpha = 0.5f
                        
                        if (!measurementManager.hasStartedMeasurement) {
                            promptText.text = "Move phone to detect surface"
                        }
                    }
                }
            } else {
                reticle.update(null, ReticleNode.State.SEARCHING)
                runOnUiThread {
                    addButton.isEnabled = false
                    addButton.alpha = 0.3f
                    
                    if (!measurementManager.hasStartedMeasurement) {
                        promptText.text = "Move phone slowly to detect surface"
                    }
                }
            }
        }

        // Setup buttons
        addButton.setOnClickListener {
            addPoint()
        }
        
        undoButton.setOnClickListener {
            measurementManager.undo()
            overlayView.postInvalidate()
            if (!measurementManager.hasStartedMeasurement) {
                doneButton.visibility = android.view.View.GONE
                doneButton.isEnabled = false
                doneButton.alpha = 0.5f
            }
        }
        
        doneButton.setOnClickListener {
            if (measurementManager.hasStartedMeasurement) {
                measurementManager.finishCurrentMeasurement()
                // Hide Done button, allow starting new measurement
                doneButton.visibility = android.view.View.GONE
                doneButton.isEnabled = false
                doneButton.alpha = 0.5f
                // Keep add button enabled for next measurement
                addButton.isEnabled = true
                addButton.alpha = 1.0f
                overlayView.postInvalidate()
            }
        }

        clearButton.setOnClickListener {
            measurementManager.clear()
            promptText.text = "Move phone to detect surface"
            doneButton.visibility = android.view.View.GONE
            doneButton.isEnabled = false
            doneButton.alpha = 0.5f
            addButton.isEnabled = true
            addButton.alpha = 1.0f
            overlayView.postInvalidate()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, recreate to start session
                recreate()
            } else {
                Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun addPoint() {
        val hitResult = lastHitResult
        
        Log.d(TAG, "addPoint called, hitResult = ${hitResult != null}")
        
        if (hitResult != null) {
            // Get the smart hit to determine if we're snapping
            val smartHit = measurementManager.getCurrentSmartHit()
            
            Log.d(TAG, "SmartHit type: ${smartHit::class.simpleName}")
            
            when (smartHit) {
                is SmartHit.SnappedVertex -> {
                    // Reuse existing anchor
                    measurementManager.addPoint(smartHit.anchor, isExistingAnchor = true)
                    Toast.makeText(this, "Snapped to vertex", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Point added - snapped to vertex")
                }
                is SmartHit.SnappedEdge -> {
                    // Create new anchor at projected edge position
                    val edgePose = smartHit.getPose()!!
                    val anchor = hitResult.createAnchor()
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    Toast.makeText(this, "Snapped to edge", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Point added - snapped to edge")
                }
                is SmartHit.Surface -> {
                    // Normal surface placement
                    val anchor = hitResult.createAnchor()
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    Toast.makeText(this, "Point added", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Point added - normal surface")
                }
                SmartHit.None -> {
                    Toast.makeText(this, "No surface detected", Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "Cannot add point - SmartHit.None")
                    return
                }
            }
            
            overlayView.postInvalidate()
            
            // Show and enable Done button after first point
            if (doneButton.visibility == android.view.View.GONE) {
                doneButton.visibility = android.view.View.VISIBLE
                doneButton.isEnabled = true
                doneButton.alpha = 1.0f
            }
        } else {
            Log.w(TAG, "Cannot add point - no hitResult")
            Toast.makeText(this, "No surface detected. Move phone to find a surface.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
