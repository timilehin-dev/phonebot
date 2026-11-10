package com.phonebot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ToolDescriptor — mirrors the tool schema in contracts/registry.json.
 *
 * A tool is anything a bot can invoke: a shell command wrapper, an MCP
 * connection, a file op, a notification, etc. botd owns the registry;
 * the app mirrors it over IPC and filters by each bot's allowlist before
 * generating the OpenAI tool_schema for the LLM prompt.
 */
@Serializable
data class ToolDescriptor(
    /** Stable tool id, e.g. "web.scrape" or "shell.exec". */
    val id: String,
    /** Human-facing name shown in the Capabilities screen. */
    val name: String,
    /** Short description fed to the LLM. */
    val description: String,
    /**
     * JSON-Schema object describing the arguments. Serialized straight
     * into the OpenAI tools[].function.parameters field.
     */
    val parameters: JsonObjectLike = JsonObjectLike(emptyMap()),
    /** Execution risk tier — drives the approval UX. */
    val risk: RiskTier = RiskTier.Info,
    /** Which backends can run this tool, e.g. ["termux-shell", "mcp"]. */
    val backends: List<String> = listOf("termux-shell"),
    /** Pipeline phase the tool belongs to. */
    val phase: Phase = Phase.Execute,
    /** Whether the tool can be re-run safely after a failure. */
    val idempotent: Boolean = true,
)

/** JsonObjectLike is a serializable stand-in for an arbitrary JSON object. */
@Serializable
data class JsonObjectLike(val fields: Map<String, Any> = emptyMap())

/**
 * Risk tier — the same enum drives:
 *  - the approval queue on the app (info / confirm / critical cards)
 *  - botd's gate: critical tools always require a human decision
 */
@Serializable
enum class RiskTier {
    /** Informational — no approval needed, just logged. */
    @SerialName("info") Info,
    /** Routine action — one-tap confirm on the app. */
    @SerialName("confirm") Confirm,
    /** Irreversible / destructive — expanded card, explicit Approve/Deny/Ask. */
    @SerialName("critical") Critical,
}

/** Pipeline phase a tool participates in. */
@Serializable
enum class Phase {
    /** Pre-checks: validation, read-only probes. */
    @SerialName("prepare") Prepare,
    /** Main execution. */
    @SerialName("execute") Execute,
    /** Post-run: cleanup, notification, digest. */
    @SerialName("finalize") Finalize,
}
