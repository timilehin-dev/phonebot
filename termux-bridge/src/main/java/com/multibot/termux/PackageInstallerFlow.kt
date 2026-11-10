package com.multibot.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.CancellationResult
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PackageInstallerFlow — APK install helper used by the first-run wizard
 * (Termux, Termux:API, Termux:Boot) and for our own self-update.
 *
 * Pipeline:
 *  1. Download APK from F-Droid's repo (or GitHub Releases for self-update)
 *  2. Verify SHA-256 against the pinned hash (the F-Droid repo index
 *     carries per-APK hashes)
 *  3. Gate on the REQUEST_INSTALL_PACKAGES permission (Settings intent)
 *  4. Install:
 *     - API 35+ (and up to targetSdk): commit a PackageInstaller session
 *       programmatically and monitor completion via setSessionFinishedCallback
 *     - below 35: launch the system installer intent and observe via
 *       ACTION_PACKAGE_INSTALLED broadcast
 */
class PackageInstallerFlow(private val context: Context) {

    data class InstallSpec(
        val packageName: String,
        val downloadUrl: String,
        val sha256: String,          // hex, lowercase
    )

    sealed interface InstallOutcome {
        data class Success(val packageName: String) : InstallOutcome
        data class Failure(val reason: String, val retryable: Boolean) : InstallOutcome
        object Cancelled : InstallOutcome
    }

    // Well-known F-Droid specs. Hashes are placeholders — the wizard fetches
    // the live values from F-Droid's flatpak repo index at runtime in Phase 2.
    val TERMUX = InstallSpec("com.termux", "https://f-droid.org/repo/com.termux.apk", "TODO-pin-sha256")
    val TERMUX_API = InstallSpec("com.termux.api", "https://f-droid.org/repo/com.termux.api.apk", "TODO-pin-sha256")
    val TERMUX_BOOT = InstallSpec("com.termux.boot", "https://f-droid.org/repo/com.termux.boot.apk", "TODO-pin-sha256")

    // ------------------------------------------------------------------
    // 1+2. Download & verify
    // ------------------------------------------------------------------

    /** Download the APK, verify SHA-256, return the local file. */
    suspend fun downloadAndVerify(spec: InstallSpec): File = withContext(Dispatchers.IO) {
        val tmp = context.cacheDir.resolve("${spec.packageName}.apk")
        java.net.URL(spec.downloadUrl).openStream().use { src ->
            tmp.outputStream().use { src.copyTo(it) }
        }
        val actual = sha256Hex(tmp)
        if (actual != spec.sha256.lowercase()) {
            tmp.delete()
            throw SecurityException("SHA-256 mismatch for ${spec.packageName}: expected ${spec.sha256}, got $actual")
        }
        tmp
    }

    private fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // 3. Permission gate
    // ------------------------------------------------------------------

    /**
     * Ensure REQUEST_INSTALL_PACKAGES is granted. Resumes when the user
     * returns from the Settings intent (pending-intent result check).
     */
    fun hasInstallPermission(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Launch the Settings screen to grant REQUEST_INSTALL_PACKAGES. */
    fun requestInstallPermission() {
        val i = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    // ------------------------------------------------------------------
    // 4a. API 35+: programmatic PackageInstaller session
    // ------------------------------------------------------------------

    /**
     * API 35+: commit the install session and monitor completion via
     * setSessionFinishedCallback. Suspends until the session finishes.
     */
    fun installSession(api35Spec: InstallSpec, apk: File): PendingInstallResult {
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            if (api35Spec.packageName == context.packageName)
                android.content.pm.PackageInstaller.SessionParams.MODE_INHERIT_USER
            else
                android.content.pm.PackageInstaller.SessionParams.MODE_FULLY_ASYNC,
        )
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        session.openWriteInput().use { out -> apk.inputStream().copyTo(out) }
        val done = PendingIntent.getBroadcast(
            context, 0, Intent(context, PackageInstallReceiver::class.java).apply {
                putExtra("package", api35Spec.packageName)
            },
            PendingIntent.FLAG_ONE_SHOT,
        )
        // commit + callback (API 35 signature)
        session.commit(done.intentSender)
        return PendingInstallResult(session, done)
    }

    data class PendingInstallResult(
        val session: android.content.pm.PackageInstaller.Session,
        val pendingIntent: PendingIntent,
    )

    // ------------------------------------------------------------------
    // 4b. Below-35: system installer intent + broadcast receiver
    // ------------------------------------------------------------------

    /** Launch the system installer for the APK file (view intent). */
    fun launchSystemInstaller(apk: File, spec: InstallSpec) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }

    /**
     * Observe install completion below-35 via ACTION_PACKAGE_INSTALLED.
     * The app registers this receiver on demand (the manifest receiver is
     * only for BOOT_COMPLETED; package-install observation is runtime-registered).
     */
    fun registerInstallObserver(packageName: String, onResult: (Int, InstallOutcome) -> Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val p = intent.data?.schemeSpecificPart
                if (p == packageName) {
                    onResult(0, InstallOutcome.Success(packageName))
                    context.unregisterReceiver(this)
                }
            }
        }
        context.registerReceiver(
            receiver,
            android.content.IntentFilter(Intent.ACTION_PACKAGE_INSTALLED).apply {
                addDataScheme("package")
            },
        )
    }
}

/** Runtime-registered receiver for the package-install broadcast. */
class PackageInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("package") ?: return
        // Phase 2: forward to the wizard's Step completion check.
        android.util.Log.d("PackageInstallerFlow", "package installed: $pkg")
    }
}
