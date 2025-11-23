package com.example.measureapp.ar

import android.content.Context
import android.graphics.Color
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
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

        if (tempLineNode == null) {
            tempLineNode = CylinderNode(
                engine = sceneView.engine,
                radius = 0.01f, // Increased from 0.005f for better visibility
                height = 1.0f,
                materialInstance = sceneView.materialLoader.createColorInstance(Color.YELLOW) // Temp line is Yellow
            ).apply {
                isShadowCaster = false
                isShadowReceiver = false
            }
            sceneView.addChildNode(tempLineNode!!)
        }

        tempLineNode?.apply {
            isVisible = true
            // Position is midpoint
            position = point1 + (difference * 0.5f)
            // Scale Y (height) to match distance
            scale = Float3(1.0f, distance, 1.0f) 
            // Rotate to look at point 2
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
        val snapDistance = 0.05f // 5cm threshold
        val hitPos = Position(hitPose.tx(), hitPose.ty(), hitPose.tz())
        
        return cornerNodes
            .filter { it.anchor != null }
            .minByOrNull { node ->
                val nodePos = node.worldPosition
                length(nodePos - hitPos)
            }
            ?.takeIf { node ->
                val nodePos = node.worldPosition
                length(nodePos - hitPos) <= snapDistance
            }
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
}
