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

        // Configure AR Scene - SIMPLIFIED like ARCoreMeasure (uses defaults!)
        sceneView.configureSession { session, config ->
            // HORIZONTAL only - most stable for floor measurements
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            config.focusMode = Config.FocusMode.AUTO
            // CRITICAL: Disable depth like StreetMeasure - rely on plane detection!
            config.depthMode = Config.DepthMode.DISABLED
        }
        
        // Enable plane visualization for better surface detection feedback
        sceneView.planeRenderer.apply {
            isEnabled = true
            isVisible = true
            // Make planes more visible (like ARuler)
            isShadowReceiver = false
        }

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
                
                // 1. Perform Hit Test
                val hitResult = frame.hitTest(centerX, centerY).firstOrNull { hit ->
                    val trackable = hit.trackable
                    when (trackable) {
                        is com.google.ar.core.Plane -> {
                            trackable.isPoseInPolygon(hit.hitPose) && 
                            trackable.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING
                        }
                        is com.google.ar.core.Point -> {
                            trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                        }
                        else -> false
                    }
                }
                
                // Validate distance from camera (max 3m)
                val validHitResult = hitResult?.let { hit ->
                    val hitPose = hit.hitPose
                    val cameraPose = camera.pose
                    val dx = hitPose.tx() - cameraPose.tx()
                    val dy = hitPose.ty() - cameraPose.ty()
                    val dz = hitPose.tz() - cameraPose.tz()
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    if (distance <= 3.0f) hit else null
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
                        centerReticle.setColorFilter(android.graphics.Color.GREEN)
                        addButton.isEnabled = true
                        addButton.alpha = 1.0f
                    } else {
                        centerReticle.setColorFilter(android.graphics.Color.WHITE)
                        addButton.isEnabled = true 
                        addButton.alpha = 0.5f
                    }
                }
            } else {
                runOnUiThread {
                    centerReticle.setColorFilter(android.graphics.Color.GRAY)
                    addButton.isEnabled = false
                    addButton.alpha = 0.3f
                    promptText.text = "Move to start"
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
                doneButton.isEnabled = false
                doneButton.alpha = 0.5f
                addButton.isEnabled = false
                addButton.alpha = 0.5f
                measurementOverlay.postInvalidate()
                promptText.text = "Measurement complete. Tap Clear to start new."
            }
        }

        clearButton.setOnClickListener {
            measurementManager.clear()
            promptText.text = "Move to start"
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
            val anchor = hitResult.createAnchor()
            measurementManager.addPoint(anchor)
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
