package com.example.measureapp.ar.ml

import android.util.Log
import com.google.ar.core.Frame
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * Auto-labels saved measurements using ML Kit on-device image classification.
 *
 * When the user finishes a measurement, the current camera frame is classified and
 * the best scene-relevant label ("Table", "Door", ...) is attached to the history
 * entry. Labels outside the allowlist (e.g. "Fun", "Event") are discarded — an
 * empty label is better than a wrong one.
 */
class MeasurementAutoLabeler {

    companion object {
        private const val TAG = "MeasurementAutoLabeler"
        private const val MIN_CONFIDENCE = 0.65f

        // ML Kit base-model labels that plausibly describe something you'd measure
        private val RELEVANT_LABELS = setOf(
            "Table", "Desk", "Chair", "Couch", "Sofa", "Bed", "Door", "Window",
            "Television", "Shelf", "Cabinet", "Refrigerator", "Curtain", "Mirror",
            "Lamp", "Rug", "Bench", "Wardrobe", "Stairs", "Picture frame",
            "Bookcase", "Countertop", "Sink", "Bathtub", "Oven", "Box",
            "Whiteboard", "Monitor", "Laptop", "Screen", "Poster", "Vehicle",
            "Car", "Bicycle", "Plant", "Houseplant", "Flooring", "Wall"
        )
    }

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(MIN_CONFIDENCE)
            .build()
    )

    /**
     * Classify the given ARCore frame's camera image. Always invokes [onResult]
     * exactly once — with the best relevant label, or null if classification
     * failed or found nothing useful.
     */
    fun labelFrame(frame: Frame, rotationDegrees: Int, onResult: (String?) -> Unit) {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire camera image for labeling", e)
            onResult(null)
            return
        }

        val input = InputImage.fromMediaImage(image, rotationDegrees)
        labeler.process(input)
            .addOnSuccessListener { labels ->
                val best = labels.firstOrNull { it.text in RELEVANT_LABELS }
                onResult(best?.text)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Image labeling failed", e)
                onResult(null)
            }
            .addOnCompleteListener {
                image.close()
            }
    }

    fun close() {
        labeler.close()
    }
}
