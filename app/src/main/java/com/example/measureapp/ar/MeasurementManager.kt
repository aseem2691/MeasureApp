package com.example.measureapp.ar

import android.content.Context
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Pose
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import dev.romainguy.kotlin.math.dot
import com.example.measureapp.data.models.UnitType
import kotlin.math.sqrt

/**
 * SmartHit - Represents the result of intelligent hit testing with snapping
 */
sealed class SmartHit {
    /** What an edge snap locked onto, so the UI can say so */
    enum class EdgeSource { LINE, RECTANGLE, PHYSICAL }

    object None : SmartHit()
    data class Surface(val hitPose: Pose) : SmartHit()
    data class SnappedVertex(val hitPosition: Position, val anchor: Anchor) : SmartHit()
    data class SnappedEdge(
        val hitPosition: Position,
        val source: EdgeSource = EdgeSource.LINE
    ) : SmartHit()

    fun getPose(): Pose? = when (this) {
        is None -> null
        is Surface -> hitPose
        is SnappedVertex -> Pose(
            floatArrayOf(hitPosition.x, hitPosition.y, hitPosition.z),
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        is SnappedEdge -> Pose(
            floatArrayOf(hitPosition.x, hitPosition.y, hitPosition.z),
            floatArrayOf(0f, 0f, 0f, 1f)
        )
    }

    fun getPosition(): Position? = when (this) {
        is None -> null
        is Surface -> Position(hitPose.tx(), hitPose.ty(), hitPose.tz())
        is SnappedVertex -> hitPosition
        is SnappedEdge -> hitPosition
    }

    fun isSnapped(): Boolean = this is SnappedVertex || this is SnappedEdge
}

/**
 * Owns measurement state: anchors, segments, chains, snapping.
 *
 * Rendering model: lines and labels are NOT 3D nodes. [renderSegments] holds anchor
 * pairs; [OverlayView] projects the live anchor poses to screen space every frame and
 * draws thin 2D lines + pills. This keeps lines crisp at any distance, automatically
 * benefits from ARCore anchor refinement, and removes the index-mismatch ghost-line
 * bugs the old CylinderNode + refreshLines() approach had. Only the small corner dots
 * are real 3D nodes (for depth realism).
 */
class MeasurementManager(
    private val context: Context,
    private val sceneView: ARSceneView,
    private val onMeasurementChanged: (String) -> Unit
) {
    /**
     * LINE measures point-to-point on surfaces. HEIGHT measures vertically: first
     * tap places a base point, then the live point is derived from the camera ray
     * geometry — no depth or plane needed for the top point (works even when the
     * Depth API is broken). AREA places corners like LINE; finishing closes the
     * polygon and reports area + perimeter.
     */
    enum class MeasureMode { LINE, HEIGHT, AREA }

    /** A closed polygon; the area pill tracks the live centroid of its anchors */
    data class AreaBadge(val anchors: List<Anchor>, val areaSquareMeters: Float)

    data class MeasurementChain(val segments: MutableList<Float> = mutableListOf())
    data class LineSegment(val start: Position, val end: Position)
    data class CompletedMeasurement(
        val totalMeters: Float,
        val segments: List<Float>,
        val points: List<Position>,
        val areaSquareMeters: Float? = null
    )

    /** A finished measurement line; endpoints follow their anchors as ARCore refines them */
    data class RenderSegment(val start: Anchor, val end: Anchor) {
        fun startPosition() = Position(start.pose.tx(), start.pose.ty(), start.pose.tz())
        fun endPosition() = Position(end.pose.tx(), end.pose.ty(), end.pose.tz())
    }

    private val anchors = mutableListOf<Anchor>()
    private val cornerNodes = mutableListOf<AnchorNode>() // 3D dots + vertex snap targets
    private val pointHadNode = mutableListOf<Boolean>()   // whether each placed point created a node
    val renderSegments = mutableListOf<RenderSegment>()   // all lines, across chains
    private val segmentDistances = mutableListOf<Float>()
    private val measurementChains = mutableListOf<MeasurementChain>()
    private var currentChain = MeasurementChain()
    private val currentChainPositions = mutableListOf<Position>()
    private val currentChainAnchors = mutableListOf<Anchor>()
    val areaBadges = mutableListOf<AreaBadge>()
    private var currentSmartHit: SmartHit = SmartHit.None

    private var lastAnchor: Anchor? = null
    var currentLivePosition: Position? = null // Midpoint of the live rubber-band segment
    var currentLiveDistance: Float = 0f
    private var isMeasuring = true
    var hasStartedMeasurement = false

    /** Detected rectangle whose corners/edges act as additional snap targets */
    var rectangleSnapTargets: DetectedRectangle? = null

    /** Nearest physical edge from the depth image (set per frame by the activity) */
    var depthEdgeSnapPosition: Position? = null

    var measureMode: MeasureMode = MeasureMode.LINE
        set(value) {
            field = value
            heightBasePosition = null
        }
    private var heightBasePosition: Position? = null

    /** True once a height base is placed and we're tracking the vertical point */
    fun isHeightMeasureActive(): Boolean =
        measureMode == MeasureMode.HEIGHT && heightBasePosition != null

    // Unit preference for formatting
    var unitType: UnitType = UnitType.METRIC

    // Adaptive distance smoothing for consistent measurements
    private var smoothedDistance: Float = 0f
    private var lastDisplayedDistance: Float = 0f

    // Recent raw Surface hit positions; a tap places the median of a tight cluster
    // instead of the single-frame position, cancelling tap-moment jitter
    private val recentSurfacePositions = ArrayDeque<Position>()
    private val SURFACE_SAMPLE_WINDOW = 6
    private val SURFACE_STABLE_SPREAD = 0.02f // 2cm

    // Snapping thresholds - iOS precision levels
    private val VERTEX_SNAP_DISTANCE = 0.035f     // 3.5cm vertex snapping
    private val EDGE_SNAP_DISTANCE = 0.03f        // 3cm edge snapping
    private val DEPTH_EDGE_SNAP_DISTANCE = 0.04f  // 4cm physical-edge snapping

    /**
     * Perform intelligent hit testing with vertex and edge snapping.
     * Priority: own vertices > rectangle corners > own edges > rectangle edges > surface.
     */
    fun performSmartHitTest(rawHit: HitResult?): SmartHit {
        if (rawHit == null) return SmartHit.None

        val rawPose = rawHit.hitPose
        val rawPos = Position(rawPose.tx(), rawPose.ty(), rawPose.tz())

        // 1. Vertex snapping to any previously placed corner (persists across chains)
        for (node in cornerNodes) {
            val anchor = node.anchor ?: continue
            val nodePos = Position(anchor.pose.tx(), anchor.pose.ty(), anchor.pose.tz())
            if (length(rawPos - nodePos) < VERTEX_SNAP_DISTANCE) {
                return SmartHit.SnappedVertex(nodePos, anchor)
            }
        }

        // 2. Detected rectangle corners (auto-detected object corners)
        rectangleSnapTargets?.let { rect ->
            for (corner in rect.corners) {
                if (length(rawPos - corner) < VERTEX_SNAP_DISTANCE) {
                    return SmartHit.SnappedEdge(corner, SmartHit.EdgeSource.RECTANGLE)
                }
            }
        }

        // 3. Edge snapping to existing measurement lines
        for (segment in renderSegments) {
            val start = segment.startPosition()
            val end = segment.endPosition()
            val projected = projectPointOnSegment(rawPos, start, end)
            if (length(rawPos - projected) < EDGE_SNAP_DISTANCE) {
                return SmartHit.SnappedEdge(projected, SmartHit.EdgeSource.LINE)
            }
        }

        // 4. Detected rectangle edges (snap along object outlines)
        rectangleSnapTargets?.let { rect ->
            for (i in rect.corners.indices) {
                val start = rect.corners[i]
                val end = rect.corners[(i + 1) % rect.corners.size]
                val projected = projectPointOnSegment(rawPos, start, end)
                if (length(rawPos - projected) < EDGE_SNAP_DISTANCE) {
                    return SmartHit.SnappedEdge(projected, SmartHit.EdgeSource.RECTANGLE)
                }
            }
        }

        // 5. Physical edges from the depth image (iOS-style edge guides)
        depthEdgeSnapPosition?.let { edge ->
            if (length(rawPos - edge) < DEPTH_EDGE_SNAP_DISTANCE) {
                return SmartHit.SnappedEdge(edge, SmartHit.EdgeSource.PHYSICAL)
            }
        }

        // 6. Normal surface tracking
        return SmartHit.Surface(rawPose)
    }

    /**
     * Call this every frame from MeasureActivity.
     * [rayOrigin]/[rayDirection] describe the screen-center camera ray (used by HEIGHT mode).
     */
    fun onUpdate(hitResult: HitResult?, rayOrigin: Position? = null, rayDirection: Position? = null) {
        if (!isMeasuring) return

        // HEIGHT mode with a placed base: track the vertical axis instead of surfaces
        if (isHeightMeasureActive()) {
            if (rayOrigin != null && rayDirection != null) {
                updateHeightMeasure(rayOrigin, rayDirection)
            }
            return
        }

        // ALWAYS perform smart hit testing so reticle works before first point
        currentSmartHit = performSmartHitTest(hitResult)

        // Track raw surface positions for tap-time averaging
        val hit = currentSmartHit
        if (hit is SmartHit.Surface) {
            recentSurfacePositions.addLast(
                Position(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
            )
            while (recentSurfacePositions.size > SURFACE_SAMPLE_WINDOW) {
                recentSurfacePositions.removeFirst()
            }
        } else {
            recentSurfacePositions.clear()
        }

        // If no start point yet, just update currentSmartHit and return
        val startAnchor = lastAnchor ?: return

        val endPose = currentSmartHit.getPose()
        if (endPose != null) {
            val startPose = startAnchor.pose

            // Calculate distance for UI immediately with smoothing to reduce jitter
            val distance = calculateDistance(startPose, endPose)
            val delta = kotlin.math.abs(distance - smoothedDistance)
            val factor = when {
                smoothedDistance == 0f -> 1.0f
                delta > 0.05f -> 0.5f
                delta < 0.01f -> 0.15f
                else -> 0.3f
            }
            smoothedDistance = smoothedDistance + (distance - smoothedDistance) * factor
            currentLiveDistance = smoothedDistance

            // Only update text if change > 1mm to prevent display jitter
            val displayDistance = if (kotlin.math.abs(smoothedDistance - lastDisplayedDistance) < 0.001f) {
                lastDisplayedDistance
            } else {
                lastDisplayedDistance = smoothedDistance
                smoothedDistance
            }

            val statusText = when (val hit = currentSmartHit) {
                is SmartHit.SnappedVertex -> "${formatDistance(displayDistance)} [Point]"
                is SmartHit.SnappedEdge -> when (hit.source) {
                    SmartHit.EdgeSource.LINE -> "${formatDistance(displayDistance)} [Line]"
                    SmartHit.EdgeSource.RECTANGLE -> "${formatDistance(displayDistance)} [Rect]"
                    SmartHit.EdgeSource.PHYSICAL -> "${formatDistance(displayDistance)} [Edge]"
                }
                else -> formatDistance(displayDistance)
            }
            onMeasurementChanged(statusText)

            // Midpoint for the live label
            val point1 = Position(startPose.tx(), startPose.ty(), startPose.tz())
            val point2 = Position(endPose.tx(), endPose.ty(), endPose.tz())
            currentLivePosition = point1 + ((point2 - point1) * 0.5f)
        } else {
            currentLivePosition = null
            currentSmartHit = SmartHit.None
        }
    }

    /**
     * Height tracking: intersect the camera ray with the VERTICAL PLANE through the
     * base point that faces the camera (normal = horizontal camera forward).
     *
     * A closest-point-to-axis approach breaks badly when the aimed top edge isn't
     * exactly above the base (steep rays graze the axis at wild heights — 2m
     * readings on a 0.5m table). The plane intersection keeps the measured point
     * at the base's distance, so the height is the ray's elevation at the object —
     * accurate as long as the user faces the object, and errors stay bounded.
     */
    private fun updateHeightMeasure(origin: Position, direction: Position) {
        val base = heightBasePosition ?: return

        val horizontal = sqrt(direction.x * direction.x + direction.z * direction.z)
        if (horizontal < 1e-3f) return // looking straight up/down
        val normal = Position(direction.x / horizontal, 0f, direction.z / horizontal)

        val denom = dot(direction, normal)
        if (kotlin.math.abs(denom) < 1e-4f) return
        val t = dot(base - origin, normal) / denom
        if (t < 0.05f || t > 15f) return

        val hitY = origin.y + direction.y * t
        val rawHeight = hitY - base.y
        val clampedHeight = rawHeight.coerceIn(-1f, 8f)
        val heightPoint = Position(base.x, base.y + clampedHeight, base.z)
        val heightMeters = kotlin.math.abs(clampedHeight)

        currentSmartHit = SmartHit.Surface(
            Pose(
                floatArrayOf(heightPoint.x, heightPoint.y, heightPoint.z),
                floatArrayOf(0f, 0f, 0f, 1f)
            )
        )
        currentLiveDistance = heightMeters
        currentLivePosition = base + ((heightPoint - base) * 0.5f)
        onMeasurementChanged("${formatDistance(heightMeters)} [Height]")
    }

    fun getCurrentSmartHit(): SmartHit = currentSmartHit

    /**
     * Median of the recent Surface hit positions when they form a tight cluster —
     * a steadier placement than the single-frame hit — or null when unstable.
     */
    fun stableSurfacePose(): Pose? {
        if (recentSurfacePositions.size < 4) return null
        val xs = recentSurfacePositions.map { it.x }.sorted()
        val ys = recentSurfacePositions.map { it.y }.sorted()
        val zs = recentSurfacePositions.map { it.z }.sorted()
        val spread = maxOf(
            xs.last() - xs.first(),
            ys.last() - ys.first(),
            zs.last() - zs.first()
        )
        if (spread > SURFACE_STABLE_SPREAD) return null
        val mid = recentSurfacePositions.size / 2
        return Pose(
            floatArrayOf(xs[mid], ys[mid], zs[mid]),
            floatArrayOf(0f, 0f, 0f, 1f)
        )
    }

    /** Start position of the live rubber-band line, or null when not measuring */
    fun activeStartPosition(): Position? = lastAnchor?.pose?.let {
        Position(it.tx(), it.ty(), it.tz())
    }

    fun addPoint(anchor: Anchor, isExistingAnchor: Boolean = false) {
        val finalAnchor = when (val hit = currentSmartHit) {
            is SmartHit.SnappedVertex -> hit.anchor
            else -> anchor
        }

        val shouldRenderSphere = !isExistingAnchor && currentSmartHit !is SmartHit.SnappedVertex

        anchors.add(finalAnchor)
        pointHadNode.add(shouldRenderSphere)
        currentChainAnchors.add(finalAnchor)
        hasStartedMeasurement = true
        finalAnchor.pose.let { p ->
            currentChainPositions.add(Position(p.tx(), p.ty(), p.tz()))
        }

        // Track the anchor as a snap target (corner markers themselves are drawn
        // as crisp 2D dots by OverlayView — 3D spheres pick up environment shading
        // and look like dirty beads)
        if (shouldRenderSphere) {
            val anchorNode = AnchorNode(sceneView.engine, finalAnchor)
            sceneView.addChildNode(anchorNode)
            cornerNodes.add(anchorNode)
        }

        if (lastAnchor != null) {
            commitSegment(lastAnchor!!, finalAnchor)
            lastAnchor = finalAnchor
        } else {
            lastAnchor = finalAnchor
            when (measureMode) {
                MeasureMode.HEIGHT -> {
                    heightBasePosition = finalAnchor.pose.let { Position(it.tx(), it.ty(), it.tz()) }
                    onMeasurementChanged("Aim above the base to measure height")
                }
                MeasureMode.AREA -> onMeasurementChanged("Add corners, then ✓ to close")
                MeasureMode.LINE -> onMeasurementChanged("Move to end point")
            }
        }

        currentSmartHit = SmartHit.None
    }

    private fun commitSegment(start: Anchor, end: Anchor) {
        val p1 = Position(start.pose.tx(), start.pose.ty(), start.pose.tz())
        val p2 = Position(end.pose.tx(), end.pose.ty(), end.pose.tz())
        val distance = length(p2 - p1)

        renderSegments.add(RenderSegment(start, end))
        segmentDistances.add(distance)
        currentChain.segments.add(distance)

        val total = currentChain.segments.sum()
        if (currentChain.segments.size == 1) {
            onMeasurementChanged(formatDistance(distance))
        } else {
            onMeasurementChanged("Total: ${formatDistance(total)} (${currentChain.segments.size} segments)")
        }
    }

    fun finishCurrentMeasurement(): CompletedMeasurement? {
        // AREA mode: close the polygon (last corner back to first) and compute area
        var areaSquareMeters: Float? = null
        if (measureMode == MeasureMode.AREA && currentChainAnchors.size >= 3) {
            val first = currentChainAnchors.first()
            val last = currentChainAnchors.last()
            if (first != last) {
                commitSegment(last, first)
            }
            areaSquareMeters = polygonArea(currentChainPositions)
            areaBadges.add(AreaBadge(currentChainAnchors.toList(), areaSquareMeters))
        }

        // Capture the finished chain so callers can persist it
        val completed = if (currentChain.segments.isNotEmpty()) {
            CompletedMeasurement(
                totalMeters = currentChain.segments.sum(),
                segments = currentChain.segments.toList(),
                points = currentChainPositions.toList(),
                areaSquareMeters = areaSquareMeters
            )
        } else null

        if (currentChain.segments.isNotEmpty()) {
            measurementChains.add(currentChain)
            currentChain = MeasurementChain()
        }
        currentChainPositions.clear()
        currentChainAnchors.clear()

        // Break the chain so the next + starts a NEW separate measurement.
        // Snap targets (cornerNodes, renderSegments) intentionally stay alive so
        // new measurements can magnet onto previous ones, iOS style.
        lastAnchor = null
        isMeasuring = true
        hasStartedMeasurement = false
        currentSmartHit = SmartHit.None
        smoothedDistance = 0f
        currentLivePosition = null
        heightBasePosition = null

        if (completed?.areaSquareMeters != null) {
            onMeasurementChanged(
                "Area: ${unitType.formatArea(completed.areaSquareMeters)}\nTap + for new"
            )
        } else if (completed != null) {
            onMeasurementChanged("Done: ${formatDistance(completed.totalMeters)}\nTap + for new")
        } else if (anchors.isNotEmpty()) {
            onMeasurementChanged("Tap + for new measurement")
        } else {
            onMeasurementChanged("Tap + to start")
        }

        return completed
    }

    /**
     * Undo the last placed point of the measurement currently in progress.
     */
    fun undo() {
        if (anchors.isEmpty() || !hasStartedMeasurement) return

        val removedAnchor = anchors.removeLastOrNull()
        val hadNode = pointHadNode.removeLastOrNull() ?: false

        if (hadNode) {
            cornerNodes.lastOrNull()?.let { node ->
                sceneView.removeChildNode(node)
                node.destroy()
            }
            cornerNodes.removeLastOrNull()
        }
        // Don't detach anchors still shared by earlier points (vertex snapping)
        if (removedAnchor != null && !anchors.contains(removedAnchor) && hadNode) {
            removedAnchor.detach()
        }

        if (currentChain.segments.isNotEmpty()) {
            renderSegments.removeLastOrNull()
            segmentDistances.removeLastOrNull()
            currentChain.segments.removeLastOrNull()
        }
        currentChainPositions.removeLastOrNull()
        currentChainAnchors.removeLastOrNull()

        lastAnchor = anchors.lastOrNull()
        smoothedDistance = 0f

        if (currentChainPositions.isEmpty()) {
            hasStartedMeasurement = false
            lastAnchor = null
            currentLivePosition = null
            heightBasePosition = null
            onMeasurementChanged("Point at surface and tap + to start")
        } else {
            val total = currentChain.segments.sum()
            if (total > 0f) {
                onMeasurementChanged("Total: ${formatDistance(total)}")
            } else {
                onMeasurementChanged("Tap + to continue")
            }
        }
    }

    fun stopMeasuring() {
        finishCurrentMeasurement()
    }

    fun clear() {
        anchors.forEach { it.detach() }
        anchors.clear()
        pointHadNode.clear()

        cornerNodes.forEach { node ->
            sceneView.removeChildNode(node)
            node.destroy()
        }
        cornerNodes.clear()

        renderSegments.clear()
        segmentDistances.clear()
        measurementChains.clear()
        currentChain = MeasurementChain()
        currentChainPositions.clear()
        currentChainAnchors.clear()
        areaBadges.clear()

        lastAnchor = null
        currentLivePosition = null
        isMeasuring = true
        hasStartedMeasurement = false
        currentSmartHit = SmartHit.None
        smoothedDistance = 0f
        heightBasePosition = null

        onMeasurementChanged("Point at surface and tap + to start")
    }

    fun getFormattedSummary(): String {
        if (currentChain.segments.isEmpty() && measurementChains.isEmpty()) return ""
        val parts = mutableListOf<String>()
        measurementChains.forEach { chain ->
            parts.add(formatDistance(chain.segments.sum()))
        }
        if (currentChain.segments.isNotEmpty()) {
            parts.add(formatDistance(currentChain.segments.sum()))
        }
        return if (parts.size == 1) parts[0] else parts.joinToString(" | ")
    }

    fun formatDistance(meters: Float): String = unitType.formatDistance(meters)

    // --- Math Helpers ---

    private fun calculateDistance(pose1: Pose, pose2: Pose): Float {
        val dx = pose1.tx() - pose2.tx()
        val dy = pose1.ty() - pose2.ty()
        val dz = pose1.tz() - pose2.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun length(v: Position): Float = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    private fun projectPointOnSegment(point: Position, start: Position, end: Position): Position {
        val segment = end - start
        val segmentLengthSq = dot(segment, segment)
        if (segmentLengthSq < 1e-8f) return start
        val t = (dot(point - start, segment) / segmentLengthSq).coerceIn(0f, 1f)
        return start + (segment * t)
    }

    /**
     * Area of a (near-planar) 3D polygon: half the magnitude of the summed cross
     * products (Newell's method) — exact for planar polygons, robust to the small
     * out-of-plane noise our plane-locked points have.
     */
    private fun polygonArea(points: List<Position>): Float {
        if (points.size < 3) return 0f
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            nx += p.y * q.z - p.z * q.y
            ny += p.z * q.x - p.x * q.z
            nz += p.x * q.y - p.y * q.x
        }
        return 0.5f * sqrt(nx * nx + ny * ny + nz * nz)
    }
}
