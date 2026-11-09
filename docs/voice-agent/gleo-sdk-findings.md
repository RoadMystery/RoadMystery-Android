# Gleo AI SDK 조사 결과 (VoiceAgent 관점)

- 작성: 김준성, 2026-11-02
- 기준: Pleos Connect SDK 문서와 릴리스 노트(2026-10-19), Pleos Connect Emulator v2.1.0
- 범위: 개발 지시서의 김준성 할 일 중 "Gleo AI SDK의 음성 인식·합성·LLM 응답 품질과 속도 테스트"

## 요약

1. **Gleo LLM SDK는 지원이 끝났습니다.** 용의자 답변(F-05)과 수사 수첩 요약(F-06)에는 외부 LLM이 필요하고, 개발 지시서에서 "선택"이던 LLM 프록시 서버(신준호)가 필수가 됩니다.
2. **STT·TTS SDK(3.3.1)는 쓸 수 있습니다.** 둘 다 온디바이스·서버·하이브리드 모드가 있습니다.
3. **지금은 온디바이스 모드만 쓸 수 있습니다.** 서버·하이브리드 모드는 Pleos Playground My Project의 Client ID/Secret이 필요한데, 문서상 STT/TTS 사용 신청이 아직 'Not Available Yet'입니다.
4. **TTS 한국어 목소리는 4개입니다.** 진행자 1명과 용의자 3명에 하나씩 줄 수 있지만, 속도·높낮이·감정 조절은 없습니다.
5. **에뮬레이터 측정 결과(2회):**
   - TTS는 요청 후 0.11~0.26초(중앙값 약 0.14초)에 소리가 납니다. 온디바이스 모드에서는 나눠 보내기를 해도 먼저 읽기 시작하지 않고, 문장을 따로 요청하면 앞 문장이 끊깁니다. 그래서 앱이 문장 대기열을 관리해야 합니다.
   - STT는 한 문장 발화를 모두 정확히 인식했고, 말이 끝난 뒤 약 0.48초 만에 최종 결과를 줬습니다. 다만 문장 사이 쉼에서 인식이 끊깁니다.
   - 자세한 숫자는 [측정 결과](#측정-결과-에뮬레이터)에 있습니다.

## 1. LLM: Gleo LLM SDK 지원 종료

- 근거: Emulator v2.1.0 릴리스 노트의 Removed Features에 "End of support for LLM SDK"가 있습니다. SDK 문서 목차에서도 LLM SDK가 빠졌습니다. Maven 저장소에는 `ai.pleos.playground:LLM`이 2.1.3.2까지 남아 있지만, 현재 에뮬레이터와 문서에서 지원하지 않으므로 쓰지 않습니다.
- 영향
  - LLM은 외부 서비스(예: Claude)를 씁니다. API 키를 앱에 넣으면 안 되므로 프록시 서버를 거쳐야 합니다.
  - 네트워크가 끊기면 심문·요약만 끄고 나머지는 계속한다는 기능 명세의 비기능 요구사항과 맞습니다. 끊겼을 때 대사는 페르소나의 `fallbackLines`를 온디바이스 TTS로 읽습니다.
  - 체감 대기 시간은 "네트워크 왕복 + LLM이 첫 문장을 만드는 시간"입니다. 이 시간과 답변 품질은 [`tools/voice-eval`](../../tools/voice-eval/)로 후보 모델별로 잽니다.
- 신준호와 정할 것
  - 프록시 API 형태: 첫 문장부터 TTS로 넘기려면 스트리밍 응답(SSE 등)이 필요합니다.
  - 프롬프트 조립 위치: 앱 또는 서버. 범인·수법 같은 비밀을 프롬프트에 넣는 "진실 포함" 방식을 쓰면 비밀이 앱에 들어가지 않도록 서버에서 조립하는 편이 안전합니다.

## 2. 음성 인식: SpeechToText 3.3.1

| 항목 | 내용 |
|---|---|
| 모드 | `HYBRID`(기본, 서버 우선·실패 시 온디바이스), `SERVER`, `ON_DEVICE` |
| 권한 | `pleos.car.permission.STT_SERVICE`. 에뮬레이터에서 설치만으로 허용됨(`granted=true`) 확인 |
| 입력 | `request()`는 차량 마이크, `sendAudio(ByteArray)`는 앱이 주는 PCM(16kHz·16bit·모노). PCM 입력은 온디바이스 모드에서만 됨 |
| 결과 | `onUpdated(text, completed)`로 중간·최종 결과, `onUpdatedEpdData`로 말 시작·끝 위치 |
| 언어 | `setLocale(Locale)` |
| 주의 | 인식된 글자만 돌려주고 명령을 실행하지는 않습니다. 앱 기능을 음성 명령으로 부르려면 Gleo Interface(함수 호출 스키마)를 따로 구현해야 합니다 |

## 3. 음성 합성: TextToSpeech 3.3.1

| 항목 | 내용 |
|---|---|
| 모드 | STT와 같음 |
| 권한 | `pleos.car.permission.TTS_SERVICE`. 설치만으로 허용됨 확인 |
| 화자 | `setSpeakerType(String)`. 한국어 `seoyoon`, `eunwoo`, `haeun`(기본), `sanghyeok`. 영어 `lyra`, `finley`, 스페인어 `iris`, `arlo`, 프랑스어 `sabine`, `noah` |
| 조절 | 화자와 언어(`setLocale`)만 바꿀 수 있습니다. 속도·높낮이·감정 설정은 없습니다 |
| 나눠 보내기 | `speak(text, isFinal=false)`로 앞부분을 보내고 마지막에 `isFinal=true`로 끝냅니다. 문서는 서버 모드의 "스트리밍 TTS"를 설명하지만, 온디바이스 모드에서는 마지막 조각이 올 때까지 읽기 시작하지 않았습니다([측정 결과](#측정-결과-에뮬레이터)) |
| 기타 | `setAudioAttributes`, `setAudioZoneId`(출력 구역 지정)가 있습니다 |

캐릭터 구분은 화자 선택과 대사의 말투로만 할 수 있습니다. 그래서 페르소나 JSON에 `voice.pleosSpeaker`와 `speechStyle`을 따로 두었습니다.

## 4. 계정과 권한

- 서버·하이브리드 모드: Pleos Playground에서 My Project를 만들면 나오는 Client ID/Secret으로 `registerApp()`을 해야 합니다. 문서의 SDK 사용 신청 화면 설명에는 TextToSpeech, SpeechToText, FusedLocation, Vehicle Control SDK가 'Not Available Yet'(신청 불가)으로 적혀 있습니다. 팀 프로젝트를 만들어 두고 상태가 바뀌는지 확인해야 합니다.
- 권한은 Pleos App Market과 미리 합의한 범위에서만 쓸 수 있습니다. 승인 안 된 권한을 선언하면 앱 심사에서 떨어집니다.

## 5. Gleo Interface (참고)

Gleo AI(차량 음성 비서)가 앱이 등록한 함수를 호출하는 방식입니다(OpenAI 함수 호출과 비슷한 App Schema). "글레오, 집사한테 물어봐"처럼 차량 음성 비서에서 바로 게임을 조작하려면 검토할 만합니다. 이번 범위에서는 앱이 STT를 직접 씁니다.

## 측정 결과 (에뮬레이터)

- 2026-11-02, Pleos Connect Emulator v2.1.0(x86_64), SDK 3.3.1, 온디바이스 모드, 측정 앱 [`apps/gleo-speech-test`](../../apps/gleo-speech-test/). 같은 조건으로 2회 측정했습니다.
- 원자료: [`data/speech-bench-emulator.log`](data/speech-bench-emulator.log)(1회), [`data/speech-bench-emulator-run2.log`](data/speech-bench-emulator-run2.log)(2회). 표는 `apps/gleo-speech-test/scripts/summarize_bench.py`로 다시 만들 수 있습니다.
- PC 위의 가상 장치에서 잰 숫자라 실차와 다릅니다. 방식끼리 비교하고 동작을 확인하는 용도로 보세요.

### TTS

"실제 소리"는 재생 음량(RMS) 콜백이 처음 0보다 커진 시점이고, `speak()` 호출부터 잽니다. `onStart` 콜백은 3~10ms에 와서 소리가 나는 시점이 아니라 요청 접수 시점에 가깝습니다. "재생 시간"은 실제 소리부터 마지막 `onDone`까지입니다.

| 측정 | 실제 소리까지(1회 / 2회) | 재생 시간(1회 / 2회) | 비고 |
|---|---|---|---|
| 앱 시작 후 첫 호출(haeun) | 363 / 366ms | 5.38 / 5.38초 | 첫 호출만 느림 |
| seoyoon | 144 / 148ms | 5.60 / 5.60초 | |
| eunwoo | 263 / 122ms | 5.55 / 5.53초 | |
| haeun | 145 / 114ms | 5.40 / 5.36초 | |
| sanghyeok | 119 / 233ms | 5.41 / 5.42초 | |
| 대기 음성 "음..."(haeun) | 63 / 164ms | 0.52 / 0.52초 | |
| 나눠 보내기(`isFinal=false`, 1초 뒤 `isFinal=true`) | 1,078 / 1,074ms | 4.17 / 4.20초 | 마지막 조각이 올 때까지 읽기 시작 안 함 |
| 문장별 요청(각각 `isFinal=true`, 1초 간격) | 84 / 231ms | 3.51 / 3.44초 | 시작 콜백 2번, 완료 1번(2회 모두) |

- TTS 자체 지연은 0.11~0.26초로 작습니다. 화자 사이 차이는 회차마다 뒤바뀌어서(eunwoo 263→122ms, sanghyeok 119→233ms) 화자 특성이 아니라 잡음으로 봅니다. 체감 대기 시간은 LLM이 첫 문장을 만드는 시간이 좌우합니다.
- 앱을 켠 뒤 첫 호출만 약 0.37초 걸립니다. 게임 시작 때 한 번 미리 초기화해 두는 편이 좋습니다.
- 문장별 요청은 들린 시간(3.4~3.5초)이 두 문장을 한 번에 읽을 때(4.2초)보다 0.7초 짧고 완료 콜백도 1번뿐이라, 두 번째 요청이 첫 문장을 끊은 것으로 보입니다.
- 결론: 온디바이스 모드에서는 나눠 보내기가 먼저 읽기를 해 주지 않고, 문장을 따로 요청하면 앞 문장이 끊깁니다. 그래서 **앱이 문장 대기열을 갖고, 앞 문장의 `onDone`을 받은 뒤 다음 문장을 `speak()`** 해야 합니다.

### STT (합성 음성 23개)

음성은 실제 마이크처럼 0.1초 분량이 쌓인 뒤 하나씩 `sendAudio()`로 넣었습니다.

| 항목 | 결과(2회 측정, 인식 문장은 두 번 모두 같음) |
|---|---|
| 한 문장 발화(11개) | 모두 정확(CER 0) |
| 두 문장 발화(12개) | 12개 모두 두 조각으로 나뉘어 최종 결과가 두 번 옴. 이어 붙인 평균 CER 0.06 |
| 전체 평균 CER | 0.03 |
| 첫 중간 결과 | 말을 시작하고 약 0.64초 뒤(중앙값 636 / 637ms) |
| 말이 끝난 뒤 최종 결과까지 | 중앙값 485 / 473ms(한 조각 발화, 범위 74~1,018ms) |
| 틀린 예 | 우산꽂이→오산고지, 서진 씨→서진식, 아시죠→아시는지요, AI지→aig, 브로치→그렇지, 피 튀기는 상황→pat기는 상환 |

- 마침표·물음표 뒤의 쉼에서 인식이 끊깁니다. 쉼표 정도의 짧은 쉼에서는 끊기지 않았습니다. 이번 측정은 음성 파일을 넣는 방식이라 끝까지 들었지만, 마이크 방식(`request()`)에서는 첫 문장에서 인식이 끝날 수 있습니다(미확인).
- 두 조각 발화는 말 끝 위치(EPD)가 조각마다 처음부터 다시 세어지는 것으로 보여, "말 끝→최종" 시간에서 뺐습니다.
- 고유명사와 드문 단어에서 틀립니다. 용의자 판별은 이름 뒤 조사를 보고 하므로 "서진식"처럼 틀린 이름에는 반응하지 않고 지금 대화 중인 용의자를 유지합니다.
- 깨끗한 합성 음성이라 이 인식률은 상한입니다. 사람 목소리와 차 안 소음에서 다시 재야 합니다.

## 6. VoiceAgent 설계에 주는 영향

- **목소리 배정:** 처음에는 진행자를 `haeun`(기본 화자)으로 두었습니다. 2026-11-09부터는 팀 F-03(`feat/f03-scene-narration`의 `NarrationVoices`)에 맞춰 진행자를 `sanghyeok`으로 씁니다. 용의자는 `seoyoon`(차분한 여성), `eunwoo`(거친 남성), `haeun`(밝은 여성)에서 겹치지 않게 고릅니다. 심야 휴게소 사건은 팀 표 그대로 박서진 `seoyoon`, 오민혁 `eunwoo`, 윤재희 `haeun`입니다.
- **대기 음성:** LLM 답을 기다리는 동안 "음..." 같은 짧은 음성을 온디바이스 TTS로 먼저 재생합니다(비기능 요구사항). 측정상 0.06~0.16초 만에 나옵니다.
- **답변 읽기:** LLM 스트리밍 답변을 문장 단위로 잘라 앱의 대기열에 넣고, 앞 문장 `onDone` 뒤에 다음 문장을 `speak(문장, isFinal=true)` 합니다. 첫 소리는 "LLM 첫 문장 시간 + 약 0.14초"가 됩니다.
- **질문 듣기:** 최종 결과가 온 뒤에도 1초 더 듣고 이어 붙입니다. 두 문장으로 묻는 플레이어 때문입니다. VoiceAgent 데모에서 녹음 파일 방식으로 두 문장 질문이 한 질문으로 합쳐지는 것을 확인했습니다([결과](../../apps/voice-agent/README.md#에뮬레이터-확인-결과)). 마이크 방식은 아직 확인하지 못했습니다.
- **용의자 판별:** "○○한테/에게 물어봐"처럼 대상을 지정하면 그 용의자로 바꾸고, 이름만 언급하면 지금 대화 중인 용의자를 유지합니다. STT 결과에는 쉼표가 없어서 부르는 말과 단순 언급을 구별할 수 없기 때문입니다. 대상 자리에서는 이름을 틀리게 인식해도 자모 단위 유사도로 찾습니다(`voice_eval/identify.py`, 예: "지브사한테" → 집사). "집사람", "요리 사진"처럼 이름이 들어간 일반 단어에는 반응하지 않습니다. 기기에서 LLM 없이 돌아가도록 규칙 기반으로 만들었습니다.
- **오프라인:** 심문·요약이 꺼질 때 읽을 대사를 페르소나 JSON의 `fallbackLines`에 둡니다.
- **LLM:** 심문은 `claude-haiku-5-5`, 요약은 `claude-sonnet-5-5`(둘 다 effort low)로 정했습니다. 근거와 측정값은 [LLM 비교와 답변 품질 개선](llm-evaluation.md)에 있습니다.

## 7. 다음 할 일

- [x] Claude API 키로 `tools/voice-eval` 실행 → 사용할 LLM 제안(개발 지시서 할 일 2): [LLM 비교와 답변 품질 개선](llm-evaluation.md)
- [x] VoiceAgent 안드로이드 모듈과 데모 앱(`apps/voice-agent`), 에뮬레이터에서 전체 흐름 확인(mock 프록시)
- [ ] 신준호: [프록시 규격](llm-proxy-contract.md)과 시나리오 JSON 형식(`content/schema/`) 합의
- [ ] 최재령: 시연 사건 원고가 나오면 `content/cases/` 교체
- [ ] 양채현: AudioPlayer의 문장 대기열 재생(앞 문장 `onDone` 뒤 다음 문장)과 위험 상황 일시정지(`stop()`) 방식 합의
- [ ] Pleos Playground My Project 만들기, STT/TTS 사용 신청 가능 시점 확인
- [ ] 마이크 방식(`request()`)에서 두 문장 질문이 첫 문장에서 끊기는지 확인
- [ ] 사람 목소리(가능하면 차 안)로 STT 다시 측정

## 출처

- [Pleos Connect SDK 개요](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/)
- [SpeechToText 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)
- [TextToSpeech 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro)
- [TextToSpeech.setSpeakerType](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/set-speaker-type)
- [Emulator 릴리스 노트](https://document.pleos.ai/en/docs/pleos-only/release-note/emulator-release-note)
- [프로젝트 생성과 SDK 사용 신청](https://document.pleos.ai/en/docs/pleos-only/create-my-project)
- [Gleo Interface](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/core-concepts)
