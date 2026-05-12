# WalkAssist

Android AR walking-assistance prototype for local spatial awareness.

## Current pipeline

- `ARCore` live camera, tracking, planes, hit tests, and raw depth
- local 2D occupancy map from ARCore world/depth observations
- `YOLO26n-seg` object detection and segmentation metadata from `app/src/main/assets/yolo26n-seg.tflite`
- `DeepLabV3-MobileNetV3 Cityscapes` floor segmentation from `app/src/main/assets/deeplabv3_cityscapes.tflite`
- heuristic floor segmentation fallback when the model is unavailable or throttled
- object distance enrichment from ARCore raw depth sampled inside object regions
- low-frequency Gemma VLM scene interpretation through ML Kit GenAI Prompt API
- Compose overlay that shows:
  - detected object boxes
  - ARCore depth distance
  - nearest obstacle card
  - local map
  - debug panel

## Current limitations

- YOLO segmentation masks are decoded as compact segment metadata; full mask-to-depth fusion is the next step
- The Cityscapes floor model is throttled and cached because it is expensive on-device
- Explicit Android IMU collection is not wired yet; phone pitch and motion currently come from ARCore camera pose
- VLM receives image plus compact CV context, but full ARCore depth/local-map context is still being structured

## Run

From Android Studio:

- Open `C:\Users\Administrator\AndroidStudioProjects\WalkAssist`
- Select the `app` run configuration
- Connect an Android device
- Run the app

From terminal:

```powershell
cmd /c gradlew.bat assembleDebug
cmd /c gradlew.bat installDebug
```

## Next recommended work

- add an explicit IMU/orientation provider for compass heading, pitch/roll/yaw, and motion stability
- fuse YOLO26n-seg masks with ARCore raw depth per object segment
- build a typed VLM spatial context object containing phone pose, local map, segment summaries, and depth evidence
