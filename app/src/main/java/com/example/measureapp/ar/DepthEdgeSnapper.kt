package com.example.measureapp.ar

import android.media.Image
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Point
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.sceneview.math.Position
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Physical edge snapping from the ARCore depth image — the closest Android
 * equivalent of iOS Measure's LiDAR edge guides.
 *
 * A physical object boundary (laptop edge against the floor behind it) shows up
 * as a sharp discontinuity in the depth map. Each call scans a small window of
 * the 16-bit depth image around the reticle, finds the strongest nearby
 * discontinuity, steps onto its FOREGROUND side (the object being measured, not
 * the background), and converts that depth pixel back to a world position via a
 * hit test validated against the expected depth.
 *
 * Depth-image coordinates align with TEXTURE_NORMALIZED space;
 * frame.transformCoordinates2d handles rotation and crop in both directions.
 */
class DepthEdgeSnapper {

    companion object {
        private const val WINDOW_RADIUS = 6          // depth pixels (~160x120 image)
        private const val MIN_JUMP_MM = 50           // absolute discontinuity floor
        private const val RELATIVE_JUMP = 0.05f      // ...or 5% of local depth
        private const val MIN_DEPTH_MM = 200
        private const val MAX_DEPTH_MM = 6000
    }

    private val viewCoords = FloatArray(2)
    private val texCoords = FloatArray(2)

    /** Diagnostics: whether the device has EVER produced a depth image this session.
     *  Some devices (e.g. S25 Ultra with certain ARCore versions) report depth as
     *  supported but their depth pipeline fails internally — then this stays false
     *  and edge snapping is silently unavailable. */
    var depthImagesAcquired = 0
        private set
    var depthImagesUnavailable = 0
        private set
    val isDepthWorking: Boolean get() = depthImagesAcquired > 0

    /**
     * Find the nearest physical edge to the given view position.
     * Returns its world position on the foreground side, or null when no clear
     * discontinuity is nearby. Call from the AR frame update (main thread).
     */
    fun findEdgeNearPoint(frame: Frame, viewX: Float, viewY: Float): Position? {
        val depthImage = try {
            frame.acquireDepthImage16Bits().also { depthImagesAcquired++ }
        } catch (e: NotYetAvailableException) {
            depthImagesUnavailable++
            if (depthImagesUnavailable % 100 == 0) {
                android.util.Log.w(
                    "DepthEdgeSnapper",
                    "Depth image still unavailable after $depthImagesUnavailable attempts " +
                        "(acquired: $depthImagesAcquired)"
                )
            }
            return null
        } catch (e: Exception) {
            depthImagesUnavailable++
            return null
        }

        try {
            return processDepthImage(frame, depthImage, viewX, viewY)
        } finally {
            depthImage.close()
        }
    }

    private fun processDepthImage(frame: Frame, depthImage: Image, viewX: Float, viewY: Float): Position? {
        val width = depthImage.width
        val height = depthImage.height
        val plane = depthImage.planes[0]
        val buffer = plane.buffer.order(ByteOrder.nativeOrder())
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // DEPTH16: 13 LSB = millimeters, 3 MSB = confidence (0 = full, 1 = none)
        fun depthAt(x: Int, y: Int): Int {
            if (x < 0 || y < 0 || x >= width || y >= height) return -1
            val raw = buffer.getShort(y * rowStride + x * pixelStride).toInt() and 0xFFFF
            val confidence = (raw shr 13) and 0x7
            val mm = raw and 0x1FFF
            return if (mm in MIN_DEPTH_MM..MAX_DEPTH_MM && (confidence == 0 || confidence >= 4)) mm else -1
        }

        // Reticle view position → depth pixel
        viewCoords[0] = viewX
        viewCoords[1] = viewY
        try {
            frame.transformCoordinates2d(
                Coordinates2d.VIEW, viewCoords,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords
            )
        } catch (e: Exception) {
            return null
        }
        val centerX = (texCoords[0] * width).toInt()
        val centerY = (texCoords[1] * height).toInt()
        if (centerX !in 1 until width - 1 || centerY !in 1 until height - 1) return null

        // Scan the window for the depth discontinuity nearest the reticle
        var bestX = -1
        var bestY = -1
        var bestPixelDistSq = Int.MAX_VALUE
        var bestForegroundX = -1
        var bestForegroundY = -1
        var bestForegroundMm = -1

        for (dy in -WINDOW_RADIUS..WINDOW_RADIUS) {
            for (dx in -WINDOW_RADIUS..WINDOW_RADIUS) {
                val distSq = dx * dx + dy * dy
                if (distSq > WINDOW_RADIUS * WINDOW_RADIUS || distSq >= bestPixelDistSq) continue

                val x = centerX + dx
                val y = centerY + dy
                val d = depthAt(x, y)
                if (d < 0) continue

                val left = depthAt(x - 1, y)
                val right = depthAt(x + 1, y)
                val up = depthAt(x, y - 1)
                val down = depthAt(x, y + 1)

                val jumpThreshold = maxOf(MIN_JUMP_MM, (d * RELATIVE_JUMP).toInt())

                // Horizontal and vertical discontinuity checks; pick the foreground
                // (smaller-depth) side of the strongest jump at this pixel
                var foreX = -1
                var foreY = -1
                var foreMm = -1

                if (left > 0 && right > 0 && abs(right - left) > jumpThreshold) {
                    if (left < right) { foreX = x - 1; foreY = y; foreMm = left }
                    else { foreX = x + 1; foreY = y; foreMm = right }
                }
                if (up > 0 && down > 0 && abs(down - up) > jumpThreshold) {
                    val (fy, fmm) = if (up < down) (y - 1) to up else (y + 1) to down
                    if (foreMm < 0 || fmm < foreMm) { foreX = x; foreY = fy; foreMm = fmm }
                }

                if (foreMm > 0) {
                    bestX = x
                    bestY = y
                    bestPixelDistSq = distSq
                    bestForegroundX = foreX
                    bestForegroundY = foreY
                    bestForegroundMm = foreMm
                }
            }
        }

        if (bestX < 0 || bestForegroundMm <= 0) return null

        // Foreground edge pixel → view coordinates → validated world position
        texCoords[0] = (bestForegroundX + 0.5f) / width
        texCoords[1] = (bestForegroundY + 0.5f) / height
        try {
            frame.transformCoordinates2d(
                Coordinates2d.TEXTURE_NORMALIZED, texCoords,
                Coordinates2d.VIEW, viewCoords
            )
        } catch (e: Exception) {
            return null
        }

        val hits = try {
            frame.hitTest(viewCoords[0], viewCoords[1])
        } catch (e: Exception) {
            return null
        }
        val hit = hits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull { it.trackable is Point }
            ?: return null

        // The hit must roughly agree with the foreground depth we measured —
        // otherwise the hit test itself bled past the edge
        val cameraPose = frame.camera.pose
        val dx = hit.hitPose.tx() - cameraPose.tx()
        val dy = hit.hitPose.ty() - cameraPose.ty()
        val dz = hit.hitPose.tz() - cameraPose.tz()
        val hitMm = sqrt(dx * dx + dy * dy + dz * dz) * 1000f
        val tolerance = maxOf(200f, bestForegroundMm * 0.15f)
        if (abs(hitMm - bestForegroundMm) > tolerance) return null

        return Position(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
    }
}
