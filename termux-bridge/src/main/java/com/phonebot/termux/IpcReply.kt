package com.phonebot.termux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One JSON-lines reply from botd. [ok] is false when `type == "error"`.
 *
 * The [raw] field holds the full reply object; use [result] to extract a
 * typed payload without re-parsing.
 */
data class IpcReply(
    /** Reply type: "ack", "state", "jobs", "llm_response", "error", … */
    val type: String,
    /** Echoed correlation id from the request. */
    val corrId: String,
    /** True when type != "error". */
    val ok: Boolean,
    /** Error detail; null when ok. */
    val error: String?,
    /** The full JSON reply (use for ad-hoc extraction). */
    val raw: JsonObject,
) {
    /** Extract a typed value from [raw] when the call succeeded. */
    inline fun <T> result(crossinline transform: (JsonObject) -> T?): T? =
        if (ok) transform(raw) else null

    companion object {
        /** Parse a raw JSON line into [IpcReply]. [wantedType] is the type the caller expected. */
        fun parse(type: String, corrId: String, line: String, json: Json): IpcReply {
            val obj = runCatching {
                json.parseToJsonElement(line).let { it as? JsonObject }
            }.getOrNull()
                ?: return IpcReply("error", corrId, false, "malformed reply: $line", buildJsonObject {})
            val replyType = obj["type"]?.jsonPrimitive?.content ?: ""
            val ok = replyType != "error"
            val error = if (ok) null else obj["error"]?.jsonPrimitive?.content
                ?: obj["message"]?.jsonPrimitive?.content
            return IpcReply(
                type = replyType,
                corrId = obj["corr_id"]?.jsonPrimitive?.content ?: corrId,
                ok = ok,
                error = error,
                raw = obj,
            )
        }
    }
}
