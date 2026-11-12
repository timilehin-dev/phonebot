package com.phonebot.app

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.phonebot.app.bridge.ApprovalNotificationCoordinator
import com.phonebot.app.bridge.BridgeRuntime
import com.phonebot.app.bridge.SystemApprovalNotifier
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
        // needs-approval -> one high-priority notification (deduped, cancelled on resolve).
        BridgeRuntime.setApprovalObserver(
            ApprovalNotificationCoordinator(SystemApprovalNotifier(this))
        )
        // Start the persistent bridge subscriber (best-effort). It reconnects until
        // botd/sshd becomes reachable with the SSH credential stored in the vault.
        BridgeRuntime.startFromVault(secretVault)
        // The post-boot reconnect is owned by BootCompletedReceiver →
        // BotdNudgeService, which reconnects the subscriber and pings botd.
        // The roster is read live from botd's `get_state`, so there is no
        // app-side copy to seed: DataStore keeps wizard/UI preferences only.
    }

    /** Kick off the foreground service that reconnects + pings botd (boot receiver / manual). */
    fun startBotdNudgeService() {
        // Bounded: BotdNudgeService stops itself once botd answers or retries run out.
        startForegroundService(
            android.content.Intent(this, com.phonebot.app.service.BotdNudgeService::class.java)
        )
    }
}
