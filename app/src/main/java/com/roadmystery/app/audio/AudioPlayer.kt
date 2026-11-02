package com.roadmystery.app.audio

import com.roadmystery.app.story.Scene
import kotlinx.coroutines.flow.Flow

sealed interface AudioEvent {
    data class SentenceStarted(val sceneId: String, val index: Int) : AudioEvent

    data class SceneFinished(val sceneId: String) : AudioEvent
}

interface AudioPlayer {
    /** 문장 시작·장면 종료를 알린다. StoryEngine이 이걸 보고 다음 장면으로 넘어간다. */
    val events: Flow<AudioEvent>

    fun play(
        scene: Scene,
        fromSentence: Int = 0,
    )

    /** 멈춘 문장 인덱스. 재개하면 이 문장 처음부터 다시 읽는다. */
    fun pause(): Int

    fun stop()
}
