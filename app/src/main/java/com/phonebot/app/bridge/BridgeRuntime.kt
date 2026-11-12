package com.phonebot.app.bridge

import com.phonebot.termux.ApprovalRequest
import com.phonebot.termux.BotdSshClient
import com.phonebot.termux.BridgeEventStore
import com.phonebot.termux.BridgeEventStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Feedback shown after a roster action (success or failure). */
data class ActionFeedback(val message: String, val isError: Boolean)

/**
 * Process-level owner for the persistent Termux bridge (Phase 2).
 *
 * Layers stay separate: [BridgeEventStream] is transport (raw JSON lines),
 * [BridgeEventStore] is the approvals domain (corr_id inbox + resolution), and
 * this runtime exposes plain state ([pendingApprovals], [roster],
 * [actionFeedback]) for the Compose UI. Screens never see SSH or JSON.
 */
object BridgeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val streamRef = AtomicReference<BridgeEventStream?>(null)
    private val storeRef = AtomicReference<BridgeEventStore?>(null)
    private val clientRef = AtomicReference<BotdSshClient?>(null)

    @Volatile
    private var approvalObserver: ApprovalObserver? = null

    private val _pendingApprovals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _pendingApprovals.asStateFlow()

    /** null until the first roster fetch completes. */
    private val _roster = MutableStateFlow<RosterSnapshot?>(null)
    val roster: StateFlow<RosterSnapshot?> = _roster.asStateFlow()

    private val _busyBot = MutableStateFlow<String?>(null)
    val busyBot: StateFlow<String?> = _busyBot.asStateFlow()

    private val _actionFeedback = MutableStateFlow<ActionFeedback?>(null)
    val actionFeedback: StateFlow<ActionFeedback?> = _actionFeedback.asStateFlow()

    private val feed = ObservatoryFeed()
    private val _observatory = MutableStateFlow(ObservatoryUiState(online = false))

    /** Live Observatory state (crew + pending approvals + recent bus events). */
    val observatory: StateFlow<ObservatoryUiState> = _observatory.asStateFlow()

    /** Wire approval notifications (called once from Application). */
    fun setApprovalObserver(observer: ApprovalObserver?) {
        approvalObserver = observer
    }

    @Synchronized
    fun start(
        host: String = "127.0.0.1",
        port: Int = 8022,
        user: String = "termux",
        auth: BotdSshClient.SshAuth = BotdSshClient.EMPTY_AUTH,
        botdHome: String = "~/phonebot",
    ) {
        if (streamRef.get() != null) return
        val client = BotdSshClient(host, port, user, auth, botdHome)
        val store = BridgeEventStore(
            cli = client,
            onEvent = { /* observability hook; UI evolves independently */ },
            onError = { _, _ -> },
        )
        val stream = BridgeEventStream(
            host = host,
            port = port,
            user = user,
            botdHome = botdHome,
            auth = auth,
            onLine = { line -> handleBridgeLine(store, line) },
            onError = { /* transport hiccup; the stream reconnects while running */ },
        )
        clientRef.set(client)
        storeRef.set(store)
        streamRef.set(stream)
        stream.connect()
        publishObservatory()
        refreshRoster()
    }

    private fun handleBridgeLine(store: BridgeEventStore, line: String) {
        store.onBridgeLine(line)
        feed.ingest(line)
        val pending = store.pendingApprovals.value
        _pendingApprovals.value = pending
        publishObservatory()
        approvalObserver?.onPending(pending)
    }

    private fun publishObservatory() {
        val roster = _roster.value
        _observatory.value = ObservatoryUiState(
            online = roster?.online == true,
            crew = roster?.bots.orEmpty(),
            pending = _pendingApprovals.value,
            events = feed.snapshot(),
            error = roster?.error,
        )
    }

    fun approve(corrId: String, onResult: (Result<Unit>) -> Unit = {}) =
        resolveApproval(corrId, approve = true, onResult = onResult)

    fun deny(corrId: String, onResult: (Result<Unit>) -> Unit = {}) =
        resolveApproval(corrId, approve = false, onResult = onResult)

    private fun resolveApproval(corrId: String, approve: Boolean, onResult: (Result<Unit>) -> Unit) {
        val store = storeRef.get() ?: run {
            onResult(Result.failure(IllegalStateException("bridge not started")))
            return
        }
        scope.launch {
            val result = if (approve) store.approve(corrId) else store.deny(corrId)
            _pendingApprovals.value = store.pendingApprovals.value
            publishObservatory()
            if (result.isSuccess) approvalObserver?.onResolved(corrId)
            onResult(result)
        }
    }

    /** Fetch the roster from botd's `get_state`; updates [roster] on completion. */
    @JvmOverloads
    fun refreshRoster(onResult: (Result<RosterSnapshot>) -> Unit = {}) {
        val client = clientRef.get()
        if (client == null) {
            val snapshot = RosterSnapshot(online = false, error = "bridge not started")
            _roster.value = snapshot
            onResult(Result.failure(IllegalStateException(snapshot.error)))
            return
        }
        scope.launch {
            val snapshot = try {
                val reply = client.cli("get_state", "{}")
                RosterMapper.fromState(reply, _pendingApprovals.value)
            } catch (t: Throwable) {
                RosterSnapshot(online = false, error = t.message ?: "get_state failed")
            }
            _roster.value = snapshot
            publishObservatory()
            onResult(
                if (snapshot.online) Result.success(snapshot)
                else Result.failure(IllegalStateException(snapshot.error ?: "botd offline")),
            )
        }
    }

    fun runBot(bot: String) = control("run", "run_bot", bot)
    fun pauseBot(bot: String) = control("pause", "pause_bot", bot)
    fun resumeBot(bot: String) = control("resume", "resume_bot", bot)

    fun dismissActionFeedback() {
        _actionFeedback.value = null
    }

    /** One roster mutation, then refresh so the UI shows what botd actually reports. */
    private fun control(verb: String, ipcType: String, bot: String) {
        val client = clientRef.get()
        if (client == null) {
            _actionFeedback.value = ActionFeedback("$verb failed: bridge not started", isError = true)
            return
        }
        _busyBot.value = bot
        _actionFeedback.value = null
        scope.launch {
            val result = runCatching {
                val reply = client.cli(ipcType, """{"bot":${jsonString(bot)}}""")
                if (reply?.ok != true) error(reply?.error ?: "$verb failed")
            }
            _busyBot.value = null
            if (result.isSuccess) {
                _actionFeedback.value = ActionFeedback("${verb.replaceFirstChar { it.titlecase() }} · $bot", isError = false)
                refreshRoster()
            } else {
                _actionFeedback.value = ActionFeedback(
                    "$verb failed · $bot: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                    isError = true,
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        streamRef.getAndSet(null)?.close()
        storeRef.set(null)
        clientRef.set(null)
        _pendingApprovals.value = emptyList()
        _roster.value = null
        _busyBot.value = null
        _actionFeedback.value = null
        feed.clear()
        publishObservatory()
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
