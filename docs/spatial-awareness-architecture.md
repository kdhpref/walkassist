# WalkAssist Spatial Awareness Architecture

## Model Choice

Use `YOLO26n-seg` as the live object model.

- `YOLO11n-seg` is lighter on paper, but official Ultralytics segmentation metrics list `YOLO26n-seg` with higher COCO mask accuracy and faster CPU ONNX latency than `YOLO11n-seg`.
- The app now keeps only the Android-ready TFLite asset: `app/src/main/assets/yolo26n-seg.tflite`.
- Legacy live-model assets were removed: `yolo11n.tflite`, `yolov8n.tflite`, and `midas_v21_small.tflite`.

## What The Intermediate Model Does

The intermediate model should not be a passive bundle of variables. It should be a typed spatial context layer between fast sensors/CV and the VLM.

Responsibilities:

- Normalize timestamps, units, and coordinate spaces.
- Fuse ARCore depth, YOLO segment metadata, local map cells, and phone pose into compact evidence.
- Attach freshness and confidence to each value.
- Reduce raw data before VLM invocation so the VLM receives semantic context instead of huge depth maps or raw masks.
- Preserve deterministic safety rules outside the VLM. The VLM explains and cross-checks; it should not be the only source of stop/go decisions.

Proposed shape:

```kotlin
data class VlmSpatialContext(
    val timestampMillis: Long,
    val phonePose: PhonePoseSummary,
    val motion: MotionSummary,
    val arDepth: ArDepthSummary,
    val localMap: LocalMapSummary,
    val objects: List<SegmentedObjectSummary>,
    val crosswalk: CrosswalkSummary,
    val primaryRisk: PrimaryRiskSummary,
)
```

## Sensor And Derived Values

### ARCore

- `cameraPose`: world position and orientation from `Frame.camera.displayOrientedPose`.
- `pitchDownDegrees`: camera forward vector angle below the horizontal plane.
- `motionMetersPerSecond`: camera pose delta over time.
- `planeCounts`: horizontal and vertical tracked plane counts.
- `rawDepth`: per-pixel depth in millimeters from `acquireRawDepthImage16Bits`.
- `rawDepthConfidence`: per-pixel confidence from `acquireRawDepthConfidenceImage`.
- `depthPoints`: `DepthPoint` hit-test results.
- `localMap`: 2D occupancy and free-space cells around the user.

### Android IMU / Orientation

- `rotationVector`: best source for yaw/pitch/roll when available.
- `gameRotationVector`: stable relative orientation without magnetic north; useful indoors, but not absolute direction.
- `accelerometer`: raw acceleration including gravity.
- `linearAcceleration`: motion acceleration with gravity removed.
- `gyroscope`: angular velocity; useful for detecting rapid phone movement and unstable frames.
- `gravity`: stable pitch/roll reference.
- `magneticField`: compass reference; combine with rotation vector or accelerometer for absolute north.
- Optional `stepDetector` or `stepCounter`: walking cadence and movement state.

### Location / Heading

- `fusedLocation.speed`: outdoor walking speed fallback.
- `fusedLocation.bearing`: course-over-ground when the user is moving.
- `compassAzimuthDegrees`: phone-facing direction relative to magnetic/true north.
- `facingCardinal`: derived label such as `north`, `east`, `south`, `west`, or 8-way labels like `northeast`.

### YOLO26n-seg Object Values

- `label`
- `confidence`
- `boundingBox`
- `segmentCoverageRatio`
- `segmentCenterXRatio`
- `segmentCenterYRatio`
- Future mask-depth fusion:
  - `segmentDepthMedianMeters`
  - `segmentDepthNearPercentileMeters`
  - `segmentDepthConfidence`
  - `lane`
  - `timeToCollisionSeconds`
  - `closingSpeedMetersPerSecond`

## VLM Invocation Policy

The VLM should run in two modes:

- Manual: the current VLM button triggers an immediate one-shot explanation.
- Continuous assist: periodic low-frequency checks, with faster invocation only when risk/confidence changes.

Recommended cadence:

- Normal walking: every 4-6 seconds.
- Low confidence, approaching object, crosswalk, stairs, curb, or blocked center lane: every 1.5-3 seconds.
- Cooldown after TTS: avoid repeating equivalent messages until evidence changes.

## VLM Input Principle

Send the RGB frame plus compact structured context:

- phone pitch/roll/yaw and facing direction
- motion/stability summary
- left/center/right free-space distances
- object segment summaries and per-segment depth
- ARCore depth confidence
- crosswalk and map evidence

The VLM output should stay constrained:

- scene summary
- walking action
- reason
- confidence
- whether it supports or disagrees with deterministic ARCore/CV guidance
