# 작업자 A - 지도/길찾기 기능 통합 메모

## 담당 범위

작업자 A는 지도 및 보행자 길찾기 기능을 담당합니다.

## 원본 코드 출처

- 원본 프로젝트: `C:\Users\Administrator\Desktop\navermap\navermap`
- 원본 핵심 파일: `app/src/main/java/com/example/navermap/MainActivity.kt`
- 원본 기능: 네이버 지도 SDK 표시, 목적지 검색, TMap 보행자 경로 API 호출, 경로 polyline 렌더링

## WalkAssist 통합 방식

원본 코드는 별도 Android 프로젝트이며 패키지명이 `com.example.navermap`입니다. 따라서 WalkAssist의 `MainActivity`를 직접 교체하지 않고, 지도 기능을 `com.example.walkassist.map` 패키지 아래 별도 모듈로 분리했습니다.

## 통합 파일 분류

- `MapNavigationActivity.kt`: 지도 화면, 목적지 입력, 마커/경로 렌더링, 위치 권한 처리
- `TMapRepository.kt`: TMap 보행자 경로 API 호출 및 응답 파싱
- `TMapService.kt`: Retrofit API 인터페이스
- `TMapModels.kt`: TMap 요청/응답 및 내부 route 모델
- `RouteVoiceAnnouncer.kt`: Toast 대신 Android TTS 음성 안내
- `activity_map_navigation.xml`: 네이버 지도와 목적지 검색 UI

## Gemini 리뷰 반영 사항

- API Key 하드코딩 제거
- `local.properties` 기반 `BuildConfig.TMAP_API_KEY` 사용
- Naver Map Client ID는 manifest placeholder 사용
- Toast 제거 및 TTS 안내로 대체
- Retrofit 인스턴스 재사용 구조 적용
- Retrofit callback 대신 coroutine suspend API 사용
- 지도/네트워크/TTS/모델 파일 분리

## 필요한 로컬 설정

`local.properties`에 다음 값을 추가해야 지도 기능이 실제 동작합니다. 이 파일은 Git에 커밋하지 않습니다.

```properties
NAVER_MAP_CLIENT_ID=your_naver_map_client_id
TMAP_API_KEY=your_tmap_api_key
```

## 남은 통합 작업

- AR 보행 보조 화면에서 지도 화면으로 진입하는 UI 연결
- 지도 경로 안내와 ARCore 위험도/TTC 안내 우선순위 통합
- ViewModel 도입 및 Activity 로직 추가 분리
- 실제 API Key가 설정된 환경에서 지도 로딩 및 경로 API 실기기 검증
