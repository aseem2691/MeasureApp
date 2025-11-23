package com.example.measureapp.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.measureapp.ar.helpers.DisplayRotationHelper
import com.example.measureapp.ar.helpers.TapHelper
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.Locale
import kotlin.math.sqrt

/**
 * Simplified GLSurfaceView.Renderer based on ARCoreMeasure architecture.
 * Handles all AR rendering: camera background, planes, points, lines.
 */
class SimpleArRenderer(
    private val session: Session,
    private val displayRotationHelper: DisplayRotationHelper,
    private val tapHelper: TapHelper?,
    private val onDistanceUpdate: (String) -> Unit
) : GLSurfaceView.Renderer {
    
    private val TAG = "SimpleArRenderer"
    
    // Rendering state
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    
    // Background renderer (camera feed)
    private val backgroundRenderer = BackgroundRenderer()
    
    // Shader programs
    private var pointProgram = 0
    private var lineProgram = 0
    
    // Measurement data
    val anchors = ArrayList<Anchor>()
    
    // Pending tap
    var pendingTap: Pair<Float, Float>? = null
    
    private var frameCount = 0
    
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        
        try {
            backgroundRenderer.createOnGlThread()
            
            // CRITICAL: Set camera texture for ARCore
            session.setCameraTextureName(backgroundRenderer.textureId)
            Log.i(TAG, "✅ Camera texture set: ${backgroundRenderer.textureId}")
            
            // Create point shader
            pointProgram = createShaderProgram(POINT_VERTEX_SHADER, POINT_FRAGMENT_SHADER)
            
            // Create line shader
            lineProgram = createShaderProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
            
            Log.i(TAG, "✅ OpenGL initialized successfully")
            Log.i(TAG, "✅ BackgroundRenderer ready, texture=${backgroundRenderer.textureId}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize OpenGL", e)
        }
    }
    
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
    }
    
    override fun onDrawFrame(gl: GL10?) {
        // Clear screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        try {
            displayRotationHelper.updateSessionIfNeeded(session)
            // Update ARCore frame
            val frame = session.update()
            val camera = frame.camera
            
            // ALWAYS draw camera background first (even when not tracking)
            backgroundRenderer.draw(frame)
            
            // Log every 30 frames to confirm rendering
            if (frameCount++ % 30 == 0) {
                Log.d(TAG, "📹 Frame $frameCount: tracking=${camera.trackingState}, texture=${backgroundRenderer.textureId}")
            }
            
            // Only process AR content when tracking
            if (camera.trackingState != TrackingState.TRACKING) {
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "⏳ Camera not tracking yet: ${camera.trackingState}")
                }
                return // Not tracking yet, but camera feed still shows
            }
            
            // Get camera matrices
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
            
            tapHelper?.poll()?.let { motionEvent ->
                handleTap(frame, motionEvent.x, motionEvent.y)
                motionEvent.recycle()
            }
            
            // Handle pending tap
            pendingTap?.let { (x, y) ->
                Log.d(TAG, "🎯 Processing pending tap: ($x, $y)")
                handleTap(frame, x, y)
                pendingTap = null
            }
            
            // Update distance display
            updateDistance()
            
            // Draw measurements
            drawMeasurements()
            
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error in draw frame", e)
        }
    }
    
    private fun handleTap(frame: Frame, x: Float, y: Float) {
        Log.d(TAG, "🔍 Hit test at screen coords ($x, $y)")

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Log.w(TAG, "⚠️ Camera not tracking yet, cannot place anchor")
            return
        }

        val hits = frame.hitTest(x, y)
        Log.d(TAG, "🔍 Hit test returned ${hits.size} hits")

        var planeHit: HitResult? = null
        var pointHit: HitResult? = null

        for (hit in hits) {
            val trackable = hit.trackable
            val isTracking = trackable.trackingState == TrackingState.TRACKING

            if (!isTracking) {
                continue
            }

            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                planeHit = hit
                val pose = hit.hitPose
                Log.d(TAG, "✅ Valid plane hit at (${pose.tx()}, ${pose.ty()}, ${pose.tz()})")
                break
            }

            if (pointHit == null && trackable is Point &&
                trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                pointHit = hit
                val pose = hit.hitPose
                Log.d(TAG, "✅ Candidate point hit at (${pose.tx()}, ${pose.ty()}, ${pose.tz()})")
            }
        }

        val chosenHit = planeHit ?: pointHit

        if (chosenHit == null) {
            Log.w(TAG, "⚠️ No valid hits found. Point camera at a flat surface.")
            return
        }

        if (anchors.size >= 16) {
            Log.d(TAG, "🗑️ Removing oldest anchor (max 16)")
            anchors[0].detach()
            anchors.removeAt(0)
        }

        val anchor = chosenHit.createAnchor()
        anchors.add(anchor)
        val pose = chosenHit.hitPose
        Log.d(TAG, "✅ Anchor created. Total anchors: ${anchors.size}")
        Log.d(TAG, "📍 Anchor position: (${pose.tx()}, ${pose.ty()}, ${pose.tz()})")
    }

    private fun updateDistance() {
        if (anchors.size < 2) {
            onDistanceUpdate("")
            return
        }

        val segmentsCm = mutableListOf<Double>()
        var previousPose = anchors[0].pose

        for (i in 1 until anchors.size) {
            val currentPose = anchors[i].pose
            val dx = previousPose.tx() - currentPose.tx()
            val dy = previousPose.ty() - currentPose.ty()
            val dz = previousPose.tz() - currentPose.tz()
            val distanceMeters = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            segmentsCm.add(distanceMeters * 100.0)
            previousPose = currentPose
        }

        val totalCm = segmentsCm.sum()

        val segmentsText = segmentsCm.joinToString(separator = " + ") {
            String.format(Locale.US, "%.1f", it)
        }

        val result = "$segmentsText = ${String.format(Locale.US, "%.1f", totalCm)} cm"
        onDistanceUpdate(result)
    }
    
    private fun drawMeasurements() {
        if (anchors.isEmpty()) return
        
        // Draw points and lines
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        
        // Draw all points
        for (anchor in anchors) {
            drawPoint(anchor.pose)
        }
        
        // Draw lines between consecutive points
        for (i in 1 until anchors.size) {
            drawLine(anchors[i - 1].pose, anchors[i].pose)
        }
    }
    
    private fun drawPoint(pose: Pose) {
        // Simple cube as point marker
        val mvpMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        
        // Position at pose
        pose.toMatrix(modelMatrix, 0)
        
        // Scale to small size (3cm cube)
        Matrix.scaleM(modelMatrix, 0, 0.03f, 0.03f, 0.03f)
        
        // Calculate MVP matrix
        val mvMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        
        // Draw cube (simplified - just draw a single point for now)
        GLES20.glUseProgram(pointProgram)
        
        val mvpHandle = GLES20.glGetUniformLocation(pointProgram, "u_MvpMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        
        val colorHandle = GLES20.glGetUniformLocation(pointProgram, "u_Color")
        GLES20.glUniform4f(colorHandle, 1.0f, 0.0f, 0.0f, 1.0f) // Red
        
        // Draw point (point size set in shader)
        val vertices = floatArrayOf(0f, 0f, 0f)
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)
        
        val posHandle = GLES20.glGetAttribLocation(pointProgram, "a_Position")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
        
        GLES20.glDisableVertexAttribArray(posHandle)
    }
    
    private fun drawLine(pose0: Pose, pose1: Pose) {
        val vertices = floatArrayOf(
            pose0.tx(), pose0.ty(), pose0.tz(),
            pose1.tx(), pose1.ty(), pose1.tz()
        )
        
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)
        
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        
        GLES20.glUseProgram(lineProgram)
        GLES20.glLineWidth(10.0f)
        
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "u_MvpMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "u_Color")
        GLES20.glUniform4f(colorHandle, 1.0f, 1.0f, 0.0f, 1.0f) // Yellow
        
        val posHandle = GLES20.glGetAttribLocation(lineProgram, "a_Position")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        
        GLES20.glDisableVertexAttribArray(posHandle)
    }
    
    fun clearMeasurements() {
        anchors.forEach { it.detach() }
        anchors.clear()
        onDistanceUpdate("")
    }
    
    private fun createShaderProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
        }
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
    
    companion object {
        private const val POINT_VERTEX_SHADER = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                gl_PointSize = 20.0;
            }
        """
        
        private const val POINT_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
        
        private const val LINE_VERTEX_SHADER = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
            }
        """
        
        private const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
