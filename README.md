# MeasureApp 📏

A free, ad-free AR measuring app for Android — inspired by Apple's iOS Measure app, built with ARCore.

Point your camera at the world and measure real objects: distances, heights, paths, and people. Measurements stay anchored in 3D space, snap magnetically to corners and edges, and are saved to a searchable history.

## Features

### Measuring
- **Line mode** — point-to-point and multi-segment (polyline) measurements on real surfaces, with live distance readout. Measurements lock to the surface they start on for stability.
- **Height mode** — measure vertical objects (doors, furniture, screens) with pure ray geometry. Works independently of the device's depth sensor.
- **Smart snapping** — the reticle magnetically snaps to previously placed points `[Point]`, existing measurement lines `[Line]`, detected rectangles `[Rect]`, and physical object edges from the depth map `[Edge]`. The reticle turns green when snapped.
- **Rectangle detection** *(opt-in)* — automatically outlines rectangular surfaces with all four side lengths and area.

### On-device AI (ML Kit / TensorFlow Lite)
- **Person height detection** — point the camera at a person and their height appears automatically; tap the pill to save it.
- **Auto-labeling** — finished measurements are classified on-device ("Table", "Door", "Window"…) so History shows what you measured, not just numbers.

### App
- **History** — measurements persist locally (Room) with swipe-to-delete and clear-all.
- **Level tool** — iOS-style bubble level using device sensors.
- **Capture & share** — screenshot the AR view with measurements composited, saved to the gallery.
- **Settings** — metric/imperial units, haptics, sounds, auto-save, AI feature toggles.
- Modern iOS-inspired dark/light UI built with Jetpack Compose + Material 3.

## Requirements

- Android 12+ (`minSdk 31`)
- An [ARCore-supported device](https://developers.google.com/ar/devices) with Google Play Services for AR installed
- Camera permission

The APK is fully **16 KB page-size aligned** (Google Play requirement for Android 15+ devices).

## Building

Standard Android Studio project (AGP 9.x, Kotlin 2.2, JDK 11):

```bash
./gradlew :app:assembleDebug     # debug build
./gradlew :app:installDebug      # install on a connected device
./gradlew :app:assembleRelease   # minified release build
```

A physical ARCore-capable device is required to run the AR features (no emulator support).

## Tech stack

| Layer | Tech |
|---|---|
| AR | ARCore 1.50 + [SceneView](https://github.com/SceneView/sceneview-android) 2.3 (Filament) |
| ML | ML Kit pose detection, image labeling (TFLite on-device, GPU-accelerated) |
| UI | Jetpack Compose + Material 3 (app shell), Android Views (AR overlay) |
| DI | Hilt |
| Storage | Room (measurements), DataStore (preferences) |

### How measuring works

Measurement lines and labels are rendered as crisp 2D overlays projected from ARCore anchor poses every frame — so they stay glued in 3D space, sharpen at any distance, and automatically benefit from ARCore's anchor refinement. Hit testing is multi-sampled and foreground-biased: depth hits clearly in front of a detected plane win (so points land on objects, not the surface behind them), while planes win ties for stability.

## Accuracy notes

Phone AR measures with camera + motion tracking (and the Depth API where available) — not LiDAR. For best results:

- Scan the surface first: point at a textured area (wood, tiles, fabric) and move the phone slowly side to side.
- Measure flat objects from directly above.
- Use **Height mode** for vertical spans instead of free-pointing at elevated edges.
- Expect ~1–2 cm accuracy on well-tracked surfaces at arm's length.

## Project structure

```
app/src/main/java/com/example/measureapp/
├── ar/            # AR pipeline: MeasureActivity, MeasurementManager, OverlayView,
│   └── ml/        #   DepthEdgeSnapper + ML Kit person height & auto-labeling
├── ui/            # Compose screens (History, Settings) + theme
├── level/         # Bubble level tool
├── navigation/    # Bottom-tab navigation shell
├── data/          # Room entities/DAOs, repositories, DataStore preferences
├── domain/        # Use cases and calculators
├── viewmodel/     # Hilt ViewModels
└── di/            # Hilt modules
```
