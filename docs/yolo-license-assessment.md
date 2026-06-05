# YOLO 라이선스 검토 메모

이 문서는 법률 자문이 아니다. 배포 또는 논문 공개 전에 모델 출처와 라이선스를 별도로 확인해야 한다.

## 저장소에서 확인 가능한 사실

- `app/src/main/assets/yolo26n-seg.tflite` 파일이 저장소에 포함되어 있다.
- Android 앱의 `ObjectAnalyzer`가 이 파일을 LiteRT/TFLite 인터프리터로 로컬 실행한다.
- Android 앱은 Ultralytics Python runtime을 직접 포함하거나 실행하지 않는다.
- `app/src/main/assets/labels.txt`는 COCO 80개 클래스 목록이다.
- 저장소 루트에 프로젝트 라이선스를 선언하는 `LICENSE` 파일이 없다.

## 저장소만으로 확인할 수 없는 사실

파일명과 기존 문서는 이 자산을 Ultralytics `yolo26n-seg` 계열로 취급하지만, 현재 저장소에는 다음 출처 증빙이 없다.

- 원본 모델 다운로드 URL 또는 checksum
- export에 사용한 정확한 명령과 Ultralytics 버전
- 원본 또는 변환 모델에 포함된 라이선스 파일
- 모델을 직접 학습했는지, pretrained 모델에서 변환했는지에 대한 기록

따라서 논문이나 배포 문서에서 모델 출처를 확정적으로 쓰기 전에 위 정보를 복원해야 한다.

## Ultralytics의 현재 공식 안내

2026-06-04 확인 기준, [Ultralytics 공식 라이선스 페이지](https://www.ultralytics.com/license)는 Ultralytics YOLO 코드, 모델, 아키텍처, 학습 파이프라인 또는 학습·미세조정 모델을 사용하는 경우 전체 프로젝트를 AGPL-3.0으로 공개하거나 Enterprise License를 취득해야 한다고 안내한다. 공식 안내는 내부 사용과 R&D도 이 구분에 포함한다고 설명한다.

이는 공급자의 라이선스 안내 요약이며, WalkAssist에 실제로 어떤 의무가 적용되는지에 대한 법률 판단은 아니다.

## 배포 전 조치

1. `yolo26n-seg.tflite`의 원본 출처, checksum, export 명령, 도구 버전을 기록한다.
2. 원본 모델과 변환 결과에 적용되는 라이선스를 확인하고 third-party notice를 추가한다.
3. WalkAssist의 공개·배포 방식과 프로젝트 라이선스를 결정한다.
4. 비공개 또는 상용 배포를 계획한다면 Ultralytics Enterprise License 또는 대체 모델 필요성을 법률 전문가와 검토한다.
5. 출처와 라이선스를 확인할 수 없다면 해당 자산을 배포물에서 제외하고 재현 가능한 모델로 교체한다.
