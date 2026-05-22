# YOLO License Assessment

This is an engineering risk note, not legal advice.

## Current Use

WalkAssist includes `app/src/main/assets/yolo26n-seg.tflite`, exported from the Ultralytics `yolo26n-seg.pt` pretrained model. The Android app runs the model locally through TensorFlow Lite, not through the Ultralytics Python runtime.

## Assessment

Ultralytics states that its trained YOLO models are AGPL-3.0 by default and that the AGPL path requires projects using AGPL-3.0 components to open-source larger works containing Ultralytics YOLO code and models. Ultralytics offers an Enterprise license for embedding YOLO code and models in commercial products without those AGPL constraints.

For this project, the practical conclusion is:

- Private local development and installing the debug APK on your own connected device does not by itself create a public release obligation.
- Distributing an APK that contains `yolo26n-seg.tflite`, publishing a closed-source app, or using the model in a commercial/proprietary product is high license risk unless the whole corresponding project is released under an AGPL-compatible open-source posture or an Ultralytics Enterprise license is obtained.
- Because this repository currently has no project-level `LICENSE` file, do not treat the repository as already AGPL-compliant solely because it is pushed to GitHub.
- If the goal is closed-source or commercial distribution, replace Ultralytics YOLO with a permissively licensed segmentation model or obtain an Enterprise license before release.

## Recommended Actions Before Release

1. Decide whether WalkAssist will be open-sourced under an AGPL-compatible license.
2. If not, remove Ultralytics-derived model assets and use a model with licensing compatible with proprietary Android distribution.
3. Add a repository `LICENSE` file and third-party notices before any public APK distribution.
