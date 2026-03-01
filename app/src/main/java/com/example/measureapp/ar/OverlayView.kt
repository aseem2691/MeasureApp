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
    var detectedDepthEdges: List<DepthEdgeDetector.DetectedEdge> = emptyList()
    
    // Paint objects (reused for performance)
    private val labelBackgroundPaint = Paint().apply {
        color = Color.rgb(255, 204, 0) // iOS yellow
        alpha = (0.95f * 255).toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(4f, 0f, 2f, Color.argb(60, 0, 0, 0))
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dpToPx(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        isAntiAlias = true
    }
    
    private val dottedLinePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dpToPx(2f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(10f), dpToPx(5f)), 0f)
        isAntiAlias = true
    }

    private val depthEdgeLinePaint = Paint().apply {
        color = Color.argb(120, 0, 200, 255) // Semi-transparent cyan
        strokeWidth = dpToPx(1.5f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(8f), dpToPx(4f)), 0f)
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val labelBorderPaint = Paint().apply {
        color = Color.rgb(255, 204, 0) // iOS yellow border
        strokeWidth = dpToPx(1f)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val baseTextSize = dpToPx(15f)
    private val cornerRadius = dpToPx(8f)
    private val paddingHorizontal = dpToPx(12f)
    private val paddingVertical = dpToPx(6f)
    
    // Track drawn label bounds to prevent overlaps
    private val drawnLabels = mutableListOf<RectF>()
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val camera = arCamera ?: return
        val manager = measurementManager ?: return
        
        // Clear previous frame's label positions
        drawnLabels.clear()
        
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

        // Draw depth-detected edge guide lines
        for (edge in detectedDepthEdges) {
            drawDepthEdgeLine(canvas, camera, edge)
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
        isLive: Boolean = false
    ) {
        // Convert 3D world position to 2D screen coordinates
        val screenCoords = worldToScreenPoint(camera, worldPosition) ?: return

        // Distance-scaled text: closer labels appear larger, farther ones smaller
        val cameraPose = camera.pose
        val dx = worldPosition.x - cameraPose.tx()
        val dy = worldPosition.y - cameraPose.ty()
        val dz = worldPosition.z - cameraPose.tz()
        val distanceFromCamera = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val scaleFactor = (1.5f / distanceFromCamera).coerceIn(0.7f, 1.5f)
        val scaledTextSize = baseTextSize * scaleFactor
        labelTextPaint.textSize = scaledTextSize

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
        
        // Make live label slightly transparent
        if (isLive) {
            labelBackgroundPaint.alpha = (0.7f * 255).toInt()
        } else {
            labelBackgroundPaint.alpha = (0.9f * 255).toInt()
        }
        
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, labelBackgroundPaint)

        // Draw thin border for better visibility against varying backgrounds
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, labelBorderPaint)

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
     * Draw a faint dashed line along a depth-detected edge
     */
    private fun drawDepthEdgeLine(canvas: Canvas, camera: Camera, edge: DepthEdgeDetector.DetectedEdge) {
        val startScreen = worldToScreenPoint(camera, edge.startWorld) ?: return
        val endScreen = worldToScreenPoint(camera, edge.endWorld) ?: return

        // Modulate alpha by edge confidence
        val alpha = (edge.confidence * 120).toInt().coerceIn(40, 120)
        depthEdgeLinePaint.alpha = alpha

        val path = Path()
        path.moveTo(startScreen.x, startScreen.y)
        path.lineTo(endScreen.x, endScreen.y)
        canvas.drawPath(path, depthEdgeLinePaint)
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
        
        // Check if point is outside the view frustum
        if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) return null
        
        // Convert NDC to screen coordinates
        val screenX = (ndcX + 1f) * width / 2f
        val screenY = (1f - ndcY) * height / 2f // Y is inverted in screen space
        
        return PointF(screenX, screenY)
    }
    
    /**
     * Convert dp to pixels
     */
    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
