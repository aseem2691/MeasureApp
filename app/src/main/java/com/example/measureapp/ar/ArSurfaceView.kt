package com.example.measureapp.ar

import android.content.Context
import android.opengl.GLSurfaceView
import com.example.measureapp.data.models.MeasurementPoint
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session

/**
 * Legacy Compose surface stub left in place for backwards compatibility.
 * The app now uses [MeasureActivity] + [SimpleArRenderer]; this view simply
 * preserves the old API to keep unused Compose screens compiling.
 */
class ArSurfaceView(context: Context) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setupRenderer(
        session: Session,
        onFrameUpdate: (Frame, Camera) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        // No-op: legacy Compose path is deprecated.
    }

    fun setOnPlaneDetectedListener(listener: (Plane) -> Unit) {
        // No-op: retained for binary compatibility.
    }

    fun updateMeasurementPoints(points: List<MeasurementPoint>) {
        // No-op: measurement rendering handled in MeasureActivity pipeline.
    }
}
