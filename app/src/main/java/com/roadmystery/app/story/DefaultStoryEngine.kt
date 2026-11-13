package com.roadmystery.app.story

import com.roadmystery.app.audio.AudioEvent
import com.roadmystery.app.audio.AudioPlayer
import com.roadmystery.app.director.Director
import com.roadmystery.app.director.Effect
import com.roadmystery.app.director.FakeDirector
import com.roadmystery.app.vehicle.HazardReason
import com.roadmystery.app.vehicle.VehicleEvent
import com.roadmystery.app.vehicle.VehicleState
import com.roadmystery.app.voice.CaseContext
import com.roadmystery.app.voice.ScriptedVoiceAgent
import com.roadmystery.app.voice.SuspectReply
import com.roadmystery.app.voice.VoiceAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 시나리오 한 챕터를 차량 이벤트에 맞춰 진행하는 상태 머신.
 *
 * - 본편(trigger 없는 장면)은 주행·정차 중 순서대로 재생된다. 첫 출발 전에는 브리핑만 재생한다.
 * - 장면이 끝나면 그 장면의 단서가 열리고, 다음 정차 때까지 pendingClues에 쌓인다.
 * - 위험 이벤트(급제동·ADAS·"멈춰")는 즉시 낭독과 심문을 멈춘다. 일정 시간 조용하면 "이어서 들려드릴게요" 안내 후
 *   멈춘 문장부터 재개한다. 짧은 시간 안에 반복되면 다음 정차 때까지 재개를 보류한다.
 * - 남은 도착 시간이 부족하면 required=false 장면을 뺀다. 목적지가 없으면 10분 챕터로 본다.
 * - 정차 중에는 심문·수첩 요약을 받는다. 출발하면 심문은 즉시 끝나고 낭독으로 돌아간다.
 * - 도착하면 낭독을 멈추고, 아직 못 들은 필수 장면의 단서를 지급한 뒤 범인 지목을 기다린다.
 *
 * 메인 스레드에서만 호출한다고 가정한다(스레드 안전하지 않음).
 */
class DefaultStoryEngine(
    private val repository: ScenarioRepository,
    private val audio: AudioPlayer,
    private val voice: VoiceAgent = ScriptedVoiceAgent(),
    private val director: Director = FakeDirector(),
    private var scope: CoroutineScope? = null,
    /** 위험 이벤트 후 이만큼 조용해야 낭독을 재개한다. */
    private val resumeDelayMs: Long = 3_000,
    /** 이 시간 안에 위험 이벤트가 [REPEAT_THRESHOLD]번 이상이면 정차 때까지 보류한다. */
    private val repeatWindowMs: Long = 60_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
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

    private val _canInteract = MutableStateFlow(false)
    override val canInteract: StateFlow<Boolean> = _canInteract.asStateFlow()

    private val _testimonies = MutableStateFlow<List<Testimony>>(emptyList())
    override val testimonies: StateFlow<List<Testimony>> = _testimonies.asStateFlow()

    private val _interrogation = MutableStateFlow<Interrogation?>(null)
    override val interrogation: StateFlow<Interrogation?> = _interrogation.asStateFlow()

    private val _snapshot = MutableStateFlow<Progress?>(null)
    override val snapshot: StateFlow<Progress?> = _snapshot.asStateFlow()

    private var chapter: Chapter? = null
    private val queue = ArrayDeque<Scene>()
    private val playedIds = mutableSetOf<String>()
    private val performedEffects = mutableSetOf<String>()

    /** 도착 시간 때문에 큐에서 이미 버린 장면. */
    private val droppedIds = mutableSetOf<String>()

    /** 현재 ETA 기준으로 건너뛸 예정인 장면. ETA가 바뀔 때마다 다시 계산한다. */
    private val skippedIds = mutableSetOf<String>()
    private var current: Scene? = null
    private var sentenceIndex = 0

    /** 위험 이벤트로 낭독을 멈춘 상태. */
    private var held = false
    private val activeHazards = mutableSetOf<HazardReason>()
    private var waitsForClearDelay = false

    /** 지속 경고 중에 도착이 왔다. 경고가 모두 풀리면 그때 도착을 처리한다. */
    private var arrivedDuringHazard = false

    /** 위험이 반복돼 다음 정차 때까지 재개하지 않는 상태. */
    private var deferred = false
    private var lastHazardAt = 0L
    private val hazardTimes = ArrayDeque<Long>()
    private var resumeJob: Job? = null
    private var etaSec: Int? = null
    private val choices = mutableMapOf<String, String>()

    /** 판정할 수 없으면 움직이는 것으로 본다. */
    private var moving = true

    /** 이어하기: 이 장면은 이 문장부터 재생한다. */
    private var resumeAt: Pair<String, Int>? = null

    /** 심문 때문에 멈춘 낭독 장면과 문장. */
    private var interrupted: Pair<Scene, Int>? = null
    private var askJob: Job? = null

    /** 주행 중 질문 구간에서 던진 질문의 대답이 진행 중이다. 같은 주행 상태의 Driving 이벤트로 끊지 않는다. */
    private var askedWhileDriving = false

    /** 정차 중 "음성으로 묻기"로 듣는 중이다. 듣는 동안 낭독은 [interrupted]에 멈춰 둔다. */
    private var listening = false

    /** 듣기 시작하면서 읽던 대답을 끊었다. 이을 낭독이 없으면 듣기가 끝날 때 다음 장면으로 간다. */
    private var cutReply = false
    private var replyCount = 0
    private val heardAnswers = mutableMapOf<String, MutableSet<String>>()

    fun bind(
        vehicle: VehicleState,
        scope: CoroutineScope,
    ): Job {
        if (this.scope == null) this.scope = scope
        return scope.launch {
            launch { audio.events.collect { onAudioEvent(it) } }
            launch { vehicle.events.collect { onVehicleEvent(it) } }
        }
    }

    /** 화면 종료·개발용 재생 전환 시 재개 예약까지 정리한다. 저장 스냅샷은 유지한다. */
    fun stop() {
        director.stop()
        askJob?.cancel()
        askedWhileDriving = false
        listening = false
        cutReply = false
        askJob = null
        cancelHazardState()
        audio.stop()
        held = false
        current = null
        interrupted = null
        _nowPlaying.value = null
        _interrogation.value = null
        _mode.value = AppMode.IDLE
    }

    override fun start(
        scenarioId: String,
        resume: Progress?,
    ) {
        director.stop()
        performedEffects.clear()
        audio.stop()
        askJob?.cancel()
        askedWhileDriving = false
        listening = false
        cutReply = false
        cancelHazardState()
        val loaded = repository.load(scenarioId)
        val errors = ScenarioParser.validate(loaded)
        check(errors.isEmpty()) { "시나리오 오류: ${errors.joinToString()}" }
        val saved = resume?.takeIf { it.scenarioId == scenarioId }
        val firstChapter = loaded.chapters.firstOrNull { it.id == saved?.chapterId } ?: loaded.chapters.first()

        queue.clear()
        playedIds.clear()
        droppedIds.clear()
        skippedIds.clear()
        choices.clear()
        heardAnswers.clear()
        current = null
        interrupted = null
        askJob = null
        sentenceIndex = 0
        held = false
        etaSec = null
        resumeAt = null
        _pendingClues.value = emptyList()
        _nowPlaying.value = null
        _outcome.value = null
        _interrogation.value = null

        _scenario.value = loaded
        chapter = firstChapter
        _clues.value = saved?.cluesFound.orEmpty().mapNotNull { loaded.clue(it) }
        _testimonies.value = saved?.testimonies.orEmpty()
        saved?.choices?.let { choices.putAll(it) }
        saved?.playedSceneIds?.let { playedIds.addAll(it) }
        queue.addAll(firstChapter.mainScenes.filterNot { it.id in playedIds })
        _mode.value = AppMode.BRIEFING
        replan()

        if (saved == null) {
            firstChapter.sceneFor(SceneTrigger.BRIEFING)?.let { play(it) }
        } else {
            resumeAt = saved.lastSceneId?.let { it to saved.lastSentenceIndex }
        }
        updateProgress()
        publishSnapshot()
    }

    override fun onVehicleEvent(event: VehicleEvent) {
        when (event) {
            VehicleEvent.Driving -> moving = true
            VehicleEvent.Stopped, VehicleEvent.Parked, VehicleEvent.Arrived -> moving = false
            else -> Unit
        }
        // 서 있으면 위험으로 낭독이 멈춰 있어도 조작을 받는다. 지속 경고가 켜져 있는 동안만 막는다.
        _canInteract.value = !moving && activeHazards.isEmpty()

        if (_mode.value == AppMode.IDLE) return
        when (event) {
            VehicleEvent.Driving -> onDriving()
            VehicleEvent.Stopped, VehicleEvent.Parked -> onStopped()
            is VehicleEvent.Hazard -> onHazard()
            is VehicleEvent.HazardState -> onHazardState(event)
            VehicleEvent.Arrived -> onArrived()
            is VehicleEvent.Place -> onPlace(event.tag)
            is VehicleEvent.PlaceExited -> {
                queue.removeAll { it.trigger == event.tag }
                if (current?.trigger == event.tag) director.stop()
            }
            is VehicleEvent.EtaChanged -> {
                etaSec = event.remainingSec
                replan()
                updateProgress()
            }
        }
    }

    fun onAudioEvent(event: AudioEvent) {
        // 위험으로 멈춘 낭독의 이벤트는 받지 않는다. 그동안 서서 들은 대답은 끝까지 따라간다.
        if (held && current?.trigger != SceneTrigger.REPLY) return
        val scene = current ?: return
        when (event) {
            is AudioEvent.SentenceStarted ->
                if (event.sceneId == scene.id) {
                    sentenceIndex = event.index
                    publishNowPlaying(scene)
                    updateProgress()
                    if (scene.trigger == null) publishSnapshot()
                }
            is AudioEvent.SceneFinished ->
                if (event.sceneId == scene.id) finishScene(scene)
        }
    }

    override fun choose(
        decisionId: String,
        optionId: String,
    ) {
        if (moving || held || activeHazards.isNotEmpty()) return
        if (decisionId != StoryEngine.DECISION_ACCUSE) {
            choices[decisionId] = optionId
            publishSnapshot()
            return
        }
        val loaded = _scenario.value ?: return
        if (_mode.value != AppMode.ENDING || _outcome.value != null) return
        if (loaded.suspect(optionId) == null) return

        choices[decisionId] = optionId
        val correct = optionId == loaded.culprit
        _outcome.value = Outcome(accusedId = optionId, culpritId = loaded.culprit, correct = correct)
        val trigger = if (correct) SceneTrigger.ENDING_CORRECT else SceneTrigger.ENDING_WRONG
        held = false
        chapter?.sceneFor(trigger)?.let { play(it) }
        publishSnapshot()
    }

    override fun ask(suspectId: String, utterance: String) = askSuspect(suspectId, utterance, whileDriving = false)

    private fun askSuspect(suspectId: String, utterance: String, whileDriving: Boolean) {
        val loaded = _scenario.value ?: return
        val suspect = loaded.suspect(suspectId) ?: return
        respond(suspectId, utterance, "${suspect.name}의 대답", whileDriving) {
            val context =
                CaseContext(
                    scenario = loaded,
                    suspect = suspect,
                    foundClueIds = _clues.value.map { it.id }.toSet(),
                    heardAnswerIds = heardAnswers[suspectId].orEmpty(),
                )
            voice.ask(utterance, context)
        }
    }

    override fun askBetweenScenes(
        suspectId: String,
        utterance: String,
    ) {
        val loaded = _scenario.value ?: return
        val suspect = loaded.suspect(suspectId) ?: return
        val scene = current ?: return
        val asking = scene.trigger == null && _mode.value == AppMode.DRIVING && moving && !held
        // 질문 구간은 장면의 마지막 문장을 읽은 뒤다. 아직 읽는 중이면 받지 않는다.
        if (!asking || sentenceIndex < scene.script.size - 1) return
        if (utterance.isBlank() || askJob?.isActive == true) return

        // 질문 구간이 끝나면서 오는 SceneFinished가 대답을 기다리는 동안 다음 장면을 시작하지 못하게,
        // 이 장면은 지금 끝난 것으로 치고 대답이 끝나면 다음 장면으로 간다.
        completeScene(scene)
        respond(suspectId = suspectId, question = utterance, title = "${suspect.name}의 대답", whileDriving = true) {
            val context =
                CaseContext(
                    scenario = loaded,
                    suspect = suspect,
                    foundClueIds = _clues.value.map { it.id }.toSet(),
                    heardAnswerIds = heardAnswers[suspectId].orEmpty(),
                )
            voice.ask(utterance, context)
        }
        if (askJob == null) playNext() // 대답을 시작하지 못했으면 질문이 없었던 것처럼 이어간다.
    }

    override fun beginListening(): Boolean {
        if (listening || held || moving || _mode.value != AppMode.STOPPED || askJob?.isActive == true) return false
        listening = true
        // 읽던 대답은 끊고, 낭독 중이었다면 멈춘 자리를 기억해 둔다(respond와 같다).
        val playing = current ?: return true
        if (playing.trigger == SceneTrigger.REPLY) {
            audio.stop()
            // 끊은 대답은 끝난 것으로 친다. 주행 중 질문의 대답이었어도 더 기다리지 않는다.
            askedWhileDriving = false
            cutReply = true
        } else {
            interrupted = playing to audio.pause()
        }
        current = null
        _nowPlaying.value = null
        return true
    }

    override fun endListening() {
        if (!listening) return
        listening = false
        val cut = cutReply
        cutReply = false
        // 질문이 이어졌으면 대답이 끝난 뒤에 낭독을 잇는다.
        if (askJob?.isActive == true || current != null) return
        val back = interrupted
        interrupted = null
        when {
            back != null -> play(back.first, back.second)
            // 끊은 대답 뒤에 이을 낭독이 없으면(주행 중 질문의 대답, 재개 안내) 대답이 끝났을 때처럼 다음 장면으로 간다.
            cut -> playNext()
        }
    }

    override fun requestSummary() {
        val loaded = _scenario.value ?: return
        val progress = _snapshot.value ?: return
        respond(suspectId = null, question = "수첩 요약", title = "수첩 요약") {
            SuspectReply(text = voice.summarize(loaded, progress))
        }
    }

    private fun respond(
        suspectId: String?,
        question: String,
        title: String,
        whileDriving: Boolean = false,
        produce: suspend () -> SuspectReply,
    ) {
        val asyncScope = scope ?: return
        // 위험으로 낭독이 멈춰 있어도 서 있으면 질문은 받는다(주행 중이면 모드가 PAUSED라 아래에서 걸린다).
        if (activeHazards.isNotEmpty() || question.isBlank() || askJob?.isActive == true) return
        val canAsk = if (whileDriving) _mode.value == AppMode.DRIVING && moving else _mode.value == AppMode.STOPPED && !moving
        if (!canAsk) return
        askedWhileDriving = whileDriving
        director.stop()

        // 진행 중이던 대답은 끊고, 낭독 중이었다면 멈춘 자리를 기억해 둔다.
        val playing = current
        when {
            playing?.trigger == SceneTrigger.REPLY -> {
                audio.stop()
                current = null
            }
            playing != null -> {
                interrupted = playing to (if (held) sentenceIndex else audio.pause())
                current = null
            }
        }
        _nowPlaying.value = null
        _interrogation.value = Interrogation(suspectId, question)

        askJob =
            asyncScope.launch {
                val reply = produce()
                // 주행 중 던진 질문은 그 사이 정차했어도 대답한다. 정차 중에 던진 질문은 출발하면 버린다.
                val canAnswer =
                    (_mode.value == AppMode.DRIVING && moving && whileDriving) ||
                        (_mode.value == AppMode.STOPPED && !moving)
                if (!canAnswer || held) {
                    askedWhileDriving = false
                    _interrogation.value = null
                    return@launch
                }
                if (suspectId != null) {
                    reply.answerId?.let { heardAnswers.getOrPut(suspectId) { mutableSetOf() } += it }
                    if (reply.isTestimony) _testimonies.value = _testimonies.value + Testimony(suspectId, question, reply.text)
                    unlock(reply.reveals)
                }
                _interrogation.value = Interrogation(suspectId, question, reply.text)
                val speaker = _scenario.value?.suspect(suspectId.orEmpty())?.voice ?: "narrator"
                play(replyScene(title, reply.text).copy(voice = speaker))
                publishSnapshot()
            }
    }

    /** 듣는 중이거나 생각 중이거나 대답 중인 심문을 끝내고 멈췄던 낭독 장면을 current로 되돌린다. 재생은 하지 않는다. */
    private fun abortInterrogation(): Boolean {
        val thinking = askJob?.isActive == true
        val waiting = listening
        askJob?.cancel()
        askedWhileDriving = false
        listening = false
        cutReply = false
        askJob = null
        val inReply = current?.trigger == SceneTrigger.REPLY
        _interrogation.value = null
        if (!thinking && !inReply && !waiting) return false

        if (inReply) audio.stop()
        val back = interrupted
        interrupted = null
        current = back?.first
        sentenceIndex = back?.second ?: 0
        val restored = current
        if (restored == null) _nowPlaying.value = null else publishNowPlaying(restored)
        return true
    }

    private fun onDriving() {
        // 주행 중 질문의 대답을 하는 동안 들어온 Driving은 이미 가고 있다는 알림이다.
        if (_mode.value == AppMode.DRIVING && askedWhileDriving) return
        when (_mode.value) {
            AppMode.ENDING -> return
            AppMode.STOPPED -> _pendingClues.value = emptyList()
            else -> Unit
        }
        if (askedWhileDriving) {
            _mode.value = AppMode.DRIVING
            return
        }
        val aborted = abortInterrogation()
        val scene = current
        when {
            held && canResumeAfterHazard() -> resumeAfterHazard()
            // 위험 직후이거나 반복 위험이면 멈춘 채로 둔다. 화면은 주행 중이므로 최소 상태(PAUSED)로 둔다.
            held -> _mode.value = AppMode.PAUSED
            else -> {
                _mode.value = AppMode.DRIVING
                when {
                    aborted && scene != null -> play(scene, sentenceIndex)
                    scene == null -> playNext()
                }
            }
        }
    }

    private fun onStopped() {
        if (activeHazards.isNotEmpty()) {
            deferred = false // 정차 조건은 충족했지만 경고 해제까지 기다린다.
            return
        }
        if (_mode.value == AppMode.ENDING) {
            if (held) resumeAfterHazard()
            return
        }
        // 정차 중에도 낭독은 이어진다. 위험 이벤트로 멈춘 낭독은 다음 출발 때 재개하지만,
        // 반복 위험으로 보류된 낭독은 정차한 지금 재개한다.
        _mode.value = AppMode.STOPPED
        if (held && deferred) {
            deferred = false
            resumeAfterHazard()
        }
    }

    private fun onHazard() {
        director.stop()
        val hadDrivingQuestion = askedWhileDriving
        if (abortInterrogation()) {
            if (current != null || hadDrivingQuestion) held = true
        } else if (current != null && !held) {
            sentenceIndex = audio.pause()
            held = true
            current?.let { publishNowPlaying(it) }
        }
        held = true
        // 서 있는 차에서는 낭독만 멈추고 정차 화면을 그대로 둔다("멈춰"라고 했을 때). 지속 경고는 서 있어도 화면을 막는다.
        val blocked = moving || activeHazards.isNotEmpty()
        _canInteract.value = !blocked
        if (_mode.value != AppMode.ENDING) _mode.value = if (blocked) AppMode.PAUSED else AppMode.STOPPED
        registerHazard()
        publishSnapshot()
    }

    private fun onHazardState(event: VehicleEvent.HazardState) {
        if (event.active) {
            if (activeHazards.add(event.reason)) onHazard()
            return
        }
        if (!activeHazards.remove(event.reason) || activeHazards.isNotEmpty() || !held) return
        if (arrivedDuringHazard) {
            // 경고 중에 미뤄 둔 도착: 낭독을 재개하지 않고 바로 지목 화면으로 간다.
            onArrived()
            return
        }
        lastHazardAt = clock()
        waitsForClearDelay = true
        resumeJob?.cancel()
        resumeJob = scope?.launch {
            delay(resumeDelayMs)
            if (held && activeHazards.isEmpty() && !deferred) resumeAfterHazard()
        }
    }

    /** 위험 시각을 기록하고, 반복이면 정차 때까지 보류하며, 아니면 재개 타이머를 다시 건다. */
    private fun registerHazard() {
        val now = clock()
        lastHazardAt = now
        hazardTimes.addLast(now)
        while (hazardTimes.isNotEmpty() && now - hazardTimes.first() > repeatWindowMs) hazardTimes.removeFirst()
        // 이미 서 있으면 보류할 이유가 없다. 다음 출발 때 재개한다.
        deferred = moving && hazardTimes.size >= REPEAT_THRESHOLD

        resumeJob?.cancel()
        resumeJob = null
        if (deferred || activeHazards.isNotEmpty()) return
        resumeJob =
            scope?.launch {
                delay(resumeDelayMs)
                if (held && moving && !deferred && activeHazards.isEmpty()) resumeAfterHazard()
            }
    }

    private fun canResumeAfterHazard() = activeHazards.isEmpty() && !deferred && clock() - lastHazardAt >= resumeDelayMs

    /** 위험으로 멈춘 낭독을 "이어서 들려드릴게요" 안내 후 멈춘 문장부터 다시 시작한다. */
    private fun resumeAfterHazard() {
        if (!held || activeHazards.isNotEmpty()) return
        if (waitsForClearDelay && clock() - lastHazardAt < resumeDelayMs) return
        waitsForClearDelay = false
        resumeJob?.cancel()
        resumeJob = null
        deferred = false
        _canInteract.value = !moving
        if (_mode.value == AppMode.PAUSED || _mode.value == AppMode.STOPPED) {
            _mode.value = if (moving) AppMode.DRIVING else AppMode.STOPPED
        }

        val scene = current
        val hasNext = scene != null || queue.isNotEmpty()
        // 결말 낭독은 안내 없이 바로 잇는다(안내 뒤 이어 재생은 DRIVING·STOPPED에서만 이뤄진다).
        if (!hasNext || _mode.value == AppMode.ENDING) {
            resume()
            return
        }
        held = false
        interrupted = scene?.let { it to sentenceIndex }
        current = null
        play(replyScene("이어서 듣기", RESUME_NOTICE))
    }

    private fun cancelHazardState() {
        resumeJob?.cancel()
        resumeJob = null
        deferred = false
        lastHazardAt = 0L
        hazardTimes.clear()
        activeHazards.clear()
        waitsForClearDelay = false
        arrivedDuringHazard = false
    }

    private fun onArrived() {
        if (held && activeHazards.isNotEmpty()) {
            arrivedDuringHazard = true
            return
        }
        if (_mode.value == AppMode.ENDING) return
        director.stop()
        abortInterrogation()
        cancelHazardState()
        audio.stop()
        val unheard = listOfNotNull(current?.takeIf { it.trigger == null }) + queue.filter { it.trigger == null }
        unlock(unheard.filter { it.required }.flatMap { it.unlocks })
        playedIds += unheard.filter { it.required }.map { it.id }
        queue.clear()
        current = null
        held = false
        // 위험으로 멈춘 채 도착했으면 이벤트 앞에서 계산한 값이 막힌 채로 남는다. 멈춤을 풀었으니 다시 계산한다.
        _canInteract.value = !moving && activeHazards.isEmpty()
        _nowPlaying.value = null
        _mode.value = AppMode.ENDING
        _progress.value = 1f
        publishSnapshot()
    }

    private fun onPlace(tag: String) {
        if (_mode.value == AppMode.ENDING || !moving || tag in SceneTrigger.endings ||
            tag == SceneTrigger.BRIEFING || tag == SceneTrigger.REPLY
        ) return
        val scene =
            chapter?.scenes?.firstOrNull { scene ->
                scene.trigger == tag &&
                    scene.id !in playedIds &&
                    scene.id != current?.id &&
                    scene.id != interrupted?.first?.id &&
                    queue.none { it.id == scene.id }
            } ?: return
        queue.addFirst(scene)
        replan()
        if (current == null && !held && !askedWhileDriving && _mode.value == AppMode.DRIVING) playNext()
    }

    private fun finishScene(scene: Scene) {
        director.stop()
        current = null
        _nowPlaying.value = null

        if (scene.trigger == SceneTrigger.REPLY) {
            askedWhileDriving = false
            val back = interrupted
            interrupted = null
            val canPlay = !held && (_mode.value == AppMode.STOPPED || _mode.value == AppMode.DRIVING)
            when {
                back != null && canPlay -> play(back.first, back.second)
                back != null -> {
                    current = back.first
                    sentenceIndex = back.second
                }
                canPlay -> playNext()
            }
            return
        }

        completeScene(scene)
        if (scene.trigger in SceneTrigger.endings) return
        if (_mode.value == AppMode.DRIVING || _mode.value == AppMode.STOPPED) playNext()
    }

    /** 장면을 끝난 것으로 기록한다: 단서를 열고 진행률과 저장 상태를 갱신한다. 다음 장면은 시작하지 않는다. */
    private fun completeScene(scene: Scene) {
        current = null
        _nowPlaying.value = null
        playedIds += scene.id
        unlock(scene.unlocks)
        updateProgress()
        publishSnapshot()
    }

    private fun playNext() {
        while (true) {
            val next = queue.removeFirstOrNull() ?: return
            if (next.id in skippedIds) {
                droppedIds += next.id
                continue
            }
            val from = resumeAt?.takeIf { it.first == next.id }?.second ?: 0
            resumeAt = null
            play(next, from)
            return
        }
    }

    private fun play(
        scene: Scene,
        fromSentence: Int = 0,
    ) {
        current = scene
        sentenceIndex = fromSentence
        publishNowPlaying(scene)
        // 첫 문장에서 재개해도 위험으로 중단한 연출을 다시 실행하지 않는다.
        if (fromSentence == 0 && performedEffects.add(scene.id)) {
            scene.effect?.let(Effect::from)?.let(director::perform)
        }
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

    /**
     * 남은 ETA 안에 필수 장면을 먼저 넣고, 남는 시간만큼 선택 장면을 순서대로 넣는다.
     * 목적지가 없어 ETA를 모르면 [DEFAULT_CHAPTER_SEC]초 챕터로 본다.
     * 끼어든 이벤트 장면(터널 등)도 남은 시간을 쓰므로 함께 계산한다.
     */
    private fun replan() {
        skippedIds.clear()
        val eta = etaSec ?: DEFAULT_CHAPTER_SEC
        val upcoming = queue.toList()
        val currentLeft = current?.takeIf { it.isPlanned() }?.let { remainingSec(it, sentenceIndex) } ?: 0
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

    private fun publishSnapshot() {
        val loaded = _scenario.value ?: return
        val ch = chapter ?: return
        val narration = (interrupted?.first ?: current)?.takeIf { it.trigger == null }
        val narrationIndex = if (interrupted != null) interrupted!!.second else sentenceIndex
        _snapshot.value =
            Progress(
                scenarioId = loaded.id,
                chapterId = ch.id,
                lastSceneId = narration?.id ?: resumeAt?.first,
                lastSentenceIndex = if (narration != null) narrationIndex else resumeAt?.second ?: 0,
                playedSceneIds = playedIds.toList(),
                cluesFound = _clues.value.map { it.id },
                testimonies = _testimonies.value,
                choices = choices.toMap(),
            )
    }

    private fun replyScene(
        title: String,
        text: String,
    ): Scene {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.ifEmpty { listOf(text) }
        return Scene(
            id = "reply_${replyCount++}",
            title = title,
            required = false,
            durationSec = (text.length / 7).coerceAtLeast(3),
            script = sentences,
            trigger = SceneTrigger.REPLY,
        )
    }

    /** 도착 시간 계산에 들어가는 장면인가. 브리핑(출발 전)·심문 대답·결말은 뺀다. */
    private fun Scene.isPlanned() =
        trigger != SceneTrigger.BRIEFING && trigger != SceneTrigger.REPLY && trigger !in SceneTrigger.endings

    private fun remainingSec(
        scene: Scene,
        fromSentence: Int,
    ): Int {
        val size = scene.script.size.coerceAtLeast(1)
        return scene.durationSec * (size - fromSentence) / size
    }

    private companion object {
        /** 목적지가 없을 때 챕터 길이로 보는 시간. */
        const val DEFAULT_CHAPTER_SEC = 600
        const val REPEAT_THRESHOLD = 2
        const val RESUME_NOTICE = "이어서 들려드릴게요."
    }
}
