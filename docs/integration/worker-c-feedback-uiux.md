# Worker C Feedback UI/UX Integration

## 담당 범위

- 담당자: 작업자 C
- 기능 분야: UI/UX, 접근성 피드백, 진동 피드백, 위험도 안내 안정화
- 원본 코드: `UI_UX_WalkGuide_여준호_260416/app/src/main/java/com/JunHo/walkguide/MainActivity.kt`
- WalkAssist 통합 브랜치: `codex/feedback-uiux`

## 통합 방식

원본 코드는 UI, TTS, 진동, 센서 상태, 히스테리시스, SOS, 권한 요청, SharedPreferences가 하나의 `MainActivity`에 집중되어 있었다. WalkAssist에는 원본 Activity를 그대로 복사하지 않고, 피드백 기능만 재사용 가능한 모듈로 분리했다.

## 추가된 파일

- `app/src/main/java/com/example/walkassist/feedback/FeedbackModels.kt`
  - 위험도 단계, 센서 상태, 센서 타입, 장애물 입력 데이터, UI 상태, 임계값 정의
- `app/src/main/java/com/example/walkassist/feedback/FeedbackViewModel.kt`
  - 거리/신뢰도 기반 위험도 상태 계산
  - 히스테리시스 적용
  - confidence window 적용
  - coroutine 기반 센서 watchdog 적용
  - 중복 안내 throttle 적용
- `app/src/main/java/com/example/walkassist/feedback/FeedbackManager.kt`
  - TalkBack 활성 여부에 따른 안내 방식 분기
  - 자체 TTS 출력
  - Audio Focus 요청
  - 위험도별 진동 패턴 출력
- `app/src/main/java/com/example/walkassist/feedback/FeedbackPreviewActivity.kt`
  - 안전/주의/위험 피드백을 독립적으로 확인할 수 있는 테스트 화면
- `app/src/main/res/layout/activity_feedback_preview.xml`
  - 피드백 상태 확인용 UI

## Gemini 리뷰 반영 내용

- Massive Activity 구조를 그대로 가져오지 않고 `ViewModel`과 `Manager`로 책임을 분리했다.
- `Handler` 기반 watchdog 대신 `viewModelScope`와 coroutine `delay`를 사용했다.
- 위험도 경계값이 흔들릴 때 경보가 튀지 않도록 히스테리시스를 유지했다.
- 낮은 confidence가 섞인 프레임을 바로 믿지 않도록 confidence window를 유지했다.
- TalkBack이 켜져 있으면 Accessibility announcement를 우선 사용하고, 그렇지 않으면 자체 TTS를 사용한다.
- 위험도별로 TTS pitch/rate와 진동 패턴을 다르게 적용했다.

## 현재 제외한 범위

- SOS 문자/전화 기능은 이번 브랜치에 통합하지 않았다.
- 보호자 설정 SharedPreferences UI도 이번 브랜치에 통합하지 않았다.
- 이유: 작업자 C의 담당 핵심은 UI/UX 및 진동 피드백이며, SOS는 별도 권한/개인정보/실제 발신 리스크가 있어 별도 브랜치에서 다루는 것이 안전하다.

## 다음 작업 제안

- 기존 AR 위험도 계산 결과를 `FeedbackViewModel.reportObstacle()`로 전달
- 메인 화면의 기본 안내 UI에 `FeedbackUiState`를 연결
- AR 안내, OCR 안내, 지도 안내가 동시에 말하지 않도록 전역 speech queue 설계
- SOS 기능이 필요하면 `SOSManager`, `UserPreferencesRepository`, 권한 launcher를 별도 브랜치로 구현
