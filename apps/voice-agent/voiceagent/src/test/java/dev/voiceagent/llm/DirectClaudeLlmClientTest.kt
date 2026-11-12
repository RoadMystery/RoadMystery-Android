package dev.voiceagent.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DirectClaudeLlmClientTest {
    private val request = LlmRequest(
        task = LlmTask.INTERROGATION,
        system = "너는 집사다.",
        messages = listOf(ChatMessage(Role.USER, "정전 때 어디 계셨어요?")),
    )
    private var server: TestProxyServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    private fun serve(respond: (ProxyRequest, OutputStream) -> Unit): DirectClaudeLlmClient {
        val started = TestProxyServer(respond).also { server = it }
        return DirectClaudeLlmClient("sk-test-key", baseUrl = started.url, allowCleartext = true)
    }

    private fun OutputStream.start() {
        sseHeaders()
        sse("message_start", """{"type":"message_start","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":2400,"output_tokens":1}}}""")
        sse("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        sse("ping", """{"type":"ping"}""")
    }

    private fun OutputStream.text(text: String) =
        sse("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}""")

    private fun OutputStream.finish(stopReason: String = "end_turn") {
        sse("content_block_stop", """{"type":"content_block_stop","index":0}""")
        sse("message_delta", """{"type":"message_delta","delta":{"stop_reason":"$stopReason"},"usage":{"output_tokens":38}}""")
        sse("message_stop", """{"type":"message_stop"}""")
    }

    @Test
    fun `plain HTTP and an empty key are refused`() {
        assertThrows(IllegalArgumentException::class.java) { DirectClaudeLlmClient("sk", baseUrl = "http://api.example") }
        assertThrows(IllegalArgumentException::class.java) { DirectClaudeLlmClient(" ") }
        DirectClaudeLlmClient("sk")
    }

    @Test(timeout = 10_000)
    fun `streams text as deltas and ends with done`() = runBlocking {
        val client = serve { _, out ->
            out.start()
            out.text("지하 저장고에")
            out.text(" 있었습니다.")
            out.finish()
        }

        val events = client.stream(request).toList()

        assertEquals(
            listOf(
                LlmEvent.Delta("지하 저장고에"),
                LlmEvent.Delta(" 있었습니다."),
                LlmEvent.Done("end_turn", "claude-sonnet-5-5", 2400, 38),
            ),
            events,
        )
    }

    @Test(timeout = 10_000)
    fun `sends the model settings, the key in a header only, and no fallbacks`() = runBlocking {
        val client = serve { _, out ->
            out.start()
            out.finish()
        }

        client.stream(request).toList()

        val sent = server!!.requests.poll(1, TimeUnit.SECONDS)!!
        assertEquals("POST", sent.method)
        assertEquals("/v1/messages", sent.path)
        assertEquals("sk-test-key", sent.headers["x-api-key"])
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
        assertFalse("sk-test-key" in sent.body)
        val body = JSONObject(sent.body)
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals(2048, body.getInt("max_tokens"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("너는 집사다.", body.getString("system"))
        assertEquals("정전 때 어디 계셨어요?", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(body.has("fallbacks"))
        assertFalse(body.has("task"))
    }

    @Test(timeout = 10_000)
    fun `the summary uses the model chosen for summaries`() = runBlocking {
        val client = serve { _, out ->
            out.start()
            out.finish()
        }

        client.stream(request.copy(task = LlmTask.SUMMARY)).toList()

        val body = JSONObject(server!!.requests.poll(1, TimeUnit.SECONDS)!!.body)
        assertEquals("claude-sonnet-5-5", body.getString("model"))
    }

    @Test(timeout = 10_000)
    fun `thinking text is not spoken`() = runBlocking {
        val client = serve { _, out ->
            out.start()
            out.sse("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"속으로 생각"}}""")
            out.text("네.")
            out.finish()
        }

        val texts = client.stream(request).toList().filterIsInstance<LlmEvent.Delta>().map { it.text }

        assertEquals(listOf("네."), texts)
    }

    @Test(timeout = 10_000)
    fun `a refusal is a failure, not an answer`() = runBlocking {
        val client = serve { _, out ->
            out.start()
            out.finish(stopReason = "refusal")
        }

        val last = client.stream(request).toList().last() as LlmEvent.Failure

        assertEquals("refusal", last.code)
    }

    @Test(timeout = 10_000)
    fun `HTTP errors become contract codes`() = runBlocking {
        val cases = mapOf(
            401 to "upstream_auth",
            403 to "upstream_auth",
            429 to "rate_limited",
            529 to "upstream_unavailable",
            500 to "upstream_unavailable",
            400 to "upstream_error",
        )
        for ((status, code) in cases) {
            val client = serve { _, out ->
                out.json(status, "x", """{"type":"error","error":{"type":"e","message":"사유"}}""")
            }
            val failure = client.stream(request).toList().single() as LlmEvent.Failure
            assertEquals("status $status", code, failure.code)
            server?.close()
        }
    }

    @Test(timeout = 10_000)
    fun `an error event in the stream ends it with a code`() = runBlocking {
        val client = serve { _, out ->
            out.start()
            out.text("앞")
            out.sse("error", """{"type":"error","error":{"type":"overloaded_error","message":"x"}}""")
        }

        val events = client.stream(request).toList()

        assertEquals(LlmEvent.Delta("앞"), events.first())
        assertEquals("upstream_unavailable", (events.last() as LlmEvent.Failure).code)
    }

    @Test(timeout = 10_000)
    fun `a stream that stops before message_stop is incomplete`() = runBlocking {
        val client = serve { _, out ->
            out.start()
            out.text("앞")
        }

        val last = client.stream(request).toList().last() as LlmEvent.Failure

        assertEquals(ProxyLlmClient.INCOMPLETE, last.code)
    }

    @Test(timeout = 10_000)
    fun `redirects are not followed`() = runBlocking {
        val client = serve { _, out ->
            out.write("HTTP/1.0 302 Found\r\nLocation: http://127.0.0.1:9/elsewhere\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }

        val failure = client.stream(request).toList().single() as LlmEvent.Failure

        assertEquals("upstream_error", failure.code)
    }
}
