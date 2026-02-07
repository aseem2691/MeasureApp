package com.example.measureapp.ar

import android.graphics.Color
import com.google.ar.core.Pose
import com.google.android.filament.MaterialInstance
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

/**
 * Simplified iOS-style 3D Reticle
 *
 * Visual Design:
 * - Center Dot: White sphere that grows/shrinks based on state
 * - Surface Disc: Flat transparent disc showing surface alignment
 *
 * States:
 * - SEARCHING: Small dot (3mm), pulsing alpha
 * - TRACKING: Medium dot (5mm) + disc, solid white
 * - SNAPPED: Large dot (7mm) + disc, yellow, 1.2x scale
 */
class ReticleNode(
    private val sceneView: ARSceneView
) : Node(sceneView.engine) {

    enum class State {
        SEARCHING,
        TRACKING,
        SNAPPED
    }

    private var centerDot: SphereNode? = null
    private var surfaceDisc: CylinderNode? = null
    private var currentState = State.SEARCHING

    // Pre-cached materials for performance (avoid creating every frame)
    private lateinit var dotSearching: MaterialInstance
    private lateinit var dotTracking: MaterialInstance
    private lateinit var dotSnapped: MaterialInstance
    private lateinit var discSearching: MaterialInstance
    private lateinit var discTracking: MaterialInstance
    private lateinit var discSnapped: MaterialInstance

    // Smooth interpolation
    private var targetPosition: Position = Position(0f, 0f, 0f)
    private var targetRotation: Quaternion = Quaternion()
    private val positionLerpFactor = 0.15f
    private val rotationLerpFactor = 0.10f

    // Animation state
    private var animationTime = 0f
    private val pulseSpeed = 2.0f

    init {
        createMaterials()
        createReticleGeometry()
        isVisible = false
    }

    private fun createMaterials() {
        val iosYellow = Color.rgb(255, 204, 0)
        dotSearching = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.5f)
        dotTracking = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.95f)
        dotSnapped = sceneView.materialLoader.createColorInstance(iosYellow, 1.0f)
        discSearching = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.15f)
        discTracking = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.25f)
        discSnapped = sceneView.materialLoader.createColorInstance(iosYellow, 0.35f)
    }

    private fun createReticleGeometry() {
        // Center dot - white sphere
        centerDot = SphereNode(
            engine = sceneView.engine,
            radius = 0.004f, // 4mm default, changes with state
            materialInstance = dotSearching
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            parent = this@ReticleNode
        }

        // Surface disc - flat cylinder for surface alignment indication
        surfaceDisc = CylinderNode(
            engine = sceneView.engine,
            radius = 0.015f, // 1.5cm radius
            height = 0.0005f, // Very flat
            materialInstance = discSearching
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            parent = this@ReticleNode
        }
    }

    fun update(pose: Pose?, state: State = State.TRACKING) {
        if (pose == null) {
            currentState = State.SEARCHING
            isVisible = false
            return
        }

        val previousState = currentState
        currentState = state
        isVisible = true

        targetPosition = Position(pose.tx(), pose.ty(), pose.tz())
        targetRotation = Quaternion(pose.qx(), pose.qy(), pose.qz(), pose.qw())

        // Only update appearance on state change
        if (previousState != currentState) {
            updateAppearance()
        }
    }

    fun smoothUpdate(deltaTime: Float = 0.016f) {
        if (!isVisible) return

        // Adaptive lerp: faster when far, slower when close for precision
        val distToTarget = length(targetPosition - position)
        val adaptivePosLerp = if (distToTarget > 0.05f) 0.3f else if (distToTarget < 0.01f) 0.12f else positionLerpFactor
        val adaptiveRotLerp = if (distToTarget > 0.05f) 0.25f else if (distToTarget < 0.01f) 0.08f else rotationLerpFactor

        position = lerp(position, targetPosition, adaptivePosLerp)
        quaternion = slerp(quaternion, targetRotation, adaptiveRotLerp)

        when (currentState) {
            State.SEARCHING -> {
                animationTime += deltaTime * pulseSpeed
                val pulse = 0.7f + 0.3f * sin(animationTime)
                scale = Float3(pulse, pulse, pulse)
                surfaceDisc?.isVisible = false
            }
            State.TRACKING -> {
                animationTime = 0f
                scale = Float3(1.0f, 1.0f, 1.0f)
                surfaceDisc?.isVisible = true
            }
            State.SNAPPED -> {
                animationTime = 0f
                scale = Float3(1.2f, 1.2f, 1.2f)
                surfaceDisc?.isVisible = true
            }
        }
    }

    private fun updateAppearance() {
        when (currentState) {
            State.SEARCHING -> {
                centerDot?.materialInstance = dotSearching
                surfaceDisc?.materialInstance = discSearching
                surfaceDisc?.isVisible = false
            }
            State.TRACKING -> {
                centerDot?.materialInstance = dotTracking
                surfaceDisc?.materialInstance = discTracking
                surfaceDisc?.isVisible = true
            }
            State.SNAPPED -> {
                centerDot?.materialInstance = dotSnapped
                surfaceDisc?.materialInstance = discSnapped
                surfaceDisc?.isVisible = true
            }
        }
    }

    // --- Helper Math Functions ---

    private fun length(v: Float3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    private fun lerp(start: Position, end: Position, t: Float): Position {
        return start + ((end - start) * t)
    }

    private fun slerp(start: Quaternion, end: Quaternion, t: Float): Quaternion {
        val dot = start.x * end.x + start.y * end.y + start.z * end.z + start.w * end.w

        if (kotlin.math.abs(dot) > 0.9995f) {
            val result = Quaternion(
                start.x + t * (end.x - start.x),
                start.y + t * (end.y - start.y),
                start.z + t * (end.z - start.z),
                start.w + t * (end.w - start.w)
            )
            return normalize(result)
        }

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
