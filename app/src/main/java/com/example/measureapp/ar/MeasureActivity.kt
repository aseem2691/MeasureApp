package com.example.measureapp.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    private lateinit var measurementSubtitle: TextView
    private lateinit var addButton: FrameLayout
    private lateinit var doneButton: ImageView
    private lateinit var doneButtonCard: androidx.cardview.widget.CardView
    private lateinit var undoButton: ImageView
    private lateinit var undoCard: androidx.cardview.widget.CardView
    private lateinit var clearButton: ImageView
    private lateinit var clearCard: androidx.cardview.widget.CardView
    private lateinit var helpHint: TextView
    private lateinit var captureButton: ImageView
    private lateinit var captureButtonCard: androidx.cardview.widget.CardView

    private lateinit var measurementManager: MeasurementManager
    private lateinit var reticle: ReticleNode
    private lateinit var rectangleDetector: RectangleDetector
    private var lastHitResult: com.google.ar.core.HitResult? = null
    private lateinit var haptic: com.example.measureapp.utils.HapticFeedback
    private lateinit var measurementCapture: MeasurementCapture
    private var lastSmartHitState: SmartHit = SmartHit.None
    private var detectedRectangle: DetectedRectangle? = null

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
        measurementSubtitle = findViewById(R.id.measurement_subtitle)
        addButton = findViewById(R.id.add_button)
        doneButton = findViewById(R.id.done_button)
        doneButtonCard = findViewById(R.id.done_button_card)
        undoButton = findViewById(R.id.undo_button)
        undoCard = findViewById(R.id.undo_card)
        clearButton = findViewById(R.id.clear_button)
        clearCard = findViewById(R.id.clear_card)
        helpHint = findViewById(R.id.help_hint)
        captureButton = findViewById(R.id.capture_button)
        captureButtonCard = findViewById(R.id.capture_button_card)

        // Initialize haptic feedback
        haptic = com.example.measureapp.utils.HapticFeedback(this)
        
        // Initialize rectangle detector
        rectangleDetector = RectangleDetector()
        
        measurementManager = MeasurementManager(this, sceneView) { measurementText ->
            runOnUiThread {
                promptText.text = measurementText
                // Update live label in overlay
                overlayView.liveLabelText = measurementText
            }
        }
        
        // Connect overlay to manager
        overlayView.measurementManager = measurementManager
        
        // Initialize measurement capture
        measurementCapture = MeasurementCapture(this, sceneView, overlayView)
        
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
            
            // CRITICAL FIX: Prevent feature points (white dots) from rendering
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
                
                // PRIORITY 1: Plane hits inside polygon (most stable)
                var hitResult = hits.firstOrNull { hit ->
                    val trackable = hit.trackable
                    trackable is com.google.ar.core.Plane && 
                    trackable.trackingState == com.google.ar.core.TrackingState.TRACKING &&
                    trackable.isPoseInPolygon(hit.hitPose)
                }
                if (hitResult != null) Log.d(TAG, "Using Plane hit (inside polygon) ✓")
                
                // PRIORITY 2: DepthPoint (ToF sensor) - good for edges but can be noisy
                if (hitResult == null) {
                    hitResult = hits.firstOrNull { hit ->
                        hit.trackable is com.google.ar.core.DepthPoint
                    }
                    if (hitResult != null) Log.d(TAG, "Using DepthPoint (ToF)")
                }
                
                // PRIORITY 3: Feature points (fallback for surfaces without plane detection)
                if (hitResult == null) {
                    hitResult = hits.firstOrNull { hit ->
                        hit.trackable is com.google.ar.core.Point &&
                        hit.trackable.trackingState == com.google.ar.core.TrackingState.TRACKING
                    }
                    if (hitResult != null) Log.d(TAG, "Using feature Point")
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
                
                // 5. RECTANGLE AUTO-DETECTION (scan periodically)
                if (frame.timestamp % 15L == 0L) { // Check every 15 frames (~0.25s at 60fps)
                    detectRectanglesInView(frame)
                    if (detectedRectangle != null) {
                        Log.d(TAG, "Rectangle detected! Sending to overlay")
                    }
                }
                
                // 6. Update overlay for 3D label rendering (includes rectangle overlay)
                overlayView.arCamera = camera
                val currentRectangle = detectedRectangle
                overlayView.detectedRectangle = currentRectangle
                if (currentRectangle != null) {
                    Log.d(TAG, "Setting overlay rectangle: ${currentRectangle.sides[0]}m x ${currentRectangle.sides[1]}m")
                }
                overlayView.postInvalidate()
                
                // 7. Monitor tracking quality and warn user
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
                    
                    // Haptic feedback when snapping state changes
                    if (smartHit != lastSmartHitState) {
                        when (smartHit) {
                            is SmartHit.SnappedVertex, is SmartHit.SnappedEdge -> {
                                haptic.lightImpact() // Light tap when snapping
                            }
                            else -> {}
                        }
                        lastSmartHitState = smartHit
                    }
                    
                    if (validHitResult != null && trackingQuality == "GOOD") {
                        addButton.isEnabled = true
                        addButton.alpha = 1.0f
                        
                        // Hide help hint once tracking
                        helpHint.visibility = android.view.View.GONE
                        
                        // Update prompt only if not currently measuring
                        if (!measurementManager.hasStartedMeasurement) {
                            promptText.text = when (smartHit) {
                                is SmartHit.SnappedVertex -> "Tap + to snap to vertex"
                                is SmartHit.SnappedEdge -> "Tap + to snap to edge"
                                else -> "Tap + to start"
                            }
                            measurementSubtitle.text = "Point at surface and tap +"
                        }
                    } else if (trackingQuality == "LIMITED" || trackingQuality == "POOR") {
                        addButton.isEnabled = false
                        addButton.alpha = 0.3f
                        promptText.text = "Move slowly"
                        measurementSubtitle.text = "Tracking quality low"
                    } else {
                        addButton.isEnabled = true 
                        addButton.alpha = 0.5f
                        
                        if (!measurementManager.hasStartedMeasurement) {
                            promptText.text = "Move device"
                            measurementSubtitle.text = "To detect surfaces"
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
                doneButtonCard.visibility = android.view.View.GONE
                undoCard.visibility = android.view.View.GONE
                clearCard.visibility = android.view.View.GONE
            }
        }
        
        doneButton.setOnClickListener {
            if (measurementManager.hasStartedMeasurement) {
                measurementManager.finishCurrentMeasurement()
                haptic.success() // Success pattern for completion
                // Hide Done button, allow starting new measurement
                doneButtonCard.visibility = android.view.View.GONE
                // Keep undo/clear visible for completed measurements
                overlayView.postInvalidate()
            }
        }

        clearButton.setOnClickListener {
            measurementManager.clear()
            promptText.text = "Point at a surface to start"
            measurementSubtitle.text = "Tap + to add point"
            doneButtonCard.visibility = android.view.View.GONE
            undoCard.visibility = android.view.View.GONE
            clearCard.visibility = android.view.View.GONE
            captureButtonCard.visibility = android.view.View.GONE
            helpHint.visibility = android.view.View.VISIBLE
            overlayView.postInvalidate()
        }
        
        captureButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    Toast.makeText(this@MeasureActivity, "Capturing...", Toast.LENGTH_SHORT).show()
                    val uri = measurementCapture.captureAndSave()
                    
                    if (uri != null) {
                        haptic.success()
                        Toast.makeText(this@MeasureActivity, "Saved to gallery", Toast.LENGTH_SHORT).show()
                        
                        // Offer to share
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share measurement"))
                    } else {
                        haptic.error()
                        Toast.makeText(this@MeasureActivity, "Failed to capture", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Capture error", e)
                    haptic.error()
                    Toast.makeText(this@MeasureActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
                    haptic.mediumImpact() // Medium tap for point placement
                    Toast.makeText(this, "Snapped to vertex", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Point added - snapped to vertex")
                }
                is SmartHit.SnappedEdge -> {
                    // Create new anchor at projected edge position
                    val edgePose = smartHit.getPose()!!
                    val anchor = hitResult.createAnchor()
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    haptic.mediumImpact() // Medium tap for point placement
                    Toast.makeText(this, "Snapped to edge", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Point added - snapped to edge")
                }
                is SmartHit.Surface -> {
                    // Normal surface placement
                    val anchor = hitResult.createAnchor()
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    haptic.mediumImpact() // Medium tap for point placement
                    Toast.makeText(this, "Point added", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Point added - normal surface")
                }
                SmartHit.None -> {
                    haptic.error() // Error pattern for invalid operation
                    Toast.makeText(this, "No surface detected", Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "Cannot add point - SmartHit.None")
                    return
                }
            }
            
            overlayView.postInvalidate()
            
            // Show Done, Undo, Clear, and Capture buttons after first point
            if (doneButtonCard.visibility == android.view.View.GONE) {
                doneButtonCard.visibility = android.view.View.VISIBLE
                undoCard.visibility = android.view.View.VISIBLE
                clearCard.visibility = android.view.View.VISIBLE
                captureButtonCard.visibility = android.view.View.VISIBLE
            }
        } else {
            Log.w(TAG, "Cannot add point - no hitResult")
            Toast.makeText(this, "No surface detected. Move phone to find a surface.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Scan current frame for rectangular surfaces
     */
    private fun detectRectanglesInView(frame: com.google.ar.core.Frame) {
        try {
            // Get planes that camera is actually looking at (within screen center)
            val planes = mutableListOf<com.google.ar.core.Plane>()
            val camera = frame.camera
            val cameraPose = camera.pose
            
            // Iterate through updated trackables and filter planes
            frame.getUpdatedTrackables(com.google.ar.core.Plane::class.java).forEach { trackable ->
                if (trackable.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                    // Check if plane center is reasonably close to camera direction
                    val planePose = trackable.centerPose
                    val distanceToPlane = (planePose.translation[0] - cameraPose.translation[0]) * (planePose.translation[0] - cameraPose.translation[0]) +
                                         (planePose.translation[1] - cameraPose.translation[1]) * (planePose.translation[1] - cameraPose.translation[1]) +
                                         (planePose.translation[2] - cameraPose.translation[2]) * (planePose.translation[2] - cameraPose.translation[2])
                    
                    // Only consider planes within 2 meters of camera
                    if (Math.sqrt(distanceToPlane.toDouble()) < 2.0) {
                        planes.add(trackable)
                    }
                }
            }
            
            if (planes.isEmpty()) {
                detectedRectangle = null
                return
            }
            
            // Find the best rectangle (closest to camera center)
            var bestRectangle: DetectedRectangle? = null
            var bestScore = 0f
            
            for (plane in planes) {
                val rectangle = rectangleDetector.detectRectangle(plane)
                if (rectangle != null) {
                    // Check if all corners are within reasonable view (not too far outside frustum)
                    val cornersInView = isRectangleInView(frame, rectangle)
                    Log.d(TAG, "Rectangle ${rectangle.sides[0]}m x ${rectangle.sides[1]}m: corners in view = $cornersInView")
                    if (!cornersInView) {
                        continue // Skip rectangles with corners outside view
                    }
                    
                    // Score based on: confidence, size, and how close to screen center (what user is looking at)
                    val size = rectangle.sides.average().toFloat()
                    
                    // Calculate how centered the rectangle is (0-1, higher is better)
                    val centeredness = calculateRectangleCenteredness(frame, rectangle)
                    
                    // Only consider rectangles that are reasonably centered (user is looking at them)
                    if (centeredness < 0.5f) {
                        Log.d(TAG, "  Skipping off-center rectangle: centeredness=$centeredness")
                        continue
                    }
                    
                    // Score: confidence * size * centeredness^2 (heavily favor centered rectangles)
                    val score = rectangle.confidence * size * centeredness * centeredness
                    
                    Log.d(TAG, "  Score: conf=${rectangle.confidence} size=${size}m centered=$centeredness => $score")
                    
                    if (score > bestScore) {
                        bestScore = score
                        bestRectangle = rectangle
                    }
                }
            }
            
            detectedRectangle = bestRectangle
            if (bestRectangle != null) {
                Log.d(TAG, "✓ Best rectangle selected: ${bestRectangle.sides[0]}m x ${bestRectangle.sides[1]}m, score=$bestScore")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Rectangle detection error: ${e.message}")
            detectedRectangle = null
        }
    }
    
    /**
     * Check if a rectangle's corners are within or near the camera view frustum
     */
    private fun isRectangleInView(frame: com.google.ar.core.Frame, rectangle: DetectedRectangle): Boolean {
        val camera = frame.camera
        
        // Get camera matrices
        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100f)
        camera.getViewMatrix(viewMatrix, 0)
        
        // Combine view and projection matrices
        val vpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        
        // Check each corner
        var cornersInView = 0
        for ((i, corner) in rectangle.corners.withIndex()) {
            val worldPos = floatArrayOf(corner.x, corner.y, corner.z, 1f)
            val clipPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(clipPos, 0, vpMatrix, 0, worldPos, 0)
            
            // Skip if behind camera
            if (clipPos[3] <= 0) {
                Log.d(TAG, "Corner $i behind camera (w=${clipPos[3]})")
                continue
            }
            
            // Calculate NDC
            val ndcX = clipPos[0] / clipPos[3]
            val ndcY = clipPos[1] / clipPos[3]
            
            Log.d(TAG, "Corner $i NDC: ($ndcX, $ndcY)")
            
            // Allow generous margin (±2.5) to handle rectangles viewed at steep angles
            // This allows rectangles partially outside screen to still be detected
            if (ndcX >= -2.5f && ndcX <= 2.5f && ndcY >= -2.5f && ndcY <= 2.5f) {
                cornersInView++
            }
        }
        
        Log.d(TAG, "isRectangleInView: $cornersInView/4 corners in view (need 2)")
        
        // Require at least 2 out of 4 corners to be in view (rectangle visible to user)
        return cornersInView >= 2
    }
    
    /**
     * Calculate how centered a rectangle is in the camera view (0-1, higher is better)
     * Heavily favors rectangles near screen center (what user is looking at)
     */
    private fun calculateRectangleCenteredness(frame: com.google.ar.core.Frame, rectangle: DetectedRectangle): Float {
        val camera = frame.camera
        
        // Get camera matrices
        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100f)
        camera.getViewMatrix(viewMatrix, 0)
        
        // Combine matrices
        val vpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        
        // Calculate average NDC position of rectangle
        var sumX = 0f
        var sumY = 0f
        var count = 0
        
        for (corner in rectangle.corners) {
            val worldPos = floatArrayOf(corner.x, corner.y, corner.z, 1f)
            val clipPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(clipPos, 0, vpMatrix, 0, worldPos, 0)
            
            if (clipPos[3] > 0) {
                val ndcX = clipPos[0] / clipPos[3]
                val ndcY = clipPos[1] / clipPos[3]
                sumX += ndcX
                sumY += ndcY
                count++
            }
        }
        
        if (count == 0) return 0f
        
        val avgX = sumX / count
        val avgY = sumY / count
        
        // Distance from center (0,0) in NDC space
        val distanceFromCenter = Math.sqrt((avgX * avgX + avgY * avgY).toDouble()).toFloat()
        
        // Convert to centeredness score (1.0 at center, 0.0 at edges)
        // NDC range is -1 to +1, so max distance is ~1.4 (corner)
        return Math.max(0f, 1f - (distanceFromCenter / 1.4f))
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
