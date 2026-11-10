package com.phonebot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * ToolRegistry — in-app mirror of botd's tool registry.
 *
 * Responsibilities:
 *  1. Load the full registry from botd over IPC (registry.json shipped by
 *     botd, fetched lazily at app startup / on-demand refresh).
 *  2. Filter by per-bot allowlist (the app's cap table decides which tools
 *     each bot is permitted to expose to its LLM).
 *  3. Generate the OpenAI tools[] array (function-calling schema) for the
 *     LLM prompt, keyed by bot.
 *
 * Phase 1: the registry source is a stub (empty / placeholder); Phase 2
 * wires the fetch to TermuxBridge.sendRequest("registry.get").
 */
class ToolRegistry(
    /** Per-bot allowlists: botId → tool ids. A bot with no entry gets
     *  the [defaultAllowlist]. */
    private val allowlists: Map<String, List<String>> = emptyMap(),
    private val defaultAllowlist: List<String> = emptyList(),
) {

    /** The full registry, last loaded from botd. Empty until the first sync. */
    @Volatile var tools: List<ToolDescriptor> = emptyList()
        private set

    /**
     * Parse botd's registry payload (a JSON array of tool objects matching
     * the contracts/registry.json schema) into [ToolDescriptor]s.
     */
    fun loadFromRegistryJson(json: String) {
        val arr = Json.parseToJsonElement(json).jsonObject["tools"]?.jsonObject?.get("items")
        val parsed: List<ToolDescriptor> = when (val raw = Json.parseToJsonElement(json)) {
            is JsonArray -> raw.map {
                ToolDescriptor.fromJsonElement(it.jsonObject)
            }
            is JsonObject -> (raw["tools"] as? JsonArray)?.map { ToolDescriptor.fromJsonElement(it.jsonObject) }
                ?: emptyList()
            else -> emptyList()
        }
        tools = parsed
    }

    /** Tools permitted for [botId] — intersection of the registry and the allowlist. */
    fun toolsForBot(botId: String): List<ToolDescriptor> {
        val allowed = allowlists[botId] ?: defaultAllowlist
        return if (allowed.isEmpty()) tools else tools.filter { it.id in allowed }
    }

    /**
     * Generate the OpenAI `tools` array (function-calling schema) for the
     * LLM prompt of [botId]:
     *
     * ```
     * [ { "type": "function",
     *     "function": { "name": …, "description": …, "parameters": { …jsonschema… } } }, … ]
     * ```
     */
    fun openaiToolSchemaForBot(botId: String): String {
        val arr = buildJsonArray {
            toolsForBot(botId).forEach { tool ->
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tool.id)
                        put("description", tool.description)
                        put("parameters", tool.parameters.toJsonElement())
                    })
                })
            }
        }
        return Json { prettyPrint = false }.encodeToString(JsonArray.serializer(), arr)
    }
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

internal fun ToolDescriptor.fromJsonElement(el: JsonObject): ToolDescriptor = ToolDescriptor(
    id = el["id"]?.jsonPrimitive?.content ?: "",
    name = el["name"]?.jsonPrimitive?.content ?: el["id"]?.jsonPrimitive?.content ?: "",
    description = el["description"]?.jsonPrimitive?.content ?: "",
    parameters = JsonObjectLike(el["parameters"]?.jsonObject?.mapValues {
        it.value.toString()
    } ?: emptyMap()),
    risk = runCatching { RiskTier.valueOf(el["risk"]?.jsonPrimitive?.content ?: "info") }.getOrDefault(RiskTier.Info),
    backends = (el["backends"] as? JsonArray)?.mapNotNull { it?.jsonPrimitive?.content } ?: emptyList(),
    phase = runCatching { Phase.valueOf(el["phase"]?.jsonPrimitive?.content ?: "execute") }.getOrDefault(Phase.Execute),
    idempotent = el["idempotent"]?.jsonPrimitive?.content?.toBoolean() ?: true,
)

internal fun JsonObjectLike.toJsonElement(): JsonObject = buildJsonObject {
    fields.forEach { (k, v) -> put(k, v.toString()) }
}
