package com.example.measureapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.measureapp.ar.AnchorManager
import com.example.measureapp.ar.HitTestManager
import com.example.measureapp.ar.PlaneDetector
import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.models.*
import com.example.measureapp.data.repository.PreferencesRepository
import com.example.measureapp.domain.calculator.AreaCalculator
import com.example.measureapp.domain.calculator.DistanceCalculator
import com.example.measureapp.domain.usecase.SaveMeasurementUseCase
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Measure screen
 * Manages AR measurement state and business logic
 */
@HiltViewModel
class MeasureViewModel @Inject constructor(
    private val distanceCalculator: DistanceCalculator,
    private val areaCalculator: AreaCalculator,
    private val hitTestManager: HitTestManager,
    private val anchorManager: AnchorManager,
    private val planeDetector: PlaneDetector,
    private val saveMeasurementUseCase: SaveMeasurementUseCase,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MeasureUiState>(MeasureUiState.Initial)
    val uiState: StateFlow<MeasureUiState> = _uiState.asStateFlow()

    private val _measurements = MutableStateFlow<List<MeasurementLine>>(emptyList())
    val measurements: StateFlow<List<MeasurementLine>> = _measurements.asStateFlow()

    private val _selectedUnit = MutableStateFlow(UnitType.METRIC)
    val selectedUnit: StateFlow<UnitType> = _selectedUnit.asStateFlow()

    private val _detectedRectangle = MutableStateFlow<Rectangle?>(null)
    val detectedRectangle: StateFlow<Rectangle?> = _detectedRectangle.asStateFlow()
    
    private val _hasDetectedPlanes = MutableStateFlow(false)
    val hasDetectedPlanes: StateFlow<Boolean> = _hasDetectedPlanes.asStateFlow()
    
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    
    // iOS Measure-style workflow state
    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()
    
    private val _livePreviewDistance = MutableStateFlow<Float?>(null)
    val livePreviewDistance: StateFlow<Float?> = _livePreviewDistance.asStateFlow()

    // Store measurement points
    private val measurementPoints = mutableListOf<MeasurementPoint>()
    
    // Track current incomplete measurement
    private var currentStartPoint: MeasurementPoint? = null
    
    // Track current frame for live preview
    private var currentFrame: Frame? = null
    
    init {
        // Observe preferences for unit type
        viewModelScope.launch {
            preferencesRepository.unitType.collect { unit ->
                _selectedUnit.value = unit
            }
        }
    }

    /**
     * Initialize HitTestManager with AR session for ToF depth support
     */
    fun initializeHitTestManager(session: com.google.ar.core.Session) {
        hitTestManager.initialize(session)
    }

    /**
     * Initialize AR session
     */
    fun initializeAr() {
        viewModelScope.launch {
            _uiState.value = MeasureUiState.RequestingPermissions
        }
    }

    /**
     * Handle camera permission granted
     */
    fun onPermissionGranted() {
        viewModelScope.launch {
            _uiState.value = MeasureUiState.InitializingAr
        }
    }

    /**
     * Handle camera permission denied
     */
    fun onPermissionDenied() {
        viewModelScope.launch {
            _uiState.value = MeasureUiState.Error("Camera permission is required for AR measurements")
        }
    }

    /**
     * AR session ready
     */
    fun onArSessionReady() {
        viewModelScope.launch {
            _uiState.value = MeasureUiState.Ready
        }
    }

    /**
     * Handle AR session error
     */
    fun onArSessionError(error: String) {
        viewModelScope.launch {
            _uiState.value = MeasureUiState.Error(error)
        }
    }

    /**
     * Handle AR frame update
     */
    fun onFrameUpdate(frame: Frame) {
        viewModelScope.launch {
            // Store current frame for live preview
            currentFrame = frame
            
            // Check for detected planes
            val hasPlanes = planeDetector.hasEnoughPlanes(frame)
            _hasDetectedPlanes.value = hasPlanes
            
            // Update live preview if measuring
            if (_isMeasuring.value && currentStartPoint != null) {
                updateLivePreview(frame)
            }
        }
    }
    
    /**
     * Update live measurement preview (dynamic line while measuring)
     */
    private fun updateLivePreview(frame: Frame) {
        val startPoint = currentStartPoint ?: return
        
        // Get center screen hit test for live preview
        val width = frame.camera.imageIntrinsics.imageDimensions[0]
        val height = frame.camera.imageIntrinsics.imageDimensions[1]
        val centerX = width / 2f
        val centerY = height / 2f
        
        // Hit test at center of screen
        val previewPoint = hitTestManager.performHitTest(frame, centerX, centerY)
        
        if (previewPoint != null) {
            val distance = distanceCalculator.calculateDistance(
                startPoint.position,
                previewPoint.position
            )
            _livePreviewDistance.value = distance
        }
    }
    
    /**
     * Start a new measurement (+ button pressed)
     * FIXED: Use center of screen for hit test (where reticle is)
     */
    fun startMeasurement(frame: Frame, screenX: Float, screenY: Float) {
        viewModelScope.launch {
            android.util.Log.d("MeasureViewModel", "🎯 startMeasurement called, isMeasuring=${_isMeasuring.value}")
            
            if (!_isMeasuring.value) {
                // First point - start measuring
                android.util.Log.d("MeasureViewModel", "Performing hit test at center ($screenX, $screenY)")
                val point = hitTestManager.performHitTest(frame, screenX, screenY)
                
                if (point != null) {
                    android.util.Log.d("MeasureViewModel", "✅ Hit test SUCCESS: position=${point.position}")
                    point.anchor?.let { anchorManager.addAnchor(it) }
                    currentStartPoint = point
                    measurementPoints.add(point)
                    _isMeasuring.value = true
                    _livePreviewDistance.value = 0f
                    android.util.Log.d("MeasureViewModel", "Started measuring, point added to list (size=${measurementPoints.size})")
                } else {
                    android.util.Log.e("MeasureViewModel", "❌ Hit test FAILED: No valid surface found")
                }
            } else {
                // Second point - complete measurement
                android.util.Log.d("MeasureViewModel", "Completing measurement...")
                stopMeasurement(frame, screenX, screenY)
            }
        }
    }
    
    /**
     * Stop current measurement (Stop button or second tap)
     */
    fun stopMeasurement(frame: Frame, screenX: Float, screenY: Float) {
        viewModelScope.launch {
            val startPoint = currentStartPoint
            
            if (startPoint != null && _isMeasuring.value) {
                android.util.Log.d("MeasureViewModel", "Stopping measurement, getting end point...")
                
                // Get end point
                val endPoint = hitTestManager.performHitTest(frame, screenX, screenY)
                
                if (endPoint != null) {
                    android.util.Log.d("MeasureViewModel", "✅ End point found: ${endPoint.position}")
                    endPoint.anchor?.let { anchorManager.addAnchor(it) }
                    
                    // Calculate distance
                    val distance = distanceCalculator.calculateDistance(
                        startPoint.position,
                        endPoint.position
                    )
                    
                    android.util.Log.d("MeasureViewModel", "📏 Distance calculated: ${distance}m")

                    // Create measurement line
                    val line = MeasurementLine(
                        id = System.currentTimeMillis(),
                        startPoint = startPoint,
                        endPoint = endPoint,
                        distanceMeters = distance
                    )

                    val currentLines = _measurements.value.toMutableList()
                    currentLines.add(line)
                    _measurements.value = currentLines
                    
                    android.util.Log.d("MeasureViewModel", "✅ Measurement added! Total measurements: ${_measurements.value.size}")

                    measurementPoints.add(endPoint)
                } else {
                    android.util.Log.e("MeasureViewModel", "❌ End point hit test FAILED")
                }
                
                // Reset for next measurement
                currentStartPoint = null
                _isMeasuring.value = false
                _livePreviewDistance.value = null
            }
        }
    }
    
    /**
     * Cancel current measurement
     */
    fun cancelMeasurement() {
        viewModelScope.launch {
            if (_isMeasuring.value && currentStartPoint != null) {
                // Remove start point
                currentStartPoint?.anchor?.detach()
                measurementPoints.removeLastOrNull()
                anchorManager.removeLast()
                
                currentStartPoint = null
                _isMeasuring.value = false
                _livePreviewDistance.value = null
            }
        }
    }
    
    /**
     * Add measurement point from screen tap using hit testing
     */
    fun addMeasurementPoint(frame: Frame, screenX: Float, screenY: Float) {
        viewModelScope.launch {
            val point = hitTestManager.performHitTest(frame, screenX, screenY)
            
            if (point != null) {
                // Add anchor to manager
                point.anchor?.let { anchorManager.addAnchor(it) }
                
                if (currentStartPoint == null) {
                    // First point of a new measurement
                    currentStartPoint = point
                    measurementPoints.add(point)
                } else {
                    // Second point - create a measurement line
                    val startPoint = currentStartPoint!!
                    val distance = distanceCalculator.calculateDistance(
                        startPoint.position,
                        point.position
                    )

                    val line = MeasurementLine(
                        id = System.currentTimeMillis(),
                        startPoint = startPoint,
                        endPoint = point,
                        distanceMeters = distance
                    )

                    val currentLines = _measurements.value.toMutableList()
                    currentLines.add(line)
                    _measurements.value = currentLines

                    measurementPoints.add(point)
                    currentStartPoint = null
                }
            }
        }
    }

    /**
     * Add measurement point from AR hit result (legacy method)
     */
    fun addMeasurementPoint(hitResult: HitResult) {
        val anchor = hitResult.createAnchor()
        val pose = anchor.pose
        val point = MeasurementPoint(
            position = Vector3(pose.tx(), pose.ty(), pose.tz()),
            anchor = anchor
        )

        if (currentStartPoint == null) {
            // First point of a new measurement
            currentStartPoint = point
            measurementPoints.add(point)
        } else {
            // Second point - create a measurement line
            val startPoint = currentStartPoint!!
            val distance = distanceCalculator.calculateDistance(
                startPoint.position,
                point.position
            )

            val line = MeasurementLine(
                id = System.currentTimeMillis(),
                startPoint = startPoint,
                endPoint = point,
                distanceMeters = distance
            )

            val currentLines = _measurements.value.toMutableList()
            currentLines.add(line)
            _measurements.value = currentLines

            measurementPoints.add(point)
            currentStartPoint = null
        }
    }

    /**
     * Update detected rectangle
     */
    fun updateDetectedRectangle(rectangle: Rectangle?) {
        viewModelScope.launch {
            _detectedRectangle.value = rectangle
        }
    }

    /**
     * Clear all measurements
     */
    fun clearMeasurements() {
        viewModelScope.launch {
            measurementPoints.clear()
            _measurements.value = emptyList()
            _detectedRectangle.value = null
            currentStartPoint = null
            anchorManager.clearAll()
        }
    }

    /**
     * Undo last measurement point
     */
    fun undoLast() {
        viewModelScope.launch {
            if (currentStartPoint != null) {
                // Remove incomplete measurement
                measurementPoints.removeLastOrNull()
                currentStartPoint?.anchor?.detach()
                currentStartPoint = null
                anchorManager.removeLast()
            } else if (_measurements.value.isNotEmpty()) {
                // Remove last completed measurement
                val lines = _measurements.value.toMutableList()
                lines.removeLastOrNull()
                _measurements.value = lines
                
                // Remove the point
                measurementPoints.removeLastOrNull()
                anchorManager.removeLast()
            }
        }
    }
    
    /**
     * Save current measurements to database
     */
    fun saveMeasurement(label: String = "", imageUri: String? = null) {
        viewModelScope.launch {
            try {
                _isSaving.value = true
                
                if (_measurements.value.isEmpty()) {
                    return@launch
                }
                
                // Calculate total distance
                val totalDistance = _measurements.value.sumOf { it.distanceMeters.toDouble() }.toFloat()
                
                // Create measurement entity
                val measurementEntity = MeasurementEntity(
                    type = MeasurementType.POINT_TO_POINT,
                    value = totalDistance,
                    unit = _selectedUnit.value,
                    label = label.ifEmpty { "Measurement ${System.currentTimeMillis()}" },
                    imageUri = imageUri,
                    projectId = null,
                    timestamp = System.currentTimeMillis(),
                    isFavorite = false
                )
                
                // Save to database
                saveMeasurementUseCase(measurementEntity, measurementPoints)
                
                // Clear after saving
                clearMeasurements()
                
            } catch (e: Exception) {
                _uiState.value = MeasureUiState.Error("Failed to save: ${e.message}")
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Toggle measurement unit
     */
    fun toggleUnit() {
        viewModelScope.launch {
            val newUnit = when (_selectedUnit.value) {
                UnitType.METRIC -> UnitType.IMPERIAL
                UnitType.IMPERIAL -> UnitType.METRIC
            }
            preferencesRepository.setUnitType(newUnit)
        }
    }

    /**
     * Format distance based on selected unit
     */
    fun formatDistance(distanceMeters: Float): String {
        return _selectedUnit.value.formatDistance(distanceMeters)
    }

    /**
     * Get all measurement points
     */
    fun getAllPoints(): List<MeasurementPoint> = measurementPoints.toList()

    /**
     * Get current incomplete point if any
     */
    fun getCurrentStartPoint(): MeasurementPoint? = currentStartPoint
}

/**
 * UI states for the Measure screen
 */
sealed class MeasureUiState {
    object Initial : MeasureUiState()
    object RequestingPermissions : MeasureUiState()
    object InitializingAr : MeasureUiState()
    object Ready : MeasureUiState()
    data class Error(val message: String) : MeasureUiState()
}
