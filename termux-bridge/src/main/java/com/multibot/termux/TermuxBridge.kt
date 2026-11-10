package com.multibot.termux

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.UnixDomainSocketAddress
import java.net.StandardProtocolFamily
import java.net.SocketAddress
import java.net.Socket
import java.nio.charset.Charset

/**
 * TermuxBridge — IPC client between the app and the botd daemon in Termux.
 *
 * Transport:
 *  - Primary: abstract-namespace Unix domain socket @multibot
 *    (java.net.UnixDomainSocketAddress, API 28+; minSdk is 33 so always available).
 *  - Fallback when the socket is down: broadcast to Termux's
 *    com.termux/.RunCommandService so Termux:API still delivers commands.
 *
 * Protocol: JSON-lines — each line is one object:
 *  - request:  {"type":"…","id":"…","payload":{…}}
 *  - response: {"type":"…","id":"…","ok":true|false,"result":{…}|null}
 *  - stream:   {"type":"stream","id":"…","chunk":…}
 *
 * LLM gateway: botd has no direct network path of its own for LLM traffic in
 * Phase 1; it sends `llm_request` frames and this class forwards them to the
 * app's [com.multibot.core.LlmClient], streaming chunks back frame-by-frame.
 *
 * Termux:API helpers: thin shell wrappers for termux-notification,
 * termux-sms, termux-tts.
 */
class TermuxBridge(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Abstract-namespace socket name shared with the botd side. */
    companion object {
        const val SOCKET_NAME = "multibot"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_SERVICE = "com.termux.RunCommandService"
    }

    // ------------------------------------------------------------------
    // Low-level socket
    // ------------------------------------------------------------------

    /** Open a raw socket to @multibot. Throws if botd is not listening. */
    fun openSocket(): Socket {
        val addr = UnixDomainSocketAddress.of(SOCKET_NAME)
        return Socket(StandardProtocolFamily.UNIX).apply {
            soTimeout = 15_000
            connect(addr)
        }
    }

    private fun isSocketUp(): Boolean = try {
        openSocket().close(); true
    } catch (e: java.io.IOException) {
        false
    }

    // ------------------------------------------------------------------
    // JSON-lines request/response
    // ------------------------------------------------------------------

    /**
     * Send one request, wait for the matching response line, return it.
     * The socket is opened per-call for Phase 1 simplicity; Phase 2 keeps
     * a persistent connection with a background read loop.
     */
    fun sendRequest(type: String, payload: String = "{}"): String {
        val sock = openSocket()
        val out = BufferedOutputStream(sock.getOutputStream())
        val in = BufferedInputStream(sock.getInputStream())
        val id = java.util.UUID.randomUUID().toString()
        val line = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("type", type)
                put("id", id)
                put("payload", json.parseToJsonElement(payload))
            },
        )
        out.write((line + "\n").toByteArray(Charset.forName("UTF-8")))
        out.flush()

        val reader = java.io.BufferedReader(in.reader())
        val respLine = reader.readLine()
            ?: throw java.io.IOException("botd closed the socket before responding")
        val resp = json.parseToJsonElement(respLine).let { el ->
            el as? kotlinx.serialization.json.JsonObject
                ?: throw IllegalStateException("malformed botd response: $respLine")
        }
        require(resp["id"]?.let { it as kotlinx.serialization.json.JsonPrimitive }.content == id) {
            "response id mismatch"
        }
        sock.close()
        return respLine
    }

    /** Stream frames for a long-running request (e.g. tool invocations with progress). */
    fun sendRequestStream(type: String, payload: String = "{}"): Flow<String> = channelFlow {
        val sock = openSocket()
        val out = BufferedOutputStream(sock.getOutputStream())
        val reader = java.io.BufferedReader(sock.getInputStream().reader())
        val id = java.util.UUID.randomUUID().toString()
        val line = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("type", type)
                put("id", id)
                put("payload", json.parseToJsonElement(payload))
            },
        )
        out.write((line + "\n").toByteArray(Charset.forName("UTF-8")))
        out.flush()
        var l = reader.readLine()
        while (l != null && (json.parseToJsonElement(l) as? kotlinx.serialization.json.JsonObject)
                ?.get("type")?.let { it.toString() } != "\"done\"") {
            send(l)
            l = reader.readLine()
        }
        sock.close()
    }

    // ------------------------------------------------------------------
    // LLM gateway — forward botd's llm_request to the app's LlmClient
    // ------------------------------------------------------------------

    /**
     * botd emits `llm_request` frames; this method is called from the
     * bridge's read loop and streams [com.multibot.core.LlmChunk]s back
     * as `stream` frames to botd.
     *
     * Phase 1: the LlmClient instance is injected; wiring through botd's
     * socket read loop is Phase 2 work.
     */
    fun handleLlmRequest(
        requestJson: String,
        client: com.multibot.core.LlmClient,
        sendFrame: (String) -> Unit,
    ) {
        // Decode the botd frame into LlmMessage list, run chatStream,
        // and pump chunks back. Left as a clear stub for Phase 2.
        client.chatStream(
            messages = com.multibot.core.LlmClient.parseMessages(requestJson),
        ).collect { chunk ->
            sendFrame(json.encodeToString(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", "stream")
                    put("chunk", com.multibot.core.LlmClient.encodeChunk(chunk))
                },
            ))
        }
    }

    // ------------------------------------------------------------------
    // Termux:API shell helpers
    // ------------------------------------------------------------------

    /** termux-notification: "title\nbody" via the Termux:API app. */
    fun termuxNotification(title: String, body: String) =
        runTermuxApiCommand("termux-notification", listOf(title, body))

    /** termux-sms: send an SMS (requires Termux:API + SMS permission). */
    fun termuxSms(number: String, body: String) =
        runTermuxApiCommand("termux-sms", listOf("-n", number, body))

    /** termux-tts: speak text with the TTS engine. */
    fun termuxTts(text: String) =
        runTermuxApiCommand("termux-tts-speak", listOf(text))

    private fun runTermuxApiCommand(cmd: String, args: List<String>): Boolean {
        return if (isSocketUp()) {
            sendRequest("shell.run", json.encodeToString(
                kotlinx.serialization.json.buildJsonObject {
                    put("cmd", cmd); put("args", args)
                },
            )); true
        } else {
            // Fallback: Intent to Termux's RunCommandService for terminal execution.
            runFallbackRunCommand(cmd + " " + args.joinToString(" "))
        }
    }

    /**
     * RUN_COMMAND fallback: when the botd socket is down (botd not deployed,
     * Termux restarted, etc.) we ask the Termux:API RunCommandService to
     * run the command in the Termux terminal session instead.
     */
    private fun runFallbackRunCommand(command: String): Boolean = try {
        val i = Intent()
            .setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND", command)
        context.startService(i)
        true
    } catch (e: Exception) {
        false // Termux not installed — wizard step 1 is what fixes this
    }

    /** Detect Termux presence (QUERY_ALL_PACKAGES already granted). */
    fun isTermuxInstalled(): Boolean {
        return context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) != null
    }
}
