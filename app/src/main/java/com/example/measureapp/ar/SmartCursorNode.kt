package com.example.measureapp.ar

import android.graphics.Color
import com.google.ar.core.Pose
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.Node
import kotlin.math.sqrt

/**
 * Smart 3D Reticle that adapts to surface orientation and snaps to edges/points
 */
class SmartCursorNode(
    private val sceneView: ARSceneView
) : Node(sceneView.engine) {
    
    enum class CursorState {
        SEARCHING,      // Red/Faded - No surface found
        TRACKING,       // White - Normal tracking
        SNAPPED         // Green - Snapped to edge/point
    }
    
    private var outerRing: CylinderNode? = null
    private var innerDot: SphereNode? = null
    private var currentState = CursorState.SEARCHING
    
    // Smoothing parameters
    private var targetPosition: Position = Position(0f, 0f, 0f)
    private var targetRotation: Quaternion = Quaternion()
    private val lerpFactor = 0.15f // Reduced for much smoother tracking
    
    init {
        createReticle()
    }
    
    private fun createReticle() {
        // Outer ring - 3cm radius, 2mm height, rotated to lie flat
        outerRing = CylinderNode(
            engine = sceneView.engine,
            radius = 0.03f,
            height = 0.002f,
            materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE, 1f)
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            isVisible = true
            // Rotate 90° around X axis so cylinder lies flat (default is vertical)
            quaternion = Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 90f * kotlin.math.PI.toFloat() / 180f)
            parent = this@SmartCursorNode
        }
        
        // Inner dot - smaller sphere for center point
        innerDot = SphereNode(
            engine = sceneView.engine,
            radius = 0.008f,  // 0.8cm diameter sphere
            materialInstance = sceneView.materialLoader.createColorInstance(Color.RED, 1f)
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            isVisible = true
            parent = this@SmartCursorNode
        }
        
        // Make sure the node itself is visible
        isVisible = true
        android.util.Log.d("SmartCursor", "Reticle created: ring (flat) + sphere (center)")
    }
    
    /**
     * Update the cursor with new position and orientation
     * @param pose The target pose from hit testing
     * @param isSnapped Whether the cursor is snapped to an edge/point
     */
    fun updateCursor(pose: Pose?, isSnapped: Boolean = false) {
        if (pose == null) {
            setState(CursorState.SEARCHING)
            isVisible = false
            return
        }
        
        isVisible = true
        setState(if (isSnapped) CursorState.SNAPPED else CursorState.TRACKING)
        
        // Set target for smooth interpolation
        targetPosition = Position(pose.tx(), pose.ty(), pose.tz())
        targetRotation = Quaternion(pose.qx(), pose.qy(), pose.qz(), pose.qw())
    }
    
    /**
     * Call this every frame to smoothly interpolate to target
     */
    fun smoothUpdate() {
        if (!isVisible) return
        
        // Lerp position
        position = lerp(position, targetPosition, lerpFactor)
        
        // Slerp rotation (spherical linear interpolation for smooth rotation)
        quaternion = slerp(quaternion, targetRotation, lerpFactor)
    }
    
    private fun setState(state: CursorState) {
        if (currentState == state) return
        currentState = state
        
        val color = when (state) {
            CursorState.SEARCHING -> Color.argb(200, 255, 0, 0) // Translucent red
            CursorState.TRACKING -> Color.WHITE
            CursorState.SNAPPED -> Color.rgb(0, 255, 0) // Bright green
        }
        
        // Update both ring and dot colors
        outerRing?.materialInstance = sceneView.materialLoader.createColorInstance(color, 1f)
        innerDot?.materialInstance = sceneView.materialLoader.createColorInstance(color, 1f)
        
        // Pulse effect when snapped - scale uniformly
        val scaleValue = if (state == CursorState.SNAPPED) 1.2f else 1.0f
        outerRing?.scale = Float3(scaleValue, scaleValue, scaleValue)
        innerDot?.scale = Float3(scaleValue, scaleValue, scaleValue)
    }
    
    // Linear interpolation for position
    private fun lerp(start: Position, end: Position, factor: Float): Position {
        return start + (end - start) * factor
    }
    
    // Spherical linear interpolation for rotation
    private fun slerp(start: Quaternion, end: Quaternion, factor: Float): Quaternion {
        var dot = start.x * end.x + start.y * end.y + start.z * end.z + start.w * end.w
        
        // If negative dot, negate one quaternion to take shorter path
        val end2 = if (dot < 0.0f) {
            dot = -dot
            Quaternion(-end.x, -end.y, -end.z, -end.w)
        } else {
            end
        }
        
        // If very close, just lerp
        if (dot > 0.9995f) {
            val result = Quaternion(
                start.x + factor * (end2.x - start.x),
                start.y + factor * (end2.y - start.y),
                start.z + factor * (end2.z - start.z),
                start.w + factor * (end2.w - start.w)
            )
            return normalizeQuaternion(result)
        }
        
        // Actual slerp
        val theta0 = kotlin.math.acos(dot)
        val theta = theta0 * factor
        val sinTheta = kotlin.math.sin(theta)
        val sinTheta0 = kotlin.math.sin(theta0)
        
        val s0 = kotlin.math.cos(theta) - dot * sinTheta / sinTheta0
        val s1 = sinTheta / sinTheta0
        
        return Quaternion(
            s0 * start.x + s1 * end2.x,
            s0 * start.y + s1 * end2.y,
            s0 * start.z + s1 * end2.z,
            s0 * start.w + s1 * end2.w
        )
    }
    
    private fun normalizeQuaternion(q: Quaternion): Quaternion {
        val len = kotlin.math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
        return if (len > 0) Quaternion(q.x / len, q.y / len, q.z / len, q.w / len) else q
    }
    
    fun testVisibility() {
        android.util.Log.d("SmartCursor", "Test: Node visible=$isVisible, outerRing=${outerRing?.isVisible}, innerDot=${innerDot?.isVisible}")
        android.util.Log.d("SmartCursor", "Position: $position, Quaternion: $quaternion")
    }
    
    override fun destroy() {
        outerRing?.destroy()
        innerDot?.destroy()
        super.destroy()
    }
}

/**
 * Data class representing the cursor state with snapping info
 */
data class CursorState(
    val position: Position,
    val rotation: Quaternion,
    val isSnapped: Boolean,
    val snapType: SnapType
)

enum class SnapType {
    NONE,           // Just tracking surface
    VERTEX,         // Snapped to existing measurement point
    EDGE,           // Snapped to plane edge
    DEPTH_EDGE      // Snapped to depth discontinuity
}
