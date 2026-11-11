package com.phonebot.termux

import androidx.annotation.Keep
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * BridgeEventStream — Phase-2 persistent channel for the Termux bridge.
 *
 * Opens a long-lived SSH exec channel running `python -m botd.cli subscribe`
 * inside Termux and streams newline-delimited JSON frames (acks, bus_event, etc.)
 * to [onLine]. Reconnects with exponential backoff while [running].
 *
 * Transport only: parsing/routing is owned by the app-side event store so UI and
 * design changes stay localised.
 */
@Keep
class BridgeEventStream(
    private val host: String = "127.0.0.1",
    private val port: Int = 8022,
    private val user: String = "termux",
    private val botdHome: String = "~/phonebot",
    private val auth: BotdSshClient.SshAuth,
    private val onLine: (rawJson: String) -> Unit,
    private val onError: (msg: String) -> Unit = {},
    private val tag: String? = "BridgeEventStream",
) {
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val backoffMs = AtomicLong(1000L)

    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelExec? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var errThread: Thread? = null

    /** Start and keep the channel alive with bounded retries. Idempotent. */
    fun connect() {
        if (!started.compareAndSet(false, true)) return
        running.set(true)
        thread(name = "bridge-event-stream-supervisor", isDaemon = true) {
            while (running.get()) {
                try {
                    openAndRead()
                    backoffMs.set(1000L)
                } catch (e: Exception) {
                    if (!running.get()) break
                    onError("bridge event stream disconnected: ${e.message ?: e}")
                } finally {
                    closeChannelSafely()
                    closeSessionSafely()
                }
                if (!running.get()) break
                val delay = backoffMs.get().coerceIn(1000L, 30_000L)
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    break
                }
                backoffMs.set((delay * 2).coerceAtMost(30_000L))
            }
        }
    }

    /** Graceful teardown. Safe to call multiple times. */
    fun close() {
        running.set(false)
        started.set(false)
        closeChannelSafely()
        closeSessionSafely()
        readerThread?.interrupt()
        errThread?.interrupt()
        readerThread = null
        errThread = null
    }

    val dispose: () -> Unit get() = ::close

    private fun openAndRead() {
        val jsch = JSch()
        when (val a = auth) {
            is BotdSshClient.SshAuth.Key ->
                jsch.addIdentity("phonebot", a.privateKeyPem.toByteArray(Charsets.UTF_8), null, null)
            is BotdSshClient.SshAuth.Password -> Unit
        }
        val s = jsch.getSession(user, host, port)
        (auth as? BotdSshClient.SshAuth.Password)?.let { s.setPassword(it.password) }
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("UserKnownHostsFile", "/dev/null")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.connect(15_000)
        session = s

        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(subscribeCommand())
        val stdout: InputStream = ch.inputStream
        val stderr: InputStream = ch.errStream
        ch.connect(15_000)
        channel = ch

        // Drain stderr so the remote side can't block on a full pipe.
        errThread = thread(name = "bridge-event-stream-stderr", isDaemon = true) {
            runCatching {
                BufferedReader(InputStreamReader(stderr, StandardCharsets.UTF_8)).use { r ->
                    while (running.get()) {
                        val line = r.readLine() ?: break
                        if (line.isNotBlank()) {
                            // stderr is diagnostic; do not treat as a protocol frame.
                        }
                    }
                }
            }
        }

        readerThread = Thread.currentThread()
        BufferedReader(InputStreamReader(stdout, StandardCharsets.UTF_8)).use { r ->
            while (running.get()) {
                val line = r.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    onLine(trimmed)
                }
            }
        }
    }

    private fun subscribeCommand(): String =
        "cd $botdHome 2>/dev/null || { echo '{\"type\":\"error\",\"code\":\"botd_home_missing\"}'; exit 1; }" +
            " && { python -m botd.cli subscribe || python3 -m botd.cli subscribe; }"

    private fun closeChannelSafely() {
        try { channel?.disconnect() } catch (_: Exception) {}
        channel = null
    }

    private fun closeSessionSafely() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }
}
