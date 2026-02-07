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
 * Professional iOS-style 3D Reticle
 *
 * Visual Design:
 * - Outer Ring: Thin cylinder (2cm radius) flat on detected surfaces
 * - Inner Dot: Small white sphere at center for precision targeting
 *
 * Behavior:
 * - Adaptive smoothing: faster when far, slower when close for precision
 * - Pre-cached materials avoid per-frame allocation
 * - State-based appearance: SEARCHING (faded pulse), TRACKING (yellow), SNAPPED (green)
 */
class ReticleNode(
    private val sceneView: ARSceneView
) : Node(sceneView.engine) {

    enum class State {
        SEARCHING,
        TRACKING,
        SNAPPED
    }

    private var outerRing: CylinderNode? = null
    private var innerDot: SphereNode? = null
    private var currentState = State.SEARCHING

    // Pre-cached materials for performance (avoid creating every frame)
    private lateinit var ringSearching: MaterialInstance
    private lateinit var ringTracking: MaterialInstance
    private lateinit var ringSnapped: MaterialInstance
    private lateinit var dotSearching: MaterialInstance
    private lateinit var dotTracking: MaterialInstance
    private lateinit var dotSnapped: MaterialInstance

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

    /**
     * Pre-cache all materials at init to avoid per-frame allocation
     */
    private fun createMaterials() {
        val iosYellow = Color.rgb(255, 204, 0)
        val iosGreen = Color.rgb(52, 199, 89)

        ringSearching = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.4f)
        ringTracking = sceneView.materialLoader.createColorInstance(iosYellow, 0.85f)
        ringSnapped = sceneView.materialLoader.createColorInstance(iosGreen, 0.9f)

        dotSearching = sceneView.materialLoader.createColorInstance(Color.WHITE, 0.5f)
        dotTracking = sceneView.materialLoader.createColorInstance(Color.WHITE, 1.0f)
        dotSnapped = sceneView.materialLoader.createColorInstance(Color.GREEN, 1.0f)
    }

    private fun createReticleGeometry() {
        // Outer ring — thin cylinder for hollow ring appearance
        outerRing = CylinderNode(
            engine = sceneView.engine,
            radius = 0.020f,  // 2cm radius
            height = 0.001f,  // 1mm thickness
            materialInstance = ringSearching
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            isVisible = true
            quaternion = Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 90f * Math.PI.toFloat() / 180f)
            parent = this@ReticleNode
        }

        // Center dot — 2mm for targeting
        innerDot = SphereNode(
            engine = sceneView.engine,
            radius = 0.002f,
            materialInstance = dotSearching
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
            isVisible = true
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

        // Only swap materials on state change (avoid per-frame allocation)
        if (previousState != currentState) {
            updateAppearance()
        }
    }

    fun smoothUpdate(deltaTime: Float = 0.016f) {
        if (!isVisible) return

        // Adaptive lerp: faster when far, slower when close for precision
        val distToTarget = length(targetPosition - position)
        val adaptivePosLerp = when {
            distToTarget > 0.05f -> 0.3f
            distToTarget < 0.01f -> 0.12f
            else -> positionLerpFactor
        }
        val adaptiveRotLerp = when {
            distToTarget > 0.05f -> 0.25f
            distToTarget < 0.01f -> 0.08f
            else -> rotationLerpFactor
        }

        position = lerp(position, targetPosition, adaptivePosLerp)
        quaternion = slerp(quaternion, targetRotation, adaptiveRotLerp)

        when (currentState) {
            State.SEARCHING -> {
                animationTime += deltaTime * pulseSpeed
                val pulse = 0.7f + 0.3f * sin(animationTime)
                scale = Float3(pulse, pulse, pulse)
            }
            State.TRACKING -> {
                animationTime = 0f
                scale = Float3(1.0f, 1.0f, 1.0f)
            }
            State.SNAPPED -> {
                animationTime = 0f
                scale = Float3(1.2f, 1.2f, 1.2f)
            }
        }
    }

    private fun updateAppearance() {
        when (currentState) {
            State.SEARCHING -> {
                outerRing?.materialInstance = ringSearching
                innerDot?.materialInstance = dotSearching
            }
            State.TRACKING -> {
                outerRing?.materialInstance = ringTracking
                innerDot?.materialInstance = dotTracking
            }
            State.SNAPPED -> {
                outerRing?.materialInstance = ringSnapped
                innerDot?.materialInstance = dotSnapped
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
