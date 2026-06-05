# WalkAssist YOLO Lab

Windows 데스크탑에서 YOLO **bounding box detection** 데이터 라벨링, 테스트 추론, 학습, export를 수행하는 PySide6 도구다.

## 지원 기능

- 이미지 폴더 기반 bounding box 라벨링
- YOLO txt 라벨 저장 및 불러오기
- 클래스 목록 편집
- `dataset.yaml` 생성
- 현재 모델 테스트 추론
- 현재 모델 예측 box 자동 추가
- `yolo detect train ...` 실행
- `yolo export ...` 실행

## 중요한 범위 구분

- 이 도구는 polygon/mask 라벨링을 지원하지 않으며 학습 명령도 `yolo detect train`이다.
- 따라서 앱의 현재 `yolo26n-seg.tflite`를 그대로 재학습하는 segmentation 파이프라인은 아니다.
- 기본 클래스 목록은 `person`, 계단, 신호등 상태 등 WalkAssist 실험용 사용자 정의 목록이다.
- 앱에 포함된 현재 `app/src/main/assets/labels.txt`는 COCO 80개 클래스이며 YOLO Lab 기본 클래스와 일치하지 않는다.
- export 결과를 Android 앱에 넣으려면 모델 출력 형태, 라벨 순서, TFLite 호환성을 별도로 검증해야 한다.

## 설치

```powershell
cd C:\Users\Administrator\AndroidStudioProjects\WalkAssist\desktop_tools\yolo_lab
python -m pip install -r requirements.txt
python -m pip install ultralytics
```

## 실행

```powershell
cd C:\Users\Administrator\AndroidStudioProjects\WalkAssist\desktop_tools\yolo_lab
python app.py
```

또는 `run_yolo_lab.bat`를 실행한다.

## 권장 detection 데이터 구조

```text
dataset_root/
  images/
    train/
    val/
    test/
  labels/
    train/
    val/
    test/
  dataset.yaml
```

라벨링 화면에서는 실제 이미지 폴더와 대응하는 labels 폴더를 직접 선택할 수 있다.

## 기본 클래스

- `person`
- `stairs_up`
- `stairs_down`
- `traffic_light_red`
- `traffic_light_green`

클래스 순서는 학습과 추론 결과 해석에 직접 영향을 준다. export 모델을 앱에서 사용할 경우 앱의 `labels.txt`도 동일한 순서로 교체해야 한다.

## 현재 제한

- 최소 기능 MVP다.
- segmentation mask 라벨링과 `yolo segment train`을 지원하지 않는다.
- 영상 프레임 추출, 학습 그래프 시각화, 프로젝트별 데이터 버전 관리는 포함하지 않는다.
