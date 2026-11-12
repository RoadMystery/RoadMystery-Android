package dev.voiceagent.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.voiceagent.agent.AgentReply
import dev.voiceagent.agent.AgentState
import dev.voiceagent.agent.VoiceAgent
import dev.voiceagent.content.Scenario
import dev.voiceagent.content.ScenarioParser
import dev.voiceagent.llm.DirectClaudeLlmClient
import dev.voiceagent.llm.LlmClient
import dev.voiceagent.llm.ProxyLlmClient
import dev.voiceagent.platform.DEMO_CASE_DIR
import dev.voiceagent.platform.PleosSpeechInput
import dev.voiceagent.platform.PleosSpeechOutput
import dev.voiceagent.platform.fromAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Emulator demo of VoiceAgent: pick a case and a suspect, tick the clues found, then ask by typing, by
 * microphone, or with a recorded question, and hear the answer. For unattended runs:
 *
 *   adb shell am start -n dev.voiceagent.demo/.MainActivity --es case midnight_rest_area --es ask "서진 씨, 자정 무렵 어디 있었어요?"
 *
 * Extras, applied in this order: `case` (a directory under content/cases/; switching starts a new game),
 * `reset` (boolean), `suspect` (id; empty clears), `clues` (comma-separated ids), `pcm` (asset path of a
 * recorded question, e.g. stt/alibi-basic.pcm; the recordings ask about the earlier demo-sapphire case),
 * `ask` (text), `summary` (boolean). Each reply is logged under the VoiceAgentDemo tag as
 * `DEMO_REPLY {json}`, with the suspect's words (`suspectText`) and the narrator's line (`narratorLine`)
 * apart, and every command list ends with `DEMO_DONE`.
 */
class MainActivity : Activity() {
    /** Speech in and out and the proxy, which do not depend on the case; [agent] is the current case's. */
    private class Services(val output: PleosSpeechOutput, val input: PleosSpeechInput, val llm: LlmClient) {
        lateinit var agent: VoiceAgent
        var stateJob: Job? = null
    }

    /** A case under content/cases/, with the title its case.json gives. */
    private class CaseEntry(val id: String, val title: String)

    private val scope = MainScope()
    private val services = CompletableDeferred<Services>()
    private var ready: Services? = null
    private lateinit var cases: List<CaseEntry>
    private lateinit var scenario: Scenario
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var questionInput: EditText
    private lateinit var suspectRow: LinearLayout
    private lateinit var clueRow: LinearLayout
    private val clueBoxes = mutableListOf<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cases = listCases()
        // On recreation the launch intent is redelivered; only a fresh launch runs its commands.
        val commands = if (savedInstanceState == null) intent else null
        val requested = commands?.getStringExtra(EXTRA_CASE)?.takeIf { id -> BuildConfig.DEBUG && cases.any { it.id == id } }
        scenario = Scenario.fromAssets(assets, requested?.let { "cases/$it" } ?: DEMO_CASE_DIR)
        setContentView(buildLayout())
        scope.launch {
            try {
                services.complete(startServices())
                log("준비 완료: ${scenario.case.title}")
            } catch (e: Exception) {
                services.completeExceptionally(e)
                Log.e(TAG, "음성 엔진 준비 실패", e)
                log("음성 엔진 준비 실패: $e")
            }
            commands?.let(::runCommands)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCommands(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        ready?.let {
            it.output.release()
            it.input.release()
        }
        super.onDestroy()
    }

    private suspend fun startServices(): Services {
        val output = PleosSpeechOutput.create(this)
        val input = try {
            PleosSpeechInput.create(this)
        } catch (e: Exception) {
            output.release()
            throw e
        }
        val llm: LlmClient = if (BuildConfig.DEMO_ANTHROPIC_API_KEY.isNotEmpty()) {
            DirectClaudeLlmClient(BuildConfig.DEMO_ANTHROPIC_API_KEY)
        } else {
            ProxyLlmClient(BuildConfig.PROXY_URL, BuildConfig.PROXY_TOKEN, allowCleartext = BuildConfig.DEBUG)
        }
        return Services(output, input, llm).also {
            startAgent(it)
            ready = it
        }
    }

    /** A fresh agent for [scenario] on the same speech engines and proxy; the screen follows its state. */
    private fun startAgent(s: Services) {
        s.stateJob?.cancel()
        s.agent = VoiceAgent(scenario, s.llm, s.output)
        s.stateJob = scope.launch { s.agent.state.collect(::showState) }
    }

    /**
     * Plays [caseId] from the start: stops the answer being spoken, loads the case off the main thread, then
     * rebuilds the suspect and clue rows before the new agent's first state reaches them. An unknown id
     * keeps the current case.
     */
    private suspend fun switchCase(s: Services, caseId: String) {
        if (caseId == scenario.case.id) return
        if (cases.none { it.id == caseId }) {
            log("없는 사건: $caseId (있는 사건: ${cases.joinToString { it.id }})")
            return
        }
        val next = withContext(Dispatchers.IO) { Scenario.fromAssets(assets, "cases/$caseId") }
        s.agent.stop()
        s.stateJob?.cancel()
        scenario = next
        fillCaseRows()
        startAgent(s)
        log("사건: ${scenario.case.title}")
    }

    private fun runCommands(intent: Intent) {
        // The adb channel is debug-only: any app on the device can send this activity an intent.
        if (!BuildConfig.DEBUG || AUTOMATION_EXTRAS.none(intent::hasExtra)) return
        runAutomation(intent)
    }

    private fun runAutomation(intent: Intent) = withServices(alwaysLogDone = true) { s ->
        intent.getStringExtra(EXTRA_CASE)?.let { switchCase(s, it) }
        if (intent.getBooleanExtra(EXTRA_RESET, false)) s.agent.reset()
        intent.getStringExtra(EXTRA_SUSPECT)?.let { s.agent.selectSuspect(it.ifEmpty { null }) }
        intent.getStringExtra(EXTRA_CLUES)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            ?.forEach(s.agent::unlockClue)
        intent.getStringExtra(EXTRA_PCM)?.let { askRecorded(s, it) }
        intent.getStringExtra(EXTRA_ASK)?.let { question -> report("ask") { s.agent.ask(question) } }
        if (intent.getBooleanExtra(EXTRA_SUMMARY, false)) report("summary") { s.agent.summarize() }
    }

    private suspend fun askRecorded(s: Services, asset: String) {
        val pcm = withContext(Dispatchers.IO) { assets.open(asset).use { it.readBytes() } }
        s.agent.stop()
        val heard = s.input.recognizePcm(pcm)
        log("인식: ${heard ?: "(없음)"}")
        report("pcm", heard) { s.agent.ask(heard.orEmpty()) }
    }

    private suspend fun report(command: String, heard: String? = null, call: suspend () -> AgentReply) {
        val startedAt = SystemClock.elapsedRealtime()
        val reply = call()
        val json = JSONObject()
            .put("command", command)
            .put("case", scenario.case.id)
            .put("heard", heard ?: JSONObject.NULL)
            .put("kind", reply.kind.name)
            .put("suspect", reply.suspectId ?: JSONObject.NULL)
            .put("sentences", JSONArray(reply.sentences))
            // Testimony is the suspect's words only, never the narrator's hint after them.
            .put("suspectText", reply.suspectText)
            .put("narratorLine", reply.narratorLine ?: JSONObject.NULL)
            .put("fillerPlayed", reply.fillerPlayed)
            .put("firstTextMs", reply.firstTextMs ?: JSONObject.NULL)
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
            .put("failure", reply.failure ?: JSONObject.NULL)
            .put("speechError", reply.speechError ?: JSONObject.NULL)
        // Debug builds only: these lines are the player's conversation (llm-proxy-contract.md).
        if (BuildConfig.DEBUG) Log.i(TAG, "DEMO_REPLY $json")
        logReply(reply)
    }

    /** One line per voice: the suspect's words, then the narrator's line after them, if any. */
    private fun logReply(reply: AgentReply) {
        reply.suspectId?.let { log("[${reply.kind}] ${speakerName(it)}: ${reply.suspectText}") }
        val narrator = if (reply.suspectId == null) reply.text else reply.narratorLine
        narrator?.let { log("[${reply.kind}] ${speakerName(null)}: $it") }
    }

    private fun withServices(alwaysLogDone: Boolean = false, block: suspend (Services) -> Unit) = scope.launch {
        try {
            block(services.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "DEMO_FAILED", e)
            log("실패: $e")
        } finally {
            if (alwaysLogDone) Log.i(TAG, "DEMO_DONE")
        }
    }

    /**
     * The cases packed as assets (content/cases/), each one a directory holding a case.json. Only case.json
     * is read for the button title; the whole case loads when it is picked.
     */
    private fun listCases(): List<CaseEntry> = assets.list("cases").orEmpty()
        .filter { CASE_ID.matches(it) && assets.list("cases/$it").orEmpty().contains("case.json") }
        .sorted()
        .mapNotNull { id ->
            try {
                val text = assets.open("cases/$id/case.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                CaseEntry(id, ScenarioParser.parseCase(text).title)
            } catch (e: Exception) {
                Log.w(TAG, "사건 $id 을(를) 읽지 못함", e)
                null
            }
        }

    private fun buildLayout(): View {
        val padding = dp(16)
        stateView = TextView(this).apply { textSize = 16f }
        logView = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        questionInput = EditText(this).apply {
            textSize = 18f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, event ->
                // A hardware Enter reports key-down and key-up; ask once.
                if (event == null || event.action == KeyEvent.ACTION_DOWN) askTyped()
                true
            }
        }
        val caseButtons = cases.map { entry -> button(entry.title) { withServices { s -> switchCase(s, entry.id) } } }
        suspectRow = horizontal()
        clueRow = horizontal()
        fillCaseRows()
        val actions = listOf(
            button("묻기") { askTyped() },
            button("말로 묻기") { withServices { s -> report("listen") { s.agent.listenAndAsk(s.input) } } },
            button("정리해줘") { withServices { s -> report("summary") { s.agent.summarize() } } },
            button("멈춰") { ready?.agent?.stop() },
            button("새 게임") { withServices { it.agent.reset() } },
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@MainActivity).apply { text = connectionStatus() })
            addView(row(caseButtons))
            addView(stateView)
            addView(scrolling(suspectRow))
            addView(scrolling(clueRow))
            addView(questionInput)
            addView(row(actions))
            addView(ScrollView(this@MainActivity).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    /** The suspect buttons, the clue boxes and the question hint of [scenario]. */
    private fun fillCaseRows() {
        suspectRow.removeAllViews()
        suspectRow.addView(button("자동 판별") { select(null) })
        scenario.suspects.values.forEach { suspect -> suspectRow.addView(button("${suspect.name}(${suspect.role})") { select(suspect.id) }) }
        clueRow.removeAllViews()
        clueBoxes.clear()
        scenario.case.clues.mapTo(clueBoxes) { clue ->
            CheckBox(this).apply {
                text = clue.title
                textSize = 16f
                // showState() disables a box before ticking it, so only a tap reaches the agent.
                setOnCheckedChangeListener { box, checked ->
                    if (checked && box.isEnabled) withServices { it.agent.unlockClue(clue.id) }
                }
            }
        }
        clueBoxes.forEach(clueRow::addView)
        val first = scenario.suspects.values.first()
        questionInput.hint = "질문 입력 (예: ${first.name}한테 그날 밤 어디 있었는지 물어봐)"
        stateView.text = "사건: ${scenario.case.title}"
    }

    private fun askTyped() {
        val question = questionInput.text.toString()
        if (question.isBlank()) return // an empty ask would cut off the answer being spoken
        questionInput.text.clear()
        withServices { s -> report("ask") { s.agent.ask(question) } }
    }

    private fun select(suspectId: String?) = withServices { it.agent.selectSuspect(suspectId) }

    private fun showState(state: AgentState) {
        val current = state.currentSuspect?.let(scenario::suspect)?.let { "${it.name}(${it.role})" } ?: "없음"
        stateView.text = "사건: ${scenario.case.title} · 지금 묻는 사람: $current · 찾은 단서 ${state.unlockedClues.size}개 · " +
            "수첩 ${state.notebook.size}개"
        clueBoxes.forEachIndexed { index, box ->
            val found = scenario.case.clues[index].id in state.unlockedClues
            box.isEnabled = !found
            box.isChecked = found
        }
    }

    /** Never shows the token itself. */
    private fun connectionStatus(): String {
        if (BuildConfig.DEMO_ANTHROPIC_API_KEY.isNotEmpty()) return "Claude 직접 호출 · 키 있음"
        val token = if (BuildConfig.PROXY_TOKEN.isEmpty()) "없음 (tools/voice-eval/.env의 VOICE_PROXY_TOKEN)" else "있음"
        return "프록시 ${BuildConfig.PROXY_URL} · 토큰 $token"
    }

    private fun speakerName(suspectId: String?): String = suspectId?.let { scenario.suspect(it).name } ?: "진행자"

    private fun horizontal(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun scrolling(content: View): View = HorizontalScrollView(this).apply { addView(content) }

    private fun row(views: List<View>): View = scrolling(horizontal().apply { views.forEach { addView(it) } })

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 18f
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun log(line: String) {
        runOnUiThread { logView.append("$line\n") }
    }

    companion object {
        const val TAG = "VoiceAgentDemo"
        const val EXTRA_CASE = "case"
        const val EXTRA_RESET = "reset"
        const val EXTRA_SUSPECT = "suspect"
        const val EXTRA_CLUES = "clues"
        const val EXTRA_PCM = "pcm"
        const val EXTRA_ASK = "ask"
        const val EXTRA_SUMMARY = "summary"
        private val AUTOMATION_EXTRAS = listOf(EXTRA_CASE, EXTRA_RESET, EXTRA_SUSPECT, EXTRA_CLUES, EXTRA_PCM, EXTRA_ASK, EXTRA_SUMMARY)

        /** The same rule as case.schema.json: a case id becomes an asset path, so it must be one. */
        private val CASE_ID = Regex("[a-z0-9_-]+")
    }
}
