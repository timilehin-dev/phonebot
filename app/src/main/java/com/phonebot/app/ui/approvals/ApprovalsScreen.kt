package com.phonebot.app.ui.approvals

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonebot.design.DesignTokens

/**
 * ApprovalsScreen — risk-tiered list of pending human decisions.
 *
 *  Legend (top):
 *   ·  INFO      — informational, no action needed (grey)
 *   ·  CONFIRM   — routine action, one-tap confirm (blue)
 *   ·  CRITICAL  — irreversible / destructive, expanded card with Approve/Deny/Ask
 *
 *  Expanded critical card:
 *   - Bot name + command in a mono block
 *   - Three action buttons: Approve / Deny / Ask (defer to next cycle)
 *
 *  Collapsed confirm cards: single-tap row with checkmark.
 *
 * Phase 1: placeholder items; live queue arrives from botd RPC in Phase 2.
 */
@Composable
fun ApprovalsScreen(onBack: () -> Unit = {}) {
    val t = DesignTokens
    var expandedCritical by remember { mutableStateOf(true) }
    var confirmState by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = DesignTokens.Density.ScreenPad.dp),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Density.CardGap.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        // Header
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Approvals", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text("3 pending", style = MaterialTheme.typography.labelSmall, color = DesignTokens.Chrome.TextFaint)
            }
            Spacer(Modifier.height(8.dp))
        }

        // Legend
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(Color(0xFF5A6272), "info")
                LegendDot(DesignTokens.Status.Waiting, "confirm")
                LegendDot(DesignTokens.Status.Critical, "critical")
            }
            Spacer(Modifier.height(4.dp))
        }

        // Critical cards (expanded)
        items(placeholderCritical.size) { i ->
            val req = placeholderCritical[i]
            CriticalApprovalCard(req, expanded = expandedCritical)
        }

        // Confirm cards (collapsed, one-tap)
        items(placeholderConfirm.size) { i ->
            val req = placeholderConfirm[i]
            ConfirmApprovalRow(req, approved = confirmState && i == 0)
        }

        // Info rows
        items(placeholderInfo.size) { i ->
            InfoApprovalRow(placeholderInfo[i])
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Legend
// ---------------------------------------------------------------------------

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = DesignTokens.Chrome.TextSecondary)
    }
}

// ---------------------------------------------------------------------------
// Placeholder data
// ---------------------------------------------------------------------------

private data class ApprovalRequest(
    val id: String,
    val bot: String,
    val botHue: Color,
    val command: String,
    val detail: String,
)

private val placeholderCritical = listOf(
    ApprovalRequest("c1", "sable", DesignTokens.BotHues.H1,
        "rm -rf /data/data/com.myapp/files/build/",
        "Sable wants to clear the build directory before the nightly deploy"),
    ApprovalRequest("c2", "atlas", DesignTokens.BotHues.H0,
        "DROP TABLE price_snapshots_2025;",
        "Atlas is decommissioning the 2025 price snapshot table"),
)

private val placeholderConfirm = listOf(
    ApprovalRequest("f1", "vex", DesignTokens.BotHues.H2,
        "curl -s https://api.example.com/deploy -X POST",
        "Vex is triggering the staging deploy"),
    ApprovalRequest("f2", "onyx", DesignTokens.BotHues.H3,
        "termux-sms 555-0101 \"Build passed\"",
        "Onyx is sending a status SMS"),
)

private val placeholderInfo = listOf(
    ApprovalRequest("i1", "vex", DesignTokens.BotHues.H2,
        "log: nightly digest compiled · 0 warnings", "Informational only"),
)

// ---------------------------------------------------------------------------
// Card composables
// ---------------------------------------------------------------------------

/**
 * Critical approval card: expanded by default. Shows the command in a
 * monospace block plus Approve / Deny / Ask buttons.
 */
@Composable
private fun CriticalApprovalCard(req: ApprovalRequest, expanded: Boolean) {
    val t = DesignTokens
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.Surface,
        modifier = Modifier.fillMaxWidth().borderCritical(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(req.botHue, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(req.bot, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(4.dp), color = DesignTokens.Status.Critical.copy(alpha = 0.18f)) {
                    Text(" CRITICAL ", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = DesignTokens.Status.Critical,
                        fontWeight = FontWeight.Bold)
                }
            }
            if (expanded) {
                // Mono command block
                Surface(shape = RoundedCornerShape(DesignTokens.Shape.BlockRadius), color = DesignTokens.Surfaces.Background) {
                    Text(
                        req.command,
                        Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = DesignTokens.Status.Critical,
                    )
                }
                Text(req.detail, style = MaterialTheme.typography.bodySmall, color = DesignTokens.Chrome.TextSecondary)
                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApprovalButton("Approve", DesignTokens.Status.Ok, Modifier.weight(1f))
                    ApprovalButton("Deny", DesignTokens.Status.Critical, Modifier.weight(1f))
                    ApprovalButton("Ask", DesignTokens.Status.Waiting, Modifier.weight(1f))
                }
                // TODO: wire Approve/Deny/Ask to the botd approval RPC (Phase 2).
            }
        }
    }
}

@Composable
private fun Modifier.borderCritical(): Modifier =
    this.border(width = 1.dp, color = DesignTokens.Status.Critical.copy(alpha = 0.5f), shape = RoundedCornerShape(20.dp))

@Composable
private fun ApprovalButton(label: String, color: Color, modifier: Modifier = Modifier) {
    val t = DesignTokens
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(DesignTokens.Shape.BlockRadius),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            label,
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Collapsed confirm row — single tap. */
@Composable
private fun ConfirmApprovalRow(req: ApprovalRequest, approved: Boolean) {
    val t = DesignTokens
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = if (approved) DesignTokens.Surfaces.Surface else DesignTokens.Surfaces.SurfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(
                if (approved) DesignTokens.Status.Ok else DesignTokens.Status.Waiting, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(req.bot, style = MaterialTheme.typography.titleSmall)
                Text(req.command, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, color = DesignTokens.Chrome.TextFaint, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (approved) "✓ approved" else "confirm",
                style = MaterialTheme.typography.labelSmall,
                color = if (approved) DesignTokens.Status.Ok else DesignTokens.Status.Waiting,
            )
        }
    }
}

/** Info-only row, no action. */
@Composable
private fun InfoApprovalRow(req: ApprovalRequest) {
    val t = DesignTokens
    Surface(shape = RoundedCornerShape(DesignTokens.Shape.CardRadius), color = DesignTokens.Surfaces.Surface) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(DesignTokens.Chrome.TextFaint, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(req.command, style = MaterialTheme.typography.bodySmall,
                color = DesignTokens.Chrome.TextSecondary, maxLines = 1)
        }
    }
}
