# F-01 주행 상태 감지·모드 전환

작업 기준: `main`의 `5a0520b` (스토리 엔진 병합본). 브랜치: `feat/f01-vehicle-state`.
이번 변경은 F-01과 그 검증 화면에 한정한다. AudioPlayer, Director, VoiceAgent, StoryEngine 구현 및 시나리오는 수정하지 않는다.

실행 화면은 [에뮬레이터 연동 확인 기록](verification/README.md)에서 확인할 수 있다.

## 판정 규칙

| 입력 | 차량 판정 | 앱으로 전달하는 이벤트 |
| --- | --- | --- |
| 유효한 속도가 0이 아님 (후진 포함) | 주행 | Driving |
| 속도 0이 2초 연속 유지, 기어 확인됨 | 정차, P면 주차 | UX 제한 없을 때 Stopped 또는 Parked |
| 위의 정차 조건 충족, UX 제한 또는 제한 상태 미수신 | 정차/주차 | Driving (화면 최소 표시 유지) |
| 속도 미수신·NaN·무한대·미래 timestamp·3초 초과 stale | 판정 불가 | Driving |
| 기어 미수신·오류 | 판정 불가 | Driving |

정차로 들어갈 때만 2초 지연한다. 출발·신호 오류·화면 제한은 다음 입력 처리 시 바로 최소 표시로 전환한다. 정차 확인 도중 움직이거나 신호가 끊기면 2초를 다시 센다.
지연 2초/유효기간 3초는 현재 앱 기본값이며 실차 검증을 거친 성능 보장치가 아니다. 속도 단위는 m/s, 시간은 단조 증가 시계(elapsedRealtime)이다.

**실기 관찰:** Pleos Connect v2.1.0 에뮬레이터에서 D + 0 km/h여도 UX 제한이 유지됐다. 차량 자체는 Stopped로 판정하지만, 앱 화면은 Driving으로 유지한다. 기능 명세의 '신호대기 중 단서 열람'은 플랫폼이 허용할 때만 가능하다. P 주차에서는 UX 제한 해제 후 Parked 이벤트가 전달됨을 확인했다.

## 구성과 연결

- `AaosMotionSignals`: AAOS `PERF_VEHICLE_SPEED` 5Hz, `GEAR_SELECTION`, `CarUxRestrictions` 구독. 연결 종료·권한 오류·지원 실패 시 unknown. 콜백과 등록 해제는 메인 스레드에서 처리한다.
- `MotionClassifier`: Android에 의존하지 않는 판정 함수. `physicalEvent`는 차량 판정, `event`는 화면 제한을 반영한 기존 엔진 입력이다.
- `MotionFlow`: 100ms 타이머로 마지막 속도 신호의 만료도 확인. 이벤트는 상태 변경 때만 발행한다.
- `FakeMotionSignals`: 속도·기어·UX 제한을 가짜로 공급한다. 이벤트를 직접 찍어 내지 않고 실제 입력과 같은 판정기를 거친다.
- `PleosVehicleState`: F-01을 위 입력에 연결. 기존 NaviHelper 동작 유지.
- `SelectedVehicleState`: 실제 입력 또는 기존 스토리 시뮬레이션 중 하나만 구독. 입력 전환 시 Driving부터 시작한다. 기존 엔진이 Arrived를 정차로 취급하므로 Parked 전에는 Arrived를 보류한다.
- `MainActivity`: 실제/시뮬레이션 전환, 차량 권한 요청, F-01 단독 테스트 화면 진입을 연결한다. `VehicleEvent` 계약은 그대로다.

`android.car.permission.CAR_SPEED`와 `android.car.permission.CAR_POWERTRAIN`을 선언한다. 실제 연결 버튼에서 권한을 요청한다. 권한 거절 상태에서는 정차 화면을 열지 않는다.
디버그 앱의 기존 게임 패널은 스토리 시연용이다. 그 패널의 출발·정차 버튼은 F-01 센서 검증을 대신하지 않는다.

## 단독 실행

디버그 앱의 **F-01 단독 판정 테스트** 버튼을 누른다. 이 화면은 스토리 엔진·TTS·연출을 시작하지 않는다.
또는 설치 후 아래 명령을 사용한다.

```text
adb shell am start -n com.roadmystery.app/.vehicle.MotionTestActivity
```

1. '정지 0 km/h · D': 약 2초 뒤 정차.
2. '출발 30 km/h': 즉시 주행.
3. 정지 후 2초 전에 다시 출발: 정차로 전환되지 않음.
4. '속도 미수신': 주행으로 처리.
5. 정차 상태에서 '속도 갱신 중단': 3초 초과 후 주행으로 처리.
6. '화면 제한': 차량 정차 판정과 별개로 화면 최소 표시 유지.
7. '실제 신호 연결': 에뮬레이터의 속도·기어·UX 제한 확인.

이 화면은 `src/debug`에만 있으므로 릴리스 APK에 포함되지 않는다. 상세 수치와 조작 버튼은 개발용 UI다.

## 검증 결과

2026-11-05 호스트 기준:

- `gradlew.bat ktlintCheck :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 성공.
- 전체 단위 테스트 41개 통과. F-01 신규 테스트 18개: 판정 11, watchdog/흐름 3, 입력 선택 3, 기존 StoryEngine 통합 1.
- Lint 오류 0, 경고 25개. 라이브러리 업데이트·기존 리소스 등의 경고는 별도 정리 대상.
- Pleos Connect v2.1.0 / API 34에서 실제 AAOS 속도·기어 수신, D 출발, 0 속도 정차, 재출발, P 주차 확인.
- Virtual Console에서 30 km/h → 0 → 20 km/h → 0/P를 전달. 앱 로그에서 Driving → physical Stopped/app Driving(UX 제한) → Driving → Parked 확인.
- D 정차 확인 시작부터 physical Stopped까지 약 2.0초 관찰. P 정차 확인도 약 2.0초 관찰. 이는 관찰 결과이며 지연 상한 보장이 아니다.
- 속도 권한 제거 시 앱이 종료 오류 없이 Driving/미수신 처리됨을 확인. 권한 복구 후 재실행으로 실제 신호 연결 확인.
- 가짜 입력 테스트에서 오래된/없는 속도, 짧은 정지, 후진, 기어 미수신, UX 제한, 입력 전환을 검증.

실차에서의 센서 주기·권한·지연은 미검증이다. F-03/TTS, F-09 차량 연출, F-10 위험 감지는 이 브랜치의 완료 범위가 아니다.

## 공식 자료

- [AAOS CarPropertyManager](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager)
- [AAOS 차량 속도 속성](https://developer.android.com/reference/android/car/VehiclePropertyIds#PERF_VEHICLE_SPEED)
- [Pleos Virtual Console 셋업](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)

Virtual Console은 `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`에서 사용한다. 웹 콘솔이 열려 있어야 차량 테스트 입력이 활성화된다.

## 커밋 전 검사

```text
git config core.hooksPath .githooks
gradlew.bat ktlintCheck :app:testDebugUnitTest :app:assembleDebug
```

ktlint는 F-01 소유 파일과 연결 파일만 검사한다. CI는 PR 또는 수동 실행으로 같은 검사를 수행한다. 원격 CI 실행 결과는 별도 확인한다.
