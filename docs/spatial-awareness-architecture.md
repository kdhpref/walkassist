# WalkAssist 공간 인식 아키텍처

이 문서는 제안 구조가 아니라 현재 코드에 구현된 공간 인식 흐름을 설명한다.

## 전체 흐름

```text
ARCore Frame
  ├─ camera pose / planes / hit tests
  ├─ raw depth + confidence
  └─ camera image
       ├─ YOLO26n-seg 객체 및 분할 polygon
       ├─ one-shot OCR
       └─ one-shot Gemini 이미지 설명

hit tests + raw depth
  └─ WorldLocalMap
       └─ 좌/중앙/우 공간, 충돌 거리, 이동 방향, TTC

측정 결과
  └─ ArMeasurementBridge
       ├─ Compose 오버레이
       └─ FeedbackViewModel / FeedbackPolicy / FeedbackManager
```

라이브 파이프라인의 중심은 `WalkAssistArFragment.kt`다. 프레임별 공간 측정과 저주기 영상 분석을 결합한 뒤 `ArMeasurementState`를 발행한다.

## 라이브 AR 파이프라인

### ARCore 측정

현재 사용하는 값:

- `Frame.camera.displayOrientedPose`: 카메라 위치, 방향, pitch, 이동 속도 계산
- 수평·수직 plane 추적
- 화면 corridor hit test
- `acquireRawDepthImage16Bits`: raw depth
- `acquireRawDepthConfidenceImage`: depth confidence
- 선택적 ARCore Geospatial pose

`rawDepth`, hit test 관측점은 `WorldLocalMap`에 누적된다. 로컬 지도는 카메라 기준 좌·중앙·우 차선의 거리와 열린 정도를 계산하는 데 사용된다.

### YOLO 객체 분할

`ObjectAnalyzer`는 `app/src/main/assets/yolo26n-seg.tflite`와 `labels.txt`를 읽어 객체 bounding box, confidence, 분할 요약 및 polygon을 생성한다.

객체 거리 계산은 다음 순서를 사용한다.

1. 분할 polygon이 충분하면 polygon 내부 raw depth를 샘플링한다.
2. 분할 기반 추정이 불가능하면 bounding box 내부와 주변 raw depth를 샘플링한다.
3. 객체 추적 결과와 결합해 접근 속도, 객체별 TTC, 회피 방향을 보강한다.

따라서 “마스크-깊이 결합은 향후 작업”이라는 설명은 현재 코드와 맞지 않는다. 이미 구현되어 있으며 품질 검증과 튜닝이 남아 있다.

### 위험도 및 피드백

`WalkAssistArFragment`는 로컬 지도 기반 충돌 거리, 접근 속도, 카메라 이동 속도로 위험도와 TTC를 계산한다. 결과는 `ArMeasurementBridge`를 거쳐 `MainActivity`와 피드백 계층에 전달된다.

피드백 계층:

- `ArFeedbackMapper`: AR 측정 상태를 피드백 입력으로 변환
- `FeedbackViewModel`: 화면 상태와 센서 watchdog 관리
- `FeedbackPolicy`: 거리 및 요청 종류별 우선순위·출력 방식 결정
- `FeedbackManager`: TTS, 진동, 접근성 announcement 실행

`FeedbackQueue` 구현은 존재하지만 현재 앱 실행 경로에서 생성하지 않는다.

## 수동 인식 기능

### Gemini VLM

화면을 짧게 누르면 현재 카메라 이미지를 `gemini-2.5-flash-lite`에 전송한다.

현재 상태:

- `WalkAssistVlmFactory`는 항상 `GeminiVlmSceneInterpreter`를 생성한다.
- `VlmModelOption`에서 선택 가능한 값은 `GEMINI_API` 하나다.
- Gemini 요청 payload에는 RGB 이미지와 프롬프트만 들어간다.
- `SpatialFrame`에 AR 상태가 들어 있어도 현재 Gemini 요청에는 구조화된 AR/객체 컨텍스트를 포함하지 않는다.
- Gemini 응답은 결정적 안전 판단의 대체물이 아니라 사용자 요청 장면 설명으로 처리된다.

Florence-2 관련 Android 코드와 데스크탑 도구는 남아 있으나 현재 VLM 팩토리와 설정 흐름에 연결되어 있지 않다.

### OCR

화면을 2초간 움직이지 않고 누르면 현재 프레임을 `OneShotOcrReader`에 전달한다. ML Kit Korean Text Recognition 결과는 `FeedbackPolicy.ocrRequest()`를 거쳐 TTS로 안내된다.

## 지도 및 경로 결합

`MapNavigationActivity`는 다음 기능을 담당한다.

- Naver Map 표시 및 현재 위치 추적
- Android `Geocoder` 목적지 검색
- TMap 보행 경로 API 호출
- 경로 polyline과 안내점 표시
- 회전 벡터 센서와 GPS bearing을 이용한 방향 안내
- `SharedRouteNavigation`을 통한 AR 화면 경로 공유

라이브 AR 화면에서 Geospatial 플래그를 켜면 공유 경로와 ARCore Geospatial pose를 사용해 현실 방향 안내를 계산한다. 이 플래그의 기본값은 OFF다.

## 영상 리플레이 진단

`SpatialReplayTestActivity`와 `VideoFrameAnalyzer`는 일반 영상 프레임을 분석하는 진단 경로다.

- YOLO 객체 분할
- `FloorSegmenter`의 색상 기반 heuristic 바닥 경계 분석
- 영상 기반 거리 추정, 경로 분석

일반 영상에는 ARCore pose, hit test, plane, raw depth가 없으므로 이 경로는 라이브 AR 파이프라인을 대체하지 않는다. 또한 기본 UI에서 `SpatialReplayTestActivity`로 이동하는 버튼은 없다.

## 기능 상태 구분

| 기능 | 상태 |
| --- | --- |
| ARCore hit test, raw depth, confidence | 라이브 활성 |
| 로컬 occupancy map | 라이브 활성 |
| YOLO26n-seg 객체 분할 | 라이브 활성 |
| YOLO polygon 내부 raw depth 결합 | 라이브 활성 |
| Gemini 이미지 설명 | 수동 활성 |
| ML Kit OCR | 수동 활성 |
| Naver Map + TMap 경로 | 지도 화면 활성 |
| Geospatial 경로 결합 | 선택 기능, 기본 OFF |
| heuristic 바닥 경계 분석 | 영상 리플레이 진단 전용 |
| Florence-2 ONNX VLM | 코드 잔존, 현재 실행 경로 비활성 |
| Qwen2 VLM | 데스크탑 자산 준비 도구만 존재 |
| 명시적 IMU 사용 | 지도 화면의 회전 벡터에서 사용, 라이브 AR 주 경로는 ARCore pose 사용 |

## 논문 작성 시 주의

- 구현된 기능, 선택적 실험 기능, 코드만 남은 비활성 기능을 구분해 기술한다.
- 라이브 AR 결과와 영상 리플레이 결과를 같은 실험 조건으로 취급하지 않는다.
- 영상 리플레이의 heuristic 바닥 경계 분석을 학습 모델 기반 분할로 기술하지 않는다.
- 현재 Gemini가 AR/depth 컨텍스트를 입력받는다고 기술하지 않는다.
