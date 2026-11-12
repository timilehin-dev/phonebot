package com.phonebot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
 * The fetch over the bridge is not wired yet (later phase); parsing and
 * allowlist filtering are implemented and unit-tested directly.
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
        val root = Json.parseToJsonElement(json)
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["tools"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        tools = array.mapNotNull { element ->
            (element as? JsonObject)?.let { toolDescriptorFromJsonElement(it) }
        }
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
                        put("parameters", tool.inputSchema)
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

internal fun toolDescriptorFromJsonElement(el: JsonObject): ToolDescriptor = ToolDescriptor(
    id = el["id"]?.jsonPrimitive?.content ?: "",
    kind = kindFrom(el["kind"]?.jsonPrimitive?.content),
    provider = el["provider"]?.jsonPrimitive?.content ?: "botd",
    name = el["name"]?.jsonPrimitive?.content ?: el["id"]?.jsonPrimitive?.content ?: "",
    description = el["description"]?.jsonPrimitive?.content ?: "",
    inputSchema = el["input_schema"]?.jsonObject ?: JsonObject(emptyMap()),
    risk = riskFrom(el["risk"]?.jsonPrimitive?.content),
    riskRules = el["risk_rules"] as? JsonArray ?: JsonArray(emptyList()),
    backends = (el["backends"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
    phase = el["phase"]?.jsonPrimitive?.intOrNull ?: 1,
    defaultAllowed = el["default_allowed"]?.jsonPrimitive?.content?.toBoolean() ?: true,
)

/** Map the contract's risk vocabulary onto the typed enum (unknown → read). */
internal fun riskFrom(value: String?): RiskTier = when (value) {
    "write" -> RiskTier.Write
    "costly" -> RiskTier.Costly
    "destructive" -> RiskTier.Destructive
    "privileged" -> RiskTier.Privileged
    else -> RiskTier.Read
}

/** Map the contract's kind vocabulary onto the typed enum (unknown → builtin). */
internal fun kindFrom(value: String?): ToolKind = when (value) {
    "custom" -> ToolKind.Custom
    "mcp" -> ToolKind.Mcp
    else -> ToolKind.Builtin
}
