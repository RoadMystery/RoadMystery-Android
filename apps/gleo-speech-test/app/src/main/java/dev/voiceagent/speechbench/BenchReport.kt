package dev.voiceagent.speechbench

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** JSON output: one file per run, plus one logcat line per result (BENCH_RESULT {...}). */
object BenchReport {

    fun ttsJson(result: TtsResult): JSONObject = JSONObject()
        .put("kind", "tts")
        .put("label", result.label)
        .put("speaker", result.speaker)
        .put("text", result.text)
        .put("startLatencyMs", result.startLatencyMs ?: JSONObject.NULL)
        .put("soundLatencyMs", result.soundLatencyMs ?: JSONObject.NULL)
        .put("playbackMs", result.playbackMs ?: JSONObject.NULL)
        .put("events", result.events)
        .put("error", result.error ?: JSONObject.NULL)

    fun sttJson(result: SttResult): JSONObject = JSONObject()
        .put("kind", "stt")
        .put("id", result.id)
        .put("expected", result.expected)
        .put("recognized", result.recognized)
        .put("segments", result.segments)
        .put("cer", result.cer ?: JSONObject.NULL)
        .put("audioMs", result.audioMs)
        .put("firstPartialMs", result.firstPartialMs ?: JSONObject.NULL)
        .put("finalAfterSpeechEndMs", result.finalAfterSpeechEndMs ?: JSONObject.NULL)
        .put("finalAfterAudioEndMs", result.finalAfterAudioEndMs ?: JSONObject.NULL)
        .put("error", result.error ?: JSONObject.NULL)

    fun toJson(tts: List<TtsResult>, stt: List<SttResult>): JSONObject = JSONObject()
        .put("device", deviceJson())
        .put("tts", JSONArray(tts.map(::ttsJson)))
        .put("stt", JSONArray(stt.map(::sttJson)))

    fun write(context: Context, json: JSONObject): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "speech-bench-$stamp.json").apply { writeText(json.toString(2)) }
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("model", Build.MODEL)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("fingerprint", Build.FINGERPRINT)
}
