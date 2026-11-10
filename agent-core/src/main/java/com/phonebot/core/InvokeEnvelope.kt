package com.phonebot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * InvokeEnvelope — request/response pair for a single tool invocation,
 * aligned verbatim to `contracts/invoke-envelope.schema.json` (the schema of
 * record). One Request frame and one Response frame cross the
 * abstract-namespace Unix socket as JSON-lines.
 *
 * The app builds [InvokeRequest]; botd executes it and returns exactly one
 * [InvokeResponse] per `corr_id` (blocked `needs_approval` calls resolve to a
 * single terminal response after the human verdict).
 */

// ---------------------------------------------------------------------------
// Request  (client → server)
// ---------------------------------------------------------------------------

@Serializable
data class InvokeRequest(
    /** Tool id to invoke, e.g. "shell.run" (see builtin-tools-phase1.json). */
    val tool: String,
    /** Tool arguments; botd validates them against the ToolDescriptor schema. */
    val args: Map<String, Any> = emptyMap(),
    /** Invocation context, echoed back on the response. */
    val ctx: InvokeContext = InvokeContext(),
)

@Serializable
data class InvokeContext(
    /** Owning bot name (directory under ~/phonebot/bots/). */
    @SerialName("bot") val bot: String = "",
    /** Session id (sessions.db row) in which the invocation occurs. */
    @SerialName("session") val session: String = "",
    /** Zero-based turn index within the session. */
    @SerialName("turn") val turn: Int = 0,
    /** Correlation id (UUIDv7), echoed verbatim in the response. */
    @SerialName("corr_id") val corrId: String = "",
    /** Scheduling priority (queue ordering, not a risk control). */
    @SerialName("priority") val priority: Priority = Priority.Normal,
) {
    @Serializable
    enum class Priority {
        @SerialName("low") Low,
        @SerialName("normal") Normal,
        @SerialName("high") High,
        @SerialName("critical") Critical,
    }
}

// ---------------------------------------------------------------------------
// Response  (server → client)
// ---------------------------------------------------------------------------

@Serializable
data class InvokeResponse(
    /** Echoed correlation id from the request. */
    @SerialName("corr_id") val corrId: String,
    /** ok = executed; error = failed; needs_approval = blocked pending a verdict. */
    @SerialName("status") val status: Status,
    /** Tool result payload (tool-specific); present on `ok`. */
    @SerialName("result") val result: JsonObject? = null,
    /** Present when status == error. */
    @SerialName("error") val error: InvokeError? = null,
    /** Execution trace; present on `ok`, informational on `error`. */
    @SerialName("trace") val trace: InvokeTrace? = null,
    /** Declared side effects, for trace chips and the audit log. */
    @SerialName("side_effects") val sideEffects: SideEffects? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("ok") Ok,
        @SerialName("error") Error,
        @SerialName("needs_approval") NeedsApproval,
    }

    @Serializable
    data class InvokeError(
        /** Stable machine-readable code, e.g. "backend_unavailable", "risk_gate". */
        val code: String,
        /** Human detail; never contains secret values. */
        val message: String? = null,
    )

    @Serializable
    data class InvokeTrace(
        /** Backend actually used, e.g. "termux"; null when blocked pre-resolution. */
        val backend: String? = null,
        /** Wall-clock execution time in milliseconds. */
        @SerialName("duration_ms") val durationMs: Long = 0L,
        /** Approval record id (audit); present when an approval was required. */
        @SerialName("approval_ref") val approvalRef: String? = null,
    )

    @Serializable
    data class SideEffects(
        val filesTouched: List<String> = emptyList(),
        val busEvents: List<String> = emptyList(),
    )
}

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
// Trace (UI side)
// ---------------------------------------------------------------------------

/**
 * A single step in a run's trace — rendered as collapsible mono chips in
 * BotThreadScreen. botd emits one per tool call, per LLM turn, per hand-off.
 * This is a *presentation* record, distinct from [InvokeResponse.InvokeTrace]
 * (the per-invocation execution trace in the schema of record).
 */
@Serializable
data class TraceRecord(
    @SerialName("trace_id") val traceId: String,
    /** Monotonic step index within the trace. */
    val step: Int,
    @SerialName("bot") val botId: String,
    val kind: TraceKind,
    val label: String,
    /** Structured detail — args, result summary, token counts. */
    val detail: Map<String, Any> = emptyMap(),
    @SerialName("ts") val timestamp: Long,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

@Serializable
enum class TraceKind {
    @SerialName("tool") Tool,
    @SerialName("llm") Llm,
    @SerialName("handoff") Handoff,
    @SerialName("approval") Approval,
    @SerialName("error") Error,
}
