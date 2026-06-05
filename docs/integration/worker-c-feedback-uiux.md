# 피드백 UI/UX 통합 상태

## 현재 범위

피드백 계층은 AR 측정, 지도 안내, OCR, Gemini 결과를 화면 상태, TTS, 진동, 접근성 announcement로 전달한다.

## 실제 구성 파일

Core:

- `app/src/main/java/com/example/walkassist/feedback/core/FeedbackModels.kt`
- `app/src/main/java/com/example/walkassist/feedback/core/FeedbackRequest.kt`
- `app/src/main/java/com/example/walkassist/feedback/core/FeedbackPolicy.kt`

State and mapping:

- `app/src/main/java/com/example/walkassist/feedback/engine/ArFeedbackMapper.kt`
- `app/src/main/java/com/example/walkassist/feedback/engine/FeedbackViewModel.kt`

Runtime:

- `app/src/main/java/com/example/walkassist/feedback/runtime/FeedbackManager.kt`
- `app/src/main/java/com/example/walkassist/feedback/runtime/SpeechFeedbackController.kt`
- `app/src/main/java/com/example/walkassist/feedback/runtime/HapticFeedbackController.kt`
- `app/src/main/java/com/example/walkassist/feedback/runtime/AccessibilityAnnouncer.kt`
- `app/src/main/java/com/example/walkassist/feedback/runtime/FeedbackQueue.kt`

UI:

- `app/src/main/java/com/example/walkassist/feedback/ui/FeedbackOverlay.kt`
- `app/src/main/java/com/example/walkassist/MainActivity.kt`

이전 문서에 적혀 있던 `FeedbackPreviewActivity.kt`와 `activity_feedback_preview.xml`은 현재 프로젝트에 존재하지 않는다.

## 현재 동작

- `ArFeedbackMapper`가 `ArMeasurementState`를 피드백 입력으로 변환한다.
- `FeedbackViewModel`은 UI 상태와 센서 입력 watchdog을 관리한다.
- `FeedbackPolicy`는 장애물 거리, 지도, OCR, 센서 상태별 우선순위와 출력 방식을 결정한다.
- `FeedbackManager`는 공용 TTS controller, 진동, tone, 접근성 announcement를 실행하고 반복 출력을 제한한다.
- `FeedbackOverlayCard`와 메인 안내 화면이 위험 단계, 거리, 방향, 센서 상태를 표시한다.
- 한국어와 영어 UI/TTS 전환을 지원한다.

## 활성 경로와 비활성 경로

- `MainActivity`, `MapNavigationActivity`, `SpatialReplayTestActivity`는 `FeedbackManager`를 직접 사용한다.
- `FeedbackQueue` 클래스는 우선순위 큐 구현을 포함하지만 현재 앱 코드에서 인스턴스화하지 않는다.
- 따라서 현재 실행 동작을 설명할 때 모든 피드백이 `FeedbackQueue`를 통과한다고 기술하면 안 된다.

## 현재 제한

- 거리 기반 장애물 정책의 여러 우선순위 경계값이 같은 값으로 설정되어 있어 세부 단계 구분이 제한적이다.
- AR 장애물 기본 정책은 음성보다 진동을 중심으로 구성되어 있으며, 지도·OCR·사용자 요청 결과는 TTS를 사용한다.
