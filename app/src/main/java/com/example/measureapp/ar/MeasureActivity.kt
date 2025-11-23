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

        // Configure AR Scene
        sceneView.configureSession { session, config ->
            // Enable both Horizontal and Vertical planes (like StreetMeasure/iOS)
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.focusMode = Config.FocusMode.AUTO
            // Depth mode can help with occlusion but might be unstable on some devices. 
            // Keeping it disabled for now as per StreetMeasure, but enabling Vertical planes is key.
            config.depthMode = Config.DepthMode.DISABLED 
        }
        
        // Ensure plane renderer is visible
        sceneView.planeRenderer.isEnabled = true
        sceneView.planeRenderer.isVisible = true

        sceneView.onSessionFailed = { exception ->
            Log.e(TAG, "AR Session failed", exception)
            Toast.makeText(this, "AR Session failed: ${exception.message}", Toast.LENGTH_LONG).show()
        }
        
        // Continuous Hit Testing for Reticle
        sceneView.onSessionUpdated = { session, frame ->
            val camera = frame.camera
            if (camera.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                val centerX = sceneView.width / 2f
                val centerY = sceneView.height / 2f
                
                val hitResult = frame.hitTest(centerX, centerY).firstOrNull { hit ->
                    val trackable = hit.trackable
                    when (trackable) {
                        is com.google.ar.core.Plane -> trackable.isPoseInPolygon(hit.hitPose)
                        // Also allow Point hits (Feature Points) for better coverage like ARCoreMeasure
                        is com.google.ar.core.Point -> trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                        else -> false
                    }
                }
                
                lastHitResult = hitResult
                
                runOnUiThread {
                    if (hitResult != null) {
                        // Valid surface detected - Green
                        centerReticle.setColorFilter(android.graphics.Color.GREEN)
                        addButton.isEnabled = true
                        addButton.alpha = 1.0f
                    } else {
                        // No surface - White/Gray
                        centerReticle.setColorFilter(android.graphics.Color.WHITE)
                        // Optional: Disable button or just keep it white
                        addButton.isEnabled = true 
                        addButton.alpha = 0.5f
                    }
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
