# 지도·길찾기 통합 상태

## 현재 범위

지도 기능은 별도 `MapNavigationActivity`에서 실행된다. Naver Map을 화면에 표시하고, Android `Geocoder`로 목적지를 찾은 뒤 TMap 보행 경로 API를 호출한다.

## 실제 구성 파일

- `app/src/main/java/com/example/walkassist/map/MapNavigationActivity.kt`: 지도 UI, 위치 권한, 목적지 검색, 경로 표시, 안내
- `app/src/main/java/com/example/walkassist/map/TMapRepository.kt`: TMap 응답 파싱 및 내부 경로 모델 변환
- `app/src/main/java/com/example/walkassist/map/TMapService.kt`: Retrofit API 인터페이스
- `app/src/main/java/com/example/walkassist/map/TMapModels.kt`: TMap 요청·응답 및 내부 route 모델
- `app/src/main/java/com/example/walkassist/map/RouteCameraGuidance.kt`: 경로와 카메라 방향 차이를 이용한 방향 안내
- `app/src/main/java/com/example/walkassist/map/SharedRouteNavigation.kt`: 지도 화면의 활성 경로를 AR 화면과 공유
- `app/src/main/res/layout/activity_map_navigation.xml`: 지도 화면 레이아웃

존재하지 않는 `RouteVoiceAnnouncer.kt`는 사용하지 않는다. 음성 안내는 공통 `FeedbackManager`를 통해 출력한다.

## 동작 흐름

1. `MainActivity`에서 길찾기 버튼을 누르면 `MapNavigationActivity`를 연다.
2. 위치 권한을 요청하고 Naver Map 현재 위치 추적을 시작한다.
3. 목적지를 `Geocoder`로 좌표화한다.
4. `TMapRepository`가 TMap 보행 경로를 요청한다.
5. 경로와 안내점을 지도에 표시하고 `SharedRouteNavigation`에 발행한다.
6. 지도 화면은 회전 벡터 센서와 GPS bearing으로 현재 방향과 경로 방향을 비교한다.
7. AR 화면에서 Geospatial 플래그를 켜면 공유 경로를 현실 방향 안내에 사용한다.

## 로컬 설정

```properties
NAVER_MAP_CLIENT_ID=your_naver_map_client_id
TMAP_API_KEY=your_tmap_api_key
```

- Naver Map Client ID는 manifest placeholder로 전달된다.
- TMap API key는 `BuildConfig.TMAP_API_KEY`로 전달된다.
- 값이 없으면 지도 인증 또는 경로 요청이 실패한다.

## 현재 제한

- 목적지 검색은 Android `Geocoder` 결과에 의존한다.
- 지도 경로와 AR 화면의 결합은 프로세스 내부 `SharedRouteNavigation` 상태를 사용한다.
- ARCore Geospatial 경로 보조는 기본값이 OFF인 디버그 플래그다.
