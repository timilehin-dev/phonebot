package com.phonebot.termux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Tier for UI rendering; domain-level so screens can evolve independently. */
enum class ApprovalRisk { Info, Confirm, Critical }

data class ApprovalRequest(
    val corrId: String,
    val bot: String,
    val command: String,
    val detail: String,
    val risk: ApprovalRisk = ApprovalRisk.Confirm,
)

/**
 * App-side approvals inbox domain for the persistent Termux bridge.
 *
 * Feed raw JSON lines from [BridgeEventStream] into [onBridgeLine]. When a
 * `bus_event` carrying a `needs-approval` payload arrives, an [ApprovalRequest]
 * keyed by `corr_id` is added to [pendingApprovals]. [approve]/[deny] resolve
 * the request via a one-shot botd CLI call over [cli] and remove it locally.
 */
class BridgeEventStore(
    private val cli: BotdSshClient,
    private val onEvent: (String) -> Unit = {},
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _pending.asStateFlow()

    /** Parse and route a single JSON line from the persistent subscriber channel. */
    fun onBridgeLine(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject
            val type = root.string("type")
            if (type == "bus_event") {
                val ev = root.obj("event") ?: return
                val evType = ev.string("type")
                val payload = ev.obj("payload")
                val corrId = ev.string("corr_id").ifBlank { payload?.string("corr_id") ?: "" }
                if (evType == "needs-approval" && corrId.isNotBlank()) {
                    val bot = (payload?.string("bot") ?: "").ifBlank { ev.string("from_bot").ifBlank { "?" } }
                    val command = (payload?.string("tool") ?: "")
                        .ifBlank { payload?.string("preview") ?: "" }
                        .ifBlank { payload?.string("action") ?: "" }
                        .ifBlank { "approval requested" }
                    val detail = (payload?.string("detail") ?: "").ifBlank { payload?.string("risk") ?: "" }
                    val risk = when {
                        detail.contains("destructive", ignoreCase = true) -> ApprovalRisk.Critical
                        command.contains("rm ", ignoreCase = true) -> ApprovalRisk.Critical
                        else -> ApprovalRisk.Confirm
                    }
                    val req = ApprovalRequest(
                        corrId = corrId,
                        bot = bot,
                        command = command,
                        detail = detail,
                        risk = risk,
                    )
                    val cur = _pending.value
                    if (cur.none { it.corrId == corrId }) {
                        _pending.value = cur + req
                    }
                    onEvent("approval pending: $corrId ($bot)")
                } else {
                    onEvent("bus_event: $evType")
                }
            } else {
                onEvent("bridge: $type")
            }
        } catch (t: Throwable) {
            onError("bridge event parse failed", t)
        }
    }

    /** Approve a pending request by corr_id (one-shot CLI). */
    fun approve(corrId: String): Result<Unit> = runCatching {
        val reply = cli.cli("approve", """{"corr_id":${jsonString(corrId)}}""")
        if (reply?.ok != true) error(reply?.error ?: "approve failed")
        remove(corrId)
        onEvent("approved: $corrId")
    }.onFailure { onError("approve failed: $corrId", it) }

    /** Deny a pending request by corr_id (one-shot CLI). */
    fun deny(corrId: String): Result<Unit> = runCatching {
        val reply = cli.cli("deny", """{"corr_id":${jsonString(corrId)}}""")
        if (reply?.ok != true) error(reply?.error ?: "deny failed")
        remove(corrId)
        onEvent("denied: $corrId")
    }.onFailure { onError("deny failed: $corrId", it) }

    fun remove(corrId: String) {
        _pending.value = _pending.value.filterNot { it.corrId == corrId }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject.obj(key: String): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrNull()

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
