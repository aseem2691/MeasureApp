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
    private lateinit var distanceText: TextView
    private lateinit var addButton: Button
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

        distanceText = findViewById(R.id.distance_text)
        addButton = findViewById(R.id.add_button)
        clearButton = findViewById(R.id.clear_button)
        centerReticle = findViewById(R.id.center_reticle)

        measurementManager = MeasurementManager(this, sceneView) { measurementText ->
            runOnUiThread {
                distanceText.text = measurementText
            }
        }
        distanceText.text = getString(R.string.measurement_hint)

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
        
        // Enable plane visualization
        sceneView.planeRenderer.isEnabled = true
        sceneView.planeRenderer.isVisible = true

        sceneView.onSessionFailed = { exception ->
            Log.e(TAG, "AR Session failed", exception)
            Toast.makeText(this, "AR Session failed: ${exception.message}", Toast.LENGTH_LONG).show()
        }
        
        // Continuous Hit Testing - exactly like ARCoreMeasure & StreetMeasure
        sceneView.onSessionUpdated = { session, frame ->
            val camera = frame.camera
            if (camera.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                val centerX = sceneView.width / 2f
                val centerY = sceneView.height / 2f
                
                // CRITICAL: Use isPoseInPolygon check like ARCoreMeasure
                val hitResult = frame.hitTest(centerX, centerY).firstOrNull { hit ->
                    val trackable = hit.trackable
                    when (trackable) {
                        is com.google.ar.core.Plane -> {
                            // Must be inside the plane polygon!
                            trackable.isPoseInPolygon(hit.hitPose) && 
                            trackable.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING
                        }
                        is com.google.ar.core.Point -> {
                            // Allow feature points with estimated normals
                            trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                        }
                        else -> false
                    }
                }
                
                // Validate distance from camera (like StreetMeasure - max 3m)
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
                
                runOnUiThread {
                    if (validHitResult != null) {
                        // Valid surface detected - Green reticle
                        centerReticle.setColorFilter(android.graphics.Color.GREEN)
                        addButton.isEnabled = true
                        addButton.alpha = 1.0f
                    } else {
                        // No surface or too far - White reticle
                        centerReticle.setColorFilter(android.graphics.Color.WHITE)
                        addButton.isEnabled = true 
                        addButton.alpha = 0.5f
                    }
                }
            } else {
                // Camera not tracking - show white reticle
                runOnUiThread {
                    centerReticle.setColorFilter(android.graphics.Color.GRAY)
                    addButton.isEnabled = false
                    addButton.alpha = 0.3f
                }
            }
        }

        // Setup buttons
        addButton.setOnClickListener {
            addPoint()
        }

        clearButton.setOnClickListener {
            measurementManager.clear()
            distanceText.text = getString(R.string.measurement_hint)
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
