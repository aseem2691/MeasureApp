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
    private val anchors = mutableListOf<Anchor>()
    private val nodes = mutableListOf<AnchorNode>()

    // Rubber Banding State
    private var tempLineNode: CylinderNode? = null
    private var lastAnchor: Anchor? = null

    // Call this every frame from MeasureActivity
    fun onUpdate(hitResult: HitResult?) {
        val startAnchor = lastAnchor ?: return
        
        // If we have a start point and a valid hit, stretch the line
        if (hitResult != null) {
            val startPose = startAnchor.pose
            val endPose = hitResult.hitPose
            
            // Calculate distance for UI immediately
            val distance = calculateDistance(startPose, endPose)
            onMeasurementChanged(formatDistance(distance))

            // Draw/Update the temporary line
            updateTemporaryLine(startPose, endPose)
        } else {
            // If we lost tracking, hide the temp line
            tempLineNode?.isVisible = false
        }
    }

    fun addPoint(anchor: Anchor) {
        anchors.add(anchor)
        
        // 1. Render the corner point (Sphere)
        val anchorNode = AnchorNode(sceneView.engine, anchor)
        sceneView.addChildNode(anchorNode)
        nodes.add(anchorNode)
        
        SphereNode(
            engine = sceneView.engine,
            radius = 0.015f, // 1.5cm
            materialInstance = sceneView.materialLoader.createColorInstance(Color.RED)
        ).apply {
            parent = anchorNode
        }

        // 2. Handle Measurement Logic
        if (lastAnchor != null) {
            // We just finished a segment. Make the temp line permanent.
            val startPose = lastAnchor!!.pose
            val endPose = anchor.pose
            createPermanentLine(startPose, endPose)
            
            // "Polyline" logic: The end of this line becomes the start of the next
            lastAnchor = anchor
        } else {
            // This is the very first point
            lastAnchor = anchor
            onMeasurementChanged("Move to end point")
        }
    }

    private fun updateTemporaryLine(startPose: Pose, endPose: Pose) {
        val point1 = Position(startPose.tx(), startPose.ty(), startPose.tz())
        val point2 = Position(endPose.tx(), endPose.ty(), endPose.tz())
        val difference = point2 - point1
        val distance = length(difference)

        if (tempLineNode == null) {
            tempLineNode = CylinderNode(
                engine = sceneView.engine,
                radius = 0.005f,
                height = 1.0f,
                materialInstance = sceneView.materialLoader.createColorInstance(Color.YELLOW) // Temp line is Yellow
            ).apply {
                parent = null
            }
            sceneView.addChildNode(tempLineNode!!)
        }

        tempLineNode?.apply {
            isVisible = true
            // Position is midpoint
            position = point1 + (difference * 0.5f)
            // Scale Z to match distance
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
            radius = 0.005f,
            height = 1.0f,
            materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE) // Permanent line is White
        ).apply {
            position = point1 + (difference * 0.5f)
            scale = Float3(1.0f, distance, 1.0f)
            quaternion = calculateRotation(difference)
        }
        sceneView.addChildNode(lineNode)
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
        
        // Clear temp line
        tempLineNode?.let {
            sceneView.removeChildNode(it)
            it.destroy()
        }
        tempLineNode = null
        lastAnchor = null
        
        onMeasurementChanged("Tap + to start")
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
