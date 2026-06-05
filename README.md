# WalkAssist

WalkAssist는 시각장애인 및 저시력 보행자를 위한 Android 보행 보조 프로토타입이다.
ARCore 공간 측정, 객체 분할, 지도 경로, OCR, 음성·진동 피드백을 하나의 앱에서 실험한다.

이 문서는 `main` 브랜치의 실제 코드와 빌드 설정을 기준으로 작성되었다.

## 기본 사용자 흐름

1. `MainActivity`가 `WalkAssistArFragment`와 Compose 오버레이를 표시한다.
2. `WalkAssistArFragment`가 ARCore 추적, hit test, raw depth, 객체 인식을 수행한다.
3. 측정 결과는 `ArMeasurementBridge`를 통해 UI와 피드백 계층으로 전달된다.
4. `FeedbackViewModel`, `FeedbackPolicy`, `FeedbackManager`가 화면 상태, 진동, TTS를 처리한다.
5. 화면을 짧게 누르면 Gemini 장면 설명을 요청하고, 2초간 길게 누르면 OCR을 실행한다.
6. 길찾기 화면은 Naver Map에 TMap 보행 경로를 표시하고 경로를 AR 화면과 공유한다.

## 현재 구현 상태

| 영역 | 상태 | 실제 동작 |
| --- | --- | --- |
| ARCore 공간 측정 | 기본 흐름에서 활성 | 카메라 자세, 평면, hit test, raw depth 및 confidence를 사용한다. |
| 로컬 공간 지도 | 기본 흐름에서 활성 | ARCore hit와 raw depth 관측을 2D occupancy map으로 누적한다. |
| 객체 인식 | 기본 흐름에서 활성 | `yolo26n-seg.tflite`를 LiteRT/TFLite 인터프리터로 실행한다. |
| 객체 마스크-깊이 결합 | 구현됨 | 분할 polygon 내부의 raw depth를 샘플링하고, 실패 시 bounding box 샘플링으로 대체한다. |
| 횡단보도 인식 | 부분 구현 | 영상 stripe 패턴과 선택적 지도 경로 cue를 결합한다. 현재 `labels.txt`에는 `crosswalk` 클래스가 없어 YOLO 횡단보도 confidence는 기본 모델에서 제공되지 않는다. |
| VLM | 기본 흐름에서 수동 실행 | 짧은 터치 시 `gemini-2.5-flash-lite`에 현재 RGB 이미지를 전송한다. 구조화된 AR/객체 컨텍스트는 현재 Gemini 요청에 포함하지 않는다. |
| OCR | 기본 흐름에서 수동 실행 | 2초 길게 누르면 ML Kit Korean Text Recognition으로 현재 프레임을 한 번 읽는다. |
| 지도 길찾기 | 기본 흐름에서 활성 | Naver Map, Android Geocoder, TMap 보행 경로 API, 회전 벡터 센서를 사용한다. |
| Geospatial 경로 보조 | 선택 기능 | 디버그 플래그 기본값은 OFF이며, 활성화 시 공유 경로와 ARCore Geospatial pose를 결합한다. |
| ARCore 녹화·재생 | 설정 화면에서 사용 | ARCore dataset 녹화와 저장된 dataset 재생을 지원한다. |
| 영상 리플레이 진단 | 진단 화면에서 사용 | YOLO 객체 분석과 heuristic 바닥 경계 분석을 수행한다. |

## 기본 흐름 밖에 남아 있는 코드

- `SpatialReplayTestActivity`는 manifest에 등록되어 있지만 기본 UI에서 진입하는 버튼은 없다.
- Florence-2 ONNX 실행 코드와 데스크탑 변환 도구는 남아 있지만, `VlmModelOption`과 `WalkAssistVlmFactory`의 현재 실행 경로는 Gemini API 하나만 선택한다.
- `desktop_tools/qwen2_vlm`은 자산 준비 실험 도구이며 Android 실행 경로와 연결되어 있지 않다.
- `FeedbackQueue`는 구현되어 있으나 현재 앱 코드에서 인스턴스화하지 않는다. 기본 피드백 출력은 `FeedbackManager`가 직접 처리한다.

코드 파일이 존재한다는 이유만으로 현재 앱 기능이라고 기술하면 안 된다. 논문이나 발표 자료에서는 위 상태 구분을 유지한다.

## 주요 코드 위치

- `app/src/main/java/com/example/walkassist/MainActivity.kt`: 앱 진입점, Compose UI, 제스처, 피드백 연결
- `app/src/main/java/com/example/walkassist/WalkAssistArFragment.kt`: 라이브 ARCore 및 비전 파이프라인
- `app/src/main/java/com/example/walkassist/ObjectAnalyzer.kt`: YOLO26n-seg 추론과 분할 polygon 복원
- `app/src/main/java/com/example/walkassist/WorldLocalMap.kt`: 로컬 occupancy map
- `app/src/main/java/com/example/walkassist/map/`: 지도 및 보행 경로
- `app/src/main/java/com/example/walkassist/ocr/`: one-shot OCR
- `app/src/main/java/com/example/walkassist/feedback/`: 피드백 정책, 상태, 출력
- `app/src/main/java/com/example/walkassist/VideoFrameAnalyzer.kt`: ARCore가 없는 영상 리플레이 진단

상세 구조는 `docs/spatial-awareness-architecture.md`를 참고한다.

## 모델 및 자산

앱에 포함되는 자산:

- `app/src/main/assets/yolo26n-seg.tflite`: 라이브 AR 및 영상 리플레이 객체 분할
- `app/src/main/assets/labels.txt`: 현재 YOLO 모델의 COCO 80개 클래스

YOLO 모델 배포 전 라이선스 검토 사항은 `docs/yolo-license-assessment.md`에 기록되어 있다.

## 로컬 설정

`local.properties`에 필요한 키를 설정한다.

```properties
NAVER_MAP_CLIENT_ID=your_naver_map_client_id
TMAP_API_KEY=your_tmap_api_key
GEMINI_API_KEY=your_gemini_api_key
```

- `NAVER_MAP_CLIENT_ID`: Naver Map SDK 인증
- `TMAP_API_KEY`: 보행 경로 요청
- `GEMINI_API_KEY`: 수동 Gemini 이미지 설명 및 지도 안내 번역

키가 없으면 해당 네트워크 기능은 정상 동작하지 않는다. 키를 Kotlin 소스에 직접 넣지 않는다.

## 빌드 및 실행

요구 환경:

- Android SDK 36
- JDK 11 호환 설정
- ARCore 및 카메라를 지원하는 Android 기기
- 최소 Android API 26

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat installDebug
```

앱은 카메라 권한을 필수로 사용한다. 지도와 선택적 Geospatial 기능은 위치 권한 및 네트워크 연결이 필요하다.

## 알려진 제한

- 기본 충돌 거리와 방향 판단은 로컬 occupancy map 및 ARCore depth에 의존한다.
- 현재 Gemini 요청은 RGB 이미지만 전송하며 AR 측정값을 구조화해 보내지 않는다.
- 현재 기본 YOLO 라벨에는 횡단보도 클래스가 없다.
- 지도 경로와 AR Geospatial 결합은 선택 기능이며 기본값이 OFF다.
- 프로젝트 텍스트 파일은 UTF-8을 사용하며 `.editorconfig`에서 인코딩을 고정한다.

## 문서

- `docs/spatial-awareness-architecture.md`: 현재 공간 인식 구조와 데이터 흐름
- `docs/integration/worker-a-map-navigation.md`: 지도·길찾기 통합 상태
- `docs/integration/worker-b-ocr.md`: OCR 통합 상태
- `docs/integration/worker-c-feedback-uiux.md`: 피드백 UI/UX 통합 상태
- `docs/yolo-license-assessment.md`: YOLO 배포 라이선스 위험 메모
- `desktop_tools/yolo_lab/README.md`: 데스크탑 bbox 라벨링·detect 학습 도구
