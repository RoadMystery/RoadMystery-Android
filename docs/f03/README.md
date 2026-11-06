# F-03 장면 낭독

## 기반과 범위

- 작업 브랜치: `feat/f03-scene-narration`. F-01의 `0113a43`을 기반으로 한다.
- 기존 `app/src/main/assets/scenarios/midnight_rest_area.json`의 **심야 휴게소의 마지막 손님**을 그대로 사용한다.
- 1챕터, 브리핑 1개, 본편 7개, 터널 회상 1개, 결말 2개가 있으며 `script`는 이미 문장별 배열이다. 녹음 음원은 없다.
- 본편 원고는 진행자가 용의자의 말을 전달하는 형식이므로 기본 진행자 음성으로 읽는다. 원고를 임의로 직접 대사로 고치지 않았다.
- 용의자 심문 대답은 기존 `Suspect.voice`를 연결한다. 문장마다 다른 화자가 필요한 새 원고는 선택 필드 `sentenceVoices`로 지정할 수 있다. 이 필드는 비어 있거나 `script`와 같은 길이여야 한다.

## 동작

1. StoryEngine의 출발·장면 종료 이벤트로 재생할 장면을 결정한다.
2. TTS 초기화 완료를 기다리고, 앞 문장의 완료 응답 후 다음 문장을 요청한다.
3. 본편 장면 끝에는 “질문할 것이 있나요? 잠시 생각할 시간을 드릴게요.”를 읽고 3초 기다린다.
4. 안내와 대기가 끝나야 `SceneFinished`를 보내 다음 장면으로 넘어간다. 브리핑·심문 답변·결말에는 질문 구간을 붙이지 않는다.
5. 중단 시 출력과 대기 타이머를 취소하고 문장 위치를 보존한다. 재개하면 그 문장의 처음부터 다시 읽는다. 질문 구간 중 중단했다면 질문 안내부터 다시 시작한다.
6. TTS 초기화·합성 실패 또는 응답 시간 초과 시 해당 문장에서 멈춘다. 실패를 장면 완료로 처리하거나 무음으로 대본을 건너뛰지 않는다. 개발 화면에서 오류를 확인하고 재시도할 수 있다.
7. 일시 오디오 포커스 상실 후 복구는 같은 문장부터 재생한다. 그 사이 위험 일시정지가 발생했다면 포커스 복구가 위험 중단을 덮어쓰지 않는다.

| 역할 / 기존 voice | Pleos 화자 |
|---|---|
| 진행자 / narrator | sanghyeok |
| 박서진 / female_calm | seoyoon |
| 오민혁 / male_rough | eunwoo |
| 윤재희 / female_bright | haeun |

`Scene.voice` 기본값은 `narrator`이며 `sentenceVoices`가 있으면 문장별 값을 우선한다. 직접 Pleos 화자 이름을 지정해도 된다. 알 수 없는 이름은 진행자로 처리한다.

TTS 3.3.1은 SDK 초기화 성공 후 리스너를 등록해야 한다. 한국어 온디바이스 합성에는 `Locale.KOREA`(`ko-KR`)를 사용한다. `Locale.KOREAN`(`ko`)은 이 에뮬레이터에서 `unsupported locale: ko-` 오류를 냈다.

## F-10과 연결

제공받은 `RoadMystery-Android-feat-f10-hazard-resume`의 `DefaultStoryEngine`과 엔진 테스트를 가져왔다. 자동 재개 시 최근 위험 이력을 지우던 부분을 보완하여, 재개 후 60초 이내 다시 위험이 발생해도 반복 위험으로 처리한다. 경과 시간은 단조 증가 시계를 쓴다.

- 단일 위험 이벤트: 음성 중단 → 3초 후 재개 안내 → 중단 문장 재생.
- 60초 이내 위험 2회: 정차 이벤트를 받을 때까지 보류.
- **실제 위험 해제 신호를 확인하는 기능은 아직 없다.** 새 이벤트가 없는 시간을 기준으로 재개하는 기존 엔진 정책을 사용한다.
- 실제 급제동·ADAS 감지, 음성 “멈춰” 명령 라우팅은 별도 F-10 작업이다.
- F-04 ETA 개선, 문장 저장 개선, F-05 LLM 브랜치 전체는 가져오지 않았다.

## 실행

### 메인 앱과 전용 테스트 화면의 차이

- 메인 앱은 F-01의 `SelectedVehicleState` → `StoryEngine` → F-03 `PleosTtsAudioPlayer`로 연결돼 있다. 디버그 빌드는 시뮬레이션 입력으로 시작하며, 실제 신호 입력을 선택하면 F-01 차량 판정 이벤트를 받는다.
- F-03 전용 테스트 화면은 별도 엔진에 `FakeVehicleState`를 연결한다. 정차·출발·위험 버튼으로 이벤트를 보내며 실제 차량 신호를 구독하지 않는다.
- 이번 검증은 음성 출력과 모의 이벤트의 중단·재개를 대상으로 한다. F-01 실제 차량 신호부터 F-03 낭독까지 이어지는 전체 동작 검증은 후속 작업이다.
- 브랜치에는 F-01 기반 코드와 F-10 엔진의 중단·재개 개선이 포함된다. F-04·F-05·문장 저장 개선 브랜치의 최신 변경을 모두 병합한 버전은 아니다.

Android Studio에서 이 프로젝트를 열고 Pleos Connect v2.1.0 에뮬레이터에 `app`을 실행한다. 개발 패널의 **F-03 낭독 테스트**로 들어간다. 실제 입력·주행 상태에서는 개발 패널을 숨기므로 테스트는 정차 상태에서 한다.

테스트 화면은 디버그 빌드에만 있으며 차량 연출을 실행하지 않는다.

- **기존 사건 낭독 시작**: 원본 사건의 브리핑과 본편을 순서대로 재생한다.
- **정차 / 출발 이벤트**: 사건을 시작한 뒤 모의 차량 이벤트를 전달한다. 상단의 한글 사건 상태·모의 차량 상태와 전달 확인 메시지로 결과를 확인한다. 일반 정차에서도 낭독은 계속되며, 음성 샘플 재생 중에는 이 버튼들을 비활성화한다.
- **위험 이벤트**: F-10 엔진에 급제동 이벤트를 전달한다. 한 번 누르면 자동 재개, 반복하면 정차까지 보류한다.
- **진행자 + 용의자 3명 음성 샘플**: 4개 화자와 질문 대기 구간을 확인한다. 기존 사건의 공식 원고와 별개인 개발용 샘플이다.
- **샘플 일시정지 / 재개**: 샘플 재생 중에만 일시정지, 샘플 정지 중에만 재개 버튼이 켜진다. 마지막으로 멈춘 문장에서 재생한다.
- **실패한 문장 재시도**: 음성 오류 상태에서만 켜지며 실패한 문장을 다시 요청한다. 처음부터 재생하는 버튼이 아니다. 초기화 자체가 실패했거나 stop 응답이 계속 없으면 앱을 다시 실행해야 한다.

```sh
adb shell am start -W -n com.roadmystery.app/.audio.NarrationTestActivity
# 4화자 샘플 중단·재개 자동 점검
adb shell am force-stop com.roadmystery.app
adb shell am start -W -n com.roadmystery.app/.audio.NarrationTestActivity --ez smoke true
```

주행용 화면은 장면 제목·진행률만 유지한다. 시뮬레이션 모드의 패널·자막은 개발용이다. Pleos OS가 앱 화면을 제한하는 정책은 변경하지 않았다.

질문 안내·대기는 F-03의 구간이며, 주행 중 음성 질문을 실제로 받는 F-05 연결까지 완료됐다는 뜻은 아니다. 녹음 음원·콘텐츠별 목소리 연출 확정도 후속 작업이다.

## 검증

- 단위 테스트: 문장 순서·화자 매핑, 취소 후 늦은 완료, 질문 대기, 대기 중 중단, 오류 재시도, 오디오 포커스와 위험 중단 충돌, F-10 엔진 통합.
- 실행 명령: `gradlew ktlintCheck :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
- 에뮬레이터 검증 결과는 `verification.md`에 기록한다. 로그의 기기 시각은 호스트의 한국 시각과 다를 수 있다.

## 참고

- [Pleos TTS 사용 순서](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro)
- [합성 시작·중단](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/TextToSpeech/start-and-stop)
- [화자 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/set-speaker-type)
- 화자 선택은 팀의 F-05 `docs/voice-agent/gleo-sdk-findings.md`와 기존 에뮬레이터 측정 기록을 참고했다.
