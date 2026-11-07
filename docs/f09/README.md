# F-09 터널 진입 연출

## 범위

브랜치: `feat/f09-tunnel-effects`. F-01과 F-03이 연결된 `feat/f03-scene-narration`의 `81b2cac`를 기준으로 F-09를 추가했다. 기존 「심야 휴게소의 마지막 손님」의 `tunnel_flashback`(터널 속 회상) 원고를 사용한다. 다른 팀원의 별도 기능 브랜치를 이번 변경에 새로 병합하지 않았다.

입력은 **가상 터널 이벤트** 또는 **Android GPS 위치로 판정한 터널 이벤트**다. OpenStreetMap 남산3호터널 한 구간의 지도 스냅샷을 포함하고 위치 수신 경로를 연결했다. 에뮬레이터 좌표 주입으로 검증하며 실차 주행은 검증하지 않았다. 전국 터널 검색 서비스는 아니다.

## 흐름

1. 가상 패널 또는 Android GPS 위치를 받은 지도 판정기가 `PlaceObservation(regionId, "tunnel", inside)`를 전달한다.
2. `PlaceEventDetector`가 중복 진입을 제거하고 `VehicleEvent.Place("tunnel")`을 보낸다. 판정 불가(null)는 이탈로 처리하지 않는다.
3. StoryEngine이 현재 장면 다음에 터널 장면을 넣는다. 진행 중인 대사를 갑자기 끊지 않는다. 장면이 대기 중일 때 이탈하면 대기열에서 제거한다.
4. 터널 장면 시작과 함께 F-03 TTS 낭독과 Director 연출을 실행한다. 사건 1회당 같은 장면은 한 번만 재생한다.
5. 조명·공조 지원과 원래 값을 모두 확인한 뒤 무드등 밝기를 낮추고 송풍을 1단계 올린다. 4초 후 원래 값으로 복원한다.
6. 미지원·권한 거부·현재값 없음·응답 시간 초과면 효과음으로 대체한다. 일부 변경 후 실패하면 이미 변경한 항목은 복원한다.
7. 위험 이벤트·이탈·장면 종료·시연 중단·앱 정상 종료 시 효과음을 멈추고 차량 연출을 복원한다. 위험 후 낭독이 첫 문장에서 재개돼도 연출은 다시 실행하지 않는다.

일반 정차는 낭독을 중단하지 않는다. 정차 상태에서 새 장소 이벤트를 넣어도 연출 장면을 추가하지 않는다. 위험 중 장소 이벤트는 대기하고 기존 F-10 재개 정책을 따른다.

## 테스트 화면

메인 앱의 개발 패널 → **F-09 터널 연출 테스트**.

- **시연 시작**: 원본 첫 본편의 마지막 문장부터 시작한다. 전체 사건을 처음부터 듣지 않고 연결을 확인하기 위한 개발용 진입점이다.
- **터널 진입**: 현재 장면과 질문 대기 구간이 끝나면 터널 회상 3문장을 낭독하고 다음 본편으로 돌아간다.
- **터널 이탈**: 대기 중 장면 취소 또는 진행 중인 차량 연출 복원. 이미 시작된 터널 낭독은 끝까지 들려준다.
- **위험 중단**: F-10에 위험 이벤트를 보내 낭독·효과음을 멈추고 조명·공조를 복원한다. 단일 위험은 기존 3초 재개 정책, 반복 위험은 정차까지 보류한다.
- **제어 지원 / 미지원**: 시연을 중단한 뒤 전환한다. 미지원이면 음성은 계속되고 효과음만 출력한다.
- 기본 화면의 밝기 70→25, 송풍 2→3→2는 **가상 차량 값**이다. 실제 TTS·효과음을 쓰지만 실제 차량 제어 성공의 증거가 아니다.

## 실제 차량 출력 경로

메인 앱의 `PleosDirector`는 Vehicle SDK 2.3.2 어댑터를 사용한다. 지원 목록의 첫 무드등 구역·첫 송풍 구역에서 읽기/쓰기 가능 여부와 현재값을 확인한다. 각 차종의 전체 좌석에 일괄 쓰지 않는다.

전원·AUTO는 송풍과 구역 ID가 다를 수 있으므로 각 속성의 지원 구역을 따로 조회한다. 선택한 송풍 구역 전체의 전원/AUTO 상태를 확인할 수 있어야 연출한다.

- 무드등 밝기만 낮춘다. 전조등·제상·공조 전원·설정 온도는 바꾸지 않는다.
- 공조가 꺼져 있거나 AUTO이면 설정을 유지하고 효과음만 사용한다.
- 사용자가 연출 도중 값을 바꾸면 복원 시 그 변경을 유지한다.
- 복원을 확인하지 못하면 상태에 표시하고 이후 차량 제어는 보류한다.
- 읽기/쓰기 요청 제한 시간과 복원 제한 시간을 둔다. 프로세스 강제 종료·기기 전원 차단·SDK가 응답 뒤에도 늦게 값을 적용하는 상황에서의 복원은 보장하지 않는다. 서비스 재연결·장기 운행 검증이 필요하다.
- NIGHT/TENSION/REVEAL은 낮은 음량 효과음만 사용한다. 이전의 원상복구 없는 무드등 색 변경은 제거했다.

효과음은 음량 15/100, 250ms의 임시 톤이다. 최종 연출 음원은 별도 콘텐츠 작업이다.

## GPS/지도 입력

`MappedPlaceDetector`는 외부에서 받은 구역 폴리곤과 위치(`PositionFix`)로 `PlaceEventDetector`를 호출한다. 위치 오차 30m 초과, 5초보다 오래된 위치, 좌표 오류, 경계 근처는 판정을 보류한다. 같은 결과를 연속 두 번 받아야 진입·이탈을 확정한다. GPS 유실 시 기존 구역 상태를 유지한다.

`AndroidTunnelLocation`이 Android `LocationManager.GPS_PROVIDER`에서 좌표를 받아 같은 판정기를 호출한다. 정확한 위치 권한이 없거나 GPS가 꺼지면 자동 감지는 비활성/보류한다. 화면이 보일 때만 수신하고 화면 종료·백그라운드 전환 시 구독을 해제한다. 같은 타임스탬프의 중복 fix, 미래 시각은 무시하고 5초 이상 끊긴 샘플은 연속 확인 횟수를 초기화한다. 메인 앱의 **실제 입력**에 위치 이벤트가 함께 연결되며 **시뮬레이션 입력**에서는 구독하지 않는다.

지도는 [OpenStreetMap way 37930783](https://www.openstreetmap.org/way/37930783), 남산3호터널 도로 중심선 약 1.29km다. 원본 API에서 받은 중심선과 버전·시각·출처를 `assets/maps/namsan3_tunnel.json`에 보존했다. 중심선 좌우 30m를 앱의 판정 허용 구역으로 파생했다. 이는 실제 터널 폭을 뜻하지 않는다. 데이터는 앱에 포함되어 실행 시 지도 서버 요청 없이 사용한다.

지도 데이터 및 파생 구역: **© OpenStreetMap contributors, ODbL 1.0**. [라이선스·출처](https://www.openstreetmap.org/copyright). `tools/import_tunnel_map.py`(Python + Shapely)로 같은 원본을 다시 가져올 수 있다. 재다운로드 시 데이터가 바뀔 수 있으므로 버전과 경로 테스트를 다시 확인한다.

**범위 제한:** 현재 포함된 한 개 도로 구간만 판정한다. 전국 지도 갱신, 내비 경로 매칭, 터널 위 도로·근처 평행 도로·고도 구분, 실차 GPS 유실은 미검증이다. 위치만 겹치는 터널 위 지상 도로를 구분하는 완성된 주행 판단은 아니다.

### GPS 자동 감지 재현

개발 패널 → **F-09 GPS 터널 자동 감지 테스트**. 터널 진입 버튼은 없으며 주행 상태만 시연용 Driving을 사용한다. 위치는 OS 수신 경로, 음성은 Pleos TTS, 출력은 PleosDirector다. 정확한 위치 권한을 허용한 뒤 에뮬레이터 좌표를 `gps-route.json` 순서로 주입한다. 안에서는 현재 낭독이 끝나 터널 장면이 재생될 때까지 기다린다.

```powershell
# 앱 설치 후 시스템의 패키지 갱신 처리가 끝난 뒤 실행한다.
# Restore 값은 테스트 직전 에뮬레이터 위치에 맞춘다.
./tools/verify-gps-tunnel.ps1 -RestoreLatitude 37.421998 -RestoreLongitude -122.084
```

스크립트는 `adb emu geo fix`로 **밖 → 안 → 밖**을 전달하고, 진입 1회·터널 장면·본편 복귀·이탈 1회를 검사한다. 터널 Place 이벤트를 직접 주입하지 않는다. 끝나면 테스트 화면을 종료하고 지정한 위치로 복원한다. 최초 준비 시 앱의 위치 권한 허용이 필요하다. 조명·송풍을 함께 보려면 Virtual Console을 켜고 Power ON / AUTO OFF를 확인한다. AUTO 상태면 효과음 대체가 정상 동작이다.

## 인터페이스 변경

- 기존 `VehicleEvent.Place(tag)` 유지, 이탈용 `PlaceExited(tag)` 추가.
- `Director.stop()` / `release()` 추가. 기본 구현을 제공하여 기존 Fake 구현과 호환.
- StoryEngine의 상태 소유권과 원본 시나리오 JSON은 유지.
- 다른 브랜치 통합 시 VehicleEvent의 분기 처리와 Director 수명 정리를 함께 반영해야 한다.

## 재현

```sh
# 기본 가상 차량 + 실제 음성 시연
adb shell am start -W -n com.roadmystery.app/.director.TunnelTestActivity --ez smoke true
# 각각 종료 후 새로 시작한다.
adb shell am force-stop com.roadmystery.app
# 제어 미지원 대체 경로
adb shell am start -W -n com.roadmystery.app/.director.TunnelTestActivity --ez smoke true --ez unsupported true
adb shell am force-stop com.roadmystery.app
# Pleos SDK 출력 검사: 가상 터널 입력이지만 차량 제어는 SDK에 요청한다.
adb shell am start -W -n com.roadmystery.app/.director.TunnelTestActivity --ez smoke true --ez realCabin true
```

검증 로그는 `F09Test`, 음성 로그는 `F03Speech` 태그로 확인한다.

확인한 결과와 미검증 범위는 [검증 기록](verification.md)에 정리했다.

### 조명·송풍 실제 값 확인

Pleos Virtual Console을 연결하고 Power ON / AUTO OFF로 설정한 뒤 아래 디버그 화면을 실행한다. 에뮬레이터에서만 실행되며, 원래 설정을 기록하고 연출 중·복원 후 SDK 값을 다시 읽는다. 마지막에는 준비 과정에서 바꾼 설정까지 복원한다. 진행 중 강제 종료하지 않는다.

```sh
adb shell am start -W -n com.roadmystery.app/.director.CabinVerificationActivity
adb logcat -s F09CabinVerify:I
```

2026-11-07 검증에서 **밝기 70→23→70, 송풍 3→4→3**, Power ON / AUTO OFF 유지 및 `RESULT control=true originalSettingsRestored=true`를 확인했다. 이 화면은 StoryEngine/TTS와 별개로 앱의 실제 PleosDirector 제어 경로를 검사한다.

## 참고

- [Pleos 송풍 설정 API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-fan-speed)
- [Pleos 공조 제어 권한](https://document.pleos.ai/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.constant/pleos-permission/-c-o-n-t-r-o-l_-c-a-r_-c-l-i-m-a-t-e)

정확한 메서드 인자·지원 범위 타입은 프로젝트가 사용하는 Vehicle 2.3.2 라이브러리와 컴파일로 확인했다.
