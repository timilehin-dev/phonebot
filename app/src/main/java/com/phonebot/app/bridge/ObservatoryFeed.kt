package com.phonebot.app.bridge

import com.phonebot.termux.ApprovalRequest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bus event kinds the Observatory can render. */
enum class ObservatoryEventKind { Task, Result, CronResult, Approval, Info }

/** One bot-to-bot event, already mapped out of botd's JSON shape. */
data class ObservatoryEvent(
    val id: String,
    val kind: ObservatoryEventKind,
    val fromBot: String,
    val toBot: String,
    val summary: String,
    val timestamp: String,
)

/** Everything the Observatory renders, independent of transport. */
data class ObservatoryUiState(
    val online: Boolean,
    val crew: List<BotRow> = emptyList(),
    val pending: List<ApprovalRequest> = emptyList(),
    val events: List<ObservatoryEvent> = emptyList(),
    val error: String? = null,
)

/**
 * Swappable Observatory data source. The Compose screen only consumes
 * [ObservatoryUiState]; a different backing store (REST, Room cache, demo data)
 * can implement this interface without touching the UI.
 */
interface ObservatoryDataSource {
    val state: StateFlow<ObservatoryUiState>
    fun refresh()
}

/** Process-wide data source backed by the live bridge. */
object BridgeObservatoryDataSource : ObservatoryDataSource {
    override val state: StateFlow<ObservatoryUiState>
        get() = BridgeRuntime.observatory

    override fun refresh() {
        BridgeRuntime.refreshRoster()
    }
}

/**
 * Pure `bus_event` -> [ObservatoryEvent] mapping.
 *
 * botd sends `{"type":"bus_event","event":{type,from_bot,to_bot,corr_id,payload,timestamp}}`.
 * Unknown/malformed frames are ignored (null) so one bad line cannot break the feed.
 */
object ObservatoryEventMapper {
    private val json = Json { ignoreUnknownKeys = true }

    private val kinds = mapOf(
        "task" to ObservatoryEventKind.Task,
        "result" to ObservatoryEventKind.Result,
        "cron_result" to ObservatoryEventKind.CronResult,
        "needs-approval" to ObservatoryEventKind.Approval,
        "fyi" to ObservatoryEventKind.Info,
    )

    fun fromBridgeLine(raw: String): ObservatoryEvent? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (root.string("type") != "bus_event") return null
        val event = runCatching { root["event"]?.jsonObject }.getOrNull() ?: return null
        val type = event.string("type")
        val kind = kinds[type] ?: return null
        val payload = runCatching { event["payload"]?.jsonObject }.getOrNull()

        val summary = firstNonBlank(
            payload?.string("summary"),
            payload?.string("message"),
            payload?.string("result"),
            payload?.string("tool"),
            payload?.string("action"),
            payload?.string("job_id"),
        ).ifBlank { type.replace('-', ' ') }

        val from = event.string("from_bot").ifBlank { "?" }
        val to = event.string("to_bot").ifBlank { "?" }
        val corr = event.string("corr_id").ifBlank { payload?.string("corr_id") ?: "" }
        val timestamp = event.string("timestamp")

        return ObservatoryEvent(
            id = "$type|$from|$to|$corr|$timestamp",
            kind = kind,
            fromBot = from,
            toBot = to,
            summary = summary.take(160),
            timestamp = timestamp,
        )
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
}

/**
 * Bounded, deduplicated feed of recent bus events (newest first).
 * Thread-safe because the persistent reader calls [ingest] off the main thread.
 */
class ObservatoryFeed(private val limit: Int = 50) {
    private val events = ArrayDeque<ObservatoryEvent>()
    private val seen = LinkedHashSet<String>()

    /** Returns true when the line produced a new event. */
    @Synchronized
    fun ingest(raw: String): Boolean {
        val event = ObservatoryEventMapper.fromBridgeLine(raw) ?: return false
        if (!seen.add(event.id)) return false
        events.addFirst(event)
        while (events.size > limit) {
            val removed = events.removeLast()
            seen.remove(removed.id)
        }
        return true
    }

    @Synchronized
    fun snapshot(): List<ObservatoryEvent> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
        seen.clear()
    }
}
