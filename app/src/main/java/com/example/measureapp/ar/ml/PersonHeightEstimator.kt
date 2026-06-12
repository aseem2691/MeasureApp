package com.example.measureapp.ar.ml

import android.content.Context
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * iOS Measure-style automatic person height detection.
 *
 * Pipeline (runs alongside the AR session, throttled):
 * 1. Every N frames, grab the ARCore CPU camera image and run ML Kit Pose Detection
 *    (a TensorFlow Lite model, GPU-accelerated when available).
 * 2. Map the nose + heel landmarks from upright-image coordinates back to sensor
 *    image pixels, then through ARCore's transformCoordinates2d into view coordinates.
 * 3. Hit-test the feet against the floor plane and the nose against the depth map
 *    to recover 3D positions, and derive height = vertical span + nose-to-crown offset.
 * 4. Median-filter recent estimates; report stability so the UI can offer "save".
 */
class PersonHeightEstimator(private val context: Context) {

    data class Result(
        val heightMeters: Float,
        val isStable: Boolean,
        val headScreen: PointF,
        val feetScreen: PointF
    )

    private data class PosePoints(val nose: PointF, val feet: PointF)

    companion object {
        private const val TAG = "PersonHeightEstimator"
        private const val DETECT_EVERY_N_FRAMES = 10
        private const val NOSE_TO_CROWN_METERS = 0.12f
        private const val MIN_HEIGHT_METERS = 0.5f
        private const val MAX_HEIGHT_METERS = 2.5f
        private const val STABLE_WINDOW = 6
        private const val STABLE_RANGE_METERS = 0.08f
        private const val POSE_TIMEOUT_MS = 1500L
        private const val MIN_LANDMARK_CONFIDENCE = 0.6f
    }

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU)
            .build()
    )

    private var isProcessing = false
    private var frameCounter = 0
    private var imageRotation = -1
    private var pendingPose: PosePoints? = null
    private var lastPoseTimeMs = 0L
    private val recentHeights = ArrayDeque<Float>()

    /**
     * Call once per ARCore frame from onSessionUpdated (main thread).
     * Returns the current height estimate, or null while no person is tracked.
     */
    fun onFrame(session: Session, frame: Frame): Result? {
        frameCounter++
        if (frameCounter % DETECT_EVERY_N_FRAMES == 0 && !isProcessing) {
            detectPose(session, frame)
        }

        if (System.currentTimeMillis() - lastPoseTimeMs > POSE_TIMEOUT_MS) {
            pendingPose = null
            recentHeights.clear()
            return null
        }
        val pose = pendingPose ?: return null

        // Image pixels → view coordinates (handles rotation, crop and aspect for us)
        val imageCoords = floatArrayOf(pose.nose.x, pose.nose.y, pose.feet.x, pose.feet.y)
        val viewCoords = FloatArray(4)
        try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS, imageCoords,
                Coordinates2d.VIEW, viewCoords
            )
        } catch (e: Exception) {
            return null
        }
        val noseView = PointF(viewCoords[0], viewCoords[1])
        val feetView = PointF(viewCoords[2], viewCoords[3])

        val floorY = floorHeightAt(frame, feetView) ?: return null
        val noseWorldY = bodyPointHeightAt(frame, noseView) ?: return null

        val rawHeight = (noseWorldY - floorY) + NOSE_TO_CROWN_METERS
        if (rawHeight < MIN_HEIGHT_METERS || rawHeight > MAX_HEIGHT_METERS) return null

        recentHeights.addLast(rawHeight)
        while (recentHeights.size > STABLE_WINDOW) recentHeights.removeFirst()
        val sorted = recentHeights.sorted()
        val median = sorted[sorted.size / 2]
        val isStable = recentHeights.size >= STABLE_WINDOW &&
            (sorted.last() - sorted.first()) < STABLE_RANGE_METERS

        // Approximate crown position on screen for the overlay indicator
        val headScreen = PointF(noseView.x, noseView.y - (feetView.y - noseView.y) * 0.08f)
        return Result(median, isStable, headScreen, feetView)
    }

    fun close() {
        poseDetector.close()
    }

    private fun detectPose(session: Session, frame: Frame) {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire camera image", e)
            return
        }

        if (imageRotation < 0) {
            imageRotation = computeCameraImageRotation(context, session)
        }

        isProcessing = true
        val imageWidth = image.width
        val imageHeight = image.height
        val input = InputImage.fromMediaImage(image, imageRotation)
        poseDetector.process(input)
            .addOnSuccessListener { pose ->
                handlePose(pose, imageWidth, imageHeight)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Pose detection failed", e)
            }
            .addOnCompleteListener {
                image.close()
                isProcessing = false
            }
    }

    private fun handlePose(pose: Pose, imageWidth: Int, imageHeight: Int) {
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
            ?.takeIf { it.inFrameLikelihood >= MIN_LANDMARK_CONFIDENCE }
            ?: run { pendingPose = null; return }

        val feetCandidates = listOfNotNull(
            pose.getPoseLandmark(PoseLandmark.LEFT_HEEL),
            pose.getPoseLandmark(PoseLandmark.RIGHT_HEEL),
            pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX),
            pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)
        ).filter { it.inFrameLikelihood >= MIN_LANDMARK_CONFIDENCE }
        if (feetCandidates.isEmpty()) {
            pendingPose = null
            return
        }
        val feetX = feetCandidates.map { it.position.x }.average().toFloat()
        val feetY = feetCandidates.map { it.position.y }.average().toFloat()

        // ML Kit reports landmarks in upright-image coordinates; convert back to
        // the sensor image pixel space that transformCoordinates2d expects
        pendingPose = PosePoints(
            nose = uprightToImagePixels(nose.position, imageWidth, imageHeight),
            feet = uprightToImagePixels(PointF(feetX, feetY), imageWidth, imageHeight)
        )
        lastPoseTimeMs = System.currentTimeMillis()
    }

    private fun uprightToImagePixels(p: PointF, width: Int, height: Int): PointF {
        return when (imageRotation) {
            90 -> PointF(p.y, height - p.x)
            180 -> PointF(width - p.x, height - p.y)
            270 -> PointF(width - p.y, p.x)
            else -> PointF(p.x, p.y)
        }
    }

    /** World-space Y of the floor under the person's feet */
    private fun floorHeightAt(frame: Frame, feetView: PointF): Float? {
        val hits = try {
            frame.hitTest(feetView.x, feetView.y)
        } catch (e: Exception) {
            return null
        }
        val planeHit = hits.firstOrNull { hit ->
            val t = hit.trackable
            t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                t.trackingState == TrackingState.TRACKING
        }
        if (planeHit != null) return planeHit.hitPose.ty()
        val depthHit = hits.firstOrNull { it.trackable is DepthPoint }
        return depthHit?.hitPose?.ty()
    }

    /** World-space Y of a point on the person's body (nose); depth map preferred */
    private fun bodyPointHeightAt(frame: Frame, view: PointF): Float? {
        val hits = try {
            frame.hitTest(view.x, view.y)
        } catch (e: Exception) {
            return null
        }
        val hit = hits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull { it.trackable is Point }
        return hit?.hitPose?.ty()
    }
}

/**
 * Rotation (degrees) to make the ARCore CPU camera image upright,
 * computed from the camera sensor orientation and current display rotation.
 */
internal fun computeCameraImageRotation(context: Context, session: Session): Int {
    return try {
        val cameraId = session.cameraConfig.cameraId
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sensorOrientation = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val displayDegrees = when (context.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        (sensorOrientation - displayDegrees + 360) % 360
    } catch (e: Exception) {
        90 // Portrait back camera default
    }
}
