package com.example.measureapp.ar

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.widget.Button
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.measureapp.R
import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.models.MeasurementType
import com.example.measureapp.data.models.UnitType
import com.example.measureapp.data.repository.MeasurementRepository
import com.example.measureapp.util.HapticFeedbackHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.ClipData
import android.content.ClipboardManager
import com.google.ar.core.Config
import com.google.ar.core.Pose
import io.github.sceneview.ar.ARSceneView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class MeasureActivity : AppCompatActivity() {

    @Inject lateinit var measurementRepository: MeasurementRepository

    private val TAG = "MeasureActivity"
    private val CAMERA_PERMISSION_CODE = 1001

    private lateinit var sceneView: ARSceneView
    private lateinit var overlayView: OverlayView
    private lateinit var promptText: TextView
    private lateinit var addButton: Button
    private lateinit var doneButton: Button
    private lateinit var undoButton: Button
    private lateinit var clearButton: Button

    private lateinit var screenshotButton: Button
    private lateinit var measurementManager: MeasurementManager
    private lateinit var reticle: ReticleNode
    private lateinit var haptics: HapticFeedbackHelper
    private var lastHitResult: com.google.ar.core.HitResult? = null
    private var previousReticleState = ReticleNode.State.SEARCHING
    private var previousSmartHit: SmartHit = SmartHit.None
    private var hasFoundSurface = false
    private lateinit var depthEdgeDetector: DepthEdgeDetector
    private var frameCount = 0

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
        screenshotButton = findViewById(R.id.screenshot_button)

        haptics = HapticFeedbackHelper(this)
        depthEdgeDetector = DepthEdgeDetector()

        measurementManager = MeasurementManager(this, sceneView) { measurementText ->
            runOnUiThread {
                promptText.text = measurementText
                // Update live label in overlay
                overlayView.liveLabelText = measurementText
            }
        }
        
        // Connect overlay to manager
        overlayView.measurementManager = measurementManager
        
        promptText.text = "Point at a surface"

        // Check Camera Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }

        // Configure AR Scene - Optimized for S25+ surface detection
        sceneView.configureSession { session, config ->
            // CRITICAL: Enable both horizontal AND vertical for better detection
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

            // Better lighting estimation for indoor/outdoor
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

            // Disable Cloud Anchors - not needed for local measurement, reduces startup latency
            config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
            
            // Optimize tracking for measurement accuracy
            // This helps maintain anchor positions when moving camera
            
            // CRITICAL for S25+: AUTO focus is essential for feature tracking
            config.focusMode = Config.FocusMode.AUTO
            
            // Enable Depth API for better edge detection on S25+ ToF sensor
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            } else {
                config.depthMode = Config.DepthMode.DISABLED
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
        
        // Initial prompt
        promptText.text = "Point at a surface"

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
                
                // Hit test priority: DepthPoint > Horizontal Plane > Any Plane > Point
                var hitResult = hits.firstOrNull { it.trackable is com.google.ar.core.DepthPoint }

                if (hitResult == null) {
                    hitResult = hits.firstOrNull { hit ->
                        val trackable = hit.trackable
                        if (trackable is com.google.ar.core.Plane &&
                            trackable.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                            val normal = trackable.centerPose.getTransformedAxis(1, 1.0f)
                            kotlin.math.abs(normal[1]) > 0.7f
                        } else false
                    }
                }

                if (hitResult == null) {
                    hitResult = hits.firstOrNull { hit ->
                        val trackable = hit.trackable
                        trackable is com.google.ar.core.Plane &&
                        trackable.trackingState == com.google.ar.core.TrackingState.TRACKING
                    }
                }

                if (hitResult == null) {
                    hitResult = hits.firstOrNull { it.trackable is com.google.ar.core.Point }
                }

                // PRIORITY 4: Instant Placement (approximate, refines over time)
                if (hitResult == null) {
                    try {
                        val instantHits = frame.hitTestInstantPlacement(centerX, centerY, 1.5f)
                        hitResult = instantHits.firstOrNull { hit ->
                            hit.trackable is com.google.ar.core.InstantPlacementPoint
                        }
                    } catch (_: Exception) { /* Instant placement not available */ }
                }

                // Validate distance from camera
                val isInstantPlacement = hitResult?.trackable is com.google.ar.core.InstantPlacementPoint
                val validRange = if (isInstantPlacement) 0.3f..5.0f else 0.1f..10.0f
                val validHitResult = hitResult?.let { hit ->
                    val hitPose = hit.hitPose
                    val cameraPose = camera.pose
                    val dx = hitPose.tx() - cameraPose.tx()
                    val dy = hitPose.ty() - cameraPose.ty()
                    val dz = hitPose.tz() - cameraPose.tz()
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    if (distance in validRange) hit else null
                }

                lastHitResult = validHitResult

                // Run depth edge detection every 10 frames
                frameCount++
                if (frameCount % 10 == 0) {
                    depthEdgeDetector.detectEdges(frame, camera)
                }
                // Pass detected depth edges to measurement manager
                measurementManager.updateDetectedDepthEdges(depthEdgeDetector.getEdges())

                // 2. UPDATE THE MANAGER - This performs smart hit testing and updates rubber band
                measurementManager.onUpdate(validHitResult)

                // 3. GET SMART HIT RESULT for reticle visualization
                val smartHit = measurementManager.getCurrentSmartHit()
                val smartPose = smartHit.getPose()

                // 4. UPDATE PROFESSIONAL RETICLE
                val reticleState = when (smartHit) {
                    is SmartHit.None -> ReticleNode.State.SEARCHING
                    is SmartHit.SnappedVertex, is SmartHit.SnappedEdge -> ReticleNode.State.SNAPPED
                    is SmartHit.SnappedDepthEdge -> ReticleNode.State.DEPTH_EDGE
                    is SmartHit.Surface -> ReticleNode.State.TRACKING
                }

                // Haptic feedback on state transitions
                if (reticleState != previousReticleState) {
                    when {
                        reticleState == ReticleNode.State.TRACKING && !hasFoundSurface -> {
                            haptics.surfaceFound()
                            hasFoundSurface = true
                        }
                        reticleState == ReticleNode.State.SNAPPED -> haptics.snapDetected()
                        reticleState == ReticleNode.State.DEPTH_EDGE -> haptics.depthEdgeSnapped()
                    }
                    previousReticleState = reticleState
                }

                // Haptic edge crossing feedback (entering/exiting any edge zone)
                val wasOnEdge = previousSmartHit.isSnapped() || previousSmartHit is SmartHit.SnappedDepthEdge
                val nowOnEdge = smartHit.isSnapped() || smartHit is SmartHit.SnappedDepthEdge
                if (wasOnEdge != nowOnEdge) {
                    haptics.edgeCrossed()
                }
                previousSmartHit = smartHit

                if (smartPose != null) {
                    reticle.update(smartPose, reticleState)
                } else {
                    val cameraPose = camera.pose
                    val forwardPose = cameraPose.compose(Pose.makeTranslation(0f, 0f, -1.0f))
                    reticle.update(forwardPose, ReticleNode.State.SEARCHING)
                }
                reticle.smoothUpdate(0.016f) // ~60 FPS
                
                // 5. Update overlay for 3D label rendering
                overlayView.arCamera = camera
                overlayView.detectedDepthEdges = measurementManager.getDetectedDepthEdges()
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
                                is SmartHit.SnappedDepthEdge -> "Tap + to snap to depth edge"
                                else -> if (isInstantPlacement) "Approximate — keep scanning" else "Tap + to start"
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
                            promptText.text = "Point at a surface"
                        }
                    }
                }
            } else {
                reticle.update(null, ReticleNode.State.SEARCHING)
                runOnUiThread {
                    addButton.isEnabled = false
                    addButton.alpha = 0.3f
                    
                    if (!measurementManager.hasStartedMeasurement) {
                        promptText.text = "Move slowly to find surface"
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
                haptics.measurementComplete()

                // Auto-save measurement to database
                val summary = measurementManager.getFormattedSummary()
                val totalDistance = measurementManager.currentLiveDistance
                if (totalDistance > 0f) {
                    lifecycleScope.launch {
                        val entity = MeasurementEntity(
                            type = MeasurementType.POINT_TO_POINT,
                            value = totalDistance,
                            unit = UnitType.METRIC,
                            label = summary
                        )
                        measurementRepository.saveMeasurement(entity, emptyList())
                    }
                }

                measurementManager.finishCurrentMeasurement()
                doneButton.visibility = android.view.View.GONE
                doneButton.isEnabled = false
                doneButton.alpha = 0.5f
                addButton.isEnabled = true
                addButton.alpha = 1.0f
                overlayView.postInvalidate()
            }
        }

        clearButton.setOnClickListener {
            measurementManager.clear()
            sceneView.planeRenderer.isVisible = true // Re-show planes for new measurement
            promptText.text = "Point at a surface"
            doneButton.visibility = android.view.View.GONE
            doneButton.isEnabled = false
            doneButton.alpha = 0.5f
            addButton.isEnabled = true
            addButton.alpha = 1.0f
            overlayView.postInvalidate()
        }

        screenshotButton.setOnClickListener {
            takeScreenshot()
        }

        promptText.setOnLongClickListener {
            val summary = measurementManager.getFormattedSummary()
            if (summary.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Measurement", summary))
                haptics.pointPlaced()
                Toast.makeText(this, "Copied: $summary", Toast.LENGTH_SHORT).show()
            }
            true
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
            val smartHit = measurementManager.getCurrentSmartHit()
            
            when (smartHit) {
                is SmartHit.SnappedVertex -> {
                    measurementManager.addPoint(smartHit.anchor, isExistingAnchor = true)
                    haptics.snapDetected()
                }
                is SmartHit.SnappedEdge -> {
                    val anchor = hitResult.createAnchor()
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    haptics.snapDetected()
                }
                is SmartHit.SnappedDepthEdge -> {
                    val anchor = hitResult.createAnchor()
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    haptics.depthEdgeSnapped()
                }
                is SmartHit.Surface -> {
                    val anchor = hitResult.createAnchor()
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    haptics.pointPlaced()
                }
                SmartHit.None -> {
                    return
                }
            }
            
            overlayView.postInvalidate()

            // Hide plane dots once measuring to reduce visual noise
            sceneView.planeRenderer.isVisible = false

            // Show and enable Done button after first point
            if (doneButton.visibility == android.view.View.GONE) {
                doneButton.visibility = android.view.View.VISIBLE
                doneButton.isEnabled = true
                doneButton.alpha = 1.0f
            }
        }
    }

    private fun takeScreenshot() {
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                // Draw the overlay (measurement labels) on top of the AR capture
                val canvas = Canvas(bitmap)
                overlayView.draw(canvas)

                // Save to gallery
                saveBitmapToGallery(bitmap)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Measure_$timestamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeasureApp")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            runOnUiThread {
                haptics.pointPlaced()
                Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show()
            }
        } ?: runOnUiThread {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
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
