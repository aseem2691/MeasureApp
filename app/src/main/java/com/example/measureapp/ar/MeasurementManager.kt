package com.example.measureapp.ar

import android.content.Context
import android.graphics.Color
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.SphereNode
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.normalize
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.math.min

/**
 * SmartHit - Represents the result of intelligent hit testing with snapping
 */
sealed class SmartHit {
    object None : SmartHit()
    data class Surface(val hitPose: Pose) : SmartHit()
    data class SnappedVertex(val hitPosition: Position, val anchor: Anchor) : SmartHit()
    data class SnappedEdge(val hitPosition: Position) : SmartHit()
    
    fun getPose(): Pose? = when (this) {
        is None -> null
        is Surface -> hitPose
        is SnappedVertex -> {
            // Create pose from position with identity rotation
            Pose(
                floatArrayOf(hitPosition.x, hitPosition.y, hitPosition.z),
                floatArrayOf(0f, 0f, 0f, 1f)
            )
        }
        is SnappedEdge -> {
            // Create pose from position with identity rotation
            Pose(
                floatArrayOf(hitPosition.x, hitPosition.y, hitPosition.z),
                floatArrayOf(0f, 0f, 0f, 1f)
            )
        }
    }
    
    fun getPosition(): Position? = when (this) {
        is None -> null
        is Surface -> Position(hitPose.tx(), hitPose.ty(), hitPose.tz())
        is SnappedVertex -> hitPosition
        is SnappedEdge -> hitPosition
    }
    
    fun isSnapped(): Boolean = this is SnappedVertex || this is SnappedEdge
}

class MeasurementManager(
    private val context: Context,
    private val sceneView: ARSceneView,
    private val onMeasurementChanged: (String) -> Unit
) {
    data class MeasurementLabel(val position: Position, val distance: Float, val text: String)
    data class MeasurementChain(val segments: MutableList<Float> = mutableListOf())
    data class LineSegment(val start: Position, val end: Position)
    
    private val anchors = mutableListOf<Anchor>()
    private val nodes = mutableListOf<AnchorNode>()
    private val cornerNodes = mutableListOf<AnchorNode>() // Track corner nodes for snapping
    private val lineNodes = mutableListOf<CylinderNode>() // Track line nodes for drift fix
    private val segmentDistances = mutableListOf<Float>() // Store each segment distance
    private val lineSegments = mutableListOf<LineSegment>() // Track line segments for edge snapping
    val labels = mutableListOf<MeasurementLabel>() // 3D positions for labels
    private val measurementChains = mutableListOf<MeasurementChain>() // Track separate measurement chains
    private var currentChain = MeasurementChain()
    private var currentSmartHit: SmartHit = SmartHit.None

    // Rubber Banding State
    private var tempLineNode: CylinderNode? = null
    private var lastAnchor: Anchor? = null
    var currentLivePosition: Position? = null // For live label
    var currentLiveDistance: Float = 0f
    private var isMeasuring = true // Track if we're actively measuring
    var hasStartedMeasurement = false // Track if user has placed at least one point
    
    // Distance smoothing for consistent measurements
    private var smoothedDistance: Float = 0f
    private val distanceSmoothingFactor = 0.3f // Smooth distance changes
    
    // Snapping thresholds - Reduced for less aggressive auto-snap
    private val VERTEX_SNAP_DISTANCE = 0.05f // 5cm for vertex snapping (less aggressive)
    private val EDGE_SNAP_DISTANCE = 0.03f   // 3cm for edge snapping (more precise)

    /**
     * Perform intelligent hit testing with vertex and edge snapping
     * This is the core of the "Pro" experience
     */
    fun performSmartHitTest(rawHit: HitResult?): SmartHit {
        if (rawHit == null) return SmartHit.None
        
        val rawPose = rawHit.hitPose
        val rawPos = Position(rawPose.tx(), rawPose.ty(), rawPose.tz())
        
        // Only perform snapping if we have at least one point placed
        if (cornerNodes.isEmpty()) {
            return SmartHit.Surface(rawPose)
        }
        
        // Priority 1: Vertex Snapping (10cm threshold)
        for (node in cornerNodes) {
            if (node.anchor != null) {
                val nodePos = node.worldPosition
                val distance = length(rawPos - nodePos)
                
                if (distance < VERTEX_SNAP_DISTANCE) {
                    android.util.Log.d("SmartHit", "Snapped to VERTEX at distance $distance")
                    highlightNode(node, true)
                    return SmartHit.SnappedVertex(nodePos, node.anchor!!)
                }
            }
        }
        
        // Priority 2: Edge Snapping (5cm threshold)
        for (lineSegment in lineSegments) {
            val projectedPoint = projectPointOnSegment(rawPos, lineSegment.start, lineSegment.end)
            val distance = length(rawPos - projectedPoint)
            
            if (distance < EDGE_SNAP_DISTANCE) {
                android.util.Log.d("SmartHit", "Snapped to EDGE at distance $distance")
                resetHighlights()
                return SmartHit.SnappedEdge(projectedPoint)
            }
        }
        
        // Priority 3: Normal surface tracking
        resetHighlights()
        return SmartHit.Surface(rawPose)
    }
    
    /**
     * Call this every frame from MeasureActivity
     * Now uses SmartHit for intelligent snapping
     */
    fun onUpdate(hitResult: HitResult?) {
        if (!isMeasuring) return // Stop updating if measurement is done
        
        // ALWAYS perform smart hit testing so reticle works before first point
        currentSmartHit = performSmartHitTest(hitResult)
        
        // If no start point yet, just update currentSmartHit and return
        val startAnchor = lastAnchor ?: return
        
        // If we have a start point and a valid hit, stretch the line
        val endPose = currentSmartHit.getPose()
        if (endPose != null) {
            val startPose = startAnchor.pose
            
            // Calculate distance for UI immediately with smoothing to reduce jitter
            val distance = calculateDistance(startPose, endPose)
            
            // Smooth the distance to reduce inconsistency and jitter
            smoothedDistance = if (smoothedDistance == 0f) {
                distance // Initialize on first measurement
            } else {
                smoothedDistance + (distance - smoothedDistance) * distanceSmoothingFactor
            }
            currentLiveDistance = smoothedDistance
            
            val statusText = when (currentSmartHit) {
                is SmartHit.SnappedVertex -> "${formatDistance(smoothedDistance)} [Vertex]"
                is SmartHit.SnappedEdge -> "${formatDistance(smoothedDistance)} [Edge]"
                else -> formatDistance(smoothedDistance)
            }
            onMeasurementChanged(statusText)

            // Draw/Update the temporary line
            updateTemporaryLine(startPose, endPose)
            
            // Store midpoint for label rendering
            val point1 = Position(startPose.tx(), startPose.ty(), startPose.tz())
            val point2 = Position(endPose.tx(), endPose.ty(), endPose.tz())
            currentLivePosition = point1 + ((point2 - point1) * 0.5f)
        } else {
            // If we lost tracking, hide the temp line
            tempLineNode?.isVisible = false
            currentLivePosition = null
            currentSmartHit = SmartHit.None
            resetHighlights()
        }
        
        // CRITICAL: Refresh line positions from anchors every frame (Drift fix)
        refreshLines()
    }
    
    /**
     * Get the current smart hit for reticle visualization
     */
    fun getCurrentSmartHit(): SmartHit = currentSmartHit

    fun addPoint(anchor: Anchor, isExistingAnchor: Boolean = false) {
        // Determine final anchor based on current SmartHit
        val finalAnchor = when (val hit = currentSmartHit) {
            is SmartHit.SnappedVertex -> hit.anchor
            else -> anchor
        }
        
        val shouldRenderSphere = !isExistingAnchor && currentSmartHit !is SmartHit.SnappedVertex
        
        anchors.add(finalAnchor)
        hasStartedMeasurement = true
        
        // 1. Render the corner point (Sphere) only if it's a new anchor
        if (shouldRenderSphere) {
            val anchorNode = AnchorNode(sceneView.engine, finalAnchor)
            sceneView.addChildNode(anchorNode)
            nodes.add(anchorNode)
            cornerNodes.add(anchorNode) // Track for snapping
            
            SphereNode(
                engine = sceneView.engine,
                radius = 0.004f, // 4mm - ultra-tiny crisp dot (iOS style)
                materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE) // White like iOS
            ).apply {
                parent = anchorNode
            }
        }

        // 2. Handle Measurement Logic
        if (lastAnchor != null) {
            // We just finished a segment. Make the temp line permanent.
            val startPose = lastAnchor!!.pose
            val endPose = finalAnchor.pose
            createPermanentLine(startPose, endPose)
            
            // "Polyline" logic: The end of this line becomes the start of the next
            lastAnchor = finalAnchor
        } else {
            // This is the very first point
            lastAnchor = finalAnchor
            onMeasurementChanged("Move to end point")
        }
        
        // Reset smart hit state after adding point
        currentSmartHit = SmartHit.None
        resetHighlights()
    }

    private fun updateTemporaryLine(startPose: Pose, endPose: Pose) {
        val point1 = Position(startPose.tx(), startPose.ty(), startPose.tz())
        val point2 = Position(endPose.tx(), endPose.ty(), endPose.tz())
        val difference = point2 - point1
        val distance = length(difference)
        
        if (distance < 0.001f) return // Too short to render

        if (tempLineNode == null) {
            // Create bright yellow/gold material for visibility
            val yellowMaterial = sceneView.materialLoader.createColorInstance(
                android.graphics.Color.rgb(255, 215, 0) // Bright gold
            )
            tempLineNode = CylinderNode(
                engine = sceneView.engine,
                radius = 0.001f, // 1mm - ultra-thin guide line (iOS style)
                height = 1.0f,
                materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.6f) // Semi-transparent white
            ).apply {
                isShadowCaster = false
                isShadowReceiver = false
            }
            sceneView.addChildNode(tempLineNode!!)
        }

        tempLineNode?.apply {
            isVisible = true
            // Position at midpoint
            position = point1 + (difference * 0.5f)
            // Scale Y-axis (cylinder height) to match distance
            scale = Float3(1.0f, distance, 1.0f) 
            // Rotate cylinder to point from start to end
            quaternion = calculateRotation(difference)
        }
    }

    private fun createPermanentLine(startPose: Pose, endPose: Pose) {
        val point1 = Position(startPose.tx(), startPose.ty(), startPose.tz())
        val point2 = Position(endPose.tx(), endPose.ty(), endPose.tz())
        val difference = point2 - point1
        val distance = length(difference)

        val lineNode = CylinderNode(
            engine = sceneView.engine,
            radius = 0.0015f, // 1.5mm - laser-thin line (iOS AR Ruler style)
            height = 1.0f,
            materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE) // Pure white
        ).apply {
            position = point1 + (difference * 0.5f)
            scale = Float3(1.0f, distance, 1.0f)
            quaternion = calculateRotation(difference)
            isShadowCaster = false
            isShadowReceiver = false
        }
        sceneView.addChildNode(lineNode)
        
        // Store line node for drift fix refresh
        lineNodes.add(lineNode)
        
        // Store line segment for edge snapping
        lineSegments.add(LineSegment(point1, point2))
        
        // Store segment distance and label position
        segmentDistances.add(distance)
        currentChain.segments.add(distance)
        val midpoint = point1 + (difference * 0.5f)
        val distanceText = formatDistance(distance)
        labels.add(MeasurementLabel(midpoint, distance, distanceText))
        
        // Show current chain total
        val currentChainTotal = currentChain.segments.sum()
        if (currentChain.segments.size == 1) {
            onMeasurementChanged("${formatDistance(distance)}")
        } else {
            onMeasurementChanged("Total: ${formatDistance(currentChainTotal)} (${currentChain.segments.size} segments)")
        }
    }

    private fun findNearestCorner(hitPose: Pose): AnchorNode? {
        val snapDistance = 0.15f // Increased to 15cm for easier snapping
        val hitPos = Position(hitPose.tx(), hitPose.ty(), hitPose.tz())
        
        android.util.Log.d("MeasurementManager", "Checking ${cornerNodes.size} vertices for snapping to $hitPos")
        
        val nearest = cornerNodes
            .filter { it.anchor != null }
            .minByOrNull { node ->
                val nodePos = node.worldPosition
                length(nodePos - hitPos)
            }
        
        if (nearest != null) {
            val dist = length(nearest.worldPosition - hitPos)
            android.util.Log.d("MeasurementManager", "Nearest vertex at distance $dist")
            if (dist <= snapDistance) {
                return nearest
            }
        }
        
        return null
    }
    
    private fun highlightNode(node: AnchorNode, active: Boolean) {
        val sphere = node.childNodes.firstOrNull() as? SphereNode
        if (active) {
            sphere?.materialInstance = sceneView.materialLoader.createColorInstance(Color.GREEN)
            sphere?.scale = Float3(1.5f, 1.5f, 1.5f)
        } else {
            sphere?.materialInstance = sceneView.materialLoader.createColorInstance(Color.RED)
            sphere?.scale = Float3(1.0f, 1.0f, 1.0f)
        }
    }
    
    private fun resetHighlights() {
        cornerNodes.forEach { node ->
            val sphere = node.childNodes.firstOrNull() as? SphereNode
            sphere?.materialInstance = sceneView.materialLoader.createColorInstance(Color.RED)
            sphere?.scale = Float3(1.0f, 1.0f, 1.0f)
        }
    }

    fun getNearbyAnchor(hitPose: Pose): Anchor? {
        // Check if hit is within 5cm of any existing anchor
        val snapDistance = 0.05f // 5cm threshold
        
        for (anchor in anchors) {
            val anchorPose = anchor.pose
            val dx = hitPose.tx() - anchorPose.tx()
            val dy = hitPose.ty() - anchorPose.ty()
            val dz = hitPose.tz() - anchorPose.tz()
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            
            if (distance <= snapDistance) {
                return anchor // Return existing anchor for snapping
            }
        }
        return null
    }
    
    fun finishCurrentMeasurement() {
        // Save current chain and start new one
        if (currentChain.segments.isNotEmpty()) {
            measurementChains.add(currentChain)
            currentChain = MeasurementChain() // Start fresh chain
        }
        
        // Break the chain - allows starting a NEW separate measurement
        lastAnchor = null
        isMeasuring = true // Keep measuring mode ON for next measurement
        hasStartedMeasurement = false // Reset for next measurement
        
        // CRITICAL: Reset smart hit state to prevent connecting to old measurements
        currentSmartHit = SmartHit.None
        smoothedDistance = 0f // Reset smoothing
        
        // CRITICAL FIX: Clear corner nodes to prevent snapping to old measurement points
        cornerNodes.clear() // This prevents diagonal connections and new measurement connections
        
        // Remove the temporary line
        tempLineNode?.let {
            sceneView.removeChildNode(it)
            it.destroy()
        }
        tempLineNode = null
        currentLivePosition = null
        
        // Show summary of all measurement chains
        if (measurementChains.isNotEmpty()) {
            val summary = buildString {
                append("Measurements: ")
                measurementChains.forEachIndexed { index, chain ->
                    val chainTotal = chain.segments.sum()
                    append("${index + 1}: ${formatDistance(chainTotal)}")
                    if (index < measurementChains.size - 1) append(" | ")
                }
            }
            onMeasurementChanged("$summary\nTap + for new")
        } else {
            onMeasurementChanged("Tap + to start new measurement")
        }
    }
    
    fun undo() {
        if (anchors.isEmpty()) return
        
        // Remove last anchor
        anchors.lastOrNull()?.detach()
        anchors.removeLastOrNull()
        
        // Remove last node (includes line and sphere)
        nodes.lastOrNull()?.let { node ->
            sceneView.removeChildNode(node)
            node.destroy()
        }
        nodes.removeLastOrNull()
        
        // Remove last segment distance and label
        segmentDistances.removeLastOrNull()
        labels.removeLastOrNull()
        
        // Update lastAnchor
        lastAnchor = anchors.lastOrNull()
        
        if (anchors.isEmpty()) {
            hasStartedMeasurement = false
            onMeasurementChanged("Move to start")
        } else {
            onMeasurementChanged("Tap + to continue")
        }
    }
    
    fun stopMeasuring() {
        finishCurrentMeasurement()
    }
    
    /**
     * DRIFT FIX: Refresh line AND node positions from anchors every frame
     * ARCore continuously refines anchor positions as it learns the environment.
     * This method updates both line geometry AND corner sphere positions.
     */
    fun refreshLines() {
        if (cornerNodes.size < 2 || lineNodes.isEmpty()) return
        
        // Update each permanent line
        for (i in 0 until lineNodes.size) {
            // Line i connects cornerNode[i] to cornerNode[i+1]
            if (i + 1 < cornerNodes.size) {
                val anchor1 = cornerNodes[i].anchor
                val anchor2 = cornerNodes[i + 1].anchor
                
                if (anchor1 != null && anchor2 != null) {
                    // CRITICAL: Use fresh anchor poses, not worldPosition
                    val pose1 = anchor1.pose
                    val pose2 = anchor2.pose
                    val start = Position(pose1.tx(), pose1.ty(), pose1.tz())
                    val end = Position(pose2.tx(), pose2.ty(), pose2.tz())
                    
                    // IMPROVEMENT: Also update the AnchorNode positions
                    // This ensures corner spheres stay aligned with ARCore's refined pose
                    cornerNodes[i].position = start
                    cornerNodes[i + 1].position = end
                    
                    // Update line geometry
                    val lineNode = lineNodes[i]
                    val diff = end - start
                    val dist = length(diff)
                    
                    if (dist >= 0.001f) {
                        lineNode.isVisible = true
                        val mid = start + (diff * 0.5f)
                        lineNode.position = mid
                        lineNode.scale = Float3(1.0f, dist, 1.0f)
                        lineNode.quaternion = calculateRotation(diff)
                    } else {
                        lineNode.isVisible = false
                    }
                }
            }
        }
    }
    
    fun clear() {
        // Remove all anchors
        anchors.forEach { it.detach() }
        anchors.clear()
        
        // Remove all nodes from scene
        nodes.forEach { node ->
            sceneView.removeChildNode(node)
            node.destroy()
        }
        nodes.clear()
        cornerNodes.clear()
        
        // Clear line nodes
        lineNodes.forEach { sceneView.removeChildNode(it) }
        lineNodes.clear()
        
        // Clear temp line
        tempLineNode?.let {
            sceneView.removeChildNode(it)
            it.destroy()
        }
        tempLineNode = null
        lastAnchor = null
        segmentDistances.clear()
        lineSegments.clear()
        labels.clear()
        currentLivePosition = null
        measurementChains.clear()
        currentChain = MeasurementChain()
        cornerNodes.clear() // Clear snapping points
        isMeasuring = true
        hasStartedMeasurement = false
        currentSmartHit = SmartHit.None
        
        onMeasurementChanged("Point at surface and tap + to start")
    }

    // --- Math Helpers ---

    private fun calculateRotation(direction: Float3): Quaternion {
        // Default Cylinder points UP (Y-axis). We need to rotate Y to align with 'direction'
        val up = Float3(0f, 1f, 0f)
        val dirNormalized = normalize(direction)
        val rotationAxis = normalize(cross(up, dirNormalized))
        val rotationAngle = acos(dot(up, dirNormalized))
        return Quaternion.fromAxisAngle(rotationAxis, Math.toDegrees(rotationAngle.toDouble()).toFloat())
    }

    private fun calculateDistance(pose1: Pose, pose2: Pose): Float {
        val dx = pose1.tx() - pose2.tx()
        val dy = pose1.ty() - pose2.ty()
        val dz = pose1.tz() - pose2.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    private fun formatDistance(meters: Float): String {
        return if (meters >= 1.0f) {
            String.format("%.2f m", meters)
        } else {
            String.format("%.1f cm", meters * 100)
        }
    }

    private fun length(v: Float3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    private fun cross(a: Float3, b: Float3) = Float3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    private fun dot(a: Float3, b: Float3) = a.x * b.x + a.y * b.y + a.z * b.z
    
    /**
     * Project a point onto a line segment (with clamping to segment endpoints)
     * This is used for edge snapping
     */
    private fun projectPointOnSegment(point: Position, segmentStart: Position, segmentEnd: Position): Position {
        val ab = segmentEnd - segmentStart
        val ap = point - segmentStart
        
        val abLengthSq = dotPos(ab, ab)
        if (abLengthSq == 0f) return segmentStart // Degenerate segment
        
        // Calculate projection parameter (0 = at start, 1 = at end)
        var t = dotPos(ap, ab) / abLengthSq
        
        // Clamp to segment bounds
        t = t.coerceIn(0.0f, 1.0f)
        
        // Return the projected point
        return segmentStart + (ab * t)
    }
    
    private fun dotPos(a: Position, b: Position): Float {
        return a.x * b.x + a.y * b.y + a.z * b.z
    }
    
    // --- Advanced Snapping Engine (Legacy - kept for plane edge detection) ---
    
    /**
     * Compute snapped cursor state with edge and vertex detection
     */
    fun computeSmartCursorState(hitResult: HitResult?, allPlanes: Collection<Plane>): CursorState? {
        if (hitResult == null) return null
        
        val hitPose = hitResult.hitPose
        val hitPos = Position(hitPose.tx(), hitPose.ty(), hitPose.tz())
        
        android.util.Log.d("MeasurementManager", "Computing cursor state - trackable: ${hitResult.trackable::class.simpleName}")
        
        // Priority 1: Snap to existing vertices (measurement points)
        val nearbyVertex = findNearestCorner(hitPose)
        if (nearbyVertex != null) {
            android.util.Log.d("MeasurementManager", "SNAPPED TO VERTEX at ${nearbyVertex.worldPosition}")
            return CursorState(
                position = nearbyVertex.worldPosition,
                rotation = Quaternion(hitPose.qx(), hitPose.qy(), hitPose.qz(), hitPose.qw()),
                isSnapped = true,
                snapType = SnapType.VERTEX
            )
        }
        
        // Priority 2: Snap to plane edges
        // Find the closest plane to the hit point (works for DepthPoint and Plane hits)
        var closestPlane: Plane? = null
        var closestDistance = Float.MAX_VALUE
        
        for (plane in allPlanes) {
            if (plane.trackingState != com.google.ar.core.TrackingState.TRACKING) continue
            
            // Check if point is near this plane
            val centerPose = plane.centerPose
            val dx = hitPos.x - centerPose.tx()
            val dy = hitPos.y - centerPose.ty()
            val dz = hitPos.z - centerPose.tz()
            val distance = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)
            
            if (distance < closestDistance && distance < 0.3f) { // Within 30cm of plane center
                closestDistance = distance
                closestPlane = plane
            }
        }
        
        if (closestPlane != null) {
            android.util.Log.d("MeasurementManager", "Found nearby plane, checking edges...")
            val edgeSnap = findNearestEdge(closestPlane, hitPos)
            if (edgeSnap != null) {
                android.util.Log.d("MeasurementManager", "SNAPPED TO EDGE at $edgeSnap")
                return CursorState(
                    position = edgeSnap,
                    rotation = Quaternion(hitPose.qx(), hitPose.qy(), hitPose.qz(), hitPose.qw()),
                    isSnapped = true,
                    snapType = SnapType.EDGE
                )
            } else {
                android.util.Log.d("MeasurementManager", "No edge within snap threshold")
            }
        } else {
            android.util.Log.d("MeasurementManager", "No nearby planes found (have ${allPlanes.size} total planes)")
        }
        
        // No snapping - just return normal tracking
        return CursorState(
            position = hitPos,
            rotation = Quaternion(hitPose.qx(), hitPose.qy(), hitPose.qz(), hitPose.qw()),
            isSnapped = false,
            snapType = SnapType.NONE
        )
    }
    
    /**
     * Find nearest edge on a plane polygon within snap threshold
     */
    private fun findNearestEdge(plane: Plane, point: Position): Position? {
        val snapThreshold = 0.15f // Increased to 15cm for easier snapping
        val polygon = plane.polygon
        
        var nearestPoint: Position? = null
        var minDistance = Float.MAX_VALUE
        
        // Iterate through polygon edges (FloatBuffer with x,z pairs)
        val polySize = polygon.remaining() / 2 // Number of vertices
        android.util.Log.d("MeasurementManager", "Checking plane with $polySize vertices, point at ${point.x}, ${point.y}, ${point.z}")
        
        for (i in 0 until polySize) {
            val x1 = polygon.get(i * 2)
            val z1 = polygon.get(i * 2 + 1)
            
            // Next vertex (wrap around)
            val nextIndex = (i + 1) % polySize
            val x2 = polygon.get(nextIndex * 2)
            val z2 = polygon.get(nextIndex * 2 + 1)
            
            // Convert to world coordinates (polygon is in plane local space)
            val planePose = plane.centerPose
            val worldA = planePose.compose(Pose.makeTranslation(x1, 0f, z1))
            val worldB = planePose.compose(Pose.makeTranslation(x2, 0f, z2))
            
            val a = Position(worldA.tx(), worldA.ty(), worldA.tz())
            val b = Position(worldB.tx(), worldB.ty(), worldB.tz())
            
            // Find closest point on line segment
            val closest = projectPointOnSegment(point, a, b)
            val distance = length(closest - point)
            
            if (distance < minDistance && distance < snapThreshold) {
                minDistance = distance
                nearestPoint = closest
            }
        }
        
        return nearestPoint
    }
}

data class CursorState(
    val position: Position,
    val rotation: Quaternion,
    val isSnapped: Boolean,
    val snapType: SnapType
)

enum class SnapType {
    NONE, VERTEX, EDGE
}
