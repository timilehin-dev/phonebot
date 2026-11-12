package com.phonebot.wizard

import android.content.Context
import com.phonebot.termux.PackageInstallerFlow
import com.phonebot.termux.TermuxBridge

/**
 * SetupWizard — the first-run state machine.
 *
 * Each [Step] exposes:
 *  - [check]()   → Boolean: is this step satisfied?
 *  - [execute]() → StepResult: do the work (suspend — installs, deploys)
 *  - [repair]()  → StepResult: re-run after a failure, skipping what's already done
 *
 * [WizardState] tracks the current step and the last-failed step so the
 * SetupWizardScreen can render the node graph (done / active / failed /
 * pending) and offer "tap to retry" on failed nodes.
 *
 * The UI (SetupWizardScreen) binds to this via a StateFlow in a later phase;
 * Phase 1 keeps the state machine testable without Android deps.
 */
sealed class Step {
    abstract val id: String
    abstract val label: String
    abstract fun check(ctx: Context, bridge: TermuxBridge, installer: PackageInstallerFlow): Boolean
    abstract suspend fun execute(ctx: Context, bridge: TermuxBridge, installer: PackageInstallerFlow): StepResult
    abstract fun repair(ctx: Context, bridge: TermuxBridge, installer: PackageInstallerFlow): StepResult

    object InstallTermux : Step() {
        override val id = "install_termux"
        override val label = "Install Termux"
        override fun check(c: Context, b: TermuxBridge, i: PackageInstallerFlow) = b.isTermuxInstalled()
        override suspend fun execute(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            StepResult.Running // Later phase: i.downloadAndVerify(TERMUX) + installSession
        override fun repair(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            if (check(c, b, i)) StepResult.Done else StepResult.Running
    }

    object InstallTermuxApi : Step() {
        override val id = "install_termux_api"
        override val label = "Install Termux:API"
        override fun check(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            try { c.packageManager.getPackageInfo("com.termux.api", 0) != null } catch (e: Exception) { false }
        override suspend fun execute(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            StepResult.Running // Later phase: downloadAndVerify(TERMUX_API) + install
        override fun repair(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            if (check(c, b, i)) StepResult.Done else StepResult.Running
    }

    object DeployBotd : Step() {
        override val id = "deploy_botd"
        override val label = "Deploy botd"
        override fun check(c: Context, b: TermuxBridge, i: PackageInstallerFlow) = b.isBotdUp()
        override suspend fun execute(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            StepResult.Running // Later phase: deploy botd payload via SSH + start daemon
        override fun repair(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            if (check(c, b, i)) StepResult.Done else StepResult.Running
    }

    object RegisterBoot : Step() {
        override val id = "register_boot"
        override val label = "Register boot"
        override fun check(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            try { c.packageManager.getPackageInfo("com.termux.boot", 0) != null } catch (e: Exception) { false }
        override suspend fun execute(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            StepResult.Running // Later phase: install Termux:Boot + enable wake-on-boot profile
        override fun repair(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            if (check(c, b, i)) StepResult.Done else StepResult.Running
    }

    object EnableWakeLock : Step() {
        override val id = "enable_wakelock"
        override val label = "Enable wake-lock"
        override fun check(c: Context, b: TermuxBridge, i: PackageInstallerFlow) = false // TODO(later phase): create the first bot(later phase): probe via botd
        override suspend fun execute(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            StepResult.Running // Later phase: termux-wake-lock + battery-optimization exemption
        override fun repair(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            if (check(c, b, i)) StepResult.Done else StepResult.Running
    }

    object ConnectLlm : Step() {
        override val id = "connect_llm"
        override val label = "Connect LLM"
        override fun check(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            // Later phase: a stored API key exists in SecretVault and a ping
            //  to the LLM endpoint returns 200/401 (auth ok).
            false
        override suspend fun execute(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            StepResult.Running // Later phase: store key in SecretVault + verify with LlmClient
        override fun repair(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            if (check(c, b, i)) StepResult.Done else StepResult.Running
    }

    object OnboardBot : Step() {
        override val id = "onboard_bot"
        override val label = "Onboard bot"
        override fun check(c: Context, b: TermuxBridge, i: PackageInstallerFlow) = false // TODO(later phase): create the first bot
        override suspend fun execute(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            StepResult.Running // Later phase: create the first bot profile + allowlist
        override fun repair(c: Context, b: TermuxBridge, i: PackageInstallerFlow) =
            StepResult.Running
    }
}

/** Terminal states for a step execution. */
sealed interface StepResult {
    object Done : StepResult
    object Running : StepResult
    data class Failed(val reason: String, val retryable: Boolean = true) : StepResult
}

/**
 * Wizard state — what the UI needs to render the node graph.
 * Phase 1 is a plain data class; A later phase wraps it in a StateFlow owned
 * by a ViewModel so the SetupWizardScreen can observe it.
 */
data class WizardState(
    /** The step currently being executed. */
    val current: Step? = null,
    /** The last step that failed, if any (drives "tap to retry" in the UI). */
    val lastFailed: Step? = null,
    /** Steps known to be satisfied. */
    val done: Set<String> = emptySet(),
    /** Overall progress: 0.0-1.0 across all steps. */
    val progress: Float = 0f,
) {
    fun stateOf(step: Step): NodeState = when {
        step.id in done -> NodeState.Done
        step == current -> NodeState.Active
        step == lastFailed -> NodeState.Failed
        else -> NodeState.Pending
    }
}

enum class NodeState { Done, Active, Failed, Pending }

/**
 * Drives the state machine. The UI (SetupWizardScreen) calls
 * [advance] to move forward and [retry] to re-run a failed step.
 */
class SetupWizard(private val stateFlow: () -> WizardState = { WizardState() }) {

    var state: WizardState = WizardState()
        private set

    /** Advance to the next step and mark [doneStep] as satisfied. */
    fun advance(doneStep: Step) {
        state = state.copy(
            done = state.done + doneStep.id,
            lastFailed = null,
            progress = state.done.size.toFloat() / 7f,
        )
    }

    /** Retry a failed step. */
    fun retry(step: Step) {
        state = state.copy(current = step, lastFailed = null)
    }

    /** Record a step failure. */
    fun markFailed(step: Step, reason: String) {
        state = state.copy(current = null, lastFailed = step)
    }
}
