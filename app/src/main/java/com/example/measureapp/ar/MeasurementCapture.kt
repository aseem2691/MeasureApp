package com.example.measureapp.ar

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.content.FileProvider
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS-style measurement photo capture
 * Captures AR scene with measurements overlaid and saves to gallery
 */
class MeasurementCapture(
    private val context: Context,
    private val sceneView: ARSceneView,
    private val overlayView: OverlayView
) {
    
    private val TAG = "MeasurementCapture"
    
    /**
     * Capture AR scene with measurements and save to gallery
     * Returns URI of saved image for sharing
     */
    suspend fun captureAndSave(): Uri? {
        try {
            // Step 1: Capture AR scene view
            val sceneBitmap = captureSceneView() ?: run {
                Log.e(TAG, "Failed to capture scene view")
                return null
            }
            
            // Step 2: Draw measurements overlay on top
            val finalBitmap = compositeWithOverlay(sceneBitmap)
            
            // Step 3: Save to MediaStore (gallery)
            val uri = saveToGallery(finalBitmap)
            
            // Clean up
            sceneBitmap.recycle()
            finalBitmap.recycle()
            
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing measurement photo", e)
            return null
        }
    }
    
    /**
     * Capture the AR scene view using PixelCopy API
     */
    private suspend fun captureSceneView(): Bitmap? = suspendCancellableCoroutine { continuation ->
        try {
            val bitmap = Bitmap.createBitmap(
                sceneView.width,
                sceneView.height,
                Bitmap.Config.ARGB_8888
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use PixelCopy for hardware-accelerated views
                val surfaceView = findSurfaceView(sceneView)
                if (surfaceView != null) {
                    PixelCopy.request(
                        surfaceView,
                        bitmap,
                        { copyResult ->
                            if (copyResult == PixelCopy.SUCCESS) {
                                continuation.resume(bitmap)
                            } else {
                                Log.e(TAG, "PixelCopy failed with result: $copyResult")
                                bitmap.recycle()
                                continuation.resume(null)
                            }
                        },
                        sceneView.handler
                    )
                } else {
                    Log.e(TAG, "SurfaceView not found in ARSceneView")
                    bitmap.recycle()
                    continuation.resume(null)
                }
            } else {
                // Fallback for older Android versions
                val canvas = Canvas(bitmap)
                sceneView.draw(canvas)
                continuation.resume(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in captureSceneView", e)
            continuation.resumeWithException(e)
        }
    }
    
    /**
     * Find SurfaceView within ARSceneView hierarchy
     */
    private fun findSurfaceView(view: android.view.View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findSurfaceView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
    
    /**
     * Composite scene bitmap with overlay drawings
     */
    private fun compositeWithOverlay(sceneBitmap: Bitmap): Bitmap {
        val compositeBitmap = Bitmap.createBitmap(
            sceneBitmap.width,
            sceneBitmap.height,
            Bitmap.Config.ARGB_8888
        )
        
        val canvas = Canvas(compositeBitmap)
        
        // Draw scene
        canvas.drawBitmap(sceneBitmap, 0f, 0f, null)
        
        // Draw overlay (measurements, labels)
        overlayView.draw(canvas)
        
        return compositeBitmap
    }
    
    /**
     * Save bitmap to MediaStore gallery
     */
    private fun saveToGallery(bitmap: Bitmap): Uri? {
        val filename = "Measurement_${System.currentTimeMillis()}.jpg"
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ MediaStore API
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MeasureApp")
            }
            
            val contentResolver = context.contentResolver
            val imageUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            imageUri?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                uri
            }
        } else {
            // Legacy file-based approach
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )
            val appDir = File(picturesDir, "MeasureApp")
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            
            val file = File(appDir, filename)
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }
            
            // Create content URI for sharing
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }
    }
}
