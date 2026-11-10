package com.multibot.termux

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/**
 * BotdSshClient — the app-side transport for the botd control plane.
 *
 * Why SSH and not a direct Unix socket?
 *  - The botd daemon binds the *abstract-namespace* socket `@multibot`,
 *    which is namespace-wide and UID-gated: an app user cannot reach a
 *    Termux-user socket from its own process. The device-reliable,
 *    no-root, no-Shizuku path is to *shell into Termux* and run the
 *    in-Termux socket client (`python -m botd.cli`), exactly like
 *    Zafiro's Termux/SSH remote backend.
 *  - Termux's own sshd (`termux-setup-ssh`, port 8022) survives reboot
 *    via Termux:Boot, so this transport inherits the same durability as
 *    the daemon it carries.
 *
 * Auth:
 *  - [SshAuth.Key]   — an Ed25519 keypair generated in the app; the
 *    public half is installed into Termux via `termux-ssh-auth -a`
 *    (Termux:API), the private half is stored encrypted in the
 *    Android Keystore vault (see [com.multibot.credentials.SecretVault]).
 *  - [SshAuth.Password] — Phase-1 wizard fallback (user's Termux passwd).
 *
 * Every [cli] call runs one short-lived `exec` channel; the reply is a
 * single JSON line on stdout, parsed into an [IpcReply].
 */
class BotdSshClient(
    /** Termux sshd host. Device-local sshd, default 8022. */
    val host: String = "127.0.0.1",
    val port: Int = 8022,
    /**
     * The Termux account that owns `~/multibot` (the daemon's home).
     * Termux's sshd accepts the package's user; `termux` on stock F-Droid
     * builds, the OS user name elsewhere. Overridable by the wizard.
     */
    val user: String = "termux",
    private val auth: SshAuth,
    /** Deployed botd root (`botd-install.sh` default: `~/multibot`). */
    val botdHome: String = "~/multibot",
    private val connectTimeoutMs: Int = 15_000,
) {

    /** Authentication material. The raw bytes never leave this class. */
    sealed interface SshAuth {
        /** PEM private key. Wizard-generated; public half pushed via termux-ssh-auth. */
        data class Key(val privateKeyPem: String) : SshAuth
        /** Termux account password (`passwd` in Termux). Wizard fallback. */
        data class Password(val password: String) : SshAuth
    }

    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    // ------------------------------------------------------------------
    // Raw exec
    // ------------------------------------------------------------------

    /**
     * Run one shell command in a Termux session and return its output.
     * [timeoutMs] bounds the *whole* operation (connect + remote run).
     */
    fun exec(command: String, timeoutMs: Long = 60_000): ExecResult {
        val session = newSession()
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        channel.setOutStream(out)
        channel.setErrStream(err)
        try {
            session.connect(connectTimeoutMs)
            channel.connect(connectTimeoutMs)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(100)
            }
            val exit = if (channel.isClosed) channel.exitStatus else -1
            return ExecResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        } finally {
            runCatching { channel.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    /**
     * Build one JSch session for [host]:[port] as [user], using the
     * configured [auth]. A fresh connection per call is Phase-1 correct:
     * it is cheap (loopback sshd, no NAT) and there is no shared state to
     * keep alive.
     */
    private fun newSession(): Session {
        val jsch = JSch()
        when (val a = auth) {
            is SshAuth.Key -> jsch.addIdentity(null, a.privateKeyPem.encodeToByteArray())
            is SshAuth.Password -> { /* set on the session below */ }
        }
        val session = jsch.getSession(user, host, port)
        (auth as? SshAuth.Password)?.let { session.password = it.password }
        // Termux sshd: no host-key verification for a loopback-only daemon.
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("UserKnownHostsFile", "/dev/null")
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        session.connectTimeout = connectTimeoutMs
        return session
    }

    /** Liveness: can we reach Termux's sshd at all? */
    fun sshdReachable(): Boolean = runCatching {
        exec("true", 15_000).ok
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // botd CLI (the socket client, executed inside Termux)
    // ------------------------------------------------------------------

    /**
     * Build the remote shell command that runs the in-Termux CLI. Tilde in
     * [botdHome] is left unquoted so the remote shell expands `~/multibot`
     * to the Termux home. If the home is missing the CLI has not been
     * deployed yet → a structured error + non-zero exit, which [cli] turns
     * into a "degraded" null.
     */
    private fun pythonCmd(cliArgs: String): String =
        "cd $botdHome 2>/dev/null || { echo '{\"type\":\"error\",\"code\":\"botd_home_missing\"}'; exit 1; }" +
            " && { python -m botd.cli $cliArgs || python3 -m botd.cli $cliArgs; }"

    /**
     * Run one control-plane call: `python -m botd.cli <type> '<json>'`.
     * The CLI opens the `@multibot` abstract socket (it runs as the Termux
     * user, so the namespace + UID checks pass) and prints the reply line.
     *
     * Returns null when the transport is degraded (sshd down, not deployed,
     * botd home missing) — the caller decides how to surface that.
     */
    fun cli(type: String, argsJson: String = "{}", timeoutMs: Long = 60_000): IpcReply? {
        val shell = pythonCmd("$type '${argsJson.replace("'", "'\\''")}'")
        val res = exec(shell, timeoutMs)
        if (!res.ok) {
            // A structured error from the shell (missing home) still has a JSON body.
            val line = res.stdout.lines().lastOrNull { it.trimStart().startsWith("{") }
                ?: return null
            return IpcReply.parse(type, "", line, JSON)
        }
        val line = res.stdout.lines().lastOrNull { it.trimStart().startsWith("{") }
            ?: return null
        return IpcReply.parse(type, "", line, JSON)
    }

    /** Ping the daemon (`get_state` is the cheapest real round trip). */
    fun isBotdUp(): Boolean = runCatching {
        cli("ping", "{}", 15_000)?.let {
            it.ok && it.raw["ok"]?.let { p -> (p as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } ?: false
        } ?: false
    }.getOrDefault(false)

    companion object {
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
