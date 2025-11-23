package com.example.measureapp.ar

import android.opengl.GLES20
import android.opengl.Matrix
import com.example.measureapp.data.models.MeasurementPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders measurement points and lines in AR space
 */
class PointRenderer {
    private var pointProgram = 0
    private var lineProgram = 0
    
    private var pointPositionAttribute = 0
    private var pointMvpUniform = 0
    private var pointColorUniform = 0
    private var pointSizeUniform = 0
    
    private var linePositionAttribute = 0
    private var lineMvpUniform = 0
    private var lineColorUniform = 0
    
    private val pointVertexShader = """
        uniform mat4 u_ModelViewProjection;
        uniform float u_PointSize;
        attribute vec4 a_Position;
        
        void main() {
            gl_Position = u_ModelViewProjection * a_Position;
            gl_PointSize = u_PointSize;
        }
    """.trimIndent()
    
    private val pointFragmentShader = """
        precision mediump float;
        uniform vec4 u_Color;
        
        void main() {
            // Draw as circle, not square
            vec2 coord = gl_PointCoord - vec2(0.5);
            if (length(coord) > 0.5) {
                discard;
            }
            gl_FragColor = u_Color;
        }
    """.trimIndent()
    
    private val lineVertexShader = """
        uniform mat4 u_ModelViewProjection;
        attribute vec4 a_Position;
        
        void main() {
            gl_Position = u_ModelViewProjection * a_Position;
        }
    """.trimIndent()
    
    private val lineFragmentShader = """
        precision mediump float;
        uniform vec4 u_Color;
        
        void main() {
            gl_FragColor = u_Color;
        }
    """.trimIndent()
    
    fun createOnGlThread() {
        // Create point shader program
        val pointVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, pointVertexShader)
        val pointFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, pointFragmentShader)
        
        pointProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(pointProgram, pointVertexShader)
        GLES20.glAttachShader(pointProgram, pointFragmentShader)
        GLES20.glLinkProgram(pointProgram)
        
        pointPositionAttribute = GLES20.glGetAttribLocation(pointProgram, "a_Position")
        pointMvpUniform = GLES20.glGetUniformLocation(pointProgram, "u_ModelViewProjection")
        pointColorUniform = GLES20.glGetUniformLocation(pointProgram, "u_Color")
        pointSizeUniform = GLES20.glGetUniformLocation(pointProgram, "u_PointSize")
        
        // Create line shader program
        val lineVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, lineVertexShader)
        val lineFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, lineFragmentShader)
        
        lineProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(lineProgram, lineVertexShader)
        GLES20.glAttachShader(lineProgram, lineFragmentShader)
        GLES20.glLinkProgram(lineProgram)
        
        linePositionAttribute = GLES20.glGetAttribLocation(lineProgram, "a_Position")
        lineMvpUniform = GLES20.glGetUniformLocation(lineProgram, "u_ModelViewProjection")
        lineColorUniform = GLES20.glGetUniformLocation(lineProgram, "u_Color")
    }
    
    fun drawPoints(
        points: List<MeasurementPoint>,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        if (pointProgram == 0 || points.isEmpty()) return
        
        GLES20.glUseProgram(pointProgram)
        GLES20.glEnableVertexAttribArray(pointPositionAttribute)
        
        // Enable blending for transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Create vertices buffer
        val vertices = FloatArray(points.size * 3)
        points.forEachIndexed { index, point ->
            vertices[index * 3] = point.position.x
            vertices[index * 3 + 1] = point.position.y
            vertices[index * 3 + 2] = point.position.z
        }
        
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
        
        // Calculate view-projection matrix
        val viewProjectionMatrix = FloatArray(16)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        
        // Disable depth test so points render on top (always visible)
        val depthTestEnabled = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        
        // Set uniforms
        GLES20.glUniformMatrix4fv(pointMvpUniform, 1, false, viewProjectionMatrix, 0)
        GLES20.glUniform4f(pointColorUniform, 1.0f, 0.0f, 0.0f, 1.0f) // Bright red points
        GLES20.glUniform1f(pointSizeUniform, 50.0f) // MUCH larger points (was 30.0f)
        
        // Draw points
        GLES20.glVertexAttribPointer(
            pointPositionAttribute,
            3,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )
        
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, points.size)
        
        GLES20.glDisableVertexAttribArray(pointPositionAttribute)
        
        // Restore depth test state
        if (depthTestEnabled) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    
    fun drawLine(
        startPoint: MeasurementPoint,
        endPoint: MeasurementPoint,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        if (lineProgram == 0) return
        
        GLES20.glUseProgram(lineProgram)
        GLES20.glEnableVertexAttribArray(linePositionAttribute)
        
        // Create line vertices
        val vertices = floatArrayOf(
            startPoint.position.x, startPoint.position.y, startPoint.position.z,
            endPoint.position.x, endPoint.position.y, endPoint.position.z
        )
        
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
        
        // Calculate view-projection matrix
        val viewProjectionMatrix = FloatArray(16)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        
        // Enable blending for semi-transparent line
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Disable depth test so line renders on top (always visible)
        val depthTestEnabled = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        
        // Set uniforms
        GLES20.glUniformMatrix4fv(lineMvpUniform, 1, false, viewProjectionMatrix, 0)
        GLES20.glUniform4f(lineColorUniform, 1.0f, 1.0f, 0.0f, 1.0f) // Solid yellow line (was 0.8f)
        
        // Set line width - MUCH THICKER for visibility
        GLES20.glLineWidth(15.0f)  // Increased from 8.0f
        
        // Draw line
        GLES20.glVertexAttribPointer(
            linePositionAttribute,
            3,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )
        
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        
        GLES20.glDisableVertexAttribArray(linePositionAttribute)
        
        // Restore depth test state
        if (depthTestEnabled) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
