package com.phonebot.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonebot.design.DesignTokens

/**
 * SetupWizardScreen — vertical node graph for the first-run wizard.
 *
 * Each step is a circle in a vertical chain:
 *   ·  done    — filled with the bot hue, checkmark
 *   ·  active  — ring pulse, amber highlight
 *   ·  failed  — red fill, tap to re-run
 *   ·  pending — hollow, grey
 *
 * Steps (in order):
 *   1. Install Termux
 *   2. Install Termux:API
 *   3. Deploy botd
 *   4. Register boot
 *   5. Enable wake-lock
 *   6. Connect LLM
 *   7. Onboard bot
 *
 * Phase 1: the state machine in :wizard/SetupWizard.kt drives the step
 * results; this screen renders the node graph.
 */
@Composable
fun SetupWizardScreen(onBack: () -> Unit = {}) {
    val t = DesignTokens

    // Phase 1: hardcoded step states; replaced by wizard.WizardState in a later phase.
    val steps = remember {
        listOf(
            WizardStep("Install Termux", "F-Droid · com.termux", WizardStepState.Done, DesignTokens.BotHues.H0),
            WizardStep("Install Termux:API", "com.termux.api", WizardStepState.Done, DesignTokens.BotHues.H1),
            WizardStep("Deploy botd", "payload → ~/phonebot", WizardStepState.Failed, DesignTokens.BotHues.H2),
            WizardStep("Register boot", "Termux:Boot intent", WizardStepState.Pending, DesignTokens.BotHues.H3),
            WizardStep("Enable wake-lock", "termux-wake-lock", WizardStepState.Pending, DesignTokens.BotHues.H4),
            WizardStep("Connect LLM", "API key → Keystore", WizardStepState.Pending, DesignTokens.BotHues.H5),
            WizardStep("Onboard bot", "first bot + chief", WizardStepState.Pending, DesignTokens.BotHues.H0),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = DesignTokens.Density.ScreenPad.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 12.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            steps.forEachIndexed { i, step ->
                WizardNode(step = step, isLast = i == steps.lastIndex) {
                    // TODO(later phase): tap re-runs the failed step via wizard.SetupWizard
                }
            }
        }
    }
}

/** One wizard step as rendered by the node graph. */
private data class WizardStep(
    val label: String,
    val detail: String,
    val state: WizardStepState,
    val hue: Color,
)

/** Visual state of a wizard node. */
private enum class WizardStepState(val color: Color) {
    Done(DesignTokens.Status.Ok),
    Active(DesignTokens.Status.Working),
    Failed(DesignTokens.Status.Critical),
    Pending(DesignTokens.Chrome.TextFaint),
}

/**
 * Single wizard node:
 *   - circle (state color) with state icon
 *   - label + mono detail line
 *   - vertical connector line to the next node
 */
@Composable
private fun WizardNode(step: WizardStep, isLast: Boolean, onReRun: () -> Unit) {
    val t = DesignTokens
    val isFailed = step.state == WizardStepState.Failed

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Circle + connector column
        Column(
            Modifier.width(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(
                        step.state.color.copy(alpha = if (isFailed) 1f else 0.25f),
                        CircleShape,
                    )
                    .border(
                        width = 2.dp,
                        color = step.state.color,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when (step.state) {
                    WizardStepState.Done -> Text("✓", color = DesignTokens.Surfaces.Background,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    WizardStepState.Active -> Text("●", color = step.hue,
                        style = MaterialTheme.typography.labelMedium)
                    WizardStepState.Failed -> Text("✗", color = DesignTokens.Surfaces.Background,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    WizardStepState.Pending -> Text("·", color = DesignTokens.Chrome.TextFaint,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            // Connector line to next node
            if (!isLast) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(2.dp)
                        .height(48.dp)
                        .background(DesignTokens.Chrome.OutlineSoft),
                )
            }
        }
        Spacer(Modifier.width(12.dp))

        // Label block
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    step.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFailed) DesignTokens.Status.Critical else DesignTokens.Chrome.Text,
                    fontWeight = if (isFailed) FontWeight.Bold else FontWeight.Normal,
                )
                if (isFailed) {
                    Spacer(Modifier.width(8.dp))
                    Text("tap to retry", style = MaterialTheme.typography.labelSmall,
                        color = DesignTokens.Status.Critical, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                step.detail,
                style = MaterialTheme.typography.labelSmall,
                color = DesignTokens.Chrome.TextFaint,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
