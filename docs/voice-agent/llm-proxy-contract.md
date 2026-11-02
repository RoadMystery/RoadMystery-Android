# VoiceAgent LLM 프록시 규격 (초안 v0)

- 작성: 김준성, 2026-11-02
- 대상: 신준호(서버). 이 규격대로 만들면 VoiceAgent 쪽은 고칠 것이 없습니다.
- 개발용 구현: `python -m voice_eval proxy`(`tools/voice-eval/voice_eval/proxy.py`). 실제 서버가 나오기 전까지 에뮬레이터 테스트에 씁니다.

## 왜 필요한가

Gleo LLM SDK 지원이 끝나서 용의자 답변(F-05)과 수사 수첩 요약(F-06)에 외부 LLM(Claude)을 씁니다. API 키를 앱에 넣으면 누구나 빼낼 수 있으므로, 키는 서버만 갖고 앱은 서버를 거쳐 부릅니다.

## 요청

`POST /v1/voice-agent/respond`

| 헤더 | 값 |
|---|---|
| `Authorization` | `Bearer <앱 토큰>` |
| `Content-Type` | `application/json` |
| `Accept` | `text/event-stream` |

```json
{
  "task": "interrogation",
  "system": "앱이 조립한 시스템 프롬프트",
  "messages": [
    {"role": "user", "content": "정전 때 어디 계셨어요?"},
    {"role": "assistant", "content": "지하 저장고에 있었습니다."},
    {"role": "user", "content": "밖에 나간 적은 없어요?"}
  ],
  "requestId": "선택. 로그 추적용, 64자 이하"
}
```

- `task`: `interrogation`(심문, F-05) 또는 `summary`(수사 수첩 요약, F-06). **모델과 설정은 서버가 task로 고릅니다.** 앱은 모델을 지정하지 않습니다(비용 통제).
- `messages`: 플레이어(`user`)부터 번갈아 있고, 마지막은 `user`. 1~21개, 각 1~1,000자.
- `system`: 1~16,000자. 프롬프트는 앱이 조립합니다. 정답이 든 `case.json`은 결말(F-11) 때문에 어차피 앱에 들어 있어서, 서버에서 조립해도 비밀이 더 지켜지지 않습니다. 다만 남용을 막는 데는 서버 조립이 낫습니다(아래 "실서버 요구 사항").
- `requestId`: 로그에 남으므로 영문·숫자와 `._-`만, 64자 이하. 다른 문자가 있으면 무시합니다.
- 본문 전체는 256KB 이하입니다(위 한도를 모두 채운 한국어 요청을 `\uXXXX`로 이스케이프해도 들어가는 크기).
- 앱 토큰은 공백 없는 ASCII 16자 이상입니다.

## 응답: SSE 스트림

성공하면 `200`과 `text/event-stream`으로 이벤트가 이어집니다.

```
event: delta
data: {"text": "지하 저장고에"}

event: delta
data: {"text": " 있었습니다."}

event: done
data: {"stopReason": "end_turn", "model": "claude-haiku-5-5", "inputTokens": 2400, "outputTokens": 38}
```

| 이벤트 | 의미 | 앱 동작 |
|---|---|---|
| `delta` | 답변 글자 조각 | 문장이 완성되는 대로 TTS 대기열에 넣음 |
| `done` | 정상 종료 | 대화 기록에 저장 |
| `error` | 실패. `code`: `refusal`, `rate_limited`, `upstream_error`, `upstream_unavailable`, `upstream_auth` | 아직 아무 말도 안 했으면 용의자의 대체 대사를 읽음 |

스트림 시작 전 실패는 일반 HTTP 오류로 답합니다: `400`(규격 위반), `401`(토큰 틀림), `429`(요청 과다, `Retry-After` 포함), `404`.

`GET /healthz` → `{"ok": true}`

## 남용 방지 (필수)

시스템 프롬프트를 그대로 넘기는 구조라, 아래가 없으면 누구나 쓰는 무료 LLM 창구가 됩니다.

- 앱 토큰 인증
- 토큰당 요청 수 제한(개발용은 분당 30회)
- 위의 길이·개수 제한
- 서버가 정한 모델만 사용, `max_tokens` 상한

질문과 답변 내용은 플레이어 음성이 담긴 개인정보일 수 있으므로 로그에 남기지 않습니다(개발용 구현도 시간과 task만 기록). Anthropic SDK의 디버그 로그(`ANTHROPIC_LOG=debug`)는 요청 본문을 찍으므로 켜지 않습니다.

## 실서버 요구 사항

개발용 프록시는 이 PC 안(127.0.0.1)에서만 열리므로 아래가 없어도 괜찮지만, 실제 서버는 반드시 갖춰야 합니다(2026-11-02 보안 리뷰 결과).

- **앱마다 다른 자격 증명.** 지금처럼 모든 앱이 같은 토큰을 쓰면 APK를 풀어 토큰을 꺼낸 사람이 남의 크레딧을 쓰고 한도까지 막을 수 있습니다. 설치마다 발급하는 짧은 수명 토큰과 기기 검증(Play Integrity 또는 Pleos 서명 확인)을 쓰세요.
- **프롬프트는 서버가 조립하거나 정해진 템플릿만 허용.** `system`을 그대로 받으면 무료 LLM 창구가 됩니다. `(caseId, suspectId, 찾은 단서 id, messages)`를 받아 서버가 조립하는 방식을 권합니다.
- **task별 `max_tokens` 상한**(개발용은 2,048), 설치·IP별 요청 한도와 하루 토큰 예산, 지출 알림과 긴급 차단 스위치.
- **HTTPS만.** 앱의 `ProxyLlmClient`는 `allowCleartext`를 켜지 않으면 http 주소를 거부합니다(개발용 데모만 켬). 리다이렉트는 따라가지 않습니다.
- **요청 처리 한도:** 본문 크기, 읽기·쓰기 제한 시간, 동시 연결 수. 앱이 연결을 끊으면 Claude 스트림도 바로 닫습니다(개발용 구현처럼).
- **개인정보:** 플레이어 발화가 외부 LLM으로 가므로 이용 안내·동의와 보관 정책이 필요합니다. 서버는 본문을 로그에 남기지 않습니다.
- 진실 포함(`truth`) 프롬프트를 쓰게 되면 범인·수법이 요청마다 오가므로, 앱의 금지 문구 검사가 마지막 방어선이 됩니다.

## 시간 기준

- 앱은 요청 후 **0.3초 안에 읽을 문장이 준비되지 않으면 대기 음성("음...")을 먼저 재생**합니다(기능 명세 비기능 요구사항). 첫 `delta`가 와도 문장이 끝나기 전까지는 말을 시작할 수 없어서 문장 기준으로 잽니다.
- 첫 `delta`가 8초 안에 오지 않으면 요청을 끊고 대체 대사를 읽습니다.
- 서버의 Claude 호출 제한 시간은 30초입니다. 앱도 답변 하나를 30초까지만 기다립니다.
- 앱 구현: [`ProxyLlmClient.kt`](../../apps/voice-agent/voiceagent/src/main/java/dev/voiceagent/llm/ProxyLlmClient.kt), 시간 규칙은 [`VoiceAgent.kt`](../../apps/voice-agent/voiceagent/src/main/java/dev/voiceagent/agent/VoiceAgent.kt).

## 대체 모델(fallback)을 쓰지 않는 이유

Claude의 서버 측 대체 모델 기능은 거절 전 모델이 쓰던 글자와 대체 모델의 글자를 한 스트림에 이어 보냅니다. 차에서는 이미 읽기 시작한 말을 되돌릴 수 없으니, 프록시는 거절을 `error`(`refusal`)로 보내고 앱이 대체 대사를 읽습니다.

## 개발용 프록시 실행

```powershell
cd tools\voice-eval
.\.venv\Scripts\python.exe -m voice_eval proxy --port 8787
.\.venv\Scripts\python.exe -m voice_eval proxy --mock     # Claude 대신 고정 답변을 스트리밍(비용 없음)
```

- `.env`에 `ANTHROPIC_API_KEY`와 `VOICE_PROXY_TOKEN`이 있어야 합니다(`.env.example` 참고). `--mock`에는 토큰만 있으면 됩니다.
- 127.0.0.1에만 열립니다. 다른 `--host`는 토큰이 평문으로 오가므로 `--allow-remote` 없이는 거부합니다. 같은 포트에 프록시가 이미 떠 있으면 시작하지 않습니다.
- 소켓 읽기·쓰기 제한 10초, 답변 `max_tokens` 2,048, 재시도 없음(차가 8초에 포기한 뒤 다시 보내지 않도록). 실패하면 콘솔에 오류 코드만 경고로 남깁니다.
- `--mock`은 첫 글자를 0.4초 뒤에 보내고 6글자씩 0.05초 간격으로 이어 보냅니다. 앱의 대기 음성과 문장 단위 재생을 API 키 없이 확인할 때 씁니다.
- 이 PC 안(127.0.0.1)에서만 열리고, 에뮬레이터에서는 `http://10.0.2.2:8787`로 접속합니다.
- 심문·요약에 쓸 모델은 `--interrogation`, `--summary`에 `candidates.json`의 후보 id로 지정합니다. 기본값은 비교 결과에 따라 심문 `haiku-5-5-low`, 요약 `sonnet-5-5-low`입니다([LLM 비교와 답변 품질 개선](llm-evaluation.md)). 프록시는 `fallbacks`를 쓰지 않으므로, Sonnet 후보로 시작하면 그 사실을 경고로 한 줄 남깁니다.
