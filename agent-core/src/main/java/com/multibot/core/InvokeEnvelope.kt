package com.multibot.core

import kotlinx.serialization.Serializable

/**
 * InvokeEnvelope — request/response pair for a single tool invocation.
 *
 * The app builds the [InvokeRequest]; botd executes it and streams back
 * [TraceRecord]s while finishing with an [InvokeResponse]. All envelopes
 * cross the abstract-namespace Unix socket as JSON-lines.
 */

// ---------------------------------------------------------------------------
// Request
// ---------------------------------------------------------------------------

@Serializable
data class InvokeRequest(
    /** Client-generated correlation id, echoed in the response. */
    val requestId: String,
    /** Bot that owns this invocation. */
    val botId: String,
    /** The tool being invoked (must be in the bot's allowlist). */
    val tool: String,
    /** Argument payload — a flat map, schema-checked against ToolDescriptor.parameters. */
    val args: Map<String, Any> = emptyMap(),
    /** Whether botd may auto-run this even if it's Confirm tier. */
    val preApproved: Boolean = false,
    /** Trace id for the current run (set by the agent loop). */
    val traceId: String? = null,
)

// ---------------------------------------------------------------------------
// Response
// ---------------------------------------------------------------------------

@Serializable
data class InvokeResponse(
    val requestId: String,
    val botId: String,
    val tool: String,
    val ok: Boolean,
    /** Structured result payload (tool-specific). */
    val result: Map<String, Any> = emptyMap(),
    /** Human-readable error message when ok=false. */
    val error: String? = null,
    val durationMs: Long,
)

// ---------------------------------------------------------------------------
// Tool call (LLM side)
// ---------------------------------------------------------------------------

/**
 * A tool call as emitted by an OpenAI-compatible LLM in a tool_calls
 * response. The agent loop converts these into [InvokeRequest]s.
 */
@Serializable
data class ToolCall(
    /** LLM-assigned call id (must be echoed back to the model). */
    val id: String,
    val name: String,
    /** Arguments as a JSON string, exactly as the model produced them. */
    val arguments: String,
)

// ---------------------------------------------------------------------------
// Trace
// ---------------------------------------------------------------------------

/**
 * A single step in a run's trace — rendered as collapsible mono chips
 * in BotThreadScreen. botd emits one per tool call, per LLM turn,
 * per hand-off.
 */
@Serializable
data class TraceRecord(
    val traceId: String,
    /** Monotonic step index within the trace. */
    val step: Int,
    val botId: String,
    /** What happened: tool call, LLM turn, hand-off, approval… */
    val kind: TraceKind,
    val label: String,
    /** Structured detail — args, result summary, token counts. */
    val detail: Map<String, Any> = emptyMap(),
    val timestamp: Long,
    val durationMs: Long? = null,
)

enum class TraceKind {
    @SerialName("tool") Tool,
    @SerialName("llm") Llm,
    @SerialName("handoff") Handoff,
    @SerialName("approval") Approval,
    @SerialName("error") Error,
}
