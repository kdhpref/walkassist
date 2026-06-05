# OCR 통합 상태

## 현재 범위

OCR은 별도 Activity나 CameraX preview를 사용하지 않는다. 라이브 AR 카메라 화면에서 사용자가 2초간 길게 누르면 다음 유효 프레임을 한 번 읽는 one-shot 기능이다.

## 실제 구성 파일

- `app/src/main/java/com/example/walkassist/ocr/OneShotOcrReader.kt`: ML Kit Korean Text Recognition 실행 및 결과 정규화
- `app/src/main/java/com/example/walkassist/WalkAssistArFragment.kt`: OCR 요청 플래그 관리, 카메라 프레임 전달
- `app/src/main/java/com/example/walkassist/MainActivity.kt`: 2초 long-press 제스처, OCR 결과의 피드백 요청
- `app/src/main/java/com/example/walkassist/feedback/core/FeedbackPolicy.kt`: OCR 음성 안내 요청 생성

빌드 의존성:

```kotlin
implementation("com.google.mlkit:text-recognition-korean:16.0.1")
```

## 동작 흐름

1. 사용자가 화면을 2초간 움직이지 않고 누른다.
2. `MainActivity`가 `WalkAssistArFragment.requestOneShotOcr()`를 호출한다.
3. AR fragment가 다음 유효 카메라 프레임을 bitmap으로 변환한다.
4. `OneShotOcrReader`가 한국어 OCR을 실행한다.
5. 인식 결과를 줄 단위로 정리하고 최대 280자로 제한한다.
6. 결과를 `FeedbackPolicy.ocrRequest()`와 `FeedbackManager`를 통해 TTS로 안내한다.

짧은 터치는 OCR이 아니라 Gemini 장면 설명 요청이다.

## 현재 제한

- OCR은 한국어 Text Recognizer 하나만 사용한다.
- 사용자가 요청한 시점과 실제 분석 프레임 사이에 짧은 지연이 생길 수 있다.
- 텍스트의 보행 관련성이나 위험 문구를 별도로 분류하지 않는다.
