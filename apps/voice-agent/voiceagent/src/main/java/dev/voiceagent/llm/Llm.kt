package dev.voiceagent.llm

import kotlinx.coroutines.flow.Flow

/** Limits of docs/voice-agent/llm-proxy-contract.md; the proxy answers 400 beyond them. */
object ProxyContract {
    const val RESPOND_PATH = "/v1/voice-agent/respond"
    const val MAX_SYSTEM_CHARS = 16_000
    const val MAX_MESSAGE_CHARS = 1_000
    const val MAX_MESSAGES = 21
}

/** The proxy picks the model and settings per task; the app never names a model. */
enum class LlmTask(val wireName: String) {
    INTERROGATION("interrogation"),
    SUMMARY("summary"),
}

enum class Role(val wireName: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatMessage(val role: Role, val content: String)

/** [messages] alternate starting with the player and end with the player's new question. */
data class LlmRequest(
    val task: LlmTask,
    val system: String,
    val messages: List<ChatMessage>,
    val requestId: String = "",
)

sealed interface LlmEvent {
    data class Delta(val text: String) : LlmEvent

    data class Done(val stopReason: String?, val model: String?, val inputTokens: Int?, val outputTokens: Int?) : LlmEvent

    /**
     * [code] is a contract error code (refusal, rate_limited, upstream_error, upstream_unavailable,
     * upstream_auth) or a client-side one (unauthorized, invalid_request, http_<status>, network,
     * incomplete, bad_event, client_error).
     */
    data class Failure(val code: String, val message: String) : LlmEvent
}

/** Streams one answer. The flow ends after Done or Failure; cancelling collection drops the request. */
fun interface LlmClient {
    fun stream(request: LlmRequest): Flow<LlmEvent>
}
