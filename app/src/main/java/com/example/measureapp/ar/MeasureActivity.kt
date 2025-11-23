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
import com.google.ar.core.Session
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import java.util.EnumSet

class MeasureActivity : AppCompatActivity() {

    private val TAG = "MeasureActivity"
    private val CAMERA_PERMISSION_CODE = 1001

    private lateinit var sceneView: ARSceneView
    private lateinit var measurementOverlay: MeasurementOverlay
    private lateinit var promptText: TextView
    private lateinit var addButton: Button
    private lateinit var doneButton: Button
    private lateinit var undoButton: Button
    private lateinit var clearButton: Button
    private lateinit var centerReticle: android.widget.ImageView

    private lateinit var measurementManager: MeasurementManager
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
        
        measurementOverlay = findViewById(R.id.measurement_overlay)
        promptText = findViewById(R.id.prompt_text)
        addButton = findViewById(R.id.add_button)
        doneButton = findViewById(R.id.done_button)
        undoButton = findViewById(R.id.undo_button)
        clearButton = findViewById(R.id.clear_button)
        centerReticle = findViewById(R.id.center_reticle)

        measurementManager = MeasurementManager(this, sceneView) { measurementText ->
            runOnUiThread {
                promptText.text = measurementText
            }
        }
        
        // Connect overlay to manager
        measurementOverlay.measurementManager = measurementManager
        
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
            
            // CRITICAL for S25+: AUTO focus is essential for feature tracking
            config.focusMode = Config.FocusMode.AUTO
            
            // Disable depth - rely on plane detection for stability
            config.depthMode = Config.DepthMode.DISABLED
        }
        
        // Enable plane visualization for better surface detection feedback
        sceneView.planeRenderer.apply {
            isEnabled = true
            isVisible = true
            // Make planes more visible - white dots show detected surfaces
            isShadowReceiver = false
        }
        
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
                
                // 1. Perform Hit Test with proper filtering
                // Take the first hit that has a tracked trackable (Plane or Point)
                val hits = frame.hitTest(centerX, centerY)
                
                val hitResult = hits.firstOrNull { hit ->
                    val trackable = hit.trackable
                    (trackable is com.google.ar.core.Plane && trackable.isPoseInPolygon(hit.hitPose)) ||
                    (trackable is com.google.ar.core.Point && 
                     trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) ||
                    // Fallback: Allow any Plane hit even outside polygon (for edges)
                    (trackable is com.google.ar.core.Plane && trackable.trackingState == com.google.ar.core.TrackingState.TRACKING)
                }
                
                // Validate distance from camera (max 5m for better range)
                val validHitResult = hitResult?.let { hit ->
                    val hitPose = hit.hitPose
                    val cameraPose = camera.pose
                    val dx = hitPose.tx() - cameraPose.tx()
                    val dy = hitPose.ty() - cameraPose.ty()
                    val dz = hitPose.tz() - cameraPose.tz()
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    if (distance <= 5.0f) hit else null
                }
                
                lastHitResult = validHitResult
                
                // 2. UPDATE THE MANAGER - This draws the dynamic rubber band line!
                measurementManager.onUpdate(validHitResult)
                
                // 2b. Update overlay for 3D label rendering
                measurementOverlay.arCamera = camera
                measurementOverlay.postInvalidate()
                
                // 3. Update UI Reticle
                runOnUiThread {
                    if (validHitResult != null) {
                        // GREEN reticle = Surface detected, safe to measure!
                        centerReticle.setColorFilter(android.graphics.Color.GREEN)
                        addButton.isEnabled = true
                        addButton.alpha = 1.0f
                        
                        // Update prompt only if not currently measuring
                        if (!measurementManager.hasStartedMeasurement) {
                            promptText.text = "Tap + to start"
                        }
                    } else {
                        // WHITE reticle = Searching for surface
                        centerReticle.setColorFilter(android.graphics.Color.WHITE)
                        addButton.isEnabled = true 
                        addButton.alpha = 0.5f
                        
                        if (!measurementManager.hasStartedMeasurement) {
                            promptText.text = "Move phone to detect surface"
                        }
                    }
                }
            } else {
                runOnUiThread {
                    // GRAY reticle = Tracking lost
                    centerReticle.setColorFilter(android.graphics.Color.GRAY)
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
            measurementOverlay.postInvalidate()
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
                measurementOverlay.postInvalidate()
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
            measurementOverlay.postInvalidate()
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
        
        if (hitResult != null) {
            // Check if we're near an existing anchor point
            val nearbyAnchor = measurementManager.getNearbyAnchor(hitResult.hitPose)
            
            if (nearbyAnchor != null) {
                // Snap to existing anchor - reuse it
                measurementManager.addPoint(nearbyAnchor, isExistingAnchor = true)
                Toast.makeText(this, "Snapped to existing point", Toast.LENGTH_SHORT).show()
            } else {
                // Create new anchor
                val anchor = hitResult.createAnchor()
                measurementManager.addPoint(anchor, isExistingAnchor = false)
            }
            
            measurementOverlay.postInvalidate()
            
            // Show and enable Done button after first point
            if (doneButton.visibility == android.view.View.GONE) {
                doneButton.visibility = android.view.View.VISIBLE
                doneButton.isEnabled = true
                doneButton.alpha = 1.0f
            }
        } else {
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
