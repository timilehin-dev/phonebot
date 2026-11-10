package com.multibot.app.ui.setup

import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multibot.design.DesignTokens

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
 *
 * Phase 1: the state machine in :wizard/SetupWizard.kt drives the step
 * results; this screen renders the node graph. No live Termux yet.
 */
@Composable
fun SetupWizardScreen(onBack: () -> Unit = {}) {
    val t = DesignTokens

    // Phase 1: hardcoded step states; replaced by wizard.WizardState in Phase 2.
    data class Step(
        val label: String,
        val detail: String,
        val state: StepState,
        val hue: Color,
    )

    enum class StepState(val color: Color) {
        Done(t.Status.Ok),
        Active(t.Status.Working),
        Failed(t.Status.Critical),
        Pending(t.Chrome.TextFaint),
    }

    val steps = remember {
        listOf(
            Step("Install Termux", "F-Droid · com.termux", StepState.Done, t.BotHues.H0),
            Step("Install Termux:API", "com.termux.api", StepState.Done, t.BotHues.H1),
            Step("Deploy botd", "git clone → make install", StepState.Failed, t.BotHues.H2),
            Step("Register boot", "Termux:Boot intent", StepState.Pending, t.BotHues.H3),
            Step("Enable wake-lock", "Termux:API wake-lock", StepState.Pending, t.BotHues.H4),
            Step("Connect LLM", "API key → Keystore", StepState.Pending, t.BotHues.H5),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = t.Density.ScreenPad.dp),
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
                    // TODO: tap re-runs the failed step via wizard.SetupWizard (Phase 2)
                }
            }
        }
    }
}

/**
 * Single wizard node:
 *   - circle (state color) with state icon
 *   - label + mono detail line
 *   - vertical connector line to the next node
 */
@Composable
private fun WizardNode(step: SetupWizardScreen.Step, isLast: Boolean, onReRun: () -> Unit) {
    val t = DesignTokens
    val isFailed = step.state == SetupWizardScreen.StepState.Failed

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Circle + connector column
        Column(
            Modifier.width(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(step.state.color.copy(alpha = if (isFailed) 1f else 0.25f), CircleShape)
                    .border(
                        width = 2.dp,
                        color = step.state.color,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when (step.state) {
                    SetupWizardScreen.StepState.Done -> Text("✓", color = t.Surfaces.Background,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    SetupWizardScreen.StepState.Active -> Text("●", color = step.hue,
                        style = MaterialTheme.typography.labelMedium)
                    SetupWizardScreen.StepState.Failed -> Text("✗", color = t.Surfaces.Background,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    SetupWizardScreen.StepState.Pending -> Text("·", color = t.Chrome.TextFaint,
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
                        .background(t.Chrome.OutlineSoft),
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
                    color = if (isFailed) t.Status.Critical else t.Chrome.Text,
                    fontWeight = if (isFailed) FontWeight.Bold else FontWeight.Normal,
                )
                if (isFailed) {
                    Spacer(Modifier.width(8.dp))
                    Text("tap to retry", style = MaterialTheme.typography.labelSmall,
                        color = t.Status.Critical, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                step.detail,
                style = MaterialTheme.typography.labelSmall,
                color = t.Chrome.TextFaint,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
