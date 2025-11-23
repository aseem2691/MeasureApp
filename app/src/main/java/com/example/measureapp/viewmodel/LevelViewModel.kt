package com.example.measureapp.viewmodel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * ViewModel for the Level screen
 * Manages device orientation sensors and level calculations
 */
@HiltViewModel
class LevelViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel(), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val _pitch = MutableStateFlow(0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _roll = MutableStateFlow(0f)
    val roll: StateFlow<Float> = _roll.asStateFlow()

    private val _isLevel = MutableStateFlow(false)
    val isLevel: StateFlow<Boolean> = _isLevel.asStateFlow()

    private val _levelMode = MutableStateFlow(LevelMode.SURFACE)
    val levelMode: StateFlow<LevelMode> = _levelMode.asStateFlow()

    private var wasLevel = false
    private val levelThreshold = 0.5f // degrees

    /**
     * Start listening to sensor events
     */
    fun startSensor() {
        viewModelScope.launch {
            rotationSensor?.let { sensor ->
                sensorManager.registerListener(
                    this@LevelViewModel,
                    sensor,
                    SensorManager.SENSOR_DELAY_UI
                )
            }
        }
    }

    /**
     * Stop listening to sensor events
     */
    fun stopSensor() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Toggle between surface and edge level modes
     */
    fun toggleLevelMode() {
        viewModelScope.launch {
            _levelMode.value = when (_levelMode.value) {
                LevelMode.SURFACE -> LevelMode.EDGE
                LevelMode.EDGE -> LevelMode.SURFACE
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            // Get rotation matrix from rotation vector
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            // Get orientation angles
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            // Convert radians to degrees
            val pitchDegrees = Math.toDegrees(orientation[1].toDouble()).toFloat()
            val rollDegrees = Math.toDegrees(orientation[2].toDouble()).toFloat()

            viewModelScope.launch {
                _pitch.value = pitchDegrees
                _roll.value = rollDegrees

                // Check if device is level
                val currentlyLevel = when (_levelMode.value) {
                    LevelMode.SURFACE -> {
                        abs(pitchDegrees) < levelThreshold && abs(rollDegrees) < levelThreshold
                    }
                    LevelMode.EDGE -> {
                        abs(abs(rollDegrees) - 90f) < levelThreshold
                    }
                }

                _isLevel.value = currentlyLevel

                // Trigger haptic feedback when transitioning to level
                if (currentlyLevel && !wasLevel) {
                    triggerHapticFeedback()
                }
                wasLevel = currentlyLevel
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    /**
     * Trigger haptic feedback
     */
    private fun triggerHapticFeedback() {
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(50)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSensor()
    }
}

/**
 * Level mode types
 */
enum class LevelMode {
    SURFACE,  // Check if surface is level
    EDGE      // Check if edge is level (vertical)
}

