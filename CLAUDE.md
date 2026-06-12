# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

This is an Android Studio project — a single-module app (`:app`) using Kotlin DSL Gradle 9.1.0, AGP 9.0.1, Kotlin 2.2.20, JDK 11, `minSdk` 31, `targetSdk` 36.

- Debug build: `./gradlew :app:assembleDebug`
- Release build: `./gradlew :app:assembleRelease` (R8 + resource shrinking on)
- Install on connected device: `./gradlew :app:installDebug`
- Lint: `./gradlew :app:lintDebug`
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Single unit test: `./gradlew :app:testDebugUnitTest --tests "com.example.measureapp.SomeTest.someMethod"`
- Instrumented (device required): `./gradlew :app:connectedDebugAndroidTest`
- Clean: `./gradlew clean`

There are currently no real test classes — only the default JUnit/Espresso scaffolding from the dependencies block.

A device with ARCore support and a rear camera is required to actually run the app. The manifest declares `android.hardware.camera.ar` as required and `com.google.ar.core` as required meta-data.

Dependency versions live in `gradle/libs.versions.toml` (version catalog). Add libraries there rather than hardcoding versions in `app/build.gradle.kts` when a catalog entry would fit.

## High-level architecture

### Entry point

`MainActivity` (package root) is the `LAUNCHER` — a Compose `ComponentActivity` that mounts `navigation/AppNavigation.kt`, the bottom-bar shell with Measure / Level / History / Settings tabs. The Measure tab's "Start Measuring" button launches `ar.MeasureActivity` (now `exported="false"`), which hosts the AR session. Completed measurements (Done button) are persisted to Room via `MeasurementRepository`, gated by the auto-save preference (default on).

### Process startup

`di.MeasureApplication` (`@HiltAndroidApp`) is the `android:name` in the manifest. It plants Timber in debug builds. All DI flows through Hilt; `MeasureActivity` is `@AndroidEntryPoint` and `@HiltViewModel`s live in `viewmodel/`.

### AR pipeline (the heart of the app)

`MeasureActivity` hosts a SceneView `ARSceneView` (`io.github.sceneview:arsceneview:2.3.0`, a Kotlin wrapper over ARCore) plus a custom `OverlayView` (a `View` that does all 2D screen-space drawing each frame). **Don't downgrade SceneView below 2.3.0** — earlier versions bundle Filament `.so`s without 16 KB page alignment (Play requirement); `check_16kb_alignment.py` at the repo root verifies the APK.

Each ARCore frame, `onSessionUpdated` does:
1. Hit-test the screen center against detected planes and the depth map.
2. Hand the hit result to `MeasurementManager`, which computes a `SmartHit` — one of `None`, `Surface`, `SnappedVertex` (to a placed corner), or `SnappedEdge` (to an existing line, detected-rectangle corner/edge, or a physical edge found by `DepthEdgeSnapper`, which scans the 16-bit depth image for discontinuities around the reticle and snaps to the foreground side). Snap targets persist across finished chains. Snapping drives the iOS-style "magnet" feel. Hit selection (`performBestHitTest` in the activity) is multi-sample and distance-based: depth hits clearly in front of a plane hit win (foreground bias against depth bleed at object boundaries), and measurements lock to the plane they started on.
3. Update the reticle (drawn by `OverlayView` in 2D: thin ring + dot, green when snapped) and the live measurement pill.
4. `RectangleDetector` separately walks ARCore plane polygons looking for 4-corner rectangles within angle/size tolerances; results render as an overlay and feed `rectangleSnapTargets`.

**Measure modes**: `MeasurementManager.MeasureMode.LINE` (point-to-point on surfaces) and `HEIGHT` (first tap anchors a base point; the live point is the closest point on the vertical world axis through the base to the screen-center camera ray — pure geometry, deliberately depth-independent because the Depth API is broken on some devices, e.g. S25 Ultra). Height measurements auto-complete after the second point. Mode pills live in `activity_measure.xml` (`mode_row`).

**Rendering model (important)**: measurement lines and labels are NOT 3D nodes. `MeasurementManager.renderSegments` holds `(startAnchor, endAnchor)` pairs; `OverlayView` projects the live anchor poses every frame and draws thin 2D lines + label pills. This keeps lines crisp, is automatically drift-corrected as ARCore refines anchors, and replaced an older `CylinderNode` + `refreshLines()` approach that produced thick shaded tubes and ghost-line index-mismatch bugs. Only the small white corner-dot spheres are real 3D nodes. When placing a `SnappedEdge` point, the anchor must be created at the **snapped** pose (`hitResult.trackable.createAnchor(pose)`), not the raw hit.

`MeasurementCapture` screenshots the SceneView's `SurfaceView` via `PixelCopy`, composites the overlay on top, and saves to MediaStore for the "share" flow.

### On-device ML (`ar/ml/`)

- **`PersonHeightEstimator`** — ML Kit Pose Detection (STREAM_MODE, TFLite under the hood) on the ARCore CPU camera image, throttled to every 10th frame. Landmarks come back in upright-image coordinates → inverse-rotated to sensor pixels → `frame.transformCoordinates2d(IMAGE_PIXELS → VIEW)` → hit tests (feet against horizontal-upward plane, nose against depth points) → height = vertical span + 0.12 m nose-to-crown offset, median-filtered over 6 samples. `MeasureActivity` renders it as an `OverlayView.PersonHeightIndicator` line plus the subtitle pill; tapping the pill saves a `PERSON_HEIGHT` entity. Gated by the `person_detection` DataStore preference.
- **`MeasurementAutoLabeler`** — ML Kit Image Labeling classifies the frame when a measurement is saved; allowlist-filtered label ("Table", "Door", …) goes into `MeasurementEntity.label`.
- **ML Kit version skew gotcha**: `pose-detection` pulls a newer `vision-common`; `image-labeling`/`object-detection` must stay on versions compiled against the same interfaces or you get "Cannot access 'Detector'" compile errors. Versions are pinned individually in the catalog — bump them together.
- The raw `org.tensorflow:tensorflow-lite*` dependencies are still unused (reserved for custom models); ML Kit bundles its own TFLite runtime.

### Data layer

- **Room** database `MeasureDatabase` (version 2, `exportSchema = true` → `app/schemas/`) with entities `MeasurementEntity`, `PointEntity` (FK + CASCADE), `ProjectEntity`. `Converters` handles enum (`MeasurementType`, `UnitType`) ↔ String. Currently uses `fallbackToDestructiveMigration(true)` — bump the version and the DB will wipe on next install.
- **`MeasurementRepository`** wraps both DAOs and exposes `Flow<List<…>>`; it uses `database.withTransaction { … }` for the measurement-plus-points insert.
- **`PreferencesRepository`** uses Jetpack DataStore (`measure_preferences`) for unit type, tutorial-seen, haptic, sound, autosave.
- DI: `di/DatabaseModule.kt` provides DB + DAOs + repositories; `di/AppModule.kt` provides calculators and `PreferencesRepository`.

### UI layers (mixed)

- `MeasureActivity` is a classic `AppCompatActivity` with an XML layout (`res/layout/activity_measure.xml`) — it does **not** use Compose because SceneView wants a real `SurfaceView` in a `ConstraintLayout`.
- `ui/screens/` (`HistoryScreen`, `SettingsScreen`) and `level/LevelScreen` are **Compose**, consumed by `AppNavigation`. (`ui/screens/MeasureScreen.kt` and `ui/NavGraphWithBottomBar.kt` are empty stub files.)
- `OverlayView` is a hand-rolled `View` doing per-frame `Canvas` drawing for labels and rectangle overlays.

### Domain layer

`domain/usecase/` (`SaveMeasurement`, `GetMeasurements`, `DeleteMeasurement`, `CalculateDistance`) and `domain/calculator/` (`DistanceCalculator`, `AreaCalculator`) are thin — they're @Inject-able units mostly called from `MeasureViewModel`. Note that `MeasureViewModel` exists and is wired through Hilt but the legacy `MeasureActivity` doesn't actually consume it — it calls `MeasurementManager` directly. Adding new measurement logic, decide deliberately which side of that boundary it goes on.

## Gotchas

- **Two utility packages**: `util/` (`PermissionUtils`, `MathUtils`, `UnitConverter`) and `utils/` (`HapticFeedback`). Don't add to whichever you find first by accident — `util/` is the one used by the data/domain layers; `utils/` is AR-side.
- **`kapt`** is used for both Room and Hilt — KSP migration hasn't happened. Don't mix the two annotation processors.
- **`logcat_file`** (~940 KB) is checked into the repo root. It's a dump, not source — don't treat it as part of the build.
- **Schema export path**: `app/schemas/` is set via `kapt arguments` (`room.schemaLocation`). When you bump `MeasureDatabase` version, a new JSON appears there — commit it.
- **`namespace = "com.example.measureapp"`** and `applicationId` are still on the placeholder; renaming is non-trivial because of the `BuildConfig` import in `MeasureApplication` and many string-fully-qualified class references in the activity.
- **Lint disables `PermissionLaunchedDuringComposition`** in `app/build.gradle.kts` — camera permission is requested from the activity (not from Compose), so the warning is irrelevant.
