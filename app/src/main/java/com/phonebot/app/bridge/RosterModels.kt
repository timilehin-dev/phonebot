package com.phonebot.app.bridge

import com.phonebot.termux.ApprovalRequest
import com.phonebot.termux.IpcReply
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Roster status derived purely from botd state + pending approvals. */
enum class BotStatus { Running, Paused, WaitingApproval, Idle }

/** One row the Bots tab can render, independent of any botd JSON shape. */
data class BotRow(
    val id: String,
    val label: String,
    val status: BotStatus,
    val currentTask: String,
    val spendUsd: Double,
)

/**
 * Result of one roster fetch. [online] is false for transport failures
 * (botd/SSH unreachable) and for error replies from botd; [error] carries the
 * reason so the UI can show a real offline state instead of stale data.
 */
data class RosterSnapshot(
    val online: Boolean,
    val bots: List<BotRow> = emptyList(),
    val error: String? = null,
)

/**
 * Pure mapping from botd's `get_state` reply into [BotRow]s.
 *
 * Kept free of Android dependencies so roster parsing is unit-testable and the
 * UI never has to know botd's JSON shape (`{"type":"state","bots":[...]}`).
 */
object RosterMapper {

    fun fromState(
        reply: IpcReply?,
        pendingApprovals: List<ApprovalRequest> = emptyList(),
    ): RosterSnapshot {
        if (reply == null) {
            return RosterSnapshot(online = false, error = "botd unreachable")
        }
        if (!reply.ok) {
            return RosterSnapshot(online = false, error = reply.error ?: "get_state failed")
        }
        val entries = reply.raw["bots"] as? JsonArray
            ?: return RosterSnapshot(online = true)
        val waitingBots = pendingApprovals.map { it.bot.lowercase() }.toSet()

        val bots = entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["bot"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val paused = obj["paused"]?.jsonPrimitive?.booleanOrNull ?: false
            val spendUsd = obj["spend_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val currentTask = obj["current_task"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val status = when {
                paused -> BotStatus.Paused
                id.lowercase() in waitingBots -> BotStatus.WaitingApproval
                currentTask.isNotEmpty() -> BotStatus.Running
                else -> BotStatus.Idle
            }
            BotRow(
                id = id,
                label = displayName(id),
                status = status,
                currentTask = currentTask,
                spendUsd = spendUsd,
            )
        }.sortedBy { it.id }

        return RosterSnapshot(online = true, bots = bots)
    }

    /** `chief-of-staff` -> `Chief Of Staff`; unknown/blank ids fall back to the raw id. */
    fun displayName(id: String): String =
        id.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { first ->
                    if (first.isLowerCase()) first.titlecase() else first.toString()
                }
            }
            .ifBlank { id }
}
