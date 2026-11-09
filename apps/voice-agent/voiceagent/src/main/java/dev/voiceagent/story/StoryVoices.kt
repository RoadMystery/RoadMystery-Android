package dev.voiceagent.story

import dev.voiceagent.agent.AgentConfig
import dev.voiceagent.content.Scenario
import dev.voiceagent.llm.LlmClient
import java.io.IOException

/**
 * One [StoryVoice] per StoryEngine scenario, opened on first use from `cases/<scenario id>` (content/cases/,
 * which the library packs as assets). [load] reads a case directory: `Scenario.fromAssets(assets, it)` on the
 * device. A scenario with no case of ours gives null, so the team app can keep its scripted answers for it;
 * [onLoadError] hears why, once per scenario.
 */
class StoryVoices(
    private val load: (caseDir: String) -> Scenario,
    private val llm: LlmClient,
    private val config: AgentConfig = AgentConfig(),
    private val onLoadError: (scenarioId: String, error: Exception) -> Unit = { _, _ -> },
) {
    private val voices = mutableMapOf<String, StoryVoice?>()

    @Synchronized
    fun forScenario(scenarioId: String): StoryVoice? {
        if (scenarioId in voices) return voices[scenarioId]
        return open(scenarioId).also { voices[scenarioId] = it }
    }

    private fun open(scenarioId: String): StoryVoice? {
        // The id becomes an asset path, so it must be a case id and nothing else (case.schema.json).
        if (!SCENARIO_ID.matches(scenarioId)) {
            onLoadError(scenarioId, IllegalArgumentException("not a scenario id: $scenarioId"))
            return null
        }
        val voice = try {
            StoryVoice(load("cases/$scenarioId"), llm, config)
        } catch (e: IOException) {
            onLoadError(scenarioId, e)
            return null
        } catch (e: RuntimeException) {
            onLoadError(scenarioId, e) // ContentException or a missing cast member: the case is broken.
            return null
        }
        if (voice.scenarioId != scenarioId) {
            onLoadError(scenarioId, IllegalStateException("cases/$scenarioId holds case '${voice.scenarioId}'"))
            return null
        }
        return voice
    }

    private companion object {
        val SCENARIO_ID = Regex("[a-z0-9_-]+")
    }
}
