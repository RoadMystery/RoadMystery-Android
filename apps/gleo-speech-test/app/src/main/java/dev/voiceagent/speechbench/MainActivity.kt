package dev.voiceagent.speechbench

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Buttons start the TTS / STT / combined benchmarks. For unattended runs:
 *   adb shell am start -n dev.voiceagent.speechbench/.MainActivity --es run all
 * then read `adb logcat -s SpeechBench`: one BENCH_RESULT line per result as it is produced,
 * BENCH_FAILED if a bench throws, and always BENCH_DONE at the end.
 */
class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        // On recreation the launch intent is redelivered; only a fresh launch should start a run.
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_RUN)?.let(::startBench)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_RUN)?.let(::startBench)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val padding = (24 * resources.displayMetrics.density).toInt()
        logView = TextView(this).apply {
            textSize = 18f
            setTextIsSelectable(true)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("TTS 측정") { startBench(RUN_TTS) })
            addView(button("STT 측정") { startBench(RUN_STT) })
            addView(button("전체 측정") { startBench(RUN_ALL) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(buttons)
            addView(
                ScrollView(this@MainActivity).apply { addView(logView) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
            )
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 20f
        setOnClickListener { onClick() }
    }

    private fun startBench(what: String) {
        if (what !in setOf(RUN_TTS, RUN_STT, RUN_ALL)) {
            appendLog("알 수 없는 측정: $what (tts, stt, all 중 하나)")
            return
        }
        // Process-wide: an Activity recreated mid-run must not start a second run on the engine.
        if (!running.compareAndSet(false, true)) {
            appendLog("이미 측정 중입니다.")
            return
        }
        executor.execute {
            try {
                runBench(what)
            } finally {
                running.set(false)
            }
        }
    }

    private fun runBench(what: String) {
        appendLog("측정 시작: $what")
        val tts = if (what == RUN_STT) emptyList() else runSafely("tts") {
            TtsBench(this, ::appendLog).run { Log.i(TAG, "BENCH_RESULT ${BenchReport.ttsJson(it)}") }
        }
        val stt = if (what == RUN_TTS) emptyList() else runSafely("stt") {
            SttBench(this, ::appendLog).let { bench ->
                bench.run(bench.loadCases()) { Log.i(TAG, "BENCH_RESULT ${BenchReport.sttJson(it)}") }
            }
        }
        val file = runCatching { BenchReport.write(this, BenchReport.toJson(tts, stt)) }
        appendLog("저장: ${file.getOrNull()?.absolutePath ?: "실패 ${file.exceptionOrNull()}"}")
        Log.i(TAG, "BENCH_DONE ${file.getOrNull()?.absolutePath ?: "-"}")
    }

    /** One bench failing must not hide the other's results or the BENCH_DONE line. */
    private fun <T> runSafely(name: String, bench: () -> List<T>): List<T> = try {
        bench()
    } catch (e: Exception) {
        Log.e(TAG, "BENCH_FAILED $name", e)
        appendLog("$name 측정 실패: $e")
        emptyList()
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        runOnUiThread { logView.append("$line\n") }
    }

    companion object {
        const val EXTRA_RUN = "run"
        const val RUN_TTS = "tts"
        const val RUN_STT = "stt"
        const val RUN_ALL = "all"

        private val running = AtomicBoolean(false)
    }
}
