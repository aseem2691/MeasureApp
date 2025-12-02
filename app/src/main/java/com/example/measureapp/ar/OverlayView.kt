package com.example.measureapp.ar

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.ar.core.Camera
import io.github.sceneview.math.Position
import kotlin.math.max

/**
 * iOS-style 2D Overlay for AR Measurements
 * 
 * Draws crisp 2D labels that track 3D measurement points with:
 * - Rounded rectangle backgrounds (white, semi-transparent)
 * - Bold black text centered inside
 * - Dotted guide lines for edge snapping mode
 * - Automatic fade-out for labels behind the camera
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Data to render
    var measurementManager: MeasurementManager? = null
    var arCamera: Camera? = null
    var liveLabelText: String? = null // For rubber-band label
    var detectedRectangle: DetectedRectangle? = null // For rectangle overlay
    
    // Paint objects (reused for performance)
    private val labelBackgroundPaint = Paint().apply {
        color = Color.WHITE
        alpha = (0.9f * 255).toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(4f, 0f, 2f, Color.LTGRAY) // Clean drop shadow
    }
    
    private val labelTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = dpToPx(14f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    
    private val dottedLinePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dpToPx(2f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(10f), dpToPx(5f)), 0f)
        isAntiAlias = true
    }
    
    private val cornerRadius = dpToPx(8f)
    private val paddingHorizontal = dpToPx(12f)
    private val paddingVertical = dpToPx(6f)
    
    // Rectangle overlay paint (iOS yellow bounding box)
    private val rectanglePaint = Paint().apply {
        color = Color.rgb(255, 204, 0) // iOS yellow #FFCC00
        strokeWidth = dpToPx(8f) // Increased for better visibility
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val rectangleCornerPaint = Paint().apply {
        color = Color.rgb(255, 204, 0)
        strokeWidth = dpToPx(10f) // Increased for better visibility
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    // Track drawn label bounds to prevent overlaps
    private val drawnLabels = mutableListOf<RectF>()
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val camera = arCamera ?: return
        val manager = measurementManager ?: return
        
        // Clear previous frame's label positions
        drawnLabels.clear()
        
        // Draw detected rectangle overlay (if any)
        detectedRectangle?.let { rect ->
            android.util.Log.d("OverlayView", "onDraw: Drawing rectangle ${rect.sides[0]}m x ${rect.sides[1]}m")
            drawRectangleOverlay(canvas, camera, rect)
        }
        
        // Draw permanent measurement labels
        for (label in manager.labels) {
            drawLabel(canvas, camera, label.position, label.text)
        }
        
        // Draw live rubber-band label (if measuring)
        val livePos = manager.currentLivePosition
        val liveText = liveLabelText
        if (livePos != null && liveText != null && manager.hasStartedMeasurement) {
            drawLabel(canvas, camera, livePos, liveText, isLive = true)
        }
        
        // Draw dotted guide line for edge snapping
        val smartHit = manager.getCurrentSmartHit()
        if (smartHit is SmartHit.SnappedEdge) {
            drawEdgeGuideLine(canvas, camera, smartHit.hitPosition)
        }
    }
    
    /**
     * Draw a single measurement label with smart positioning to avoid overlaps
     */
    private fun drawLabel(
        canvas: Canvas,
        camera: Camera,
        worldPosition: Position,
        text: String,
        isLive: Boolean = false,
        useYellowBackground: Boolean = false
    ) {
        // Convert 3D world position to 2D screen coordinates
        val screenCoords = worldToScreenPoint(camera, worldPosition) ?: return
        
        // Calculate label dimensions
        val textBounds = Rect()
        labelTextPaint.getTextBounds(text, 0, text.length, textBounds)
        
        val labelWidth = textBounds.width() + paddingHorizontal * 2
        val labelHeight = textBounds.height() + paddingVertical * 2
        
        // Try to find non-overlapping position
        var offsetY = 0f
        val offsetStep = labelHeight + dpToPx(4f)
        var rectF = RectF(
            screenCoords.x - labelWidth / 2,
            screenCoords.y - labelHeight / 2 + offsetY,
            screenCoords.x + labelWidth / 2,
            screenCoords.y + labelHeight / 2 + offsetY
        )
        
        // Check for overlaps and adjust position
        var attempts = 0
        while (attempts < 5 && hasOverlap(rectF)) {
            attempts++
            offsetY = if (attempts % 2 == 1) offsetStep * ((attempts + 1) / 2) else -offsetStep * (attempts / 2)
            rectF = RectF(
                screenCoords.x - labelWidth / 2,
                screenCoords.y - labelHeight / 2 + offsetY,
                screenCoords.x + labelWidth / 2,
                screenCoords.y + labelHeight / 2 + offsetY
            )
        }
        
        // Store this label's bounds
        drawnLabels.add(rectF)
        
        // Choose background color (yellow for rectangles, white for measurements)
        val bgPaint = if (useYellowBackground) {
            Paint(labelBackgroundPaint).apply {
                color = Color.rgb(255, 204, 0) // iOS yellow
                alpha = (0.95f * 255).toInt()
            }
        } else {
            labelBackgroundPaint.apply {
                alpha = if (isLive) (0.7f * 255).toInt() else (0.9f * 255).toInt()
            }
        }
        
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)
        
        // Draw text (centered)
        val textX = screenCoords.x
        val textY = screenCoords.y - textBounds.exactCenterY()
        canvas.drawText(text, textX, textY, labelTextPaint)
    }
    
    /**
     * Draw a dotted guide line from bottom of screen to the snapped edge point
     */
    private fun drawEdgeGuideLine(canvas: Canvas, camera: Camera, edgePosition: Position) {
        val screenCoords = worldToScreenPoint(camera, edgePosition) ?: return
        
        // Draw vertical dotted line from bottom to the point
        val path = Path()
        path.moveTo(screenCoords.x, height.toFloat())
        path.lineTo(screenCoords.x, screenCoords.y)
        
        canvas.drawPath(path, dottedLinePaint)
    }
    
    /**
     * Check if a label rect overlaps with any previously drawn labels
     */
    private fun hasOverlap(rect: RectF): Boolean {
        for (existing in drawnLabels) {
            if (RectF.intersects(existing, rect)) {
                return true
            }
        }
        return false
    }
    
    /**
     * Convert 3D world position to 2D screen coordinates
     * Returns null if the point is behind the camera or outside the frustum
     */
    private fun worldToScreenPoint(camera: Camera, worldPosition: Position): PointF? {
        // Get camera projection and view matrices
        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100f)
        camera.getViewMatrix(viewMatrix, 0)
        
        // Combine view and projection matrices
        val vpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        
        // Transform world position to clip space
        val worldPos = floatArrayOf(worldPosition.x, worldPosition.y, worldPosition.z, 1f)
        val clipPos = FloatArray(4)
        android.opengl.Matrix.multiplyMV(clipPos, 0, vpMatrix, 0, worldPos, 0)
        
        // Check if point is behind camera
        if (clipPos[3] <= 0) return null
        
        // Perspective divide to get normalized device coordinates (NDC)
        val ndcX = clipPos[0] / clipPos[3]
        val ndcY = clipPos[1] / clipPos[3]
        
        // Check if point is outside the view frustum (use generous margin for rectangles)
        if (ndcX < -2.5f || ndcX > 2.5f || ndcY < -2.5f || ndcY > 2.5f) return null
        
        // Convert NDC to screen coordinates (clamp to screen bounds)
        val screenX = ((ndcX + 1f) * width / 2f).coerceIn(0f, width.toFloat())
        val screenY = ((1f - ndcY) * height / 2f).coerceIn(0f, height.toFloat()) // Y is inverted in screen space
        
        return PointF(screenX, screenY)
    }
    
    /**
     * Draw yellow bounding box overlay for detected rectangle
     * iOS Measure style with corner markers and dimension labels
     */
    private fun drawRectangleOverlay(canvas: Canvas, camera: Camera, rectangle: DetectedRectangle) {
        android.util.Log.d("OverlayView", "drawRectangleOverlay called")
        
        // Project all 4 corners to screen space
        val screenCorners = rectangle.corners.mapNotNull { corner ->
            worldToScreenPoint(camera, corner)
        }
        
        android.util.Log.d("OverlayView", "Projected ${screenCorners.size}/4 corners for drawing")
        
        // Draw whatever corners we can (at least 2 needed for a line)
        if (screenCorners.size < 2) {
            android.util.Log.w("OverlayView", "Not drawing - need at least 2 corners, got ${screenCorners.size}")
            return
        }
        
        android.util.Log.d("OverlayView", "✓ Drawing rectangle with ${screenCorners.size} visible corners!")
        
        // Draw bounding box lines (only between visible corners)
        if (screenCorners.size == 4) {
            // All 4 corners visible - draw complete box
            for (i in 0 until 4) {
                val start = screenCorners[i]
                val end = screenCorners[(i + 1) % 4]
                canvas.drawLine(start.x, start.y, end.x, end.y, rectanglePaint)
            }
        } else {
            // Partial rectangle - just draw lines between consecutive visible corners
            for (i in 0 until screenCorners.size - 1) {
                canvas.drawLine(screenCorners[i].x, screenCorners[i].y, 
                               screenCorners[i+1].x, screenCorners[i+1].y, rectanglePaint)
            }
        }
        
        // Draw corner markers (only for complete rectangles with all 4 corners)
        if (screenCorners.size == 4) {
            val cornerLength = dpToPx(12f)
            for (i in 0 until 4) {
                val corner = screenCorners[i]
                val prev = screenCorners[(i - 1 + 4) % 4]
                val next = screenCorners[(i + 1) % 4]
                
                // Calculate directions for L-shape
                val dx1 = (prev.x - corner.x) * 0.1f
                val dy1 = (prev.y - corner.y) * 0.1f
                val dx2 = (next.x - corner.x) * 0.1f
                val dy2 = (next.y - corner.y) * 0.1f
                
                // Draw L-shape marker
                canvas.drawLine(corner.x, corner.y, corner.x + dx1, corner.y + dy1, rectangleCornerPaint)
                canvas.drawLine(corner.x, corner.y, corner.x + dx2, corner.y + dy2, rectangleCornerPaint)
            }
        }
        
        // Draw dimension labels and area (only for complete rectangles)
        if (screenCorners.size == 4) {
            val sidesText = rectangle.sides.map { side ->
                val cm = (side * 100).toInt()
                if (cm > 100) {
                    String.format("%.1f m", side)
                } else {
                    "$cm cm"
                }
            }
            
            for (i in 0 until 4) {
                val start = screenCorners[i]
                val end = screenCorners[(i + 1) % 4]
                val midX = (start.x + end.x) / 2f
                val midY = (start.y + end.y) / 2f
                val midPoint = PointF(midX, midY)
                
                drawLabel(canvas, camera, Position(
                    rectangle.corners[i].x + (rectangle.corners[(i + 1) % 4].x - rectangle.corners[i].x) / 2f,
                    rectangle.corners[i].y + (rectangle.corners[(i + 1) % 4].y - rectangle.corners[i].y) / 2f,
                    rectangle.corners[i].z + (rectangle.corners[(i + 1) % 4].z - rectangle.corners[i].z) / 2f
                ), sidesText[i], useYellowBackground = true)
            }
            
            // Draw area label at center
            val centerX = screenCorners.map { it.x }.average().toFloat()
            val centerY = screenCorners.map { it.y }.average().toFloat()
            val center3D = Position(
                rectangle.corners.map { it.x }.average().toFloat(),
                rectangle.corners.map { it.y }.average().toFloat(),
                rectangle.corners.map { it.z }.average().toFloat()
            )
            val areaText = String.format("%.2f m²", rectangle.area)
            drawLabel(canvas, camera, center3D, areaText, useYellowBackground = true)
        }
    }
    
    /**
     * Convert dp to pixels
     */
    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
