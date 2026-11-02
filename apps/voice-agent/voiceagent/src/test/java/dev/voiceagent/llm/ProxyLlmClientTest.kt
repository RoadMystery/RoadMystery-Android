package dev.voiceagent.llm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProxyLlmClientTest {
    private val request = LlmRequest(
        task = LlmTask.INTERROGATION,
        system = "너는 집사다.\n짧게 답하라.",
        messages = listOf(
            ChatMessage(Role.USER, "정전 때 어디 계셨어요?"),
            ChatMessage(Role.ASSISTANT, "지하 저장고에 있었습니다."),
            ChatMessage(Role.USER, "밖에 나간 적은 없어요?"),
        ),
        requestId = "test-1",
    )
    private val release = CountDownLatch(1)
    private var server: TestProxyServer? = null

    @After
    fun tearDown() {
        release.countDown()
        server?.close()
    }

    private fun serve(respond: (ProxyRequest, OutputStream) -> Unit): ProxyLlmClient {
        val started = TestProxyServer(respond).also { server = it }
        return ProxyLlmClient(started.url, "dev-token", allowCleartext = true)
    }

    @Test
    fun `plain HTTP is refused unless the development proxy is allowed`() {
        assertThrows(IllegalArgumentException::class.java) { ProxyLlmClient("http://10.0.2.2:8787", "dev-token") }
        ProxyLlmClient("https://proxy.example", "dev-token")
        ProxyLlmClient("http://10.0.2.2:8787", "dev-token", allowCleartext = true)
    }

    @Test(timeout = 10_000)
    fun `redirects are not followed and a null delta is a bad event`() = runBlocking {
        val moved = serve { _, out ->
            out.write("HTTP/1.0 302 Found\r\nLocation: http://127.0.0.1:9/elsewhere\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        assertEquals("http_302", (moved.stream(request).toList().single() as LlmEvent.Failure).code)
        server?.close()

        val nullText = serve { _, out ->
            out.sseHeaders()
            out.sse("delta", """{"text": null}""")
        }
        assertEquals(ProxyLlmClient.BAD_EVENT, (nullText.stream(request).toList().first() as LlmEvent.Failure).code)
    }

    @Test(timeout = 10_000)
    fun `streams deltas and done, sending the contract's request`() = runBlocking {
        val client = serve { _, out ->
            out.sseHeaders()
            out.sse("delta", """{"text": "지하 저장고에"}""")
            out.sse("delta", """{"text": " 있었습니다."}""")
            out.sse("done", """{"stopReason": "end_turn", "model": "claude-haiku-5-5", "inputTokens": 2400, "outputTokens": 38}""")
        }

        val events = client.stream(request).toList()

        assertEquals(
            listOf(
                LlmEvent.Delta("지하 저장고에"),
                LlmEvent.Delta(" 있었습니다."),
                LlmEvent.Done("end_turn", "claude-haiku-5-5", 2400, 38),
            ),
            events,
        )
        val sent = server!!.requests.single()
        assertEquals("POST", sent.method)
        assertEquals(ProxyContract.RESPOND_PATH, sent.path)
        assertEquals("Bearer dev-token", sent.headers["authorization"])
        assertEquals("text/event-stream", sent.headers["accept"])
        val body = JSONObject(sent.body)
        assertEquals("interrogation", body.getString("task"))
        assertEquals(request.system, body.getString("system"))
        assertEquals("test-1", body.getString("requestId"))
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("밖에 나간 적은 없어요?", messages.getJSONObject(2).getString("content"))
    }

    @Test(timeout = 10_000)
    fun `an error event ends the stream with the server's code`() = runBlocking {
        val client = serve { _, out ->
            out.sseHeaders()
            out.sse("delta", """{"text": "음"}""")
            out.sse("error", """{"code": "refusal", "message": "the model declined"}""")
            out.sse("delta", """{"text": "never read"}""")
        }

        val events = client.stream(request).toList()

        assertEquals(listOf(LlmEvent.Delta("음"), LlmEvent.Failure("refusal", "the model declined")), events)
    }

    @Test(timeout = 10_000)
    fun `HTTP errors become failure codes`() = runBlocking {
        val cases = mapOf(
            401 to "unauthorized",
            429 to "rate_limited",
            400 to "invalid_request",
            503 to "http_503",
        )
        for ((status, code) in cases) {
            val client = serve { _, out -> out.json(status, "Error", """{"error": {"code": "x", "message": "status $status"}}""") }
            val failure = client.stream(request).toList().single() as LlmEvent.Failure
            assertEquals(code, failure.code)
            // The JDK drops a 401 body in streaming mode, so only the other messages are checked.
            if (status != 401) assertEquals("status $status", failure.message)
            server?.close()
        }
    }

    @Test(timeout = 10_000)
    fun `a stream that ends without done is incomplete and bad data is a bad event`() = runBlocking {
        val cut = serve { _, out ->
            out.sseHeaders()
            out.sse("delta", """{"text": "지하"}""")
        }
        assertEquals(LlmEvent.Failure(ProxyLlmClient.INCOMPLETE, "stream ended without done"), cut.stream(request).toList().last())
        server?.close()

        val garbled = serve { _, out ->
            out.sseHeaders()
            out.sse("delta", "not json")
        }
        assertEquals(LlmEvent.Failure(ProxyLlmClient.BAD_EVENT, "malformed delta event"), garbled.stream(request).toList().single())
    }

    @Test(timeout = 10_000)
    fun `an unreachable proxy is a network failure`() = runBlocking {
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

        val events = ProxyLlmClient("http://127.0.0.1:$port", "dev-token", allowCleartext = true).stream(request).toList()

        assertEquals(ProxyLlmClient.NETWORK, (events.single() as LlmEvent.Failure).code)
    }

    @Test(timeout = 10_000)
    fun `cancelling collection returns at once even while the server is silent`() = runBlocking {
        val client = serve { _, out ->
            out.sseHeaders()
            out.sse("delta", """{"text": "첫 조각"}""")
            release.await(8, TimeUnit.SECONDS) // a stalled upstream
        }
        val started = System.nanoTime()

        val first = client.stream(request).first()

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(LlmEvent.Delta("첫 조각"), first)
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
    }

    @Test
    fun `SSE parser follows the spec for comments, missing spaces and multi-line data`() {
        val parser = SseParser()
        val lines = listOf(": keep-alive", "event:delta", "data: {\"text\":", "data:  \"a\"}", "", "", "data: plain", "")
        val events = lines.mapNotNull(parser::feed)
        assertEquals(listOf(SseEvent("delta", "{\"text\":\n \"a\"}"), SseEvent("message", "plain")), events)
    }
}
