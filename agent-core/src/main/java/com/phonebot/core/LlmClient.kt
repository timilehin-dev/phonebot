package com.phonebot.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.AndroidEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
 *  - OpenRouter attribution headers (HTTP-Referer / X-Title)
 *
 * This class is the app-side twin of botd's `botd.llm.OpenAICompatClient`:
 * both POST to `$base_url/chat/completions` and normalize the same
 * OpenAI-compatible shape. The [com.phonebot.termux.TermuxBridge] LLM
 * relay forwards botd's `llm_request` frames through this class.
 */
class LlmClient(
    private val baseUrl: String,
    /** Opaque key handle; resolved lazily so the raw secret never outlives
     *  the HTTP call. SecretVault hands LlmClient a resolver closure. */
    private val apiKeyResolver: () -> String,
    private val model: String,
    /** Extra static headers (OpenRouter attribution, etc.). */
    private val extraHeaders: Map<String, String> = emptyMap(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(AndroidEngine()) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(expectSuccess)
        install(Logging) {
            level = LogLevel.INFO
            // The API key rides in the Authorization header; keep it out of logs.
            sanitizeRequest { request ->
                request.filterHeaders { name -> name.lowercase() != "authorization" }
            }
        }
    }

    private fun baseHeaders(): List<Pair<String, String>> = buildList {
        add(HttpHeaders.Authorization to "Bearer " + apiKeyResolver())
        extraHeaders.forEach { (k, v) -> add(k to v) }
    }

    /**
     * One streaming chat completion. Emits [LlmChunk]s as they arrive:
     *  - [LlmChunk.Delta]     — text delta
     *  - [LlmChunk.ToolDelta] — accumulated tool_call chunk
     *  - [LlmChunk.Done]     — finish_reason + usage
     */
    suspend fun chatStream(
        messages: List<LlmMessage>,
        tools: List<JsonObject>? = null,
        temperature: Double? = null,
    ): Flow<LlmChunk> = flow {
        val resp = http.post("$baseUrl/chat/completions") {
            headers {
                baseHeaders().forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.Accept, "text/event-stream")
            }
            contentType(ContentType.Application.Json)
            setBody(chatBody(stream = true, temperature = temperature))
        }.bodyAsSse()
        resp.collect { emit(it) }
    }

    /**
     * Non-streaming variant — returns the full assistant reply in one go.
     * Used for tool-call round trips where the agent loop needs the
     * complete tool_calls array before resuming the loop.
     */
    suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<JsonObject>? = null,
        temperature: Double? = null,
    ): LlmResult {
        val resp = http.post("$baseUrl/chat/completions") {
            headers {
                baseHeaders().forEach { (k, v) -> append(k, v) }
            }
            contentType(ContentType.Application.Json)
            setBody(chatBody(stream = false, temperature = temperature))
        }.bodyAsJsonObject()

        val choice = resp["choices"]?.jsonObject?.get("0")?.jsonObject
            ?: return LlmResult(content = "", finishReason = "no_choice")
        val message = choice["message"]?.jsonObject ?: return LlmResult(content = "")
        val toolCalls = message["tool_calls"]?.jsonArray?.map { tc ->
            val obj = tc.jsonObject
            val fn = obj["function"]?.jsonObject
            ToolCall(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                name = fn?.get("name")?.jsonPrimitive?.content ?: "",
                arguments = fn?.get("arguments")?.jsonPrimitive?.content ?: "{}",
            )
        }.orEmpty()
        val usage = resp["usage"]?.jsonObject?.mapValues { (_, v) ->
            v.jsonPrimitive.content.toIntOrNull() ?: 0
        } ?: emptyMap()
        return LlmResult(
            content = message["content"]?.jsonPrimitive?.contentOrNull ?: "",
            toolCalls = toolCalls,
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            usage = usage,
        )
    }

    private fun chatBody(stream: Boolean, temperature: Double?): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", stream)
        if (temperature != null) put("temperature", temperature)
        put("messages", LlmMessageCodec.toJsonArray(messages))
        if (!tools.isNullOrEmpty()) put("tools", JsonArray(tools))
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

/** Full (non-streaming) assistant reply. */
data class LlmResult(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null,
    val usage: Map<String, Int> = emptyMap(),
)

sealed interface LlmChunk {
    data class Delta(val text: String) : LlmChunk
    /** Accumulated tool_call slot; index matches the LLM's tool_calls[] slot. */
    data class ToolDelta(val index: Int, val id: String, val name: String, val argsDelta: String) : LlmChunk
    data class Done(val finishReason: String, val usage: Map<String, Int> = emptyMap()) : LlmChunk
}

/** Structured error carrying the HTTP status so callers can branch on 401/429/… */
class LlmHttpException(val status: Int, message: String, cause: Exception? = null) :
    Exception(message, cause)

// ---------------------------------------------------------------------------
// Bridge-side wire helpers (public so termux-bridge can use them)
// ---------------------------------------------------------------------------

/**
 * Encode/decode helpers so the LLM relay can turn botd `llm_request` frames
 * into [LlmMessage] lists and [LlmChunk] objects back, without termux-bridge
 * depending on any kotlinx-serialization models beyond these.
 */
object LlmMessageCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parse `{"messages":[…],"tools":[…]}` or a bare messages array. */
    fun parseMessages(messagesJson: String): List<LlmMessage> {
        val root = json.parseToJsonElement(messagesJson)
        val arr = when (root) {
            is JsonObject -> root["messages"]?.jsonArray ?: JsonArray(emptyList())
            is JsonArray -> root
            else -> JsonArray(emptyList())
        }
        return arr.map { el ->
            val obj = el.jsonObject
            LlmMessage(
                role = obj["role"]?.jsonPrimitive?.content ?: "user",
                content = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                toolCalls = obj["tool_calls"]?.jsonArray?.map { tc ->
                    val o = tc.jsonObject
                    val fn = o["function"]?.jsonObject
                    ToolCall(
                        id = o["id"]?.jsonPrimitive?.content ?: "",
                        name = fn?.get("name")?.jsonPrimitive?.content ?: "",
                        arguments = fn?.get("arguments")?.jsonPrimitive?.content ?: "{}",
                    )
                }.orEmpty(),
                toolCallId = obj["tool_call_id"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /** Encode one [LlmChunk] as a `{"type": …}` object for socket frames. */
    fun encodeChunk(chunk: LlmChunk): JsonObject = when (chunk) {
        is LlmChunk.Delta -> buildJsonObject {
            put("type", "delta")
            put("text", chunk.text)
        }
        is LlmChunk.ToolDelta -> buildJsonObject {
            put("type", "tool_delta")
            put("index", chunk.index)
            put("id", chunk.id)
            put("name", chunk.name)
            put("args_delta", chunk.argsDelta)
        }
        is LlmChunk.Done -> buildJsonObject {
            put("type", "done")
            put("finish_reason", chunk.finishReason)
            put("usage", buildJsonObject {
                chunk.usage.forEach { (k, v) -> put(k, v) }
            })
        }
    }

    fun toJsonArray(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        messages.forEach { msg ->
            add(buildJsonObject {
                put("role", msg.role)
                if (msg.content.isNotEmpty()) put("content", msg.content)
                msg.toolCallId?.let { put("tool_call_id", it) }
                if (msg.toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        buildJsonArray {
                            msg.toolCalls.forEach { tc ->
                                add(buildJsonObject {
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
}

private val JsonElement.contentOrNull
    get() = (this as? JsonPrimitive)?.contentOrNull

// ---------------------------------------------------------------------------
// SSE / JSON body decoding
// ---------------------------------------------------------------------------

/**
 * Parse an OpenAI-compatible SSE body (`data: {…}\n\n` lines) into a
 * [Flow] of [LlmChunk]. For our relay use case the response is a single
 * POST whose body we read whole, so a line reader over `body<String>()`
 * is sufficient — no need to stream byte-by-byte from the socket.
 */
private suspend fun HttpResponse.bodyAsSse(): Flow<LlmChunk> {
    val text = body<String>()
    val json = Json { ignoreUnknownKeys = true }
    val chunks = mutableListOf<LlmChunk>()
    val toolParts = LinkedHashMap<Int, ToolCall>()

    for (raw in text.split("\n", "\r\n", "\r")) {
        val line = raw.trim()
        if (line.isEmpty() || !line.startsWith("data:")) continue
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") continue
        val obj = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: continue
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject } ?: continue
        val delta = choice["delta"]?.jsonObject ?: continue

        val textDelta = delta["content"]?.jsonPrimitive?.contentOrNull
        if (!textDelta.isNullOrEmpty()) chunks += LlmChunk.Delta(textDelta)

        (delta["tool_calls"] as? JsonArray)?.forEach { tcEl ->
            val tc = (tcEl as? JsonObject) ?: return@forEach
            val idx = tc["index"]?.jsonPrimitive?.content.toIntOrNull() ?: 0
            val id = tc["id"]?.jsonPrimitive?.content
            val fn = tc["function"]?.jsonObject
            val nameDelta = fn?.get("name")?.jsonPrimitive?.contentOrNull
            val argsDelta = fn?.get("arguments")?.jsonPrimitive?.contentOrNull
            if (!nameDelta.isNullOrEmpty() || !argsDelta.isNullOrEmpty()) {
                val prev = toolParts[idx]
                toolParts[idx] = ToolCall(
                    id = id ?: prev?.id ?: "",
                    name = (prev?.name ?: "") + (nameDelta ?: ""),
                    arguments = (prev?.arguments ?: "") + (argsDelta ?: ""),
                )
            }
        }

        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val usage = obj["usage"]?.jsonObject?.mapValues { (_, v) ->
            v.jsonPrimitive.content.toIntOrNull() ?: 0
        }
        if (finish != null || usage != null) {
            chunks += LlmChunk.Done(finishReason = finish ?: "stop", usage = usage ?: emptyMap())
        }
    }

    toolParts.forEach { (idx, tc) ->
        chunks += LlmChunk.ToolDelta(idx, tc.id, tc.name, tc.arguments)
    }
    return flow { chunks.forEach { emit(it) } }
}

private suspend fun HttpResponse.bodyAsJsonObject(): JsonObject {
    val text = body<String>()
    return (Json { ignoreUnknownKeys = true }.parseToJsonElement(text) as? JsonObject)
        ?: throw IllegalStateException("LLM response is not a JSON object: ${text.take(200)}")
}
