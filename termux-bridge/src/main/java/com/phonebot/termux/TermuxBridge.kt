package com.phonebot.termux

import android.content.Context
import android.content.Intent

/**
 * TermuxBridge — the app-side control plane to the durable botd daemon in
 * Termux.
 *
 * Transport (corrected, per docs/ADR-001):
 *  - **Primary:** [BotdSshClient] — SSH into Termux's local sshd
 *    (`termux-setup-ssh`, 127.0.0.1:8022) and run the in-Termux socket
 *    client `python -m botd.cli <type> <args>`, which opens the
 *    *abstract-namespace* socket `@phonebot`. This is the device-reliable,
 *    no-root, no-Shizuku path: the app user cannot address a UID-gated
 *    abstract socket from its own process, but it *can* shell into Termux —
 *    the exact "deep Termux" thesis, and the same Termux/SSH remote backend
 *    Zafiro uses.
 *  - **Fallback:** when SSH isn't configured yet (first wizard run), the
 *    [termuxNotification] / [termuxSms] / [termuxTts] helpers fall back to
 *    Termux:API's `RUN_COMMAND` intent so Termux:API still delivers a command
 *    to the live terminal session.
 *
 * The abstract-socket framing / handler names are authoritative in
 * `botd/ipc.py`; the CLI re-derives the same address, so app and daemon can
 * never drift.
 */
class TermuxBridge(private val context: Context) {

    /** SSH control plane; null until [configureSsh] is called with credentials. */
    private var client: BotdSshClient? = null

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    /**
     * Wire the SSH transport. Call once, after the wizard has a keypair or
     * password for Termux's sshd. Idempotent: re-calling re-points [client].
     */
    fun configureSsh(
        auth: BotdSshClient.SshAuth,
        host: String = "127.0.0.1",
        port: Int = 8022,
        user: String = "termux",
        botdHome: String = "~/phonebot",
    ) {
        client = BotdSshClient(host, port, user, auth, botdHome)
    }

    // ------------------------------------------------------------------
    // Presence + liveness
    // ------------------------------------------------------------------

    /** Detect Termux (QUERY_ALL_PACKAGES already granted). */
    fun isTermuxInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.termux", 0) }.isSuccess

    /** Detect Termux:API (RUN_COMMAND helpers need it). */
    fun isTermuxApiInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.termux.api", 0) }.isSuccess

    /** Detect Termux:Boot (reboot persistence). */
    fun isTermuxBootInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.termux.boot", 0) }.isSuccess

    /** True when Termux's sshd answers. */
    fun sshdReachable(): Boolean = client?.sshdReachable() ?: false

    /**
     * True when the botd daemon is up AND answering a `ping` over the
     * `@phonebot` abstract socket (via SSH + `botd.cli`). Replaces the old
     * JVM `isSocketUp()` — which was architecturally impossible on Android.
     */
    fun isBotdUp(): Boolean = client?.isBotdUp() ?: false

    // ------------------------------------------------------------------
    // Control plane (typed wrappers over the botd CLI)
    // ------------------------------------------------------------------
    // Each returns null when the transport is degraded (sshd down, not
    // deployed, botd home missing). Callers surface that as "botd offline".

    /** Start a user-triggered turn on [bot]. [triggerJson] is `{"message":…}`. */
    fun runBot(bot: String, triggerJson: String = "{}"): IpcReply? =
        client?.cli("run_bot", cliFrame("bot" to bot, "trigger" to triggerJson))

    /** Approve a pending tool call (a `needs-approval` gate). */
    fun approve(corrId: String): IpcReply? =
        client?.cli("approve", cliFrame("corr_id" to corrId))

    /** Deny a pending tool call. */
    fun deny(corrId: String): IpcReply? =
        client?.cli("deny", cliFrame("corr_id" to corrId))

    fun pauseBot(bot: String): IpcReply? = client?.cli("pause_bot", cliFrame("bot" to bot))

    fun resumeBot(bot: String): IpcReply? = client?.cli("resume_bot", cliFrame("bot" to bot))

    /** Snapshot of every bot flag. null when degraded. */
    fun getState(): IpcReply? = client?.cli("get_state", "{}")

    /** The live `jobs.json` map. */
    fun listJobs(): IpcReply? = client?.cli("list_jobs", "{}")

    /**
     * Ask botd to run a cloud completion (botd owns `endpoint.json` + the
     * named secret). Long timeout: an agent turn can make real HTTP calls.
     */
    fun llmRequest(
        model: String,
        messagesJson: String,
        toolsJson: String? = null,
        stream: Boolean = false,
    ): IpcReply? = client?.cli(
        "llm_request",
        buildLlmFrame(model, messagesJson, toolsJson, stream),
        timeoutMs = 300_000,
    )

    private fun buildLlmFrame(
        model: String,
        messagesJson: String,
        toolsJson: String?,
        stream: Boolean,
    ): String {
        val pairs = mutableListOf<Pair<String, String>>()
        pairs += "model" to model
        pairs += "messages" to messagesJson
        if (toolsJson != null) pairs += "tools" to toolsJson
        pairs += "stream" to (if (stream) "true" else "false")
        return cliFrame(*pairs.toTypedArray())
    }

    /** Merge an app-supplied payload into the bot's `state.json`. */
    fun updatePayload(bot: String, payloadJson: String): IpcReply? =
        client?.cli("update_payload", cliFrame("bot" to bot, "payload" to payloadJson))

    // ------------------------------------------------------------------
    // Termux:API shell helpers (RUN_COMMAND plane — NOT the botd socket)
    // ------------------------------------------------------------------
    // These go through Termux:API's RunCommandService intent. Requires the
    // Termux:API package; the wizard's second step installs it.

    /** `termux-notification`: title\nbody via Termux:API. */
    fun termuxNotification(title: String, body: String): Boolean =
        runFallbackRunCommand("termux-notification \"$title\" \"$body\"")

    /** `termux-sms`: send an SMS (requires Termux:API + SMS permission). */
    fun termuxSms(number: String, body: String): Boolean =
        runFallbackRunCommand("termux-sms -n \"$number\" \"$body\"")

    /** `termux-tts-speak`: speak text. */
    fun termuxTts(text: String): Boolean =
        runFallbackRunCommand("termux-tts-speak \"$text\"")

    /**
     * RUN_COMMAND fallback: ask Termux:API's RunCommandService to run a
     * command in the live Termux session. Returns false when Termux:API is
     * not installed — the wizard installs it.
     */
    private fun runFallbackRunCommand(command: String): Boolean = try {
        val i = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND", command)
        }
        context.startService(i)
        true
    } catch (e: Exception) {
        false
    }

    private companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }

    private fun cliFrame(vararg pairs: Pair<String, String>): String {
        val obj = kotlinx.serialization.json.buildJsonObject {
            pairs.forEach { (k, v) ->
                put(k, json.parseToJsonElement(v))
            }
        }
        return obj.toString()
    }
}
