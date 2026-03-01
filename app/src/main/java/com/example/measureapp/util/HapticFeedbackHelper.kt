package com.example.measureapp.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Provides haptic feedback for AR measurement interactions.
 */
class HapticFeedbackHelper(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun pointPlaced() {
        vibrate(VibrationEffect.EFFECT_CLICK)
    }

    fun snapDetected() {
        vibrate(VibrationEffect.EFFECT_TICK)
    }

    fun measurementComplete() {
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    fun surfaceFound() {
        vibrate(VibrationEffect.EFFECT_TICK)
    }

    /**
     * Light tick when reticle crosses any edge boundary (enters or exits edge zone)
     */
    fun edgeCrossed() {
        vibrate(VibrationEffect.EFFECT_TICK)
    }

    /**
     * Double-tick when snapping to a depth-detected edge
     */
    fun depthEdgeSnapped() {
        if (vibrator.hasVibrator()) {
            // Double-tick waveform: tick, short pause, tick
            val timings = longArrayOf(0, 20, 40, 20)
            val amplitudes = intArrayOf(0, 80, 0, 120)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }
    }

    private fun vibrate(effectId: Int) {
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        }
    }
}
