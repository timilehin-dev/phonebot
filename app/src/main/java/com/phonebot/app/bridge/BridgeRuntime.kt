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

/**
 * Process-level owner for the persistent Termux bridge (Phase 2).
 *
 * Keeps transport ([BridgeEventStream]), domain/store ([BridgeEventStore]), and
 * UI bindings decoupled: the stream only delivers raw lines, the store owns the
 * approvals inbox state and corr_id resolution, and UI observes [pendingApprovals]
 * and invokes [approve]/[deny].
 */
object BridgeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val streamRef = AtomicReference<BridgeEventStream?>(null)
    private val storeRef = AtomicReference<BridgeEventStore?>(null)
    private val clientRef = AtomicReference<BotdSshClient?>(null)

    private val _pendingApprovals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _pendingApprovals.asStateFlow()

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
            onEvent = { /* observability hook; UI can evolve independently */ },
            onError = { _, _ -> },
        )
        val stream = BridgeEventStream(
            host = host,
            port = port,
            user = user,
            botdHome = botdHome,
            auth = auth,
            onLine = { line ->
                store.onBridgeLine(line)
                _pendingApprovals.value = store.pendingApprovals.value
            },
            onError = { /* transport hiccup; stream will reconnect while running */ },
        )
        clientRef.set(client)
        storeRef.set(store)
        streamRef.set(stream)
        // Seed with current (empty) state and start the persistent subscriber.
        _pendingApprovals.value = store.pendingApprovals.value
        stream.connect()
    }

    fun approve(corrId: String, onResult: (Result<Unit>) -> Unit = {}) {
        val store = storeRef.get() ?: run {
            onResult(Result.failure(IllegalStateException("bridge not started")))
            return
        }
        scope.launch {
            val res = store.approve(corrId)
            _pendingApprovals.value = store.pendingApprovals.value
            onResult(res)
        }
    }

    fun deny(corrId: String, onResult: (Result<Unit>) -> Unit = {}) {
        val store = storeRef.get() ?: run {
            onResult(Result.failure(IllegalStateException("bridge not started")))
            return
        }
        scope.launch {
            val res = store.deny(corrId)
            _pendingApprovals.value = store.pendingApprovals.value
            onResult(res)
        }
    }

    @Synchronized
    fun stop() {
        streamRef.getAndSet(null)?.close()
        storeRef.set(null)
        clientRef.set(null)
        _pendingApprovals.value = emptyList()
    }
}
