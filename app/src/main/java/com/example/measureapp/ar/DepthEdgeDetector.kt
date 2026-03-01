package com.example.measureapp.ar

import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import io.github.sceneview.math.Position
import kotlin.math.sqrt

/**
 * Depth-guided edge detection using ARCore Raw Depth API.
 *
 * Detects real object edges from depth discontinuities (table edges, door frames,
 * furniture boundaries) by analyzing depth gradients in the raw depth image.
 *
 * Gracefully degrades on non-depth devices — returns empty edge list.
 */
class DepthEdgeDetector {

    data class DetectedEdge(
        val startWorld: Position,
        val endWorld: Position,
        val confidence: Float,  // 0..1
        val isVertical: Boolean
    )

    // Cached edge results between detection frames
    private var cachedEdges: List<DetectedEdge> = emptyList()

    // Configuration
    private val GRADIENT_THRESHOLD_MM = 50    // Depth jump > 50mm = edge
    private val CONFIDENCE_THRESHOLD = 128    // 0..255, require at least ~50% confidence
    private val SAMPLE_STEP = 4              // Sample every 4th pixel for performance
    private val MAX_EDGES = 20               // Limit to prevent overload
    private val MIN_CLUSTER_POINTS = 3       // Minimum points to form an edge
    private val CLUSTER_DISTANCE_M = 0.08f   // 8cm cluster radius in world space

    /**
     * Detect edges from depth discontinuities.
     * Call every N frames from MeasureActivity.
     */
    fun detectEdges(frame: Frame, camera: Camera) {
        try {
            val depthImage = frame.acquireRawDepthImage16Bits()
            val confidenceImage = frame.acquireRawDepthConfidenceImage()

            try {
                cachedEdges = processDepthImage(depthImage, confidenceImage, camera)
            } finally {
                depthImage.close()
                confidenceImage.close()
            }
        } catch (_: Exception) {
            // Depth not available on this device or frame — graceful degradation
            cachedEdges = emptyList()
        }
    }

    /**
     * Get the latest detected edges (cached from last detectEdges call).
     */
    fun getEdges(): List<DetectedEdge> = cachedEdges

    /**
     * Get depth confidence at a specific screen point (0..1 range).
     * Returns 0 if depth data not available.
     */
    fun getConfidenceAtScreenPoint(
        frame: Frame,
        screenX: Float,
        screenY: Float,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): Float {
        return try {
            val confidenceImage = frame.acquireRawDepthConfidenceImage()
            try {
                val imgX = ((screenX / viewWidth) * confidenceImage.width).toInt()
                    .coerceIn(0, confidenceImage.width - 1)
                val imgY = ((screenY / viewHeight) * confidenceImage.height).toInt()
                    .coerceIn(0, confidenceImage.height - 1)

                val buffer = confidenceImage.planes[0].buffer
                val rowStride = confidenceImage.planes[0].rowStride
                val value = buffer.get(imgY * rowStride + imgX).toInt() and 0xFF
                value / 255f
            } finally {
                confidenceImage.close()
            }
        } catch (_: Exception) {
            0f
        }
    }

    /**
     * Process raw depth + confidence images to find edge points,
     * then cluster them into line segments.
     */
    private fun processDepthImage(
        depthImage: Image,
        confidenceImage: Image,
        camera: Camera
    ): List<DetectedEdge> {
        val depthWidth = depthImage.width
        val depthHeight = depthImage.height
        val depthBuffer = depthImage.planes[0].buffer
        val depthRowStride = depthImage.planes[0].rowStride
        val confBuffer = confidenceImage.planes[0].buffer
        val confRowStride = confidenceImage.planes[0].rowStride

        // Get camera intrinsics for depth-to-3D conversion
        val intrinsics = camera.textureIntrinsics
        val fx = intrinsics.focalLength[0]
        val fy = intrinsics.focalLength[1]
        val cx = intrinsics.principalPoint[0]
        val cy = intrinsics.principalPoint[1]

        // Scale intrinsics from texture resolution to depth resolution
        val texDims = intrinsics.imageDimensions
        val scaleX = depthWidth.toFloat() / texDims[0]
        val scaleY = depthHeight.toFloat() / texDims[1]
        val dfx = fx * scaleX
        val dfy = fy * scaleY
        val dcx = cx * scaleX
        val dcy = cy * scaleY

        val cameraPose = camera.pose
        val edgePoints3D = mutableListOf<Position>()

        // Compute depth gradients using Sobel-like operators, sampling every SAMPLE_STEP pixels
        for (y in SAMPLE_STEP until depthHeight - SAMPLE_STEP step SAMPLE_STEP) {
            for (x in SAMPLE_STEP until depthWidth - SAMPLE_STEP step SAMPLE_STEP) {
                // Read confidence at this pixel
                val confValue = confBuffer.get(y * confRowStride + x).toInt() and 0xFF
                if (confValue < CONFIDENCE_THRESHOLD) continue

                // Read depth values for gradient computation (16-bit unsigned, in mm)
                val depthCenter = getDepthMm(depthBuffer, depthRowStride, x, y)
                if (depthCenter == 0) continue // No depth data

                val depthLeft = getDepthMm(depthBuffer, depthRowStride, x - SAMPLE_STEP, y)
                val depthRight = getDepthMm(depthBuffer, depthRowStride, x + SAMPLE_STEP, y)
                val depthUp = getDepthMm(depthBuffer, depthRowStride, x, y - SAMPLE_STEP)
                val depthDown = getDepthMm(depthBuffer, depthRowStride, x, y + SAMPLE_STEP)

                // Compute gradient magnitude
                val gradX = if (depthLeft > 0 && depthRight > 0) (depthRight - depthLeft) else 0
                val gradY = if (depthUp > 0 && depthDown > 0) (depthDown - depthUp) else 0
                val gradMagnitude = sqrt((gradX * gradX + gradY * gradY).toFloat())

                if (gradMagnitude > GRADIENT_THRESHOLD_MM) {
                    // Convert pixel to 3D world coordinates
                    val depthM = depthCenter / 1000f
                    val worldX = (x - dcx) / dfx * depthM
                    val worldY = (y - dcy) / dfy * depthM
                    val worldZ = depthM

                    // Transform from camera space to world space
                    val cameraPoint = floatArrayOf(worldX, -worldY, -worldZ) // Flip Y and Z for ARCore convention
                    val worldPoint = cameraPose.transformPoint(cameraPoint)

                    edgePoints3D.add(Position(worldPoint[0], worldPoint[1], worldPoint[2]))
                }
            }
        }

        // Cluster edge points into line segments
        return clusterEdgePoints(edgePoints3D)
    }

    /**
     * Read 16-bit unsigned depth value in millimeters from the depth buffer.
     */
    private fun getDepthMm(buffer: java.nio.ByteBuffer, rowStride: Int, x: Int, y: Int): Int {
        val offset = y * rowStride + x * 2  // 16-bit = 2 bytes per pixel
        if (offset < 0 || offset + 1 >= buffer.capacity()) return 0
        val low = buffer.get(offset).toInt() and 0xFF
        val high = buffer.get(offset + 1).toInt() and 0xFF
        return (high shl 8) or low
    }

    /**
     * Cluster nearby 3D edge points into line segments using a simple
     * connected-component approach.
     */
    private fun clusterEdgePoints(points: List<Position>): List<DetectedEdge> {
        if (points.size < MIN_CLUSTER_POINTS) return emptyList()

        val used = BooleanArray(points.size)
        val edges = mutableListOf<DetectedEdge>()

        for (i in points.indices) {
            if (used[i]) continue

            // Start a new cluster
            val cluster = mutableListOf(points[i])
            used[i] = true

            // Find all nearby points
            for (j in i + 1 until points.size) {
                if (used[j]) continue

                // Check if this point is close to any point in the cluster
                val closeToCluster = cluster.any { clusterPt ->
                    distance(clusterPt, points[j]) < CLUSTER_DISTANCE_M
                }

                if (closeToCluster) {
                    cluster.add(points[j])
                    used[j] = true
                }
            }

            if (cluster.size >= MIN_CLUSTER_POINTS) {
                // Fit a line segment to the cluster (use min/max extents)
                val edge = fitLineSegment(cluster)
                if (edge != null) {
                    edges.add(edge)
                    if (edges.size >= MAX_EDGES) break
                }
            }
        }

        return edges
    }

    /**
     * Fit a line segment to a cluster of 3D points.
     * Uses the two most distant points as segment endpoints.
     */
    private fun fitLineSegment(cluster: List<Position>): DetectedEdge? {
        if (cluster.size < MIN_CLUSTER_POINTS) return null

        // Find the two most distant points (diameter of the cluster)
        var maxDist = 0f
        var startIdx = 0
        var endIdx = 1

        for (i in cluster.indices) {
            for (j in i + 1 until cluster.size) {
                val d = distance(cluster[i], cluster[j])
                if (d > maxDist) {
                    maxDist = d
                    startIdx = i
                    endIdx = j
                }
            }
        }

        // Reject very short edges (< 3cm)
        if (maxDist < 0.03f) return null

        val start = cluster[startIdx]
        val end = cluster[endIdx]

        // Determine if edge is mostly vertical
        val dy = kotlin.math.abs(end.y - start.y)
        val dxz = sqrt(
            (end.x - start.x) * (end.x - start.x) +
            (end.z - start.z) * (end.z - start.z)
        )
        val isVertical = dy > dxz

        // Confidence based on cluster density (more points = higher confidence)
        val confidence = (cluster.size.toFloat() / 20f).coerceIn(0.3f, 1.0f)

        return DetectedEdge(
            startWorld = start,
            endWorld = end,
            confidence = confidence,
            isVertical = isVertical
        )
    }

    private fun distance(a: Position, b: Position): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
