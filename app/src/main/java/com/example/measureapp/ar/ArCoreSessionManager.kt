package com.example.measureapp.ar

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*

/**
 * Manager for ARCore session lifecycle and availability checks
 */
class ArCoreSessionManager(private val context: Context) {

    private var session: Session? = null
    private var installRequested = false

    /**
     * Check if ARCore is supported and installed on this device
     */
    fun checkArCoreAvailability(activity: Activity): ArCoreAvailability {
        return when (ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                ArCoreAvailability.SUPPORTED_INSTALLED
            }
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                ArCoreAvailability.SUPPORTED_NOT_INSTALLED
            }
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                ArCoreAvailability.UNSUPPORTED
            }
            else -> ArCoreAvailability.UNKNOWN
        }
    }

    /**
     * Request ARCore installation if needed
     */
    fun requestInstall(activity: Activity): Boolean {
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    false
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Create and configure ARCore session
     */
    fun createSession(activity: Activity): Result<Session> {
        return try {
            // Create session
            val newSession = Session(context, setOf(Session.Feature.SHARED_CAMERA))

            // Configure session for optimal measurement
            val config = newSession.config.apply {
                planeFindingMode = com.google.ar.core.Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                lightEstimationMode = com.google.ar.core.Config.LightEstimationMode.AMBIENT_INTENSITY
                updateMode = com.google.ar.core.Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = com.google.ar.core.Config.FocusMode.AUTO

                // Enable depth API for ToF sensors (like S25 Ultra)
                depthMode = com.google.ar.core.Config.DepthMode.AUTOMATIC

                // Enable instant placement for faster measurements
                instantPlacementMode = com.google.ar.core.Config.InstantPlacementMode.LOCAL_Y_UP
            }

            newSession.configure(config)

            val wm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(WindowManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
            val metrics = context.resources.displayMetrics
            newSession.setDisplayGeometry(rotation, metrics.widthPixels, metrics.heightPixels)

            session = newSession

            Result.success(newSession)
        } catch (e: UnavailableArcoreNotInstalledException) {
            Result.failure(ArCoreException.NotInstalled("ARCore is not installed"))
        } catch (e: UnavailableApkTooOldException) {
            Result.failure(ArCoreException.OutdatedApk("ARCore APK is too old"))
        } catch (e: UnavailableSdkTooOldException) {
            Result.failure(ArCoreException.OutdatedSdk("Device SDK is too old"))
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Result.failure(ArCoreException.Unsupported("Device is not compatible with ARCore"))
        } catch (e: Exception) {
            Result.failure(ArCoreException.Unknown("Failed to create ARCore session: ${e.message}"))
        }
    }

    /**
     * Resume the ARCore session
     */
    fun resume(): Result<Unit> {
        return try {
            session?.resume()
            Result.success(Unit)
        } catch (e: CameraNotAvailableException) {
            Result.failure(ArCoreException.CameraNotAvailable("Camera not available"))
        } catch (e: Exception) {
            Result.failure(ArCoreException.Unknown("Failed to resume session: ${e.message}"))
        }
    }

    /**
     * Pause the ARCore session
     */
    fun pause() {
        session?.pause()
    }

    /**
     * Cleanup and destroy the ARCore session
     */
    fun destroy() {
        session?.close()
        session = null
    }

    fun getSession(): Session? = session
}

/**
 * ARCore availability states
 */
enum class ArCoreAvailability {
    SUPPORTED_INSTALLED,
    SUPPORTED_NOT_INSTALLED,
    UNSUPPORTED,
    UNKNOWN
}

/**
 * Custom exceptions for ARCore errors
 */
sealed class ArCoreException(message: String) : Exception(message) {
    class NotInstalled(message: String) : ArCoreException(message)
    class OutdatedApk(message: String) : ArCoreException(message)
    class OutdatedSdk(message: String) : ArCoreException(message)
    class Unsupported(message: String) : ArCoreException(message)
    class CameraNotAvailable(message: String) : ArCoreException(message)
    class Unknown(message: String) : ArCoreException(message)
}
