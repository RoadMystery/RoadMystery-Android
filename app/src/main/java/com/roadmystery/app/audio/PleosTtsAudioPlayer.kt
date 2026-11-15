package com.roadmystery.app.audio

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.roadmystery.app.story.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pleos TextToSpeech SDK로 대본을 한 문장씩 읽는다. 녹음 파일이 준비되면 그걸 재생하는 구현으로 바꾼다.
 *
 * - 클라이언트 ID/시크릿이 있으면 HYBRID(서버 음성, 실패 시 온디바이스), 없으면 ON_DEVICE.
 * - 초기화에 실패하면 무음 타이머(FakeAudioPlayer)로 대신 진행해 시연이 끊기지 않게 한다.
 * - 오디오 포커스를 일시적으로 잃으면(내비 안내 등) 문장을 멈췄다가 포커스가 돌아오면 그 문장부터 다시 읽는다.
 */
class PleosTtsAudioPlayer(
    context: Context,
    private val scope: CoroutineScope,
    clientId: String,
    clientSecret: String,
    private val fallback: FakeAudioPlayer = FakeAudioPlayer(scope),
) : AudioPlayer {
    private val ttsEvents = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 64)
    override val events: Flow<AudioEvent> = merge(ttsEvents.asSharedFlow(), fallback.events)

    private val useServer = clientId.isNotBlank() && clientSecret.isNotBlank()
    private val tts = TextToSpeech(context.applicationContext, if (useServer) Mode.HYBRID else Mode.ON_DEVICE)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private val focusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> scope.launch { onFocusChange(change) } }
            .build()

    private val _ttsReady = MutableStateFlow<Boolean?>(null)

    /** null: 초기화 중, true: TTS 사용, false: 무음 대체 */
    val ttsReady: StateFlow<Boolean?> = _ttsReady.asStateFlow()

    private var scene: Scene? = null
    private var index = 0
    private var speaking = false
    private var focusPaused = false

    private val ttsListener =
        object : EventListener {
            override fun onStart() = Unit

            override fun onReady() = Unit

            override fun onDone() {
                scope.launch { onSentenceDone() }
            }

            override fun onError(errorType: ErrorType) {
                Log.w(TAG, "TTS 오류: $errorType")
                scope.launch { onSentenceDone() }
            }

            override fun onStop() = Unit

            override fun onUpdatedRms(rms: Double) = Unit
        }

    init {
        tts.initialize(
            object : OnInitializeListener {
                override fun onSuccess() {
                    // SDK 내부 서비스는 initialize 이후에 생기므로 설정은 여기서 한다.
                    tts.addEventListener(ttsListener)
                    tts.setAudioAttributes(attributes)
                    tts.setLocale(Locale.KOREAN)
                    if (!useServer) {
                        _ttsReady.value = true
                        return
                    }
                    tts.registerApp(
                        clientId,
                        clientSecret,
                        object : OnServerConnectionListener {
                            override fun onConnected() {
                                _ttsReady.value = true
                            }

                            override fun onFailed(errorType: ErrorType) {
                                Log.w(TAG, "TTS 서버 등록 실패, 온디바이스로 계속: $errorType")
                                _ttsReady.value = true
                            }
                        },
                    )
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS 초기화 실패, 무음으로 대체: $errorType")
                    _ttsReady.value = false
                }
            },
        )
    }

    /** 무음 대체로 갈 때 쓰는 낭독 속도. */
    var fallbackSpeed: Float
        get() = fallback.speed
        set(value) {
            fallback.speed = value
        }

    override fun play(
        scene: Scene,
        fromSentence: Int,
    ) {
        stopSpeaking()
        if (_ttsReady.value != true) {
            fallback.play(scene, fromSentence)
            return
        }
        fallback.stop()
        this.scene = scene
        index = fromSentence
        audioManager.requestAudioFocus(focusRequest)
        speakCurrent()
    }

    override fun pause(): Int {
        if (scene == null) return fallback.pause()
        stopSpeaking()
        return index
    }

    override fun stop() {
        stopSpeaking()
        fallback.stop()
        scene = null
        index = 0
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun release() {
        stop()
        runCatching { tts.release() }
    }

    private fun speakCurrent() {
        val current = scene ?: return
        val sentence = current.script.getOrNull(index)
        if (sentence == null) {
            scene = null
            speaking = false
            ttsEvents.tryEmit(AudioEvent.SceneFinished(current.id))
            return
        }
        speaking = true
        ttsEvents.tryEmit(AudioEvent.SentenceStarted(current.id, index))
        tts.speak(sentence, true)
    }

    private fun onSentenceDone() {
        if (!speaking || focusPaused) return
        index++
        speakCurrent()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            // CAN_DUCK은 플랫폼이 볼륨만 줄이도록 둔다(Pleos 오디오 포커스 정책: duck 중 pause 금지).
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (speaking) {
                focusPaused = true
                tts.stop()
            }
            AudioManager.AUDIOFOCUS_GAIN ->
                if (focusPaused) {
                    focusPaused = false
                    speakCurrent()
                }
        }
    }

    private fun stopSpeaking() {
        speaking = false
        focusPaused = false
        if (scene != null) runCatching { tts.stop() }
        scene = null
    }

    private companion object {
        const val TAG = "PleosTtsAudioPlayer"
    }
}
