package dev.voiceagent.llm

import dev.voiceagent.llm.ProxyLlmClient.Companion.readCapped
import dev.voiceagent.llm.ProxyLlmClient.Companion.readLineCapped
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Model settings sent with every request. The proxy used to pick these per task; now the app does. */
data class ClaudeProfile(
    val model: String = "claude-sonnet-5-5",
    val effort: String = "low",
    val maxTokens: Int = 2_048,
)

/**
 * [LlmClient] that calls the Claude Messages API directly, without the proxy. The API key is inside
 * the app, so use a dedicated demo key with a low spend limit and never commit it.
 *
 * Failures use the same codes as [ProxyLlmClient], so VoiceAgent's fallback to the scripted lines
 * works unchanged. Nothing about the question or answer is logged. No retries and no model
 * fallbacks: VoiceAgent gives up after 8 s and must not see a late answer.
 */
class DirectClaudeLlmClient(
    private val apiKey: String,
    private val profile: ClaudeProfile = ClaudeProfile(),
    baseUrl: String = DEFAULT_BASE_URL,
    /** Plain HTTP is only for tests against a local stand-in. */
    allowCleartext: Boolean = false,
    private val connectTimeoutMs: Int = ProxyLlmClient.DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = ProxyLlmClient.DEFAULT_READ_TIMEOUT_MS,
) : LlmClient {
    private val endpoint = URL(baseUrl.trimEnd('/') + MESSAGES_PATH)

    init {
        require(apiKey.isNotBlank()) { "the API key is empty" }
        require(endpoint.protocol == "https" || allowCleartext) { "the API URL must use https: $baseUrl" }
    }

    /** See [ProxyLlmClient]: cancellation must not wait for a blocked chunked read. */
    private val readers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun stream(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        val connection = endpoint.openConnection() as HttpURLConnection
        val reader = readers.launch {
            try {
                exchange(connection, request) { trySend(it) }
            } catch (e: IOException) {
                trySend(LlmEvent.Failure(ProxyLlmClient.NETWORK, e.javaClass.simpleName))
            } catch (e: RuntimeException) {
                trySend(LlmEvent.Failure(ProxyLlmClient.CLIENT_ERROR, e.javaClass.simpleName))
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
        connection.instanceFollowRedirects = false // a redirect must not carry the key elsewhere
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", API_VERSION)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            val error = connection.errorStream?.use { readCapped(it, ProxyLlmClient.MAX_ERROR_BYTES) }.orEmpty()
            emit(httpFailure(status, error))
            return
        }
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val parser = SseParser()
            val turn = Turn()
            while (currentCoroutineContext().isActive) {
                val line = try {
                    reader.readLineCapped(ProxyLlmClient.MAX_LINE_CHARS)
                } catch (e: OversizedLineException) {
                    emit(LlmEvent.Failure(ProxyLlmClient.BAD_EVENT, e.message.orEmpty()))
                    return
                } ?: break
                val event = parser.feed(line)?.let { turn.accept(it) } ?: continue
                emit(event)
                if (event !is LlmEvent.Delta) return
            }
            emit(LlmEvent.Failure(ProxyLlmClient.INCOMPLETE, "stream ended without message_stop"))
        }
    }

    private fun requestBody(request: LlmRequest): String {
        val messages = JSONArray()
        request.messages.forEach { messages.put(JSONObject().put("role", it.role.wireName).put("content", it.content)) }
        return JSONObject()
            .put("model", profile.model)
            .put("max_tokens", profile.maxTokens)
            .put("stream", true)
            .put("system", request.system)
            .put("messages", messages)
            .put("output_config", JSONObject().put("effort", profile.effort))
            .toString()
    }

    /** Collects what `message_start` and `message_delta` say until `message_stop` ends the answer. */
    private class Turn {
        private var model: String? = null
        private var inputTokens: Int? = null
        private var outputTokens: Int? = null
        private var stopReason: String? = null

        fun accept(event: SseEvent): LlmEvent? = try {
            val data = JSONObject(event.data)
            when (event.name) {
                "message_start" -> {
                    val message = data.optJSONObject("message")
                    model = message?.optStringOrNull("model")
                    inputTokens = message?.optJSONObject("usage")?.optIntOrNull("input_tokens")
                    null
                }
                "content_block_delta" -> {
                    val delta = data.optJSONObject("delta")
                    // thinking_delta and input_json_delta are not spoken.
                    if (delta?.optString("type") == "text_delta" && !delta.isNull("text")) {
                        LlmEvent.Delta(delta.getString("text"))
                    } else {
                        null
                    }
                }
                "message_delta" -> {
                    stopReason = data.optJSONObject("delta")?.optStringOrNull("stop_reason") ?: stopReason
                    outputTokens = data.optJSONObject("usage")?.optIntOrNull("output_tokens") ?: outputTokens
                    null
                }
                "message_stop" ->
                    if (stopReason == "refusal") {
                        LlmEvent.Failure("refusal", "the model declined")
                    } else {
                        LlmEvent.Done(stopReason, model, inputTokens, outputTokens)
                    }
                "error" -> {
                    val error = data.optJSONObject("error")
                    LlmEvent.Failure(errorCode(error?.optStringOrNull("type")), error?.optStringOrNull("message").orEmpty())
                }
                else -> null // ping and block start/stop events carry nothing to say.
            }
        } catch (e: JSONException) {
            LlmEvent.Failure(ProxyLlmClient.BAD_EVENT, "malformed ${event.name} event")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val MESSAGES_PATH = "/v1/messages"
        const val API_VERSION = "2023-06-01"

        private fun errorCode(type: String?): String = when (type) {
            "authentication_error", "permission_error" -> "upstream_auth"
            "rate_limit_error" -> "rate_limited"
            "overloaded_error" -> "upstream_unavailable"
            else -> "upstream_error"
        }

        private fun httpFailure(status: Int, body: String): LlmEvent.Failure {
            val code = when {
                status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN -> "upstream_auth"
                status == HTTP_TOO_MANY_REQUESTS -> "rate_limited"
                status == HTTP_OVERLOADED || status >= HttpURLConnection.HTTP_INTERNAL_ERROR -> "upstream_unavailable"
                else -> "upstream_error"
            }
            val message = try {
                JSONObject(body).getJSONObject("error").getString("message")
            } catch (e: JSONException) {
                "HTTP $status"
            }
            return LlmEvent.Failure(code, message)
        }

        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_OVERLOADED = 529

        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

        private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    }
}
