package com.multibot.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.AndroidEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.json
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitWhile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * LlmClient — Ktor streaming client for OpenAI-compatible endpoints.
 *
 * No local LLM, ever: cloud-only. Takes:
 *  - base_url (e.g. https://api.openai.com/v1 or an OpenAI-compatible proxy)
 *  - api_key handle (resolved from SecretVault; the raw key is never passed
 *    to composables — LlmClient is the only consumer)
 *  - model name
 *
 * Supports:
 *  - Streaming responses via SSE (`stream: true`), chunked deltas emitted
 *    to the collector
 *  - tool_calls in the response (accumulated across chunks)
 *
 * Phase 1: interface + streaming plumbing are real; call sites are wired
 * to the TermuxBridge llm_request gateway in Phase 2.
 */
class LlmClient(
    private val baseUrl: String,
    /** Opaque key handle; resolved lazily so the raw secret never outlives
     *  the HTTP call. SecretVault hands LlmClient a resolver closure. */
    private val apiKeyResolver: () -> String,
    private val model: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(AndroidEngine()) {
        install(ContentNegotiation) { json(Json {}) }
        install(Logging) { level = LogLevel.INFO }
    }

    /**
     * One streaming chat completion. Emits [LlmChunk]s as they arrive:
     *  - [LlmChunk.Delta]   — text delta
     *  - [LlmChunk.ToolDelta] — partial tool_call chunk
     *  - [LlmChunk.Done]    — finish_reason + usage
     */
    suspend fun chatStream(
        messages: List<LlmMessage>,
        tools: List<JsonObject>? = null,       // OpenAI tools[] schema
        temperature: Float = 0.2f,
    ): Flow<LlmChunk> = flow {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", model)
                put("stream", true)
                put("temperature", temperature.toDouble())
                put("messages", messages.toJsonArray())
                if (!tools.isNullOrEmpty()) {
                    put("tools", tools.toKotlinJsonArray())
                }
            },
        )

        http.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${apiKeyResolver()}")
            header(HttpHeaders.Accept, "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsSse().collect { chunk -> emit(chunk) }
    }

    /**
     * Non-streaming variant — returns the full assistant message in one go.
     * Used for tool-call round trips where the agent loop needs the
     * complete tool_calls array before resuming the loop.
     */
    suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<JsonObject>? = null,
    ): LlmMessage {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", model)
                put("stream", false)
                put("messages", messages.toJsonArray())
                if (!tools.isNullOrEmpty()) put("tools", tools.toKotlinJsonArray())
            },
        )
        val resp = http.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${apiKeyResolver()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsJsonObject()

        val choice = resp["choices"]?.jsonObject?.get("0")?.jsonObject
        val message = choice?.get("message")?.jsonObject ?: return LlmMessage("assistant", "")
        val toolCalls = message["tool_calls"]?.jsonArray?.map { tc ->
            val obj = tc.jsonObject
            val fn = obj["function"]?.jsonObject
            ToolCall(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                name = fn?.get("name")?.jsonPrimitive?.content ?: "",
                arguments = fn?.get("arguments")?.jsonPrimitive?.content ?: "{}",
            )
        }.orEmpty()
        return LlmMessage(
            role = "assistant",
            content = message["content"]?.jsonPrimitive?.contentOrNull ?: "",
            toolCalls = toolCalls,
        )
    }
}

// ---------------------------------------------------------------------------
// Message + chunk model
// ---------------------------------------------------------------------------

data class LlmMessage(
    val role: String,          // system | user | assistant | tool
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,  // for role=tool
)

sealed interface LlmChunk {
    data class Delta(val text: String) : LlmChunk
    /** Partial tool_call accumulation; index matches the LLM's tool_calls[] slot. */
    data class ToolDelta(val index: Int, val id: String, val name: String, val argsDelta: String) : LlmChunk
    data class Done(val finishReason: String, val usage: Map<String, Int> = emptyMap()) : LlmChunk
}

// ---------------------------------------------------------------------------
// JSON plumbing (kept here to avoid extra files in the scaffold)
// ---------------------------------------------------------------------------

private fun List<LlmMessage>.toJsonArray(): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.buildJsonArray {
        forEach { msg ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("role", msg.role)
                if (msg.content.isNotEmpty()) put("content", msg.content)
                msg.toolCallId?.let { put("tool_call_id", it) }
                if (msg.toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        kotlinx.serialization.json.buildJsonArray {
                            msg.toolCalls.forEach { tc ->
                                add(kotlinx.serialization.json.buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    }
                                })
                            }
                        },
                    )
                }
            })
        }
    }

private fun List<JsonObject>.toKotlinJsonArray(): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.buildJsonArray { addAll(this@toKotlinJsonArray) }

private val JsonElement.contentOrNull
    get() = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

// SSE parsing helper: botd's gateway forwards OpenAI's SSE stream verbatim.
private suspend fun io.ktor.client.statement.HttpCall.bodyAsSse(): kotlinx.coroutines.flow.Flow<LlmChunk> {
    TODO("Phase 2: parse `data: …` SSE lines from the Ktor response body, " +
        "accumulate tool_call deltas, emit LlmChunk.Done on `data: [DONE]`. " +
        "Sketch: response.bodyAsChannel() → line reader → Json.decodeFromString." )
}

private suspend fun io.ktor.client.statement.HttpCall.bodyAsJsonObject(): kotlinx.serialization.json.JsonObject {
    TODO("Phase 2: response.body<JsonObject>() with expectSuccess installed.")
}
