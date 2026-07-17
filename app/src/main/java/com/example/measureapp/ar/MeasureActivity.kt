package com.example.measureapp.ar

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import com.example.measureapp.ar.ml.MeasurementAutoLabeler
import com.example.measureapp.ar.ml.PersonHeightEstimator
import com.example.measureapp.ar.ml.computeCameraImageRotation
import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.models.MeasurementPoint
import com.example.measureapp.data.models.MeasurementType
import com.example.measureapp.data.models.Vector3
import com.example.measureapp.data.repository.MeasurementRepository
import com.example.measureapp.data.repository.PreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
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

@AndroidEntryPoint
class MeasureActivity : AppCompatActivity() {

    @Inject lateinit var preferencesRepository: PreferencesRepository
    @Inject lateinit var measurementRepository: MeasurementRepository

    private var autoSaveEnabled = true
    private var soundEnabled = true
    private val actionSound by lazy {
        android.media.MediaActionSound().apply {
            load(android.media.MediaActionSound.FOCUS_COMPLETE)
            load(android.media.MediaActionSound.SHUTTER_CLICK)
        }
    }

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
    private lateinit var modeLineText: TextView
    private lateinit var modeHeightText: TextView
    private lateinit var modeAreaText: TextView

    private lateinit var measurementManager: MeasurementManager
    private lateinit var rectangleDetector: RectangleDetector
    private val depthEdgeSnapper = DepthEdgeSnapper()
    private var cachedDepthEdge: io.github.sceneview.math.Position? = null
    private var depthHealthNotified = false
    private var lastHitResult: com.google.ar.core.HitResult? = null
    private var lockedPlane: com.google.ar.core.Plane? = null
    private lateinit var haptic: com.example.measureapp.utils.HapticFeedback
    private lateinit var measurementCapture: MeasurementCapture
    private var lastSmartHitState: SmartHit = SmartHit.None
    private var detectedRectangle: DetectedRectangle? = null
    private var candidateRectangle: DetectedRectangle? = null
    private var rectangleStableCount = 0
    private var hasFoundSurface = false
    private var lastInteractionTime: Long = System.currentTimeMillis()
    private var isPillDimmed = false
    private var lastAddPointTime: Long = 0L
    private var frameCount = 0

    // On-device ML (ML Kit / TensorFlow Lite)
    private lateinit var personHeightEstimator: PersonHeightEstimator
    private lateinit var measurementAutoLabeler: MeasurementAutoLabeler
    private var personDetectionEnabled = true
    private var rectangleDetectionEnabled = false
    private var lastStableHeightMeters: Float? = null
    private var currentFrame: com.google.ar.core.Frame? = null
    private var cameraImageRotation = -1

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
        modeLineText = findViewById(R.id.mode_line_text)
        modeHeightText = findViewById(R.id.mode_height_text)
        modeAreaText = findViewById(R.id.mode_area_text)

        modeLineText.setOnClickListener { setMeasureMode(MeasurementManager.MeasureMode.LINE) }
        modeHeightText.setOnClickListener { setMeasureMode(MeasurementManager.MeasureMode.HEIGHT) }
        modeAreaText.setOnClickListener { setMeasureMode(MeasurementManager.MeasureMode.AREA) }

        // Initialize haptic feedback
        haptic = com.example.measureapp.utils.HapticFeedback(this)

        // Initialize rectangle detector
        rectangleDetector = RectangleDetector()

        // Initialize on-device ML (pose detection for person height, labeling for history)
        personHeightEstimator = PersonHeightEstimator(this)
        measurementAutoLabeler = MeasurementAutoLabeler()
        
        measurementManager = MeasurementManager(this, sceneView) { measurementText ->
            runOnUiThread {
                promptText.text = measurementText
                // Update live label in overlay
                overlayView.liveLabelText = measurementText
                // Track interaction for auto-dim
                lastInteractionTime = System.currentTimeMillis()
                if (isPillDimmed) {
                    isPillDimmed = false
                    findViewById<androidx.cardview.widget.CardView>(R.id.measurement_card)?.animate()
                        ?.alpha(1.0f)?.setDuration(200)?.start()
                }
            }
        }
        
        // Set unit preference on manager
        lifecycleScope.launch {
            preferencesRepository.unitType.collect { unit ->
                measurementManager.unitType = unit
            }
        }

        lifecycleScope.launch {
            preferencesRepository.autoSaveEnabled.collect { enabled ->
                autoSaveEnabled = enabled
            }
        }

        lifecycleScope.launch {
            preferencesRepository.hapticFeedbackEnabled.collect { enabled ->
                haptic.isEnabled = enabled
            }
        }

        lifecycleScope.launch {
            preferencesRepository.personDetectionEnabled.collect { enabled ->
                personDetectionEnabled = enabled
            }
        }

        lifecycleScope.launch {
            preferencesRepository.rectangleDetectionEnabled.collect { enabled ->
                rectangleDetectionEnabled = enabled
            }
        }

        lifecycleScope.launch {
            preferencesRepository.soundEnabled.collect { enabled ->
                soundEnabled = enabled
            }
        }

        // First-run quick tips (good plane scanning is most of the accuracy battle)
        lifecycleScope.launch {
            if (preferencesRepository.showTutorial.first()) {
                androidx.appcompat.app.AlertDialog.Builder(this@MeasureActivity)
                    .setTitle("Quick tips")
                    .setMessage(
                        "• Point at a textured surface (wood, tiles, fabric) and move " +
                            "your phone slowly side to side until it locks on\n\n" +
                            "• Tap + to place points — the reticle snaps to corners " +
                            "and edges, turning green\n\n" +
                            "• Use Height mode for vertical objects and Area mode for " +
                            "surfaces\n\n" +
                            "• Measure flat objects from directly above for best accuracy"
                    )
                    .setPositiveButton("Got it", null)
                    .setOnDismissListener {
                        lifecycleScope.launch { preferencesRepository.setTutorialShown() }
                    }
                    .show()
            }
        }

        // Tapping the height pill saves the detected person height to History
        measurementSubtitle.setOnClickListener {
            val height = lastStableHeightMeters ?: return@setOnClickListener
            lifecycleScope.launch {
                try {
                    measurementRepository.saveMeasurement(
                        MeasurementEntity(
                            type = MeasurementType.PERSON_HEIGHT,
                            value = height,
                            unit = measurementManager.unitType,
                            label = "Person height"
                        ),
                        emptyList()
                    )
                    haptic.success()
                    Toast.makeText(this@MeasureActivity, "Height saved to History", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save person height", e)
                }
            }
        }

        // Connect overlay to manager
        overlayView.measurementManager = measurementManager
        
        // Initialize measurement capture
        measurementCapture = MeasurementCapture(this, sceneView, overlayView)
        
        promptText.text = "Scanning..."
        measurementSubtitle.visibility = android.view.View.GONE

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
        
        // Initial prompt
        promptText.text = "Scanning..."

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
                
                frameCount++

                // 1. Multi-sample, foreground-biased hit test (see performBestHitTest)
                val validHitResult = performBestHitTest(frame, camera, centerX, centerY)

                lastHitResult = validHitResult

                // 1b. Physical edge detection from the depth image (throttled)
                if (frameCount % 3 == 0) {
                    cachedDepthEdge = depthEdgeSnapper.findEdgeNearPoint(frame, centerX, centerY)
                }
                measurementManager.depthEdgeSnapPosition = cachedDepthEdge

                // Depth health check: after ~20s of session, warn once if the device
                // never produced a depth image (ARCore depth pipeline failure)
                if (!depthHealthNotified && frameCount == 600 && !depthEdgeSnapper.isDepthWorking) {
                    depthHealthNotified = true
                    Log.w(TAG, "Depth API produced no depth images this session — " +
                        "edge snapping and depth-assisted placement are unavailable")
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Depth sensing unavailable on this device — try updating " +
                                "'Google Play Services for AR' in the Play Store",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
                // 2. UPDATE THE MANAGER - This performs smart hit testing and updates rubber band.
                // The screen-center camera ray drives HEIGHT-mode vertical tracking.
                val displayPose = camera.displayOrientedPose
                val rayDir = displayPose.rotateVector(floatArrayOf(0f, 0f, -1f))
                measurementManager.onUpdate(
                    validHitResult,
                    rayOrigin = Position(displayPose.tx(), displayPose.ty(), displayPose.tz()),
                    rayDirection = Position(rayDir[0], rayDir[1], rayDir[2])
                )
                
                // 3. GET SMART HIT RESULT for reticle visualization
                val smartHit = measurementManager.getCurrentSmartHit()

                // 4. UPDATE RETICLE (2D overlay — thin ring, never blocks the view)
                overlayView.reticleState = when (smartHit) {
                    is SmartHit.None -> OverlayView.ReticleState.SEARCHING
                    is SmartHit.SnappedVertex, is SmartHit.SnappedEdge -> OverlayView.ReticleState.SNAPPED
                    is SmartHit.Surface -> OverlayView.ReticleState.TRACKING
                }
                overlayView.reticleWorld = smartHit.getPosition()

                // 5. RECTANGLE AUTO-DETECTION (opt-in via Settings; scan periodically)
                if (rectangleDetectionEnabled) {
                    if (frameCount % 15 == 0) {
                        detectRectanglesInView(frame)
                        // Detected rectangle corners/edges become snap targets
                        measurementManager.rectangleSnapTargets = detectedRectangle
                    }
                } else if (detectedRectangle != null) {
                    detectedRectangle = null
                    candidateRectangle = null
                    rectangleStableCount = 0
                    measurementManager.rectangleSnapTargets = null
                }

                // 6. Update overlay for 3D label rendering (includes rectangle overlay)
                overlayView.arCamera = camera
                overlayView.detectedRectangle = detectedRectangle
                overlayView.postInvalidate()

                // 6b. ML person height detection (pose model + depth hit tests)
                currentFrame = frame
                if (cameraImageRotation < 0) {
                    cameraImageRotation = computeCameraImageRotation(this, session)
                }
                if (personDetectionEnabled) {
                    val heightResult = personHeightEstimator.onFrame(session, frame)
                    runOnUiThread { updatePersonHeightUi(heightResult) }
                } else if (overlayView.personHeightIndicator != null) {
                    runOnUiThread { updatePersonHeightUi(null) }
                }
                
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
                    
                    if ((validHitResult != null || measurementManager.isHeightMeasureActive()) && trackingQuality == "GOOD") {
                        addButton.isEnabled = true
                        addButton.alpha = 1.0f
                        
                        // Hide help hint once tracking
                        helpHint.visibility = android.view.View.GONE
                        
                        // Update prompt only if not currently measuring
                        if (!measurementManager.hasStartedMeasurement) {
                            promptText.text = when (smartHit) {
                                is SmartHit.SnappedVertex -> "Snap to vertex"
                                is SmartHit.SnappedEdge -> "Snap to edge"
                                else -> "Tap + to start"
                            }
                        }

                        // Auto-dim pill after 3 seconds of inactivity
                        val timeSinceInteraction = System.currentTimeMillis() - lastInteractionTime
                        if (timeSinceInteraction > 3000 && !isPillDimmed && measurementManager.hasStartedMeasurement) {
                            isPillDimmed = true
                            findViewById<androidx.cardview.widget.CardView>(R.id.measurement_card)?.animate()
                                ?.alpha(0.4f)?.setDuration(500)?.start()
                        }
                    } else if (trackingQuality == "LIMITED" || trackingQuality == "POOR") {
                        addButton.isEnabled = false
                        addButton.alpha = 0.3f
                        promptText.text = "Move slowly"
                    } else {
                        addButton.isEnabled = true
                        addButton.alpha = 0.5f

                        if (!measurementManager.hasStartedMeasurement) {
                            promptText.text = "Scanning..."
                        }
                    }
                }
            } else {
                overlayView.reticleState = OverlayView.ReticleState.SEARCHING
                overlayView.reticleWorld = null
                overlayView.postInvalidate()
                runOnUiThread {
                    addButton.isEnabled = false
                    addButton.alpha = 0.3f

                    if (!measurementManager.hasStartedMeasurement) {
                        promptText.text = "Scanning..."
                    }
                }
            }
        }

        // Setup buttons
        addButton.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastAddPointTime < 200L) return@setOnClickListener // Prevent double-tap
            lastAddPointTime = now
            addPoint()
        }
        
        undoButton.setOnClickListener {
            measurementManager.undo()
            overlayView.postInvalidate()
            if (!measurementManager.hasStartedMeasurement) {
                lockedPlane = null
                doneButtonCard.visibility = android.view.View.GONE
                undoCard.visibility = android.view.View.GONE
                clearCard.visibility = android.view.View.GONE
            }
        }
        
        doneButton.setOnClickListener {
            if (measurementManager.hasStartedMeasurement) {
                completeMeasurement()
            }
        }

        clearButton.setOnClickListener {
            measurementManager.clear()
            lockedPlane = null
            promptText.text = "Tap + to start"
            doneButtonCard.visibility = android.view.View.GONE
            undoCard.visibility = android.view.View.GONE
            clearCard.visibility = android.view.View.GONE
            captureButtonCard.visibility = android.view.View.GONE
            helpHint.visibility = android.view.View.VISIBLE
            // Re-show plane renderer after clearing
            sceneView.planeRenderer.isVisible = true
            overlayView.postInvalidate()
        }
        
        captureButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    if (soundEnabled) actionSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
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

        // Long-press on prompt text copies measurement to clipboard
        promptText.setOnLongClickListener {
            val summary = measurementManager.getFormattedSummary()
            if (summary.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Measurement", summary))
                haptic.lightImpact()
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

    /**
     * Multi-sample, foreground-biased hit testing.
     *
     * The depth map bleeds into the background at object boundaries, so a single
     * center ray aimed at an edge (e.g. the top of a laptop screen) can land meters
     * behind the target. We sample a small cross pattern and keep the hit closest to
     * the camera; offset samples must beat the current best by a clear margin so the
     * center sample wins unless depth genuinely slipped to the background.
     *
     * While a measurement is in progress, hits on the plane it started on win outright
     * so planar measurements stay planar (iOS behavior).
     */
    private fun performBestHitTest(
        frame: com.google.ar.core.Frame,
        camera: com.google.ar.core.Camera,
        centerX: Float,
        centerY: Float
    ): com.google.ar.core.HitResult? {
        val sampleRadius = 12f * resources.displayMetrics.density
        val offsets = arrayOf(
            0f to 0f,
            -sampleRadius to 0f, sampleRadius to 0f,
            0f to -sampleRadius, 0f to sampleRadius
        )
        val cameraPose = camera.pose

        var best: com.google.ar.core.HitResult? = null
        var bestDistance = Float.MAX_VALUE

        for ((index, offset) in offsets.withIndex()) {
            val hits = try {
                frame.hitTest(centerX + offset.first, centerY + offset.second)
            } catch (e: Exception) {
                continue
            }

            // Plane lock: prefer the surface the current measurement started on, but
            // it no longer wins outright — see the distance rule below. (An outright
            // win projected aims THROUGH raised objects onto the table behind them.)
            val lockedHit = lockedPlane
                ?.takeIf { it.trackingState == com.google.ar.core.TrackingState.TRACKING }
                ?.let { plane -> hits.firstOrNull { it.trackable == plane } }

            val planeHit = lockedHit ?: hits.firstOrNull { hit ->
                val t = hit.trackable
                t is com.google.ar.core.Plane &&
                    t.trackingState == com.google.ar.core.TrackingState.TRACKING &&
                    t.isPoseInPolygon(hit.hitPose)
            }
            val depthHit = hits.firstOrNull { it.trackable is com.google.ar.core.DepthPoint }
                ?: hits.firstOrNull { hit ->
                    hit.trackable is com.google.ar.core.Point &&
                        hit.trackable.trackingState == com.google.ar.core.TrackingState.TRACKING
                }

            // Choose by DISTANCE, not fixed type priority. A plane behind a foreground
            // object is "inside polygon" yet wrong — a trackpad 2cm above the table is
            // 5-6cm closer ALONG THE RAY at shallow angles, exactly when the plane
            // projection error is biggest. So a depth/feature hit clearly in front
            // (>5cm) wins; the plane wins ties for stability (its error is small at
            // steep angles anyway).
            val candidate = when {
                planeHit == null -> depthHit
                depthHit == null -> planeHit
                else -> {
                    val planeDistance = hitDistance(planeHit, cameraPose)
                    val depthDistance = hitDistance(depthHit, cameraPose)
                    if (depthDistance < planeDistance - 0.05f) depthHit else planeHit
                }
            }

            if (candidate != null) {
                val distance = hitDistance(candidate, cameraPose)
                val margin = if (index == 0) 0f else 0.05f
                if (distance in 0.1f..10.0f && distance < bestDistance - margin) {
                    bestDistance = distance
                    best = candidate
                }
            }
        }
        return best
    }

    private fun hitDistance(hit: com.google.ar.core.HitResult, cameraPose: Pose): Float {
        val dx = hit.hitPose.tx() - cameraPose.tx()
        val dy = hit.hitPose.ty() - cameraPose.ty()
        val dz = hit.hitPose.tz() - cameraPose.tz()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun saveMeasurementToHistory(completed: MeasurementManager.CompletedMeasurement) {
        // Classify the scene first so the history entry gets a meaningful name
        val frame = currentFrame
        if (frame != null && cameraImageRotation >= 0) {
            measurementAutoLabeler.labelFrame(frame, cameraImageRotation) { label ->
                persistMeasurement(completed, label)
            }
        } else {
            persistMeasurement(completed, null)
        }
    }

    private fun persistMeasurement(completed: MeasurementManager.CompletedMeasurement, label: String?) {
        lifecycleScope.launch {
            try {
                val area = completed.areaSquareMeters
                val type = when {
                    area != null -> MeasurementType.AREA
                    completed.points.size > 2 -> MeasurementType.PATH
                    else -> MeasurementType.POINT_TO_POINT
                }
                measurementRepository.saveMeasurement(
                    MeasurementEntity(
                        type = type,
                        value = area ?: completed.totalMeters,
                        unit = measurementManager.unitType,
                        label = label ?: "",
                        rectangleArea = area
                    ),
                    completed.points.map { MeasurementPoint(Vector3(it.x, it.y, it.z)) }
                )
                val message = if (label != null) "Saved to History · $label" else "Saved to History"
                Toast.makeText(this@MeasureActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save measurement to history", e)
            }
        }
    }

    private fun updatePersonHeightUi(result: PersonHeightEstimator.Result?) {
        if (result == null) {
            if (overlayView.personHeightIndicator != null) {
                overlayView.personHeightIndicator = null
                measurementSubtitle.visibility = android.view.View.GONE
                lastStableHeightMeters = null
            }
            return
        }
        val formatted = measurementManager.unitType.formatDistance(result.heightMeters)
        overlayView.personHeightIndicator = OverlayView.PersonHeightIndicator(
            head = result.headScreen,
            feet = result.feetScreen,
            text = formatted,
            isStable = result.isStable
        )
        lastStableHeightMeters = if (result.isStable) result.heightMeters else null
        measurementSubtitle.visibility = android.view.View.VISIBLE
        measurementSubtitle.text = if (result.isStable) "🧍 $formatted · tap to save" else "🧍 $formatted"
    }

    private fun playPointSound() {
        if (soundEnabled) {
            actionSound.play(android.media.MediaActionSound.FOCUS_COMPLETE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        personHeightEstimator.close()
        measurementAutoLabeler.close()
        actionSound.release()
    }

    /** Finish the in-progress measurement, persist it, reset for the next one */
    private fun completeMeasurement() {
        val completed = measurementManager.finishCurrentMeasurement()
        lockedPlane = null
        haptic.success()
        doneButtonCard.visibility = android.view.View.GONE
        // Keep undo/clear visible for completed measurements
        overlayView.postInvalidate()

        if (completed != null && autoSaveEnabled) {
            saveMeasurementToHistory(completed)
        }
    }

    private fun setMeasureMode(mode: MeasurementManager.MeasureMode) {
        if (measurementManager.measureMode == mode) return
        if (measurementManager.hasStartedMeasurement) {
            completeMeasurement()
        }
        measurementManager.measureMode = mode

        val segments = listOf(
            MeasurementManager.MeasureMode.LINE to modeLineText,
            MeasurementManager.MeasureMode.HEIGHT to modeHeightText,
            MeasurementManager.MeasureMode.AREA to modeAreaText
        )
        for ((segmentMode, text) in segments) {
            if (segmentMode == mode) {
                text.setBackgroundResource(R.drawable.segment_selected)
                text.setTextColor(android.graphics.Color.BLACK)
            } else {
                text.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                text.setTextColor(android.graphics.Color.WHITE)
            }
        }

        promptText.text = when (mode) {
            MeasurementManager.MeasureMode.LINE -> "Tap + to start"
            MeasurementManager.MeasureMode.HEIGHT -> "Tap + on the base of the object"
            MeasurementManager.MeasureMode.AREA -> "Tap + on each corner, ✓ to close"
        }
        haptic.lightImpact()
    }

    private fun addPoint() {
        // HEIGHT mode with a base placed: the point is on the vertical axis (no surface needed)
        if (measurementManager.isHeightMeasureActive()) {
            val heightPose = measurementManager.getCurrentSmartHit().getPose()
            val anchor = try {
                heightPose?.let { sceneView.session?.createAnchor(it) }
            } catch (e: Exception) {
                null
            }
            if (anchor == null) {
                haptic.error()
                return
            }
            measurementManager.addPoint(anchor)
            haptic.mediumImpact()
            playPointSound()
            overlayView.postInvalidate()
            // Heights are two-point measurements — complete and save immediately
            completeMeasurement()
            return
        }

        val hitResult = lastHitResult

        if (hitResult != null) {
            val isFirstPoint = !measurementManager.hasStartedMeasurement
            val smartHit = measurementManager.getCurrentSmartHit()

            when (smartHit) {
                is SmartHit.SnappedVertex -> {
                    measurementManager.addPoint(smartHit.anchor, isExistingAnchor = true)
                    haptic.mediumImpact()
                }
                is SmartHit.SnappedEdge -> {
                    // Anchor at the SNAPPED position, not the raw hit — otherwise
                    // the magnet effect is visual-only and the point lands off-edge
                    val snappedPose = smartHit.getPose()
                    val anchor = if (snappedPose == null) {
                        hitResult.createAnchor()
                    } else {
                        try {
                            hitResult.trackable.createAnchor(snappedPose)
                        } catch (e: Exception) {
                            try {
                                sceneView.session?.createAnchor(snappedPose)
                            } catch (e2: Exception) {
                                null
                            } ?: hitResult.createAnchor()
                        }
                    }
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    haptic.mediumImpact()
                }
                is SmartHit.Surface -> {
                    // Place at the multi-frame median position when the reticle has
                    // been steady — cancels single-frame jitter at tap time
                    val stablePose = measurementManager.stableSurfacePose()
                    val anchor = if (stablePose != null) {
                        try {
                            hitResult.trackable.createAnchor(stablePose)
                        } catch (e: Exception) {
                            hitResult.createAnchor()
                        }
                    } else {
                        hitResult.createAnchor()
                    }
                    measurementManager.addPoint(anchor, isExistingAnchor = false)
                    haptic.mediumImpact()
                }
                SmartHit.None -> {
                    haptic.error()
                    return
                }
            }

            playPointSound()

            // Lock subsequent hit tests to the surface the measurement started on
            if (isFirstPoint) {
                lockedPlane = hitResult.trackable as? com.google.ar.core.Plane
            }

            // Reset interaction time for auto-dim
            lastInteractionTime = System.currentTimeMillis()

            overlayView.postInvalidate()

            // Hide plane renderer after first point for cleaner AR view
            if (sceneView.planeRenderer.isEnabled) {
                sceneView.planeRenderer.isVisible = false
            }

            // Show Done, Undo, Clear, and Capture buttons after first point
            if (doneButtonCard.visibility == android.view.View.GONE) {
                doneButtonCard.visibility = android.view.View.VISIBLE
                undoCard.visibility = android.view.View.VISIBLE
                clearCard.visibility = android.view.View.VISIBLE
                captureButtonCard.visibility = android.view.View.VISIBLE
            }
        } else {
            haptic.error()
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
                candidateRectangle = null
                rectangleStableCount = 0
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
                    if (!cornersInView) {
                        continue // Skip rectangles with corners outside view
                    }
                    
                    // Score based on: confidence, size, and how close to screen center (what user is looking at)
                    val size = rectangle.sides.average().toFloat()
                    
                    // Calculate how centered the rectangle is (0-1, higher is better)
                    val centeredness = calculateRectangleCenteredness(frame, rectangle)
                    
                    if (centeredness < 0.5f) continue

                    val score = rectangle.confidence * size * centeredness * centeredness
                    
                    if (score > bestScore) {
                        bestScore = score
                        bestRectangle = rectangle
                    }
                }
            }
            
            // Stability gate: only show a rectangle seen in the same place across
            // consecutive scans — otherwise unstable detections flash yellow outlines
            if (bestRectangle != null && candidateRectangle != null &&
                rectanglesMatch(candidateRectangle!!, bestRectangle)
            ) {
                rectangleStableCount++
            } else {
                rectangleStableCount = if (bestRectangle != null) 1 else 0
            }
            candidateRectangle = bestRectangle
            detectedRectangle = if (bestRectangle != null && rectangleStableCount >= 2) {
                bestRectangle
            } else {
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Rectangle detection error: ${e.message}")
            candidateRectangle = null
            rectangleStableCount = 0
            detectedRectangle = null
        }
    }

    /** Same rectangle if every corner moved less than 5cm between scans */
    private fun rectanglesMatch(a: DetectedRectangle, b: DetectedRectangle): Boolean {
        if (a.corners.size != b.corners.size) return false
        for (i in a.corners.indices) {
            val dx = a.corners[i].x - b.corners[i].x
            val dy = a.corners[i].y - b.corners[i].y
            val dz = a.corners[i].z - b.corners[i].z
            if (kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) > 0.05f) return false
        }
        return true
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
            
            if (clipPos[3] <= 0) continue

            val ndcX = clipPos[0] / clipPos[3]
            val ndcY = clipPos[1] / clipPos[3]
            
            // Allow generous margin (±2.5) to handle rectangles viewed at steep angles
            // This allows rectangles partially outside screen to still be detected
            if (ndcX >= -2.5f && ndcX <= 2.5f && ndcY >= -2.5f && ndcY <= 2.5f) {
                cornersInView++
            }
        }
        
        // Require at least 2 out of 4 corners to be in view
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
