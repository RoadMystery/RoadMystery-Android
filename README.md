# RoadMystery

목적지에 도착하는 순간 범인이 밝혀지는, 이동 경로가 무대가 되는 차량용 오디오 미스터리.

현대차·기아 차량용 앱 개발 공모전(Pleos Playground) 출품작입니다. 주행 중에는 음성으로만 이야기가 진행되고, 신호대기·주차 중에는 단서 카드·인물 관계도·범인 지목 화면이 열립니다.

## APK

- 위치: [`apk/RoadMystery_RoadMystery_release.apk`](apk/RoadMystery_RoadMystery_release.apk)
- release 빌드, 서명 완료 (versionName 1.0)

## 실행 방법

### APK로 실행

1. Android Studio Device Manager에서 Pleos Connect v2.1.0 AVD를 실행합니다.
2. APK를 설치합니다.
   ```
   adb install -r apk/RoadMystery_RoadMystery_release.apk
   ```
   APK 파일을 에뮬레이터 화면에 끌어다 놓아도 설치됩니다.
3. 앱 목록에서 **RoadMystery**를 실행합니다.

### 시연 진행

- 앱 화면 왼쪽 **시뮬레이션 패널**의 **사건 시작**을 누르면 브리핑 낭독이 시작됩니다. 저장된 진행이 있으면 **이어하기**가 보입니다.
- **시연 자동 재생**을 누르면 출발 → 정차 → 터널 → 급제동 → 정차 → 도착 1분 전 → 도착 순서가 자동으로 진행되어, 한 챕터를 처음부터 결말까지 볼 수 있습니다.
- 패널 버튼으로 이벤트를 직접 넣을 수도 있습니다: 출발, 정차, 주차, 터널, 도착 1분 전, 도착, 급제동, ADAS 경고, ADAS 지속 경고·해제, "멈춰".
- Virtual Console에서 기어(P/D)를 바꾸거나 내비게이션 목적지를 설정해도 같은 이벤트가 들어옵니다.
- 정차 중에는 단서 카드, 관계도, 용의자 심문, 범인 지목을 쓸 수 있습니다. 출발하면 이 화면은 바로 최소화됩니다.
- TTS가 동작하지 않으면 무음으로 진행되고, 낭독 내용은 패널의 **자막**에 표시됩니다.

### 소스로 빌드

```
git clone https://github.com/RoadMystery/RoadMystery-Android.git
cd RoadMystery-Android
./gradlew assembleDebug        # 디버그 APK: app/build/outputs/apk/debug/
./gradlew test                 # 단위 테스트
```

Android Studio에서 프로젝트를 열고 Pleos Connect AVD를 대상으로 `app`을 실행해도 됩니다.

## 테스트 환경

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** (Android Automotive OS, API 34, x86_64) |
| 화면 규격 | **2560 × 1440 px, 160 dpi** (가로) |
| 개발 도구 | Android Studio 2025.1 (Narwhal) 이상, JDK 17, Gradle 8.13, AGP 8.12.3 |
| 언어·UI | Kotlin 2.0.21, Jetpack Compose (Material 3) |
| SDK | compileSdk 36, minSdk 34, targetSdk 36 |

### 사용한 Pleos Connect SDK

| SDK | 버전 | 용도 |
|---|---|---|
| Vehicle | 2.3.2 | 기어(P/D)로 주행·정차 판정, 무드등 연출 |
| NaviHelper | 3.0.5 | 남은 시간, 목적지 도착 |
| TextToSpeech / SpeechToText | 3.3.1 | 장면 낭독, 음성 심문 |

## 필요한 설정

- **APK 실행에는 별도 설정이 필요 없습니다.** TTS·STT는 키 없이 ON_DEVICE 모드로 동작합니다.
- Pleos SDK는 `settings.gradle.kts`에 등록된 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`, 인증 없음)에서 받습니다.
- (선택) HYBRID·SERVER 음성 모드를 쓰려면 `local.properties`에 Pleos Playground > My Project의 키를 넣습니다.
  ```
  pleos.clientId=...
  pleos.clientSecret=...
  ```
- (선택) 서명된 release APK를 직접 만들려면 `local.properties`에 키스토어 정보를 넣고 `./gradlew assembleRelease`를 실행합니다. 이 값이 없으면 서명하지 않은 release APK가 만들어집니다.
  ```
  release.storeFile=/path/to/keystore.jks
  release.storePassword=...
  release.keyAlias=...
  release.keyPassword=...
  ```

## 구조

```
com.roadmystery.app
 ├─ vehicle/   차량 이벤트(Pleos Vehicle·NaviHelper 연동, 시뮬레이션용 Fake, 시연 자동 재생)
 ├─ story/     시나리오 데이터, StoryEngine(상태 머신), 진행 저장
 ├─ audio/     장면 낭독(Pleos TTS), 문장 단위 일시정지·재개
 ├─ voice/     음성 심문, 수첩 요약, STT
 ├─ director/  무드등·효과음 연출
 └─ ui/        Compose 화면
```

시나리오는 `app/src/main/assets/scenarios/`의 JSON 파일입니다.
