package com.example.measureapp.ar

import android.content.Context
import android.graphics.Color
import com.google.ar.core.Anchor
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
    private val lineNodes = mutableListOf<AnchorNode>()
    private val distances = mutableListOf<Float>()

    fun addPoint(anchor: Anchor) {
        anchors.add(anchor)

        // Create a visual node for the anchor (Sphere)
        val anchorNode = AnchorNode(sceneView.engine, anchor)
        (sceneView.childNodes as MutableList<io.github.sceneview.node.Node>).add(anchorNode)
        nodes.add(anchorNode)

        // Visual representation of the point
        SphereNode(
            engine = sceneView.engine,
            radius = 0.01f, // 1cm radius
            materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE)
        ).apply {
            parent = anchorNode
        }

        // If we have at least 2 points, draw a line between the last two
        if (anchors.size >= 2) {
            val prevAnchor = anchors[anchors.size - 2]
            val currentAnchor = anchors[anchors.size - 1]
            drawLine(prevAnchor, currentAnchor)
        } else {
            onMeasurementChanged("Start Point Placed")
        }
    }

    private fun drawLine(anchor1: Anchor, anchor2: Anchor) {
        val pose1 = anchor1.pose
        val pose2 = anchor2.pose

        val point1 = Position(pose1.tx(), pose1.ty(), pose1.tz())
        val point2 = Position(pose2.tx(), pose2.ty(), pose2.tz())

        // Calculate distance
        val distance = calculateDistance(pose1, pose2)
        distances.add(distance)
        
        updateMeasurementText()

        // Create a node that will hold the line. 
        val lineNode = AnchorNode(sceneView.engine, anchor1)
        (sceneView.childNodes as MutableList<io.github.sceneview.node.Node>).add(lineNode)
        lineNodes.add(lineNode)

        // We need to calculate the local position of point2 relative to point1
        val difference = point2 - point1
        val direction = normalize(difference)
        
        // Rotation to align Y axis (default cylinder axis) with the direction vector
        val up = Float3(0f, 1f, 0f)
        val rotationAxis = normalize(cross(up, direction))
        val rotationAngle = acos(dot(up, direction))
        
        val rotation = Quaternion.fromAxisAngle(rotationAxis, Math.toDegrees(rotationAngle.toDouble()).toFloat())

        // Create the cylinder
        CylinderNode(
            engine = sceneView.engine,
            radius = 0.005f, // 5mm thickness
            height = distance,
            materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE)
        ).apply {
            parent = lineNode
            position = difference * 0.5f
            this.quaternion = rotation
        }
    }

    private fun updateMeasurementText() {
        val sb = StringBuilder()
        var total = 0f
        
        for (i in distances.indices) {
            val d = distances[i] * 100 // cm
            total += d
            if (i > 0) sb.append(" + ")
            sb.append(String.format("%.1f", d))
        }
        
        if (distances.size > 1) {
            sb.append(" = ").append(String.format("%.1f", total)).append(" cm")
        } else if (distances.isNotEmpty()) {
             sb.append(" cm")
        }
        
        onMeasurementChanged(sb.toString())
    }

    fun clear() {
        val children = sceneView.childNodes as MutableList<io.github.sceneview.node.Node>
        for (node in nodes) {
            node.destroy()
            children.remove(node)
        }
        for (node in lineNodes) {
            node.destroy()
            children.remove(node)
        }
        for (anchor in anchors) {
            anchor.detach()
        }
        nodes.clear()
        lineNodes.clear()
        anchors.clear()
        distances.clear()
    }

    private fun calculateDistance(pose1: Pose, pose2: Pose): Float {
        val dx = pose1.tx() - pose2.tx()
        val dy = pose1.ty() - pose2.ty()
        val dz = pose1.tz() - pose2.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // Math helpers
    private fun cross(a: Float3, b: Float3): Float3 {
        return Float3(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x
        )
    }
    
    private fun dot(a: Float3, b: Float3): Float {
        return a.x * b.x + a.y * b.y + a.z * b.z
    }
}
