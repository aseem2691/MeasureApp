package com.example.measureapp.ar.helpers

import android.app.Activity
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Session

/**
 * Mirrors the rotation helper from the official ARCore samples.
 * Keeps track of viewport size and notifies the session when display geometry changes.
 */
class DisplayRotationHelper(activity: Activity) {

    private val windowManager: WindowManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.getSystemService(WindowManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            activity.getSystemService(Activity.WINDOW_SERVICE) as? WindowManager
        }

    private val contextActivity: Activity = activity

    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    fun onResume() {
        viewportChanged = true
    }

    fun onPause() {
        // No-op but kept for API parity with sample helper
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (!viewportChanged) return
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            contextActivity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
        session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
        viewportChanged = false
    }
}
