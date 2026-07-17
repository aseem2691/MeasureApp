package com.example.measureapp.ar

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.ar.core.Camera
import io.github.sceneview.math.Position

/**
 * iOS-style 2D overlay for AR measurements.
 *
 * Everything except the small 3D corner dots renders here, in screen space:
 * - Measurement lines: thin white lines projected from anchor poses every frame
 *   (always crisp, automatically drift-corrected, no 3D tube shading)
 * - Live rubber-band line while measuring
 * - Distance labels: dark pills with yellow accent
 * - Reticle: thin ring + center dot at the smart-hit point (never blocks the view)
 * - Detected rectangle outline + dimension labels
 * - ML person-height indicator
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ReticleState { HIDDEN, SEARCHING, TRACKING, SNAPPED }

    data class PersonHeightIndicator(
        val head: PointF,
        val feet: PointF,
        val text: String,
        val isStable: Boolean
    )

    // Data to render
    var measurementManager: MeasurementManager? = null
    var arCamera: Camera? = null
    var liveLabelText: String? = null
    var detectedRectangle: DetectedRectangle? = null
    var personHeightIndicator: PersonHeightIndicator? = null
    var reticleWorld: Position? = null
    var reticleState: ReticleState = ReticleState.HIDDEN

    // Smoothed reticle screen position (lerp for the iOS magnet feel)
    private var reticleScreen: PointF? = null

    private val iosYellow = Color.rgb(255, 204, 0)
    private val iosGreen = Color.rgb(52, 199, 89)

    // --- Paints (reused for performance) ---

    private val labelBackgroundPaint = Paint().apply {
        color = Color.argb(230, 28, 28, 30)
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(6f, 0f, 2f, Color.argb(80, 0, 0, 0))
    }

    // Pre-built variants — creating Paint objects per label per frame causes GC churn
    private val labelBackgroundLivePaint = Paint(labelBackgroundPaint).apply {
        alpha = 178
    }
    private val labelBackgroundYellowPaint = Paint(labelBackgroundPaint).apply {
        color = Color.rgb(255, 204, 0)
        alpha = 242
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dpToPx(14f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        isAntiAlias = true
    }

    private val labelAccentPaint = Paint().apply {
        color = iosYellow
        strokeWidth = dpToPx(2.5f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Measurement lines: thin white with a soft dark under-stroke for contrast
    private val lineShadowPaint = Paint().apply {
        color = Color.argb(70, 0, 0, 0)
        strokeWidth = dpToPx(3.5f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dpToPx(1.8f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val liveLinePaint = Paint(linePaint).apply {
        alpha = 200
    }

    private val personLinePaint = Paint(linePaint).apply { alpha = 220 }
    private val personLineStablePaint = Paint(linePaint).apply {
        color = iosGreen
        alpha = 220
    }

    private val endpointDotPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(3f, 0f, 1f, Color.argb(100, 0, 0, 0))
    }

    private val endpointRingPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
        isAntiAlias = true
    }

    private val reticleRingPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dpToPx(2f)
        style = Paint.Style.STROKE
        isAntiAlias = true
        setShadowLayer(4f, 0f, 1f, Color.argb(90, 0, 0, 0))
    }

    private val reticleDotPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(3f, 0f, 1f, Color.argb(90, 0, 0, 0))
    }

    private val rectanglePaint = Paint().apply {
        color = iosYellow
        strokeWidth = dpToPx(2.5f)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val rectangleCornerPaint = Paint().apply {
        color = iosYellow
        strokeWidth = dpToPx(4f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val cornerRadius = dpToPx(12f)
    private val paddingHorizontal = dpToPx(10f)
    private val paddingVertical = dpToPx(5f)

    // Track drawn label bounds to prevent overlaps
    private val drawnLabels = mutableListOf<RectF>()

    // Cached matrices to avoid per-frame allocation
    private val cachedProjectionMatrix = FloatArray(16)
    private val cachedViewMatrix = FloatArray(16)
    private val cachedVPMatrix = FloatArray(16)
    private val cachedWorldPos = FloatArray(4)
    private val cachedClipPos = FloatArray(4)
    private var matricesValid = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val camera = arCamera ?: return
        val manager = measurementManager ?: return

        drawnLabels.clear()
        updateMatrices(camera)

        // Detected rectangle outline (hidden while measuring to reduce clutter;
        // its corners/edges still act as snap targets — reticle turns green)
        if (!manager.hasStartedMeasurement) {
            detectedRectangle?.let { rect ->
                drawRectangleOverlay(canvas, rect)
            }
        }

        // Permanent measurement lines + labels (projected live from anchors)
        for (segment in manager.renderSegments) {
            val start = segment.startPosition()
            val end = segment.endPosition()
            val p1 = worldToScreenUnclamped(start) ?: continue
            val p2 = worldToScreenUnclamped(end) ?: continue

            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, lineShadowPaint)
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint)
            drawEndpointDot(canvas, p1)
            drawEndpointDot(canvas, p2)

            val distance = length(end - start)
            val mid = Position(
                (start.x + end.x) / 2f,
                (start.y + end.y) / 2f,
                (start.z + end.z) / 2f
            )
            drawLabel(canvas, mid, manager.formatDistance(distance))
        }

        // Area badges: pill at the live centroid of each closed polygon
        for (badge in manager.areaBadges) {
            var cx = 0f; var cy = 0f; var cz = 0f
            for (anchor in badge.anchors) {
                val p = anchor.pose
                cx += p.tx(); cy += p.ty(); cz += p.tz()
            }
            val n = badge.anchors.size
            if (n > 0) {
                val centroid = Position(cx / n, cy / n, cz / n)
                drawLabel(
                    canvas, centroid,
                    manager.unitType.formatArea(badge.areaSquareMeters),
                    useYellowBackground = true
                )
            }
        }

        // Live rubber-band line from last placed point to the reticle
        val liveStart = manager.activeStartPosition()
        val liveEnd = manager.getCurrentSmartHit().getPosition()
        if (manager.hasStartedMeasurement && liveStart != null && liveEnd != null) {
            val p1 = worldToScreenUnclamped(liveStart)
            val p2 = worldToScreenUnclamped(liveEnd)
            if (p1 != null && p2 != null) {
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, lineShadowPaint)
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, liveLinePaint)
                drawEndpointDot(canvas, p1)
            }
        }

        // Live distance label at the rubber-band midpoint
        val livePos = manager.currentLivePosition
        val liveText = liveLabelText
        if (livePos != null && liveText != null && manager.hasStartedMeasurement) {
            drawLabel(canvas, livePos, liveText, isLive = true)
        }

        // Reticle (thin ring + dot — drawn last so it's always visible)
        drawReticle(canvas)

        // ML person height indicator
        personHeightIndicator?.let { indicator ->
            drawPersonHeightIndicator(canvas, indicator)
        }
    }

    /** Crisp iOS-style corner marker: white dot with a subtle dark outline */
    private fun drawEndpointDot(canvas: Canvas, point: PointF) {
        val radius = dpToPx(4f)
        canvas.drawCircle(point.x, point.y, radius, endpointDotPaint)
        canvas.drawCircle(point.x, point.y, radius, endpointRingPaint)
    }

    /**
     * iOS-style reticle: small ring + center dot at the smart-hit point.
     * White while tracking, green + enlarged when snapped, faint while searching.
     */
    private fun drawReticle(canvas: Canvas) {
        if (reticleState == ReticleState.HIDDEN) {
            reticleScreen = null
            return
        }

        val target = reticleWorld?.let { worldToScreenUnclamped(it) }
            ?: PointF(width / 2f, height / 2f)

        // Smooth toward target for the magnet feel
        val current = reticleScreen
        val smoothed = if (current == null) target else PointF(
            current.x + (target.x - current.x) * 0.35f,
            current.y + (target.y - current.y) * 0.35f
        )
        reticleScreen = smoothed

        val snapped = reticleState == ReticleState.SNAPPED
        val searching = reticleState == ReticleState.SEARCHING

        val ringRadius = if (snapped) dpToPx(14f) else dpToPx(11f)
        reticleRingPaint.color = if (snapped) iosGreen else Color.WHITE
        reticleRingPaint.alpha = if (searching) 110 else 235
        reticleDotPaint.color = if (snapped) iosGreen else Color.WHITE
        reticleDotPaint.alpha = if (searching) 110 else 255

        canvas.drawCircle(smoothed.x, smoothed.y, ringRadius, reticleRingPaint)
        canvas.drawCircle(smoothed.x, smoothed.y, dpToPx(2.5f), reticleDotPaint)
    }

    /**
     * Vertical measuring line over a detected person with the height label.
     */
    private fun drawPersonHeightIndicator(canvas: Canvas, indicator: PersonHeightIndicator) {
        val paint = if (indicator.isStable) personLineStablePaint else personLinePaint
        val capHalf = dpToPx(10f)

        canvas.drawLine(indicator.head.x, indicator.head.y, indicator.feet.x, indicator.feet.y, paint)
        canvas.drawLine(indicator.head.x - capHalf, indicator.head.y, indicator.head.x + capHalf, indicator.head.y, paint)
        canvas.drawLine(indicator.feet.x - capHalf, indicator.feet.y, indicator.feet.x + capHalf, indicator.feet.y, paint)

        val labelPos = PointF(indicator.head.x, indicator.head.y - dpToPx(18f))
        drawScreenLabel(canvas, labelPos, indicator.text)
    }

    private fun drawLabel(
        canvas: Canvas,
        worldPosition: Position,
        text: String,
        isLive: Boolean = false,
        useYellowBackground: Boolean = false
    ) {
        val screenCoords = worldToScreenPoint(worldPosition) ?: return
        drawScreenLabel(canvas, screenCoords, text, isLive, useYellowBackground)
    }

    /**
     * Draw a pill label at a 2D screen position with smart de-overlap
     */
    private fun drawScreenLabel(
        canvas: Canvas,
        screenCoords: PointF,
        text: String,
        isLive: Boolean = false,
        useYellowBackground: Boolean = false
    ) {
        val textBounds = Rect()
        labelTextPaint.getTextBounds(text, 0, text.length, textBounds)

        val labelWidth = textBounds.width() + paddingHorizontal * 2
        val labelHeight = textBounds.height() + paddingVertical * 2

        var offsetY = 0f
        val offsetStep = labelHeight + dpToPx(4f)
        var rectF = RectF(
            screenCoords.x - labelWidth / 2,
            screenCoords.y - labelHeight / 2 + offsetY,
            screenCoords.x + labelWidth / 2,
            screenCoords.y + labelHeight / 2 + offsetY
        )

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

        drawnLabels.add(rectF)

        val bgPaint = when {
            useYellowBackground -> labelBackgroundYellowPaint
            isLive -> labelBackgroundLivePaint
            else -> labelBackgroundPaint
        }

        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

        if (!useYellowBackground) {
            val accentPadding = dpToPx(3f)
            canvas.drawLine(
                rectF.left + accentPadding,
                rectF.top + accentPadding + dpToPx(2f),
                rectF.left + accentPadding,
                rectF.bottom - accentPadding - dpToPx(2f),
                labelAccentPaint
            )
        }

        val textX = rectF.centerX()
        val textY = rectF.centerY() - textBounds.exactCenterY()
        canvas.drawText(text, textX, textY, labelTextPaint)
    }

    private fun hasOverlap(rect: RectF): Boolean {
        for (existing in drawnLabels) {
            if (RectF.intersects(existing, rect)) return true
        }
        return false
    }

    // --- Projection ---

    private fun updateMatrices(camera: Camera) {
        if (width <= 0 || height <= 0) {
            matricesValid = false
            return
        }
        camera.getProjectionMatrix(cachedProjectionMatrix, 0, 0.01f, 100f)
        camera.getViewMatrix(cachedViewMatrix, 0)
        android.opengl.Matrix.multiplyMM(cachedVPMatrix, 0, cachedProjectionMatrix, 0, cachedViewMatrix, 0)
        matricesValid = true
    }

    private fun projectToNdc(worldPosition: Position): PointF? {
        if (!matricesValid) return null

        cachedWorldPos[0] = worldPosition.x
        cachedWorldPos[1] = worldPosition.y
        cachedWorldPos[2] = worldPosition.z
        cachedWorldPos[3] = 1f
        android.opengl.Matrix.multiplyMV(cachedClipPos, 0, cachedVPMatrix, 0, cachedWorldPos, 0)

        if (cachedClipPos[3] <= 0) return null
        return PointF(cachedClipPos[0] / cachedClipPos[3], cachedClipPos[1] / cachedClipPos[3])
    }

    /**
     * Projection for labels: clamps to screen bounds, culls when far off-screen
     */
    private fun worldToScreenPoint(worldPosition: Position): PointF? {
        val ndc = projectToNdc(worldPosition) ?: return null
        if (ndc.x < -1.2f || ndc.x > 1.2f || ndc.y < -1.2f || ndc.y > 1.2f) return null
        return PointF(
            ((ndc.x + 1f) * width / 2f).coerceIn(0f, width.toFloat()),
            ((1f - ndc.y) * height / 2f).coerceIn(0f, height.toFloat())
        )
    }

    /**
     * Projection for line endpoints: NOT clamped (clamping bends lines), generous
     * off-screen tolerance so lines crossing the screen render correctly
     */
    private fun worldToScreenUnclamped(worldPosition: Position): PointF? {
        val ndc = projectToNdc(worldPosition) ?: return null
        if (ndc.x < -4f || ndc.x > 4f || ndc.y < -4f || ndc.y > 4f) return null
        return PointF(
            (ndc.x + 1f) * width / 2f,
            (1f - ndc.y) * height / 2f
        )
    }

    /**
     * Yellow outline for the auto-detected rectangle, with dimension labels
     */
    private fun drawRectangleOverlay(canvas: Canvas, rectangle: DetectedRectangle) {
        val screenCorners = rectangle.corners.mapNotNull { corner ->
            worldToScreenUnclamped(corner)
        }
        // Only draw complete rectangles — partial outlines look like stray lines
        if (screenCorners.size == 4) {
            for (i in 0 until 4) {
                val start = screenCorners[i]
                val end = screenCorners[(i + 1) % 4]
                canvas.drawLine(start.x, start.y, end.x, end.y, rectanglePaint)
            }

            // L-shaped corner markers
            val cornerFraction = 0.12f
            for (i in 0 until 4) {
                val corner = screenCorners[i]
                val prev = screenCorners[(i - 1 + 4) % 4]
                val next = screenCorners[(i + 1) % 4]
                canvas.drawLine(
                    corner.x, corner.y,
                    corner.x + (prev.x - corner.x) * cornerFraction,
                    corner.y + (prev.y - corner.y) * cornerFraction,
                    rectangleCornerPaint
                )
                canvas.drawLine(
                    corner.x, corner.y,
                    corner.x + (next.x - corner.x) * cornerFraction,
                    corner.y + (next.y - corner.y) * cornerFraction,
                    rectangleCornerPaint
                )
            }

            // Dimension labels on each side + area at center
            val sidesText = rectangle.sides.map { side ->
                val cm = (side * 100).toInt()
                if (cm > 100) String.format("%.1f m", side) else "$cm cm"
            }
            for (i in 0 until 4) {
                val mid3d = Position(
                    (rectangle.corners[i].x + rectangle.corners[(i + 1) % 4].x) / 2f,
                    (rectangle.corners[i].y + rectangle.corners[(i + 1) % 4].y) / 2f,
                    (rectangle.corners[i].z + rectangle.corners[(i + 1) % 4].z) / 2f
                )
                drawLabel(canvas, mid3d, sidesText[i], useYellowBackground = true)
            }

            val center3D = Position(
                rectangle.corners.map { it.x }.average().toFloat(),
                rectangle.corners.map { it.y }.average().toFloat(),
                rectangle.corners.map { it.z }.average().toFloat()
            )
            drawLabel(canvas, center3D, String.format("%.2f m²", rectangle.area), useYellowBackground = true)
        }
    }

    private fun length(v: Position): Float =
        kotlin.math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
