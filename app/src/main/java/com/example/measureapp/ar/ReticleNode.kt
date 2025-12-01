package com.example.measureapp.ar

import android.graphics.Color
import com.google.ar.core.Pose
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.Node
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2

/**
 * Professional 3D Reticle (iOS AR Ruler style)
 * 
 * Visual Design:
 * - Outer Ring: Thin white cylinder (4cm radius) that lies flat on detected surfaces
 * - Inner Dot: Small white sphere (0.5cm radius) at the center
 * 
 * Behavior:
 * - Smoothly interpolates position and rotation to follow hit test results
 * - Adapts to surface orientation (flat on floors, vertical on walls)
 * - Changes appearance based on state:
 *   - SEARCHING: Faded white with subtle pulse animation
 *   - TRACKING: Solid white, stable
 *   - SNAPPED: Bright green with scale bump (haptic feedback)
 */
class ReticleNode(
    private val sceneView: ARSceneView
) : Node(sceneView.engine) {
    
    enum class State {
        SEARCHING,  // No surface detected
        TRACKING,   // Surface detected, normal tracking
        SNAPPED     // Snapped to vertex/edge
    }
    
    private var outerRing: CylinderNode? = null
    private var innerRing: CylinderNode? = null // iOS-style inner ring
    private var innerDot: SphereNode? = null
    private var currentState = State.SEARCHING
    
    // Smooth interpolation state - Much higher smoothing to reduce wobble
    private var targetPosition: Position = Position(0f, 0f, 0f)
    private var targetRotation: Quaternion = Quaternion()
    private val positionLerpFactor = 0.15f // Very smooth - reduces wobble significantly
    private val rotationLerpFactor = 0.10f // Very smooth rotation - no jitter
    
    // Animation state
    private var animationTime = 0f
    private val pulseSpeed = 2.0f
    
    init {
        createReticleGeometry()
        isVisible = false // Start hidden until first hit
    }
    
    /**
     * Create iOS AR Ruler style reticle with HOLLOW CENTER:
     * - Outer circle (2cm radius) - very transparent to see through
     * - Inner circle (0.8cm radius) - solid area (NOT hollow, just smaller for precision)
     * - Tiny center dot (1mm) for exact positioning
     * - 4 crosshair lines extending from outer ring
     * 
     * Key: The "hollow" is achieved by making everything semi-transparent
     * so you can see the object underneath perfectly
     */
    private fun createReticleGeometry() {
        // Create HOLLOW ring using line segments that form a circle outline
        // This creates a true ring (only the edge visible, not a solid disk)
        val ringRadius = 0.018f // 1.8cm radius
        val ringThickness = 0.0012f // 1.2mm line thickness
        val numSegments = 24 // Smooth circle
        
        val outerSegments = mutableListOf<CylinderNode>()
        for (i in 0 until numSegments) {
            val angle1 = (i.toFloat() / numSegments) * 2f * Math.PI.toFloat()
            val angle2 = ((i + 1).toFloat() / numSegments) * 2f * Math.PI.toFloat()
            
            val x1 = ringRadius * cos(angle1)
            val z1 = ringRadius * sin(angle1)
            val x2 = ringRadius * cos(angle2)
            val z2 = ringRadius * sin(angle2)
            
            val midX = (x1 + x2) / 2f
            val midZ = (z1 + z2) / 2f
            val segmentLength = sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1))
            
            val segmentNode = CylinderNode(
                engine = sceneView.engine,
                radius = ringThickness / 2f,
                height = segmentLength,
                materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.8f)
            ).apply {
                isShadowCaster = false
                isShadowReceiver = false
                position = Position(midX, 0f, midZ)
                
                // Rotate to align with circle edge
                val angle = atan2(z2 - z1, x2 - x1)
                quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), Math.toDegrees(angle.toDouble()).toFloat()) *
                           Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 90f)
                parent = this@ReticleNode
            }
            outerSegments.add(segmentNode)
        }
        
        // Store first segment as reference
        outerRing = outerSegments.firstOrNull()
        
        // Small hollow inner ring for precision
        val innerRadius = 0.004f // 4mm
        val innerSegments = 12
        val innerRingSegments = mutableListOf<CylinderNode>()
        
        for (i in 0 until innerSegments) {
            val angle1 = (i.toFloat() / innerSegments) * 2f * Math.PI.toFloat()
            val angle2 = ((i + 1).toFloat() / innerSegments) * 2f * Math.PI.toFloat()
            
            val x1 = innerRadius * cos(angle1)
            val z1 = innerRadius * sin(angle1)
            val x2 = innerRadius * cos(angle2)
            val z2 = innerRadius * sin(angle2)
            
            val midX = (x1 + x2) / 2f
            val midZ = (z1 + z2) / 2f
            val segmentLength = sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1))
            
            val segmentNode = CylinderNode(
                engine = sceneView.engine,
                radius = ringThickness / 2f,
                height = segmentLength,
                materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.95f)
            ).apply {
                isShadowCaster = false
                isShadowReceiver = false
                position = Position(midX, 0f, midZ)
                
                val angle = atan2(z2 - z1, x2 - x1)
                quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), Math.toDegrees(angle.toDouble()).toFloat()) *
                           Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 90f)
                parent = this@ReticleNode
            }
            innerRingSegments.add(segmentNode)
        }
        
        // Store first segment as reference
        innerRing = innerRingSegments.firstOrNull()
        
        // Ultra-tiny Center Dot - 1mm for PINPOINT precision (iOS style)
        innerDot = SphereNode(
            engine = sceneView.engine,
            radius = 0.001f, // 1mm radius - pinpoint dot
            materialInstance = sceneView.materialLoader.createColorInstance(
                Color.WHITE,
                1.0f // Fully visible center
            )
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            isVisible = true
            parent = this@ReticleNode
        }
        
        // Create 4 crosshair lines (iOS style) - ultra-thin extending lines
        val lineLength = 0.008f // 8mm lines - subtle
        val lineThickness = 0.0003f // 0.3mm thick - match ring thickness
        val lineDistance = 0.021f // Start just outside the 2cm ring
        
        // Horizontal line (right)
        createCrosshairLine(lineLength, lineThickness, Position(lineDistance + lineLength/2, 0f, 0f), 0f)
        // Horizontal line (left)
        createCrosshairLine(lineLength, lineThickness, Position(-(lineDistance + lineLength/2), 0f, 0f), 0f)
        // Vertical line (top)
        createCrosshairLine(lineLength, lineThickness, Position(0f, 0f, -(lineDistance + lineLength/2)), 90f)
        // Vertical line (bottom)
        createCrosshairLine(lineLength, lineThickness, Position(0f, 0f, lineDistance + lineLength/2), 90f)
        
        android.util.Log.d("ReticleNode", "Created iOS-style reticle with crosshair")
    }
    
    private fun createCrosshairLine(length: Float, thickness: Float, position: Position, rotationDeg: Float) {
        CylinderNode(
            engine = sceneView.engine,
            radius = thickness,
            height = length,
            materialInstance = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.7f) // Semi-transparent
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            this.position = position
            // Rotate to horizontal, then rotate around Y axis for orientation
            val flatRot = Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 90f * Math.PI.toFloat() / 180f)
            val orientRot = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), rotationDeg * Math.PI.toFloat() / 180f)
            quaternion = orientRot * flatRot
            parent = this@ReticleNode
        }
    }
    
    /**
     * Update the reticle's target position and state
     * Call this every frame with the current hit test result
     * 
     * @param pose The target pose from hit testing (null if no surface detected)
     * @param state The current interaction state
     */
    fun update(pose: Pose?, state: State = State.TRACKING) {
        if (pose == null) {
            // No surface detected
            currentState = State.SEARCHING
            isVisible = false
            return
        }
        
        // Update state and make visible
        currentState = state
        isVisible = true
        
        // Set interpolation targets
        targetPosition = Position(pose.tx(), pose.ty(), pose.tz())
        
        // Extract rotation from pose and apply surface alignment correction
        val poseRotation = Quaternion(pose.qx(), pose.qy(), pose.qz(), pose.qw())
        
        // Keep the reticle "lying flat" on the surface by maintaining the pose rotation
        // (The outerRing already has a 90° X-rotation baked in to lie flat)
        targetRotation = poseRotation
        
        // Update visual appearance based on state
        updateAppearance()
    }
    
    /**
     * Smooth interpolation - call this every frame to animate towards target
     */
    fun smoothUpdate(deltaTime: Float = 0.016f) {
        if (!isVisible) return
        
        // Lerp position
        position = lerp(position, targetPosition, positionLerpFactor)
        
        // Slerp rotation (smooth spherical interpolation)
        quaternion = slerp(quaternion, targetRotation, rotationLerpFactor)
        
        // Animate pulse effect for SEARCHING state
        if (currentState == State.SEARCHING) {
            animationTime += deltaTime * pulseSpeed
            val pulse = 0.7f + 0.3f * sin(animationTime)
            scale = Float3(pulse, pulse, pulse)
        } else {
            // Reset animation time and scale
            animationTime = 0f
            if (currentState == State.SNAPPED) {
                scale = Float3(1.2f, 1.2f, 1.2f) // Slightly larger when snapped
            } else {
                scale = Float3(1.0f, 1.0f, 1.0f)
            }
        }
    }
    
    /**
     * Update colors and appearance based on current state
     * All states use semi-transparency for cleaner look
     */
    private fun updateAppearance() {
        when (currentState) {
            State.SEARCHING -> {
                // Very faded white when searching
                outerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    0.4f // More transparent when searching
                )
                innerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    0.5f
                )
                innerDot?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    0.5f
                )
                android.util.Log.d("ReticleNode", "State: SEARCHING")
            }
            State.TRACKING -> {
                // Semi-transparent white
                outerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    0.5f // Very transparent outer ring - hollow effect
                )
                innerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    0.7f // Semi-transparent inner ring
                )
                innerDot?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    0.9f // Most solid for precision
                )
                android.util.Log.d("ReticleNode", "State: TRACKING")
            }
            State.SNAPPED -> {
                // Bright green, semi-transparent (iOS snap indicator)
                outerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.rgb(76, 217, 100), // iOS green
                    0.7f
                )
                innerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.rgb(76, 217, 100),
                    0.85f
                )
                innerDot?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.GREEN,
                    1.0f
                )
                android.util.Log.d("ReticleNode", "State: SNAPPED")
            }
        }
    }
    
    // --- Helper Math Functions ---
    
    private fun lerp(start: Position, end: Position, t: Float): Position {
        return start + ((end - start) * t)
    }
    
    private fun slerp(start: Quaternion, end: Quaternion, t: Float): Quaternion {
        // Simplified slerp for smooth rotation
        // For production, you might want to use a more robust implementation
        val dot = start.x * end.x + start.y * end.y + start.z * end.z + start.w * end.w
        
        // If quaternions are very close, just lerp
        if (kotlin.math.abs(dot) > 0.9995f) {
            val result = Quaternion(
                start.x + t * (end.x - start.x),
                start.y + t * (end.y - start.y),
                start.z + t * (end.z - start.z),
                start.w + t * (end.w - start.w)
            )
            return normalize(result)
        }
        
        // Standard slerp
        val theta = kotlin.math.acos(kotlin.math.abs(dot))
        val sinTheta = sin(theta.toDouble()).toFloat()
        val a = sin((1.0 - t) * theta.toDouble()).toFloat() / sinTheta
        val b = sin(t * theta.toDouble()).toFloat() / sinTheta
        
        val adjustedEnd = if (dot < 0) Quaternion(-end.x, -end.y, -end.z, -end.w) else end
        
        return Quaternion(
            a * start.x + b * adjustedEnd.x,
            a * start.y + b * adjustedEnd.y,
            a * start.z + b * adjustedEnd.z,
            a * start.w + b * adjustedEnd.w
        )
    }
    
    private fun normalize(q: Quaternion): Quaternion {
        val mag = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
        return if (mag > 0.0001f) {
            Quaternion(q.x / mag, q.y / mag, q.z / mag, q.w / mag)
        } else {
            Quaternion(0f, 0f, 0f, 1f)
        }
    }
}
