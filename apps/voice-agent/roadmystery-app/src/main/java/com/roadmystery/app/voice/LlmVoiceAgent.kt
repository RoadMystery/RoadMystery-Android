package com.roadmystery.app.voice

import com.roadmystery.app.story.Progress
import com.roadmystery.app.story.Scenario
import dev.voiceagent.prompt.Testimony
import dev.voiceagent.story.StoryVoices

/**
 * F-05 음성 심문과 F-06 수첩 요약을 LLM으로 한다. VoiceAgent 모듈(apps/voice-agent/voiceagent)의
 * StoryVoice를 이 앱의 VoiceAgent 인터페이스에 잇는다(docs/voice-agent/team-integration.md).
 *
 * - MainActivity에서 ScriptedVoiceAgent 대신 넣는다. 우리 사건 원고(content/cases/<시나리오 id>)가 없는
 *   시나리오는 [fallback]이 지금처럼 대본으로 답한다.
 * - 대답은 글로만 돌려준다. 읽기는 StoryEngine의 대답 장면이 한다(F-03에서는 용의자 voice).
 * - 대답은 사건 원고의 대사를 그대로 읽은 것이다. 모델은 대사 id만 고르고, 요약도 수첩을 그대로 읽는다
 *   (docs/voice-agent/line-answers.md). answerId에는 처음 읽은 대사 id를 넣는다.
 * - 대답으로 단서를 열지 않으므로 reveals는 비어 있다. 단서는 장면이 끝날 때 열린다(모든 단서가 필수 본편
 *   장면에서 열린다는 ScenarioParser.validate 규칙).
 * - 정차 화면의 질문 버튼과 "음성으로 묻기"는 그대로 쓴다. 질문에 다른 사람 이름이 있어도 고른 용의자가 답한다.
 */
class LlmVoiceAgent(
    private val voices: StoryVoices,
    private val fallback: VoiceAgent = ScriptedVoiceAgent(),
) : VoiceAgent {
    override suspend fun ask(
        utterance: String,
        context: CaseContext,
    ): SuspectReply {
        val voice = voices.forScenario(context.scenario.id) ?: return fallback.ask(utterance, context)
        val reply = voice.ask(context.suspect.id, utterance, context.foundClueIds)
        return SuspectReply(text = reply.text, answerId = reply.lineIds.firstOrNull())
    }

    override suspend fun summarize(
        scenario: Scenario,
        progress: Progress,
    ): String {
        val voice = voices.forScenario(scenario.id) ?: return fallback.summarize(scenario, progress)
        val notebook = progress.testimonies.map { Testimony(it.suspectId, it.question, it.answer) }
        return voice.summarize(progress.cluesFound, notebook)
    }
}
