# WalkAssist

Android obstacle-distance prototype for pedestrian assistance.

## Current pipeline

- `CameraX` preview and frame analysis
- `YOLOv8n` object detection from `app/src/main/assets/yolov8n.tflite`
- floor-boundary estimation from the lower camera image
- distance estimation from:
  - phone pitch
  - camera height assumption
  - detected object ground-contact point
- Compose overlay that shows:
  - detected object boxes
  - estimated distance
  - nearest obstacle card
  - debug panel

## Current limitations

- Distance is still a geometry estimate, not a true depth measurement
- Accuracy depends on:
  - seeing the floor clearly
  - object touching the floor
  - stable phone pitch
  - correct camera height/FOV assumptions
- `midas_v21_small.tflite` is still in the repo but not used in the current live pipeline

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

## Branches

- Active implementation branch: `codex/floor-segmentation`

## Next recommended work

- replace heuristic floor segmentation with a trained floor segmentation model
- tune camera-height and FOV assumptions per device
- add object-specific distance confidence and temporal filtering improvements
