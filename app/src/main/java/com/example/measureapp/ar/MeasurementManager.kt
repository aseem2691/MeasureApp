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

class MeasurementManager(
    private val context: Context,
    private val sceneView: ARSceneView,
    private val onMeasurementChanged: (String) -> Unit
) {
    data class MeasurementLabel(val position: Position, val distance: Float)
    data class MeasurementChain(val segments: MutableList<Float> = mutableListOf())
    
    private val anchors = mutableListOf<Anchor>()
    private val nodes = mutableListOf<AnchorNode>()
    private val cornerNodes = mutableListOf<AnchorNode>() // Track corner nodes for snapping
    private val segmentDistances = mutableListOf<Float>() // Store each segment distance
    val labels = mutableListOf<MeasurementLabel>() // 3D positions for labels
    private val measurementChains = mutableListOf<MeasurementChain>() // Track separate measurement chains
    private var currentChain = MeasurementChain()
    private var snappedAnchor: Anchor? = null // Track if we're snapped to an existing anchor

    // Rubber Banding State
    private var tempLineNode: CylinderNode? = null
    private var lastAnchor: Anchor? = null
    var currentLivePosition: Position? = null // For live label
    var currentLiveDistance: Float = 0f
    private var isMeasuring = true // Track if we're actively measuring
    var hasStartedMeasurement = false // Track if user has placed at least one point

    // Call this every frame from MeasureActivity
    fun onUpdate(hitResult: HitResult?) {
        if (!isMeasuring) return // Stop updating if measurement is done
        val startAnchor = lastAnchor ?: return
        
        // If we have a start point and a valid hit, stretch the line
        if (hitResult != null) {
            val startPose = startAnchor.pose
            var endPose = hitResult.hitPose
            
            // Check for corner snapping
            val nearbyCorner = findNearestCorner(endPose)
            if (nearbyCorner != null) {
                // Snap to existing corner
                snappedAnchor = nearbyCorner.anchor
                endPose = nearbyCorner.anchor.pose
                highlightNode(nearbyCorner, true)
            } else {
                snappedAnchor = null
                resetHighlights()
            }
            
            // Calculate distance for UI immediately
            val distance = calculateDistance(startPose, endPose)
            currentLiveDistance = distance
            val statusText = if (snappedAnchor != null) {
                "${formatDistance(distance)} [Snapped]"
            } else {
                formatDistance(distance)
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
            snappedAnchor = null
            resetHighlights()
        }
    }

    fun addPoint(anchor: Anchor, isExistingAnchor: Boolean = false) {
        // Use snapped anchor if we detected one during onUpdate
        val finalAnchor = snappedAnchor ?: anchor
        val shouldRenderSphere = !isExistingAnchor && snappedAnchor == null
        
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
                radius = 0.015f, // 1.5cm
                materialInstance = sceneView.materialLoader.createColorInstance(Color.RED)
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
        
        // Reset snapped state after adding point
        snappedAnchor = null
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
                radius = 0.008f,
                height = 1.0f,
                materialInstance = yellowMaterial
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
            radius = 0.01f, // Increased from 0.005f for better visibility
            height = 1.0f,
            materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE) // Permanent line is White
        ).apply {
            position = point1 + (difference * 0.5f)
            scale = Float3(1.0f, distance, 1.0f)
            quaternion = calculateRotation(difference)
            isShadowCaster = false
            isShadowReceiver = false
        }
        sceneView.addChildNode(lineNode)
        
        // Store segment distance and label position
        segmentDistances.add(distance)
        currentChain.segments.add(distance)
        val midpoint = point1 + (difference * 0.5f)
        labels.add(MeasurementLabel(midpoint, distance))
        
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
        
        // Clear temp line
        tempLineNode?.let {
            sceneView.removeChildNode(it)
            it.destroy()
        }
        tempLineNode = null
        lastAnchor = null
        segmentDistances.clear()
        labels.clear()
        currentLivePosition = null
        measurementChains.clear()
        currentChain = MeasurementChain()
        isMeasuring = true
        hasStartedMeasurement = false
        snappedAnchor = null
        
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
    
    // --- Advanced Snapping Engine ---
    
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
            val closest = closestPointOnSegment(a, b, point)
            val distance = length(closest - point)
            
            if (distance < minDistance && distance < snapThreshold) {
                minDistance = distance
                nearestPoint = closest
            }
        }
        
        return nearestPoint
    }
    
    /**
     * Find the closest point on a line segment (A-B) to point P
     */
    private fun closestPointOnSegment(a: Position, b: Position, p: Position): Position {
        val ab = b - a
        val ap = p - a
        val abLengthSq = dotPos(ab, ab)
        
        if (abLengthSq == 0f) return a // A and B are the same point
        
        var t = dotPos(ap, ab) / abLengthSq
        t = t.coerceIn(0.0f, 1.0f)
        
        return a + (ab * t)
    }
    
    private fun dotPos(a: Position, b: Position): Float {
        return a.x * b.x + a.y * b.y + a.z * b.z
    }
}
