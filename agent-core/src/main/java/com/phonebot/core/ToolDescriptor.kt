package com.phonebot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * ToolDescriptor — mirrors `contracts/tool-descriptor.schema.json` (the
 * schema of record), the same descriptor botd serves for builtin, custom
 * and MCP-provided tools.
 *
 * botd owns the registry; the app mirrors it over IPC, filters by each bot's
 * allowlist, and renders the result in Settings → Capabilities → Tools.
 * `inputSchema` is passed through verbatim into the OpenAI
 * `tools[].function.parameters` field, so it must stay a JSON object.
 */
@Serializable
data class ToolDescriptor(
    /** Globally unique tool id, e.g. "shell.run". */
    val id: String,
    /** builtin = shipped with botd; custom = bot-local; mcp = MCP server. */
    val kind: ToolKind = ToolKind.Builtin,
    /** Who serves the tool: core | botd | bot:<id> | mcp:<name>. */
    val provider: String = "botd",
    /** Model-facing name (typically identical to id). */
    val name: String = "",
    /** Model-facing description: what it does, when to use it, what it returns. */
    val description: String = "",
    /** JSON Schema for the tool arguments; enforced by botd at invoke time. */
    @SerialName("input_schema") val inputSchema: JsonObject = JsonObject(emptyMap()),
    /** Base risk tier (contract vocabulary: read/write/costly/destructive/privileged). */
    val risk: RiskTier = RiskTier.Read,
    /** Optional per-call escalation rules; botd evaluates them before execution. */
    @SerialName("risk_rules") val riskRules: JsonArray = JsonArray(emptyList()),
    /** Ordered execution backends; botd resolves the first available. */
    val backends: List<String> = listOf("termux"),
    /** Build phase in which the tool ships (1–4). */
    val phase: Int = 1,
    /** Whether the tool is enabled before a human opts out. */
    @SerialName("default_allowed") val defaultAllowed: Boolean = true,
)

/** Tool provenance, matching the contract's `kind` enum. */
@Serializable
enum class ToolKind {
    @SerialName("builtin") Builtin,
    @SerialName("custom") Custom,
    @SerialName("mcp") Mcp,
}

/**
 * Risk tier — the contract's five tiers. read+write are silent (trace chip);
 * destructive+privileged always need approval; costly is spend-cap gated.
 */
@Serializable
enum class RiskTier {
    @SerialName("read") Read,
    @SerialName("write") Write,
    @SerialName("costly") Costly,
    @SerialName("destructive") Destructive,
    @SerialName("privileged") Privileged,
}
