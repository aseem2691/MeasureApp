# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclassmembers class * {
    @dagger.hilt.* *;
    @javax.inject.* *;
}

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * {
    @androidx.room.* *;
}

# --- ARCore ---
-keep class com.google.ar.** { *; }

# --- SceneView / Filament ---
-keep class io.github.sceneview.** { *; }
-keep class com.google.android.filament.** { *; }

# --- ML Kit ---
-keep class com.google.mlkit.** { *; }

# --- TensorFlow Lite ---
-keep class org.tensorflow.** { *; }

# --- App enums (used in Room converters) ---
-keepclassmembers enum com.example.measureapp.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- App data models ---
-keep class com.example.measureapp.data.models.** { *; }
-keep class com.example.measureapp.data.local.entities.** { *; }

# --- Suppress warnings for optional dependencies ---
-dontwarn com.google.mlkit.vision.common.internal.Detector
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
