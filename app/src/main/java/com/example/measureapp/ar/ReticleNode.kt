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
    private var innerDot: SphereNode? = null
    private var currentState = State.SEARCHING
    
    // Smooth interpolation state
    private var targetPosition: Position = Position(0f, 0f, 0f)
    private var targetRotation: Quaternion = Quaternion()
    private val positionLerpFactor = 0.25f // Faster response
    private val rotationLerpFactor = 0.20f // Slightly smoother rotation
    
    // Animation state
    private var animationTime = 0f
    private val pulseSpeed = 2.0f
    
    init {
        createReticleGeometry()
        isVisible = false // Start hidden until first hit
    }
    
    /**
     * Create the visual components of the reticle
     */
    private fun createReticleGeometry() {
        // Outer Ring - 3cm radius, 1.5mm thick, lies flat on surface
        outerRing = CylinderNode(
            engine = sceneView.engine,
            radius = 0.03f,  // 3cm radius - optimal size
            height = 0.0015f, // 1.5mm thickness
            materialInstance = sceneView.materialLoader.createColorInstance(
                Color.WHITE,
                1.0f
            )
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            isVisible = true
            // Rotate to lie flat (cylinder defaults to Y-axis up)
            quaternion = Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 90f * Math.PI.toFloat() / 180f)
            parent = this@ReticleNode
        }
        
        // Inner Dot - 0.5cm radius sphere (precise center indicator)
        innerDot = SphereNode(
            engine = sceneView.engine,
            radius = 0.005f, // 0.5cm radius - clear and precise
            materialInstance = sceneView.materialLoader.createColorInstance(
                Color.WHITE,
                1.0f
            )
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            isVisible = true
            parent = this@ReticleNode
        }
        
        android.util.Log.d("ReticleNode", "Created reticle with ring and dot")
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
     */
    private fun updateAppearance() {
        when (currentState) {
            State.SEARCHING -> {
                // Faded white
                outerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    0.6f // Semi-transparent
                )
                innerDot?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    0.6f
                )
                android.util.Log.d("ReticleNode", "State: SEARCHING")
            }
            State.TRACKING -> {
                // Solid white
                outerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    1.0f
                )
                innerDot?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.WHITE,
                    1.0f
                )
                android.util.Log.d("ReticleNode", "State: TRACKING")
            }
            State.SNAPPED -> {
                // Bright green (iOS snap indicator)
                outerRing?.materialInstance = sceneView.materialLoader.createColorInstance(
                    Color.GREEN,
                    1.0f
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
