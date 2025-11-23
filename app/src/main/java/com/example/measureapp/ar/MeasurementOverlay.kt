package com.example.measureapp.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.ar.core.Camera
import io.github.sceneview.math.Position

class MeasurementOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var measurementManager: MeasurementManager? = null
    var arCamera: Camera? = null

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 56f // Larger text like ARuler
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        style = Paint.Style.FILL
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 2f, Color.BLACK) // Add shadow for depth
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#E6000000") // Slightly more opaque
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val strokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val padding = 24f // More padding
    private val cornerRadius = 20f // Rounder corners

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val manager = measurementManager ?: return
        val camera = arCamera ?: return

        // Draw permanent segment labels
        manager.labels.forEach { label ->
            drawLabel(canvas, camera, label.position, formatDistance(label.distance))
        }

        // Draw live rubber band label
        val livePos = manager.currentLivePosition
        if (livePos != null && manager.currentLiveDistance > 0f) {
            drawLabel(canvas, camera, livePos, formatDistance(manager.currentLiveDistance), isLive = true)
        }
    }

    private fun drawLabel(canvas: Canvas, camera: Camera, position: Position, text: String, isLive: Boolean = false) {
        // Project 3D position to 2D screen coordinates
        val screenPos = project3DTo2D(position, camera) ?: return

        // Measure text bounds
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)

        // Calculate background rect
        val bgRect = RectF(
            screenPos.x - textBounds.width() / 2f - padding,
            screenPos.y - textBounds.height() / 2f - padding,
            screenPos.x + textBounds.width() / 2f + padding,
            screenPos.y + textBounds.height() / 2f + padding
        )

        // Use yellow for live measurement, dark for permanent
        if (isLive) {
            backgroundPaint.color = Color.parseColor("#F0FFD700") // Bright gold
            strokePaint.color = Color.parseColor("#FFA500") // Orange border
        } else {
            backgroundPaint.color = Color.parseColor("#E6000000")
            strokePaint.color = Color.WHITE
        }

        // Draw background
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, backgroundPaint)
        
        // Draw border for better visibility
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, strokePaint)

        // Draw text
        textPaint.color = if (isLive) Color.BLACK else Color.WHITE
        canvas.drawText(
            text,
            screenPos.x,
            screenPos.y + textBounds.height() / 2f,
            textPaint
        )
    }

    private fun project3DTo2D(position: Position, camera: Camera): android.graphics.PointF? {
        // Get view and projection matrices
        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)

        // Combined MVP matrix
        val vpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 3D position as homogeneous coordinates
        val worldPos = floatArrayOf(position.x, position.y, position.z, 1f)
        val clipSpace = FloatArray(4)
        
        // Transform to clip space
        android.opengl.Matrix.multiplyMV(clipSpace, 0, vpMatrix, 0, worldPos, 0)

        // Check if behind camera
        if (clipSpace[3] <= 0f) return null

        // Perspective divide to NDC (-1 to 1)
        val ndcX = clipSpace[0] / clipSpace[3]
        val ndcY = clipSpace[1] / clipSpace[3]

        // Check if outside view frustum
        if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) return null

        // Convert NDC to screen coordinates
        val screenX = (ndcX + 1f) * 0.5f * width
        val screenY = (1f - ndcY) * 0.5f * height // Flip Y axis

        return android.graphics.PointF(screenX, screenY)
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= 1.0f) {
            String.format("%.2f m", meters)
        } else {
            String.format("%.1f cm", meters * 100)
        }
    }
}
