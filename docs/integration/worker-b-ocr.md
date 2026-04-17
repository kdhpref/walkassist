# Worker B OCR Integration

## 담당 범위

- 담당자: 작업자 B
- 기능 분야: OCR, 실시간 카메라 문자 인식, 문자 음성 안내
- 원본 코드: https://github.com/sungyu22/OcrApp
- WalkAssist 통합 브랜치: `codex/ocr-integration`

## 통합 방식

원본 OcrApp은 별도 Android 프로젝트이므로 `MainActivity.kt`를 그대로 복사하지 않고, WalkAssist 앱 내부에 `com.example.walkassist.ocr` 패키지로 기능을 분리했다.

## 추가된 파일

- `app/src/main/java/com/example/walkassist/ocr/OcrReaderActivity.kt`
  - OCR 화면, CameraX Preview 바인딩, 카메라 권한 요청, 접근성 안내 담당
- `app/src/main/java/com/example/walkassist/ocr/OcrTextAnalyzer.kt`
  - CameraX `ImageAnalysis.Analyzer`
  - ML Kit Korean Text Recognition 실행 담당
- `app/src/main/java/com/example/walkassist/ocr/OcrViewModel.kt`
  - 최근 발화 텍스트와 발화 시각 보존
  - 동일 텍스트 중복 발화 방지 및 3초 throttle 판단
- `app/src/main/java/com/example/walkassist/ocr/OcrUiState.kt`
  - OCR 화면 상태 모델
- `app/src/main/java/com/example/walkassist/ocr/OcrTtsManager.kt`
  - 한국어 TTS 초기화 및 음성 출력 담당
- `app/src/main/res/layout/activity_ocr_reader.xml`
  - OCR 카메라 프리뷰와 인식 텍스트 표시 UI

## Gemini 리뷰 반영 내용

- Toast 기반 권한 오류 안내를 제거하고 TTS 및 Accessibility announcement 기반 안내로 변경했다.
- `lastSpokenText`, `lastSpokenTime` 상태를 Activity가 아닌 `OcrViewModel`로 이동했다.
- OCR 분석 로직을 Activity 내부 클래스가 아닌 `OcrTextAnalyzer` 독립 파일로 분리했다.
- TTS 초기화 완료 시 "문자 인식을 시작합니다." 안내를 출력하도록 구성했다.
- 카메라 바인딩 실패 시 로그만 남기지 않고 "카메라를 시작할 수 없습니다." 음성 안내를 제공한다.
- UI 반영은 `lifecycleScope`를 사용하여 Activity 생명주기와 함께 관리한다.

## 현재 한계

- 아직 메인 AR 화면에서 OCR 화면으로 진입하는 버튼은 연결하지 않았다.
- OCR 결과를 위험도 판단 엔진이나 보행 안내 우선순위 엔진과 통합하지 않았다.
- 텍스트의 의미 필터링, 표지판 우선순위, 횡단보도/신호등 안내와의 충돌 해결은 후속 작업이 필요하다.
- 실제 기기에서 카메라 권한 거부, 카메라 점유 상태, ML Kit 초기 다운로드 상황을 추가 검증해야 한다.

## 다음 작업 제안

- 메인 UI에서 OCR 모드를 열 수 있는 진입점 추가
- OCR 결과를 안내 우선순위 엔진에 이벤트로 전달
- 표지판, 방향 안내, 위험 문구 등 보행 관련 텍스트만 읽도록 필터링
- TTS 안내 충돌 방지를 위해 AR 위험 안내, 지도 안내, OCR 안내를 하나의 speech queue로 통합
