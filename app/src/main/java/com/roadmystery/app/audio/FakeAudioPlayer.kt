package com.roadmystery.app.audio

import com.roadmystery.app.story.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 소리 없이 시간만 흘려 보내는 가짜 플레이어.
 * 장면 길이(durationSec)를 문장 수로 나눠 한 문장씩 넘긴다. speed로 빨리 감기할 수 있다.
 */
class FakeAudioPlayer(
    private val scope: CoroutineScope,
    var speed: Float = 1f,
) : AudioPlayer {
    private val _events = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AudioEvent> = _events.asSharedFlow()

    private var job: Job? = null
    private var index = 0

    override fun play(
        scene: Scene,
        fromSentence: Int,
    ) {
        job?.cancel()
        index = fromSentence
        val sentenceMs = scene.durationSec * 1000L / scene.script.size.coerceAtLeast(1)
        job =
            scope.launch {
                for (i in fromSentence until scene.script.size) {
                    index = i
                    _events.emit(AudioEvent.SentenceStarted(scene.id, i))
                    delay((sentenceMs / speed.coerceAtLeast(0.1f)).toLong())
                }
                _events.emit(AudioEvent.SceneFinished(scene.id))
            }
    }

    override fun pause(): Int {
        job?.cancel()
        job = null
        return index
    }

    override fun stop() {
        job?.cancel()
        job = null
        index = 0
    }
}
