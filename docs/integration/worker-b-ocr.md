# Worker B OCR Integration

## 담당 범위

- 담당자: 작업자 B
- 기능 분야: OCR, 현재 카메라 프레임 문자 인식, 문자 음성 안내
- 원본 코드: https://github.com/sungyu22/OcrApp
- WalkAssist 통합 브랜치: `codex/ocr-integration`

## 통합 방식

원본 OcrApp은 별도 Android 프로젝트이므로 `MainActivity.kt`를 그대로 복사하지 않았다. WalkAssist의 기존 AR/피드백 흐름을 유지하기 위해, OCR 버튼을 누른 순간 기존 AR 카메라 루프에서 다음 유효 프레임 한 장을 가져와 한 번만 읽는 방식으로 통합했다.

## 추가된 파일

- `app/src/main/java/com/example/walkassist/ocr/OneShotOcrReader.kt`
  - ML Kit Korean Text Recognition 실행 담당
  - 전달받은 비트맵 한 장에서 텍스트를 추출하고 TTS에 적합하도록 줄바꿈을 정리

## 변경된 파일

- `app/src/main/java/com/example/walkassist/MainActivity.kt`
  - UI모드와 카메라모드 양쪽 모두에서 접근 가능한 하단 중앙 OCR 버튼 유지
  - OCR 버튼 클릭 시 별도 Activity를 열지 않고 `WalkAssistArFragment.requestOneShotOcr()` 호출
  - OCR 결과를 기존 `FeedbackManager` 경로로 한 번 안내
- `app/src/main/java/com/example/walkassist/WalkAssistArFragment.kt`
  - OCR 요청 플래그를 추가해 다음 유효 AR 카메라 프레임 한 장만 OCR로 전달
  - 기존 AR 측정, 객체 감지, 피드백 루프를 중지하지 않음
- `app/build.gradle.kts`
  - `com.google.mlkit:text-recognition-korean:16.0.1` 의존성 추가

## 제거된 모드형 구현

- 별도 OCR Activity, CameraX Preview 레이아웃, OCR 전용 ViewModel/TTS/Analyzer는 제거했다.
- OCR은 앱의 모드를 바꾸지 않고 현재 화면에서 한 번 실행되는 보조 동작으로 동작한다.

## 현재 한계

- 버튼을 누른 직후 ARCore가 아직 카메라 이미지를 제공하지 못하면 다음 유효 프레임까지 기다린다.
- OCR 결과는 보행 안내 우선순위 엔진과 의미적으로 통합하지 않았고, 현재는 인식된 텍스트를 한 번 읽는 수준이다.
- 표지판, 방향 안내, 위험 문구 등 보행 관련 텍스트만 읽도록 필터링하는 작업은 후속으로 남아 있다.

## 검증 포인트

- UI모드/카메라모드에서 OCR 버튼 위치가 기존 조작을 방해하지 않는지 확인
- OCR 버튼 클릭 후 화면 전환 없이 기존 AR 안내가 계속 동작하는지 확인
- 텍스트가 있는 장면에서 한 번만 읽고 반복 발화하지 않는지 확인
