package com.phonebot.app

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.phonebot.app.bridge.BridgeRuntime
import com.phonebot.credentials.SecretVault

/**
 * Phonebot Application.
 *
 * The app is the control/UI/credential plane only — execution lives in Termux
 * (botd daemon). This class wires the app-side state stores that are safe to
 * initialize eagerly:
 *
 *  - [DataStore] (Preferences) for wizard progress, bot roster, UI prefs
 *  - [SecretVault] (Android Keystore) for LLM API keys + MCP connection secrets
 *
 * No LLM is ever loaded locally; cloud OpenAI-compatible endpoints only.
 */
class App : Application() {

    /** Process-lifetime handle to the app DataStore file. */
    val appDataStore by preferencesDataStore(name = "phonebot_app")

    /** Keystore-backed vault. API keys are encrypted here; the raw value never
     *  reaches the UI — only [com.phonebot.credentials.SecretHandle] references. */
    lateinit var secretVault: SecretVault
        private set

    /** Wizard step snapshot (current + last-failed). Phase 1 is placeholder-driven. */
    var wizardSnapshot: String? = null

    override fun onCreate() {
        super.onCreate()
        secretVault = SecretVault(this)
        // Start the persistent bridge subscriber (best-effort). It reconnects until
        // botd/sshd becomes reachable with configured credentials.
        BridgeRuntime.start()
        // TODO(Phase 2): start BotdNudgeService only when botd is actually deployed
        // (checked via TermuxBridge socket ping); boot receiver handles the post-boot path.
        // TODO(Phase 2): seed DataStore with bot roster + allowlists from botd registry.json.
    }

    /** Kick off the foreground service that nudges botd (used by boot receiver + manual). */
    fun startBotdNudgeService() {
        // BotdNudgeService is declared in AndroidManifest.xml; Phase 1 is a stub that
        // just posts a notification so the wiring is testable without a live Termux socket.
        startForegroundService(
            android.content.Intent(this, com.phonebot.app.service.BotdNudgeService::class.java)
        )
    }
}
