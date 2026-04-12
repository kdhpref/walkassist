# WalkAssist YOLO Lab

Windows 데스크탑에서 `YOLO 데이터 라벨링 -> 테스트 추론 -> 학습 실행 -> export`까지 빠르게 돌리기 위한 PySide6 도구입니다.

## 기능

- 이미지 폴더 기반 bbox 라벨링
- YOLO txt 라벨 저장 / 불러오기
- 클래스 목록 관리
- `dataset.yaml` 생성
- 현재 모델로 `테스트 추론` 실행
- 현재 모델 예측을 `자동 박스 추가`
- 선택한 박스의 클래스를 수동으로 다시 지정
- `yolo detect train ...` 명령 실행
- `yolo export ...` 명령 실행

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

또는 [run_yolo_lab.bat](/C:/Users/Administrator/AndroidStudioProjects/WalkAssist/desktop_tools/yolo_lab/run_yolo_lab.bat)를 실행합니다.

## 권장 데이터 구조

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

라벨링 탭에서는 `images/train` 같은 실제 이미지 폴더와 대응하는 `labels/train` 폴더를 직접 선택해서 작업하면 됩니다.

## 기본 클래스 예시

- `person`
- `crosswalk`
- `stairs_up`
- `stairs_down`
- `traffic_light_red`
- `traffic_light_green`

신호등 상태를 실제 안내에 활용하려면 `traffic_light` 하나만 두는 것보다 상태별 클래스로 직접 라벨링하는 편이 더 단순하고 발표용 설명도 분명합니다.

추천 작업 흐름:
1. `테스트 추론`으로 현재 이미지 예측 확인
2. `자동 박스 추가`로 bbox 초안 생성
3. 필요한 박스를 클릭
4. 클래스 콤보에서 `traffic_light_red` 또는 `traffic_light_green` 선택
5. `선택 박스 클래스 적용`
6. 저장

## 현재 범위

- 최소 기능 MVP입니다.
- 영상 프레임 추출, 학습 그래프 시각화, 프로젝트별 메타데이터 관리는 아직 포함하지 않았습니다.
