package dev.voiceagent.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [LlmClient] for the VoiceAgent LLM proxy (docs/voice-agent/llm-proxy-contract.md): one POST,
 * answered with server-sent events. The API key stays on the server; the app holds only [token].
 * Nothing about the question or answer is logged.
 */
class ProxyLlmClient(
    baseUrl: String,
    private val token: String,
    /** Plain HTTP is only for the development proxy on the PC; the token travels in a header. */
    allowCleartext: Boolean = false,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : LlmClient {
    private val endpoint = URL(baseUrl.trimEnd('/') + ProxyContract.RESPOND_PATH)

    init {
        require(endpoint.protocol == "https" || allowCleartext) { "the proxy URL must use https: $baseUrl" }
    }

    /**
     * Readers run outside the flow: on the JVM a blocked chunked read holds a lock that disconnect()
     * needs, so cancellation must not wait for the reader to finish.
     */
    private val readers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun stream(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        val connection = endpoint.openConnection() as HttpURLConnection
        val reader = readers.launch {
            try {
                exchange(connection, request) { trySend(it) }
            } catch (e: OversizedLineException) {
                trySend(LlmEvent.Failure(BAD_EVENT, e.message.orEmpty()))
            } catch (e: IOException) {
                // Also the expected end of a read cut short by disconnect(); then nobody is listening.
                trySend(LlmEvent.Failure(NETWORK, e.javaClass.simpleName))
            } catch (e: RuntimeException) {
                trySend(LlmEvent.Failure(CLIENT_ERROR, e.javaClass.simpleName))
            }
            close()
        }
        awaitClose {
            reader.cancel()
            readers.launch { connection.disconnect() }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun exchange(connection: HttpURLConnection, request: LlmRequest, emit: (LlmEvent) -> Unit) {
        val body = requestBody(request).toByteArray(Charsets.UTF_8)
        connection.requestMethod = "POST"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false // a redirect must not carry the token elsewhere
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            val error = connection.errorStream?.use { readCapped(it, MAX_ERROR_BYTES) }.orEmpty()
            emit(httpFailure(status, error))
            return
        }
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val parser = SseParser()
            while (currentCoroutineContext().isActive) {
                val line = reader.readLineCapped(MAX_LINE_CHARS) ?: break
                val event = parser.feed(line)?.let(::toLlmEvent) ?: continue
                emit(event)
                if (event !is LlmEvent.Delta) return
            }
            emit(LlmEvent.Failure(INCOMPLETE, "stream ended without done"))
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000

        /** Above the proxy's 30 s upstream timeout; VoiceAgent's own 8 s first-text limit comes first. */
        const val DEFAULT_READ_TIMEOUT_MS = 35_000

        const val NETWORK = "network"
        const val INCOMPLETE = "incomplete"
        const val BAD_EVENT = "bad_event"
        const val CLIENT_ERROR = "client_error"

        /** A broken or hostile server cannot make the app buffer more than this. */
        internal const val MAX_ERROR_BYTES = 4_096
        internal const val MAX_LINE_CHARS = 64_000

        internal fun readCapped(stream: InputStream, limit: Int): String {
            val buffer = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val count = stream.read(buffer, total, limit - total)
                if (count < 0) break
                total += count
            }
            return String(buffer, 0, total, Charsets.UTF_8)
        }

        /** BufferedReader.readLine (\n, \r or \r\n) that gives up on lines longer than [max]. */
        internal fun BufferedReader.readLineCapped(max: Int): String? {
            val line = StringBuilder()
            while (true) {
                val char = read()
                when {
                    char < 0 -> return if (line.isEmpty()) null else line.toString()
                    char == '\n'.code -> return line.toString()
                    char == '\r'.code -> {
                        mark(1)
                        if (read() != '\n'.code) reset()
                        return line.toString()
                    }
                    line.length >= max -> throw OversizedLineException(max)
                    else -> line.append(char.toChar())
                }
            }
        }

        private fun requestBody(request: LlmRequest): String {
            val messages = JSONArray()
            request.messages.forEach { messages.put(JSONObject().put("role", it.role.wireName).put("content", it.content)) }
            val body = JSONObject()
                .put("task", request.task.wireName)
                .put("system", request.system)
                .put("messages", messages)
            if (request.requestId.isNotEmpty()) body.put("requestId", request.requestId)
            return body.toString()
        }

        private fun httpFailure(status: Int, body: String): LlmEvent.Failure {
            val code = when (status) {
                HttpURLConnection.HTTP_BAD_REQUEST -> "invalid_request"
                HttpURLConnection.HTTP_UNAUTHORIZED -> "unauthorized"
                HTTP_TOO_MANY_REQUESTS -> "rate_limited"
                else -> "http_$status"
            }
            val message = try {
                JSONObject(body).getJSONObject("error").getString("message")
            } catch (e: JSONException) {
                "HTTP $status"
            }
            return LlmEvent.Failure(code, message)
        }

        private fun toLlmEvent(event: SseEvent): LlmEvent? = try {
            val data = JSONObject(event.data)
            when (event.name) {
                // Android's getString turns JSON null into the text "null", which would be spoken.
                "delta" -> if (data.isNull("text")) LlmEvent.Failure(BAD_EVENT, "delta without text") else LlmEvent.Delta(data.getString("text"))
                "done" -> LlmEvent.Done(
                    stopReason = data.stringOrNull("stopReason"),
                    model = data.stringOrNull("model"),
                    inputTokens = data.intOrNull("inputTokens"),
                    outputTokens = data.intOrNull("outputTokens"),
                )
                "error" -> LlmEvent.Failure(data.stringOrNull("code") ?: "upstream_error", data.stringOrNull("message").orEmpty())
                else -> null // Unknown events are skipped, as SSE clients do.
            }
        } catch (e: JSONException) {
            LlmEvent.Failure(BAD_EVENT, "malformed ${event.name} event")
        }

        private const val HTTP_TOO_MANY_REQUESTS = 429

        private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

        private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    }
}

internal class OversizedLineException(max: Int) : IOException("event line longer than $max characters")

internal data class SseEvent(val name: String, val data: String)

/** Line-by-line server-sent events parser (WHATWG): `event` and `data` fields, a blank line dispatches. */
internal class SseParser {
    private var name = ""
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line without its terminator; returns the event that [line] completes, if any. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> name = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(name.ifEmpty { "message" }, data.toString()) else null
        name = ""
        data.setLength(0)
        hasData = false
        return event
    }
}
