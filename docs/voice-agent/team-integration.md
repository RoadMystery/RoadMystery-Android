# 팀 앱과 맞추기 (F-05·F-06)

- 상태: 2026-11-09. 팀 저장소 RoadMystery-Android의 `main`(`5a0520b`)과 열린 브랜치(`feat/f03-scene-narration` 등)를 읽고, 우리 쪽(VoiceAgent 모듈, 사건 원고, 평가 도구)만 고쳤습니다. 팀 파일은 바꾸지 않았습니다.
- 대상: 신준호(StoryEngine), 양채현(F-03 낭독, F-10 위험 정지), 최재령(원고, 화면).
- 요약: 팀 앱의 `ScriptedVoiceAgent` 자리에 넣을 `LlmVoiceAgent`를 만들었습니다. 팀 시연 사건 "심야 휴게소의 마지막 손님"은 같은 id로 우리 원고 형식에 옮겼습니다. 운전 중 음성 질문은 우리 쪽 준비를 마쳤고, 팀 파일 두 곳에 연결이 필요합니다([아래](#팀-파일에-필요한-연결)).

## 팀 코드에서 읽은 것

| 팀 코드 | 하는 일 | 우리 쪽 대응 |
|---|---|---|
| `voice/VoiceAgent.kt` | `ask(utterance, CaseContext): SuspectReply`, `summarize(Scenario, Progress): String`. 지금 구현은 대본에서 키워드로 답을 고르는 `ScriptedVoiceAgent` | 같은 인터페이스로 `LlmVoiceAgent`를 만듦 |
| `story/DefaultStoryEngine.kt` | 정차 중(`AppMode.STOPPED`)에만 질문을 받음. 대답을 `Testimony(suspectId, question, reply.text)`로 남기고, `reveals`로 단서를 열고, 대답을 대답 장면으로 `AudioPlayer`에 넘김. 출발하면 심문을 끊음 | 대답은 용의자 말만 글로 돌려줌. 단서는 열지 않음. 끊기면 요청도 끊김 |
| `ui/InterrogationPanel.kt` | 용의자를 고르고, 시나리오의 질문 버튼을 누르거나 "음성으로 묻기"(STT 한 번)로 물음. "수첩 요약 듣기" | 고른 용의자가 답함. 질문 버튼 문구를 질문 세트에 넣어 둠 |
| `assets/scenarios/midnight_rest_area.json` | 시연 사건. 용의자 `s_seojin`·`s_minhyuk`·`s_jaehee`, 단서 5개, 범인 `s_seojin` | 같은 id로 우리 원고에 옮김 |
| `feat/f03-scene-narration`(열린 브랜치) | 대답 장면을 용의자 `voice`로 읽음. 화자 표(진행자 `sanghyeok`, `female_calm` `seoyoon`, `male_rough` `eunwoo`, `female_bright` `haeun`). 본편 장면 끝에 "질문할 것이 있나요?"와 3초 질문 구간 | 목소리를 이 표에 맞춤. 질문 구간에서 들은 말을 가르는 기능을 만듦 |

## 우리 쪽에서 바꾼 것

1. **사건 원고 `content/cases/midnight_rest_area/`.** 팀 JSON에 있는 사실만 옮겼고, 사건·용의자·단서 id는 팀 것 그대로입니다.
   - 단서 설명에는 그 단서를 여는 장면의 내용을 담았습니다. 예를 들어 영수증 단서에는 `s01_scene`의 수면제 성분과 컵홀더의 종이컵 두 개가 들어갑니다.
   - 용의자의 공개 진술과 반응은 팀의 `persona`, `interrogation` 답, 장면 서술에서 옮겼습니다.
     - 박서진: 커피를 건넨 뒤 편의점과 화장실에 있었다는 주장이 코인 로커 단서에서 무너집니다. 이것이 범인의 핵심 반응입니다.
     - 오민혁: 바로 내렸다는 주장이 블랙박스 단서에서 무너집니다. 이 핵심 반응에 비서가 그 습관을 알았다는 말이 들어갑니다.
     - 윤재희: 아는 사람과 통화했다는 주장이 통화 기록 단서에서 무너집니다. 수면제 포장에 대한 반응이 핵심 반응입니다.
   - 자백 대사는 팀의 `ending_correct`에 있는 사실만 씁니다.
   - 무료 검사(`validate`)와 Python·JVM 테스트의 모든 원고 규칙을 지킵니다. 질문 세트 `tools/voice-eval/probes/midnight_rest_area/`(용의자 질문 61개, 요약 5개)도 새로 썼습니다.
2. **스키마.** 사건·인물 id에 밑줄을 허용하고, 단서 id가 `clue_`로 시작해도 되게 했습니다([작성 규칙](scenario-rules.md)).
3. **목소리.** 진행자를 `sanghyeok`으로 바꿨습니다(`AgentConfig.narratorSpeaker`, 검사기의 `NARRATOR_SPEAKER`). 그 목소리를 쓰던 jade-crown의 서준기와 demo-sapphire의 집사는 `eunwoo`로, 김옥분과 요리사는 `haeun`으로 옮겼습니다. 프롬프트는 바뀌지 않습니다.
4. **새 API `dev.voiceagent.story`.**
   - `StoryVoice`: 엔진이 고른 용의자로 답하고, 글만 돌려주며, 엔진이 넘긴 찾은 단서를 따라갑니다. 요약은 엔진 `Progress`의 단서와 증언으로 합니다.
   - `StoryVoices`: 시나리오 id마다 `cases/<id>`를 엽니다. 없으면 `null`을 돌려 대본으로 넘어가게 합니다.
   - `WindowTurn`: 질문 구간에서 들은 말을 가립니다.
   - `VoiceAgent`에도 고른 용의자를 받는 `ask(utterance, suspectId)`와 바깥 수첩용 `summarize(unlockedClues, notebook)`를 더했습니다.
5. **팀 앱에 넣을 파일 `apps/voice-agent/roadmystery-app/`.** 팀 `app/` 모듈과 같은 경로입니다. `LlmVoiceAgent.kt`는 팀 인터페이스 구현이고, `LlmVoiceAgentTest.kt`는 팀 `DefaultStoryEngine`에 끼워 보는 테스트입니다. 테스트는 `-Dvoiceagent.repo=<이 저장소 경로>`를 줄 때만 돕니다.
6. **대답은 원고 대사만(2026-11-09).** 모델은 대사 id만 고르고, 앱은 그 대사를 원고 그대로 돌려줍니다([원고 대사만 읽는 대답](line-answers.md)).
   - `LlmVoiceAgent`는 처음 읽은 대사 id를 `SuspectReply.answerId`에 넣습니다.
   - 요약은 LLM 없이 수첩을 그대로 읽습니다. 팀 `ScriptedVoiceAgent.summarize`와 같은 꼴입니다.
   - 팀 대본처럼 원고 밖의 말이 나오지 않으면서, 질문은 모델이 뜻으로 알아듣고 대사를 고릅니다.

## 팀 인터페이스에 맞추며 달라지는 것

- **대기 음성 "음..."(0.3초)은 이 길로는 나지 않습니다.** 엔진이 대답 전체를 받은 뒤 대답 장면으로 읽기 때문입니다. 그동안 화면에는 "생각 중…"이 보입니다. 대답을 다 받은 뒤에 읽기 시작하므로, 첫 소리가 우리 데모(첫 글자까지 0.6~1.4초)보다 늦습니다.
- **진행자 힌트, 지목 권유, "아까 한 말과 견줘 보세요"는 대답에서 뺍니다.** 엔진은 대답 전체를 용의자 목소리로 읽고 증언으로 남기므로, 진행자 말이 섞이면 용의자가 한 말이 됩니다. 이 말은 `StoryReply.narratorLine`에만 남습니다.
- **대답이 단서를 열지 않습니다**(`reveals`는 빈 목록). 팀 검사기 규칙상 모든 단서가 필수 본편 장면에서 열리므로 빠지는 단서는 없습니다.
- **앱을 다시 켜면 용의자와 나눈 앞 대화와 끌어낸 핵심 반응이 비어 있습니다.** `CaseContext`에는 증언이 없기 때문입니다. 요약은 엔진이 저장한 증언을 쓰므로 그대로입니다.
- **새 게임은 찾은 단서가 줄어드는 것으로 압니다.** 단서를 하나도 찾지 못한 채 다시 시작하면 앞 게임의 대화가 남을 수 있습니다.
- **우리 원고가 없는 시나리오는 지금처럼 대본으로 답합니다**(`LlmVoiceAgent`의 `fallback`).
- **질문 버튼은 단서를 찾기 전에도 눌립니다.** 정차 화면은 시나리오의 질문 버튼을 늘 다 보여 줍니다.
  - 대본으로 답할 때는 버튼 답이 그 단서를 열었습니다. LLM은 단서를 찾기 전에는 그 사실을 모릅니다.
  - 그래서 "커피는 왜 두 잔 샀나요?"(영수증 전), "수면제를 본 적 있나요?"(포장 전), "블랙박스는 왜 꺼져 있었죠?"(블랙박스 전)는 아직 모를 사실을 질문이 먼저 건넵니다. jade-crown에서 잰 "단서 전에 자기 일을 새로 말함"이 나오기 쉬운 자리입니다.
  - 질문 세트에 이 칸을 넣어 두었습니다(`*-button-early`). 처음 잴 때 먼저 볼 곳입니다.
  - 대사만 읽는 지금 방식에서는 원고에 없는 말을 하지 않습니다. 그런 질문에는 공개 진술이나 화제 돌리기로 답합니다.
- **'CCTV'처럼 영어 글자가 든 대답은 그 문장부터 읽지 않습니다.** 앱은 영어 글자를 지시문으로 봅니다. 팀 대본과 단서 카드가 'CCTV'를 쓰니 플레이어도 그렇게 말할 것이고, 용의자가 따라 말하면 대답이 끊깁니다.
  - 우리 원고는 '감시 카메라'로 쓰고 오민혁의 말투 규칙에도 넣었습니다.
  - 이 규칙은 1970년대가 무대인 jade-crown으로 정한 것이라 지금은 바꾸지 않았습니다. 질문 세트에 'CCTV'라고 묻는 칸(`cctv-echo`)을 용의자마다 넣었습니다.
  - 대사만 읽는 지금 방식에서는 원고 대사만 읽으므로 이 일이 없습니다. 원고에는 영어 글자를 쓰지 않습니다(`validate`).

## 팀 파일에 필요한 연결

이번에는 팀 파일을 건드리지 않았습니다. 넣을 때 바꿀 곳입니다.

### 1. ScriptedVoiceAgent를 LlmVoiceAgent로 (MainActivity, 빌드 설정)

- `settings.gradle.kts`에서 VoiceAgent 모듈을 붙입니다. 모듈을 팀 저장소로 옮기거나 경로로 붙이고, 루트 `build.gradle.kts`에 `com.android.library` 플러그인을 더합니다. 그리고 `app`에 `implementation(project(":voiceagent"))`를 넣습니다.
- `roadmystery-app/`의 두 파일을 `app/src/`의 같은 경로에 넣습니다.
- `LlmVoiceAgentTest`를 JVM 단위 테스트로 돌리려면 `app`에 `testImplementation("org.json:json:20240303")`이 필요합니다. 단위 테스트의 Android `org.json`은 빈 껍데기라서 우리 사건을 열지 못하고 대본으로 넘어갑니다. 기기에서는 필요 없습니다.
- `MainActivity`에서 엔진을 만들 때 다음처럼 넣습니다.

  ```kotlin
  import dev.voiceagent.content.Scenario as CaseScenario
  import dev.voiceagent.llm.ProxyLlmClient
  import dev.voiceagent.platform.fromAssets
  import dev.voiceagent.story.StoryVoices

  val llm = ProxyLlmClient(PROXY_URL, PROXY_TOKEN, allowCleartext = BuildConfig.DEBUG)
  val voices = StoryVoices({ dir -> CaseScenario.fromAssets(assets, dir) }, llm, onLoadError = { id, e ->
      Log.w(TAG, "VoiceAgent: $id 사건이 없어 대본으로 진행 (${e.message})")
  })
  engine = DefaultStoryEngine(..., voice = LlmVoiceAgent(voices), ...)
  // 사건은 처음 쓸 때 asset에서 읽어 파싱하므로, 첫 정차에서 끊기지 않게 시작할 때 메인 스레드 밖에서 미리 연다.
  lifecycleScope.launch(Dispatchers.IO) { voices.forScenario(DEMO_SCENARIO_ID) }
  ```

- 프록시 주소와 앱 토큰은 `local.properties`에서 읽어 `BuildConfig`로 넣습니다(우리 데모 앱 `demo/build.gradle.kts`와 같은 방식). 키는 커밋하지 않습니다.
- 개발용 프록시가 `http`(에뮬레이터에서 `http://10.0.2.2:8787`)이면 디버그 빌드에 평문 허용 `network_security_config`가 필요합니다. 실제 서버가 `https`면 필요 없습니다. 인터넷 권한은 우리 모듈 매니페스트에서 합쳐집니다.

### 2. 운전 중 음성 질문 (StoryEngine과 F-03 질문 구간)

운전 중에는 화면을 만질 수 없으니, 질문은 장면 끝 질문 구간에 음성으로만 받습니다. 질문이 없으면 이야기는 그대로 이어집니다.

1. **F-03 질문 구간(양채현):** "질문할 것이 있나요?" 뒤 3초 동안 STT로 듣습니다.
   - 말을 시작할 때까지 기다리는 시간과 말이 끝날 때까지 듣는 시간은 따로 둡니다. 그래야 말하는 도중에 끊기지 않습니다.
   - 들은 말(아무 말도 없으면 `null`)을 `StoryVoices.forScenario(id)?.readWindow(heard)`에 넘깁니다.
2. **`readWindow` 결과에 따라:**
   - `WindowTurn.Continue`: 아무 말이 없거나 "없어요", "아니요, 괜찮아요", "계속해 주세요" 같은 말입니다. 지금처럼 다음 장면으로 갑니다.
   - `WindowTurn.Ask(suspectId, question)`: `engine.ask(suspectId, question)`를 부르고, 대답 장면이 끝나면 다음 장면으로 갑니다. 질문이 이름을 부르면 그 사람이, 아니면 마지막으로 물은 사람이 답합니다.
   - `WindowTurn.AskWho(question)`: 누구에게 묻는지 알 수 없는 경우입니다. 진행자가 `askWhoLine()`("누구에게 물어볼까요? …")을 읽고 다음 질문 구간에서 받거나, 그냥 진행합니다.
3. **StoryEngine(신준호):** `respond()`는 지금 `AppMode.STOPPED`에서만 질문을 받습니다.
   - 질문 구간에서 온 음성 질문은 주행 중에도 받게 해야 합니다. 화면 없이 음성만 쓰므로 주행 모드 원칙("음성으로만 진행")에 맞습니다.
   - 위험 이벤트에서 심문을 끊는 동작은 그대로 둡니다. 다만 주행 중 받은 질문은 출발 이벤트로 끊지 않아야 합니다.

### 3. 선택

- **진행자 말 따로 읽기:** `SuspectReply`에 진행자 말 칸을 두면 됩니다. 아니면 f03의 `sentenceVoices`로 대답 장면 마지막 문장만 `narrator`로 읽습니다. 그러면 힌트와 지목 권유가 다시 살아납니다.
- **이어하기 때 수사 진척도 살리기:** `Progress`에 끌어낸 핵심 반응 id 목록을 저장하는 칸입니다([수사 진척도](investigation-progress.md#storyengine과-맞출-것)).
- **질문 버튼을 찾은 단서에 맞춰 보이기(최재령):** 버튼 질문마다 필요한 단서를 두고 `GameUiState.clues`에 있을 때만 보이게 합니다. 그러면 단서 전에 사실을 건네는 질문이 버튼으로는 나가지 않습니다.

### 4. 원고 확인 (최재령)

- 오민혁의 `relation`은 "그날 밤 처음 고용된 대리운전 기사"입니다. 그런데 `q_habit` 답과 결말은 그가 이 차를 전에도 몰았고 비서가 그 습관을 알았다고 말합니다. 우리 원고는 블랙박스 반응에서만 "이 차 전에도 몇 번 몰았다"고 말하게 했습니다. 어느 쪽인지 정해 주시면 맞추겠습니다.
- `s03_store`(편의점 직원, 필수 아님)는 단서를 열지 않아서 VoiceAgent가 들었는지 알 수 없습니다. 플레이어가 그 이야기를 꺼내면 박서진은 처음 듣는 말로 받습니다.
- 우리 원고에서 박서진은 코인 로커 단서가 나오면 그 시간 편의점에 들르지 않았다고 말을 바꿉니다. 겉 이야기를 단서로 무너뜨리려는 핵심 반응입니다. `ending_correct`의 "편의점에 있었다던 그 시간"은 그 뒤에도 맞는 말이지만, 결말을 그녀가 끝까지 버틴 것처럼 읽게 할지 함께 정하면 좋겠습니다.

## 확인한 것

- Python 테스트 443개, JVM 테스트 148개가 통과했고 데모 앱 빌드도 됩니다. LLM 호출은 없었습니다.
- 팀 `main`(`5a0520b`) 임시 복제본에 우리 모듈과 `roadmystery-app/`의 두 파일을 붙여 봤습니다. 팀 저장소에는 올리지 않았습니다.
  - 팀 앱이 우리 모듈과 함께 컴파일되고 APK까지 만들어집니다(`:app:assembleDebug`). 우리 사건 원고도 팀 앱 asset으로 합쳐집니다. 우리 모듈의 Java 17 바이트코드와 팀 앱의 Java 11 설정이 함께 빌드됩니다.
  - 팀 기존 테스트 23개(엔진 14개 포함)와 우리 통합 테스트 2개, 모두 25개가 통과합니다. 건너뛴 것은 없습니다.
  - 우리 원고가 없는 시나리오가 대본으로 넘어가는 테스트도 통과합니다.
  - 팀 엔진을 거쳐 LLM 대답이 증언과 대답 장면이 되고, 그 증언으로 수첩 요약까지 하는 테스트도 통과합니다. LLM은 가짜이고, 질문에 다른 사람 이름이 있어도 고른 용의자가 답합니다.
  - 이 테스트는 처음에 대본 답이 나와 실패했습니다. 단위 테스트의 Android `org.json`이 빈 껍데기라 사건을 열지 못했기 때문입니다. `org.json:json`을 테스트 의존성에 넣자 통과했습니다. 이제 사건을 열지 못하면 테스트가 까닭을 드러냅니다.
- 2026-11-09 원고 대사만 읽는 방식으로 바꾼 뒤, 팀 `main` 최신(`ae990d9`)으로 맞춘 임시 복제본에서 다시 돌렸습니다.
  - 컴파일, APK, 팀 테스트 26개와 우리 통합 테스트 2개가 모두 통과합니다.
  - 팀 엔진에서 대답은 원고 대사로 증언이 되고, 요약은 모델 없이 수첩을 읽습니다.
- 2026-11-11 팀 저장소의 브랜치(`main`, F-01·F-03·F-09, F-10)를 모두 합친 임시 사본에 붙여 다시 확인했습니다.
  - 팀 테스트 80개와 우리 통합 테스트 2개가 통과하고, 에뮬레이터에서 심문과 수첩 요약이 팀 엔진을 거쳐 읽힙니다.
  - 합친 뒤에만 드러나는 팀 쪽 문제 둘과 PR #7에서 볼 것은 저장소에 올리지 않은 통합 QA 보고서에 따로 적었습니다.
- 원고 대사만 읽는 지금 방식에서는 지어냄과 못 찾은 단서 누설이 구조로 막혀, 사건마다 재지 않아도 됩니다. 심문 사건 `midnight_rest_area`를 포함한 네 사건 212문항을 실제 Haiku로 재 보니, 95%가 질문에 맞는 대사였습니다([원고 대사만 읽는 대답](line-answers.md)).
