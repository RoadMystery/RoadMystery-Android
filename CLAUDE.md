# RoadMystery — 차량용 오디오 미스터리 앱

현대차·기아 차량용 앱 개발 공모전(Pleos Playground) 출품작.
목적지에 도착하는 순간 범인이 밝혀지는, 이동 경로가 무대가 되는 차량용 오디오 미스터리.

## 목표
- 12월 3~4일 최종 PT에서 싱글플레이 한 챕터를 에뮬레이터로 시작부터 결말까지 끊김 없이 시연한다.
- 데모 범위: 사건 1개, 용의자 3명, 10분 내외 챕터 1개. 멀티플레이·인앱 구매는 범위 밖.

## 핵심 컨셉: 두 가지 모드
- 주행 모드: 음성으로만 진행. 화면에는 장면 제목과 진행률만 표시.
- 정차 모드: 신호대기·주차 시 단서 카드, 인물 관계도, 선택·범인 지목 화면을 표시.
- 출발하면 정차 화면은 즉시 최소화된다.

## 기술 스택
- Kotlin, Jetpack Compose (Material 3)
- Android Automotive OS 기반 Pleos Connect, 에뮬레이터는 Pleos Connect v2.0 (API 34)
- Pleos Connect SDK: Vehicle, NaviHelper, ADAS, Fused Location, Gleo AI(음성 인식·합성·LLM)
- 로컬 저장: DataStore 또는 Room

## 패키지 구조
```
com.roadmystery.app
 ├─ vehicle/   VehicleEvent, VehicleState, FakeVehicleState (차량 신호 → 이벤트)
 ├─ story/     AppMode, 시나리오 데이터, StoryEngine (상태 머신·재생 목록)
 ├─ audio/     AudioPlayer (장면 음성 재생, 문장 단위 일시정지·재개)
 ├─ voice/     VoiceAgent (음성 심문, 수첩 요약)
 ├─ director/  Director (조명·공조·효과음 연출)
 ├─ ui/        화면 (Compose)
 └─ MainActivity.kt
```

## 개발 원칙 (반드시 지킬 것)
1. 엔진은 차량과 분리한다. StoryEngine은 차량 SDK를 직접 호출하지 않고 VehicleEvent만 받는다.
2. 목업으로 병렬 개발한다. 모든 모듈은 인터페이스 + Fake 구현을 먼저 만든다.
3. 시뮬레이션 패널을 유지한다. 출발·정차·터널·급제동·도착 버튼으로 FakeVehicleState에 이벤트를 넣는다. 시연용 이벤트 순서 자동 재생 기능도 이 패널에 둔다.
4. 장면 낭독은 미리 만든 음성 파일(assets)로 재생한다. 실시간 생성은 용의자 답변과 수첩 요약에만 쓴다.
5. 안전이 기능보다 우선한다.
    - 주행 중에는 이미지·긴 텍스트·선택지를 표시하지 않는다.
    - 급제동·ADAS 경고·"멈춰" 명령이 오면 낭독과 심문을 즉시 멈춘다.
    - 차량 상태를 판정할 수 없으면 주행 모드로 간주한다.
6. UI는 StoryEngine의 상태(mode, clues 등)를 구독만 한다. UI가 상태를 직접 바꾸지 않는다.

## 모듈 인터페이스 (초안)
```kotlin
enum class HazardReason { HARD_BRAKE, ADAS_WARNING, USER_STOP }

sealed interface VehicleEvent {
    data object Driving : VehicleEvent
    data object Stopped : VehicleEvent
    data object Parked : VehicleEvent
    data object Arrived : VehicleEvent
    data class EtaChanged(val remainingSec: Int) : VehicleEvent
    data class Hazard(val reason: HazardReason) : VehicleEvent
    data class Place(val tag: String) : VehicleEvent // "tunnel", 지역명 등
}

interface VehicleState { val events: Flow<VehicleEvent> }

enum class AppMode { IDLE, BRIEFING, DRIVING, PAUSED, STOPPED, ENDING }

interface StoryEngine {
    val mode: StateFlow<AppMode>
    val pendingClues: StateFlow<List<Clue>>
    fun start(scenarioId: String)
    fun onVehicleEvent(event: VehicleEvent)
    fun choose(decisionId: String, optionId: String)
}

interface AudioPlayer {
    fun play(scene: Scene, fromSentence: Int = 0)
    fun pause(): Int // 멈춘 문장 인덱스
    fun stop()
}

interface VoiceAgent {
    suspend fun ask(utterance: String, context: CaseContext): SuspectReply
    suspend fun summarize(progress: Progress): String
}

interface Director { fun perform(effect: Effect) } // 제어 불가 시 효과음만

interface ProgressStore {
    suspend fun load(scenarioId: String): Progress?
    suspend fun save(progress: Progress)
}
```

## 시나리오 데이터 형식
- scenario: id, title, culprit, suspects[], chapters[], clues[]
- suspect: id, name, voice, persona(말투·알리바이·숨긴 사실), revealable(답변으로 공개 가능한 단서 id)
- scene: id, required(false면 도착 시간에 맞춰 넣거나 뺌), durationSec, script, unlocks(열리는 단서), trigger(차량 이벤트 태그)
- progress: scenarioId, chapterId, lastSceneId, lastSentenceIndex, cluesFound, testimonies, choices
- 용의자 답변(LLM)은 범인과 트릭을 직접 공개하지 않는다.

## 코딩 규칙
- 커밋: Conventional Commits (feat:, fix:, refactor:, chore:, docs:)
- 브랜치: GitHub Flow, 기능 ID 포함 (예: feat/f05-voice-ask)
- 포맷: ktlint
- main은 항상 에뮬레이터에서 실행 가능한 상태로 유지
- 화면 문구는 한국어

## 빌드·검증
- 컴파일 확인: `./gradlew assembleDebug`
- 단위 테스트: `./gradlew test` (StoryEngine은 FakeVehicleState로 이벤트 순서를 넣는 테스트 작성)
- 에뮬레이터 실행은 Android Studio에서 Pleos Connect AVD로 직접 한다.