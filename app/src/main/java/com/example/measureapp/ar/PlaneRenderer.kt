package com.example.measureapp.ar

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders detected AR planes with semi-transparent overlays
 */
class PlaneRenderer {
    private var program = 0
    private var positionAttribute = 0
    private var modelViewProjectionUniform = 0
    private var colorUniform = 0
    
    private val vertexShader = """
        uniform mat4 u_ModelViewProjection;
        attribute vec4 a_Position;
        
        void main() {
            gl_Position = u_ModelViewProjection * a_Position;
        }
    """.trimIndent()
    
    private val fragmentShader = """
        precision mediump float;
        uniform vec4 u_Color;
        
        void main() {
            gl_FragColor = u_Color;
        }
    """.trimIndent()
    
    // Circle vertices for plane visualization
    private val circleVertices: FloatBuffer
    private val circleIndices: ShortBuffer
    private val numSegments = 16 // Reduced for performance
    
    init {
        // Create circle vertices
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()
        
        // Center point
        vertices.add(0f)
        vertices.add(0f)
        vertices.add(0f)
        
        // Circle perimeter - SMALLER radius to reduce visual clutter
        val radius = 0.15f // 15cm radius (reduced from 50cm)
        for (i in 0..numSegments) {
            val angle = 2.0f * Math.PI.toFloat() * i / numSegments
            vertices.add(radius * cos(angle))
            vertices.add(0f)
            vertices.add(radius * sin(angle))
        }
        
        // Create triangle fan indices
        for (i in 1..numSegments) {
            indices.add(0)
            indices.add(i.toShort())
            indices.add((i + 1).toShort())
        }
        
        // Convert to buffers
        circleVertices = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                vertices.forEach { put(it) }
                position(0)
            }
        
        circleIndices = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                indices.forEach { put(it) }
                position(0)
            }
    }
    
    fun createOnGlThread() {
        // Create shader program
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        // Get attribute and uniform locations
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }
    
    fun drawPlanes(
        planes: Collection<Plane>,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        // TEMPORARILY DISABLED FOR TESTING - Remove this return to re-enable planes
        return
        
        if (program == 0) return
        
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        
        // Enable blending for transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        val modelViewProjectionMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        
        // Planes are already filtered by ArCoreRenderer, render all provided
        planes.forEach { plane ->
            if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                // Get plane pose
                plane.centerPose.toMatrix(modelMatrix, 0)
                
                // Calculate MVP matrix
                val tempMatrix = FloatArray(16)
                Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
                Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, tempMatrix, 0)
                
                // Set MVP matrix
                GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)
                
                // Set color based on plane type - REDUCED ALPHA to minimize overdraw
                val color = if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING || 
                                plane.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING) {
                    floatArrayOf(0.0f, 0.5f, 1.0f, 0.15f) // Blue with 15% alpha (reduced from 30%)
                } else {
                    floatArrayOf(0.0f, 1.0f, 0.5f, 0.15f) // Green with 15% alpha (reduced from 30%)
                }
                GLES20.glUniform4fv(colorUniform, 1, color, 0)
                
                // Draw circle
                GLES20.glVertexAttribPointer(
                    positionAttribute,
                    3,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    circleVertices
                )
                
                GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES,
                    circleIndices.capacity(),
                    GLES20.GL_UNSIGNED_SHORT,
                    circleIndices
                )
            }
        }
        
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
