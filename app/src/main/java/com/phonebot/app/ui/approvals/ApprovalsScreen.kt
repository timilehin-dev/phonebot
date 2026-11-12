package com.phonebot.app.ui.approvals

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonebot.app.bridge.ApprovalSwipe
import com.phonebot.app.bridge.ApprovalSwipePolicy
import com.phonebot.design.DesignTokens
import com.phonebot.termux.ApprovalRequest
import com.phonebot.termux.ApprovalRisk
import kotlin.math.roundToInt

/**
 * ApprovalsScreen — risk-tiered list of pending human decisions.
 *
 * Bound to the app-level approvals store (Phase 2): live `needs-approval`
 * bus events appear here and Approve/Deny resolve by `corr_id` to resume
 * or deny the suspended tool call. UI remains decoupled from transport and
 * from the store implementation via plain state + callbacks.
 */
@Composable
fun ApprovalsScreen(
    pending: List<ApprovalRequest>,
    onApprove: (corrId: String) -> Unit,
    onDeny: (corrId: String) -> Unit,
    onBack: () -> Unit = {},
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = DesignTokens.Density.ScreenPad.dp),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Density.CardGap.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Approvals", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "${pending.size} pending",
                    style = MaterialTheme.typography.labelSmall,
                    color = DesignTokens.Chrome.TextFaint,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(Color(0xFF5A6272), "info")
                LegendDot(DesignTokens.Status.Waiting, "confirm")
                LegendDot(DesignTokens.Status.Critical, "critical")
            }
            Spacer(Modifier.height(4.dp))
        }

        if (pending.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
                    color = DesignTokens.Surfaces.SurfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "No pending approvals",
                            style = MaterialTheme.typography.titleSmall,
                            color = DesignTokens.Chrome.TextSecondary,
                        )
                        Text(
                            "Live needs-approval events from botd will appear here when the bridge is connected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DesignTokens.Chrome.TextFaint,
                        )
                    }
                }
            }
        } else {
            items(pending, key = { it.corrId }) { req ->
                when (req.risk) {
                    ApprovalRisk.Critical -> CriticalApprovalCard(
                        req = req,
                        onApprove = { onApprove(req.corrId) },
                        onDeny = { onDeny(req.corrId) },
                    )
                    ApprovalRisk.Confirm -> SwipeableConfirmRow(
                        req = req,
                        onApprove = { onApprove(req.corrId) },
                        onDeny = { onDeny(req.corrId) },
                    )
                    ApprovalRisk.Info -> InfoApprovalRow(req)
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = DesignTokens.Chrome.TextSecondary)
    }
}

@Composable
private fun CriticalApprovalCard(
    req: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = DesignTokens.Status.Critical.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp),
            ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(DesignTokens.Status.Critical, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(req.bot, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(4.dp), color = DesignTokens.Status.Critical.copy(alpha = 0.18f)) {
                    Text(
                        " CRITICAL ",
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = DesignTokens.Status.Critical,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Surface(shape = RoundedCornerShape(DesignTokens.Shape.BlockRadius), color = DesignTokens.Surfaces.Background) {
                Text(
                    req.command,
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = DesignTokens.Status.Critical,
                )
            }
            if (req.detail.isNotBlank()) {
                Text(req.detail, style = MaterialTheme.typography.bodySmall, color = DesignTokens.Chrome.TextSecondary)
            }
            Text(
                "corr_id: ${req.corrId}",
                style = MaterialTheme.typography.labelSmall,
                color = DesignTokens.Chrome.TextFaint,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApprovalButton("Approve", DesignTokens.Status.Ok, Modifier.weight(1f), onClick = onApprove)
                ApprovalButton("Deny", DesignTokens.Status.Critical, Modifier.weight(1f), onClick = onDeny)
            }
        }
    }
}

@Composable
private fun ApprovalButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(DesignTokens.Shape.BlockRadius),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            label,
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Confirm-tier row with swipe-to-resolve (Phase 2: notify → swipe → resolve).
 *
 * Only this tier is swipeable: [ApprovalSwipePolicy] refuses to resolve
 * `critical` requests, so a destructive action always needs the explicit
 * buttons. The gesture feeds the same `onApprove`/`onDeny` callbacks as the
 * buttons, so resolution goes through one path.
 */
@Composable
private fun SwipeableConfirmRow(
    req: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val policy = remember { ApprovalSwipePolicy() }
    var offsetPx by remember(req.corrId) { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(targetValue = offsetPx, label = "approval-swipe")

    Box(Modifier.fillMaxWidth()) {
        // Reveal layer: shows the decision the current drag would make.
        Row(
            Modifier.matchParentSize().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (animatedOffset > 0f) "APPROVE ▶" else "",
                style = MaterialTheme.typography.labelSmall,
                color = DesignTokens.Status.Ok,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (animatedOffset < 0f) "◀ DENY" else "",
                style = MaterialTheme.typography.labelSmall,
                color = DesignTokens.Status.Critical,
                fontWeight = FontWeight.Bold,
            )
        }

        Box(
            Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .pointerInput(req.corrId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val fraction = offsetPx / size.width.coerceAtLeast(1).toFloat()
                            when (policy.decide(fraction, req.risk)) {
                                ApprovalSwipe.Approve -> onApprove()
                                ApprovalSwipe.Deny -> onDeny()
                                ApprovalSwipe.None -> Unit
                            }
                            offsetPx = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetPx = (offsetPx + dragAmount)
                                .coerceIn(-size.width.toFloat(), size.width.toFloat())
                        },
                    )
                },
        ) {
            ConfirmApprovalRow(req = req, onApprove = onApprove, onDeny = onDeny)
        }
    }
}

@Composable
private fun ConfirmApprovalRow(
    req: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.SurfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(DesignTokens.Status.Waiting, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(req.bot, style = MaterialTheme.typography.titleSmall)
                    Text(
                        req.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = DesignTokens.Chrome.TextFaint,
                        maxLines = 2,
                    )
                }
            }
            if (req.detail.isNotBlank()) {
                Text(req.detail, style = MaterialTheme.typography.bodySmall, color = DesignTokens.Chrome.TextSecondary)
            }
            Text(
                "corr_id: ${req.corrId}",
                style = MaterialTheme.typography.labelSmall,
                color = DesignTokens.Chrome.TextFaint,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApprovalButton("Approve", DesignTokens.Status.Ok, Modifier.weight(1f), onClick = onApprove)
                ApprovalButton("Deny", DesignTokens.Status.Critical, Modifier.weight(1f), onClick = onDeny)
            }
        }
    }
}

@Composable
private fun InfoApprovalRow(req: ApprovalRequest) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(DesignTokens.Chrome.TextFaint, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(req.bot, style = MaterialTheme.typography.labelMedium, color = DesignTokens.Chrome.TextSecondary)
                Text(
                    req.command,
                    style = MaterialTheme.typography.bodySmall,
                    color = DesignTokens.Chrome.TextSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}
