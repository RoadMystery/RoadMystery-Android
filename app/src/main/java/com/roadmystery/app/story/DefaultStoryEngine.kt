package com.roadmystery.app.story

import com.roadmystery.app.audio.AudioEvent
import com.roadmystery.app.audio.AudioPlayer
import com.roadmystery.app.vehicle.VehicleEvent
import com.roadmystery.app.vehicle.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 시나리오 한 챕터를 차량 이벤트에 맞춰 진행하는 상태 머신.
 *
 * - 본편(trigger 없는 장면)은 주행·정차 중 순서대로 재생된다. 첫 출발 전에는 브리핑만 재생한다.
 * - 장면이 끝나면 그 장면의 단서가 열리고, 다음 정차 때까지 pendingClues에 쌓인다.
 * - 위험 이벤트(급제동·ADAS·"멈춰")는 즉시 낭독을 멈추고, 다음 출발 때 멈춘 문장부터 재개한다.
 * - 남은 도착 시간이 부족하면 required=false 장면을 뺀다.
 * - 도착하면 낭독을 멈추고, 아직 못 들은 필수 장면의 단서를 지급한 뒤 범인 지목을 기다린다.
 *
 * 메인 스레드에서만 호출한다고 가정한다(스레드 안전하지 않음).
 */
class DefaultStoryEngine(
    private val repository: ScenarioRepository,
    private val audio: AudioPlayer,
) : StoryEngine {
    private val _mode = MutableStateFlow(AppMode.IDLE)
    override val mode: StateFlow<AppMode> = _mode.asStateFlow()

    private val _scenario = MutableStateFlow<Scenario?>(null)
    override val scenario: StateFlow<Scenario?> = _scenario.asStateFlow()

    private val _clues = MutableStateFlow<List<Clue>>(emptyList())
    override val clues: StateFlow<List<Clue>> = _clues.asStateFlow()

    private val _pendingClues = MutableStateFlow<List<Clue>>(emptyList())
    override val pendingClues: StateFlow<List<Clue>> = _pendingClues.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    override val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    override val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    private var chapter: Chapter? = null
    private val queue = ArrayDeque<Scene>()
    private val playedIds = mutableSetOf<String>()

    /** 도착 시간 때문에 큐에서 이미 버린 장면. */
    private val droppedIds = mutableSetOf<String>()

    /** 현재 ETA 기준으로 건너뛸 예정인 장면. ETA가 바뀔 때마다 다시 계산한다. */
    private val skippedIds = mutableSetOf<String>()
    private var current: Scene? = null
    private var sentenceIndex = 0

    /** 위험 이벤트로 낭독을 멈춘 상태. */
    private var held = false
    private var etaSec: Int? = null
    private val choices = mutableMapOf<String, String>()

    fun bind(
        vehicle: VehicleState,
        scope: CoroutineScope,
    ): Job =
        scope.launch {
            launch { audio.events.collect { onAudioEvent(it) } }
            launch { vehicle.events.collect { onVehicleEvent(it) } }
        }

    override fun start(scenarioId: String) {
        audio.stop()
        val loaded = repository.load(scenarioId)
        val errors = ScenarioParser.validate(loaded)
        check(errors.isEmpty()) { "시나리오 오류: ${errors.joinToString()}" }
        val firstChapter = loaded.chapters.first()

        queue.clear()
        playedIds.clear()
        droppedIds.clear()
        skippedIds.clear()
        choices.clear()
        current = null
        sentenceIndex = 0
        held = false
        etaSec = null
        _clues.value = emptyList()
        _pendingClues.value = emptyList()
        _nowPlaying.value = null
        _outcome.value = null

        _scenario.value = loaded
        chapter = firstChapter
        queue.addAll(firstChapter.mainScenes)
        _mode.value = AppMode.BRIEFING
        firstChapter.sceneFor(SceneTrigger.BRIEFING)?.let { play(it) }
        updateProgress()
    }

    override fun onVehicleEvent(event: VehicleEvent) {
        if (_mode.value == AppMode.IDLE) return
        when (event) {
            VehicleEvent.Driving -> onDriving()
            VehicleEvent.Stopped, VehicleEvent.Parked -> onStopped()
            is VehicleEvent.Hazard -> onHazard()
            VehicleEvent.Arrived -> onArrived()
            is VehicleEvent.Place -> onPlace(event.tag)
            is VehicleEvent.EtaChanged -> {
                etaSec = event.remainingSec
                replan()
                updateProgress()
            }
        }
    }

    fun onAudioEvent(event: AudioEvent) {
        val scene = current ?: return
        when (event) {
            is AudioEvent.SentenceStarted ->
                if (event.sceneId == scene.id) {
                    sentenceIndex = event.index
                    publishNowPlaying(scene)
                    updateProgress()
                }
            is AudioEvent.SceneFinished ->
                if (event.sceneId == scene.id && !held) finishScene(scene)
        }
    }

    override fun choose(
        decisionId: String,
        optionId: String,
    ) {
        if (decisionId != StoryEngine.DECISION_ACCUSE) {
            choices[decisionId] = optionId
            return
        }
        val loaded = _scenario.value ?: return
        if (_mode.value != AppMode.ENDING || _outcome.value != null) return
        if (loaded.suspect(optionId) == null) return

        choices[decisionId] = optionId
        val correct = optionId == loaded.culprit
        _outcome.value = Outcome(accusedId = optionId, culpritId = loaded.culprit, correct = correct)
        val trigger = if (correct) SceneTrigger.ENDING_CORRECT else SceneTrigger.ENDING_WRONG
        chapter?.sceneFor(trigger)?.let { play(it) }
    }

    private fun onDriving() {
        when (_mode.value) {
            AppMode.ENDING -> return
            AppMode.STOPPED -> _pendingClues.value = emptyList()
            else -> Unit
        }
        _mode.value = AppMode.DRIVING
        when {
            held -> resume()
            current == null -> playNext()
        }
    }

    private fun onStopped() {
        if (_mode.value == AppMode.ENDING) {
            if (held) resume()
            return
        }
        // 정차 중에도 낭독은 이어진다. 위험 이벤트로 멈춘 낭독은 다음 출발 때 재개한다.
        _mode.value = AppMode.STOPPED
    }

    private fun onHazard() {
        if (current != null && !held) {
            sentenceIndex = audio.pause()
            held = true
            current?.let { publishNowPlaying(it) }
        }
        if (_mode.value != AppMode.ENDING) _mode.value = AppMode.PAUSED
    }

    private fun onArrived() {
        if (_mode.value == AppMode.ENDING) return
        audio.stop()
        val unheard = listOfNotNull(current?.takeIf { it.trigger == null }) + queue.filter { it.trigger == null }
        unlock(unheard.filter { it.required }.flatMap { it.unlocks })
        queue.clear()
        current = null
        held = false
        _nowPlaying.value = null
        _mode.value = AppMode.ENDING
        _progress.value = 1f
    }

    private fun onPlace(tag: String) {
        if (_mode.value == AppMode.ENDING) return
        val scene =
            chapter?.scenes?.firstOrNull { scene ->
                scene.trigger == tag &&
                    scene.id !in playedIds &&
                    scene.id != current?.id &&
                    queue.none { it.id == scene.id }
            } ?: return
        queue.addFirst(scene)
        if (current == null && !held && _mode.value == AppMode.DRIVING) playNext()
    }

    private fun finishScene(scene: Scene) {
        current = null
        _nowPlaying.value = null
        playedIds += scene.id
        unlock(scene.unlocks)
        updateProgress()
        if (scene.trigger in SceneTrigger.endings) return
        if (_mode.value == AppMode.DRIVING || _mode.value == AppMode.STOPPED) playNext()
    }

    private fun playNext() {
        while (true) {
            val next = queue.removeFirstOrNull() ?: return
            if (next.id in skippedIds) {
                droppedIds += next.id
                continue
            }
            play(next)
            return
        }
    }

    private fun play(
        scene: Scene,
        fromSentence: Int = 0,
    ) {
        current = scene
        sentenceIndex = fromSentence
        held = false
        publishNowPlaying(scene)
        audio.play(scene, fromSentence)
    }

    private fun resume() {
        held = false
        val scene = current
        if (scene == null) playNext() else play(scene, sentenceIndex)
    }

    private fun unlock(ids: List<String>) {
        val loaded = _scenario.value ?: return
        val known = _clues.value.map { it.id }.toSet()
        val fresh = ids.distinct().filterNot { it in known }.mapNotNull { loaded.clue(it) }
        if (fresh.isEmpty()) return
        _clues.value = _clues.value + fresh
        _pendingClues.value = _pendingClues.value + fresh
    }

    /** 남은 ETA 안에 필수 장면을 먼저 넣고, 남는 시간만큼 선택 장면을 순서대로 넣는다. */
    private fun replan() {
        skippedIds.clear()
        val eta = etaSec ?: return
        val upcoming = queue.filter { it.trigger == null }
        val currentLeft = current?.let { remainingSec(it, sentenceIndex) } ?: 0
        var slack = eta - currentLeft - upcoming.filter { it.required }.sumOf { it.durationSec }
        upcoming.filterNot { it.required }.forEach { scene ->
            if (scene.durationSec <= slack) slack -= scene.durationSec else skippedIds += scene.id
        }
    }

    private fun updateProgress() {
        val main = chapter?.mainScenes?.filterNot { it.id in skippedIds || it.id in droppedIds } ?: return
        val total = main.sumOf { it.durationSec }.coerceAtLeast(1)
        var done = main.filter { it.id in playedIds }.sumOf { it.durationSec }.toFloat()
        current?.takeIf { it.trigger == null }?.let {
            done += it.durationSec * sentenceIndex.toFloat() / it.script.size.coerceAtLeast(1)
        }
        _progress.value = (done / total).coerceIn(0f, 1f)
    }

    private fun publishNowPlaying(scene: Scene) {
        _nowPlaying.value =
            NowPlaying(
                sceneId = scene.id,
                title = scene.title,
                sentenceIndex = sentenceIndex,
                sentenceCount = scene.script.size,
                sentence = scene.script.getOrNull(sentenceIndex),
            )
    }

    private fun remainingSec(
        scene: Scene,
        fromSentence: Int,
    ): Int {
        val size = scene.script.size.coerceAtLeast(1)
        return scene.durationSec * (size - fromSentence) / size
    }
}
