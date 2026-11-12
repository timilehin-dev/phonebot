package com.phonebot.app.ui.observatory

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import com.phonebot.app.bridge.BotRow
import com.phonebot.app.bridge.BotStatus
import com.phonebot.app.bridge.ObservatoryEvent
import com.phonebot.app.bridge.ObservatoryEventKind
import com.phonebot.app.bridge.ObservatoryUiState
import com.phonebot.design.DesignTokens
import com.phonebot.termux.ApprovalRequest
import com.phonebot.termux.ApprovalRisk

/**
 * Observatory — the home screen. Three bands:
 *
 *  1. Digest strip: fleet-level summary from the live bridge.
 *  2. Bot crew grid: one card per bot from botd's `get_state` roster.
 *  3. Live activity: real `bus_event` task/result/cron/approval traffic.
 *  4. Needs-you: the most urgent pending approval.
 *
 * Data arrives as [ObservatoryUiState] through [com.phonebot.app.bridge.ObservatoryDataSource],
 * so the UI can be re-themed or backed by a different source later without
 * touching botd/JSON.
 */
@Composable
fun ObservatoryScreen(
    state: ObservatoryUiState,
    onRefresh: () -> Unit = {},
    onBotSelected: (botId: String) -> Unit,
) {
    var filter by remember { mutableStateOf(ObservatoryFilter.All) }
    LaunchedEffect(Unit) { onRefresh() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DesignTokens.Density.ScreenPad.dp),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Density.CardGap.dp),
    ) {
        item { DigestStrip(state, onRefresh) }
        item { FilterChipRow(selected = filter, onSelect = { filter = it }) }
        item { CrewSection(state, filter, onBotSelected) }
        item { SectionHeader("Activity", "${state.events.size} recent") }
        if (state.events.isEmpty()) {
            item {
                EmptyCard(
                    title = if (state.online) "No bus activity yet" else "botd offline",
                    body = state.error
                        ?: "Task/result traffic between bots will appear here as it happens.",
                )
            }
        } else {
            items(state.events, key = { it.id }) { event -> ActivityRow(event) }
        }
        item { NeedsYouStack(state.pending) }
        item { Spacer(modifier = Modifier.height(64.dp)) } // room for bottom nav
    }
}

// ---------------------------------------------------------------------------
// Band 1: digest strip
// ---------------------------------------------------------------------------

@Composable
private fun DigestStrip(state: ObservatoryUiState, onRefresh: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.Surface,
        contentColor = DesignTokens.Chrome.Text,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fleet", style = MaterialTheme.typography.labelSmall, color = DesignTokens.Chrome.TextFaint)
                Spacer(Modifier.weight(1f))
                Text(
                    "refresh",
                    style = MaterialTheme.typography.labelSmall,
                    color = DesignTokens.Chrome.TextSecondary,
                    modifier = Modifier.clickable(onClick = onRefresh),
                )
            }
            DigestLine(
                "${state.crew.size} bots · ${state.pending.size} waiting on you",
                if (state.pending.isEmpty()) DesignTokens.Status.Ok else DesignTokens.Status.Waiting,
            )
            DigestLine(
                "${state.events.size} events · " + if (state.online) "live bridge" else "botd offline",
                if (state.online) DesignTokens.Status.Ok else DesignTokens.Status.Critical,
            )
        }
    }
}

@Composable
private fun DigestLine(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = DesignTokens.Chrome.TextSecondary)
    }
}

// ---------------------------------------------------------------------------
// Filter chips
// ---------------------------------------------------------------------------

private enum class ObservatoryFilter(val label: String) {
    All("All"),
    Working("Working"),
    Waiting("Waiting"),
    Approval("Needs approval"),
}

@Composable
private fun FilterChipRow(selected: ObservatoryFilter, onSelect: (ObservatoryFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ObservatoryFilter.entries.forEach { f ->
            val isSel = f == selected
            Surface(
                shape = RoundedCornerShape(DesignTokens.Shape.ChipRadius),
                color = if (isSel) DesignTokens.Surfaces.SurfaceHigh else DesignTokens.Surfaces.SurfaceContainer,
                modifier = Modifier.clickable { onSelect(f) },
            ) {
                Text(
                    text = " ${f.label} ",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) DesignTokens.Chrome.Text else DesignTokens.Chrome.TextSecondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Band 2: crew grid + bot card
// ---------------------------------------------------------------------------

private data class CrewMember(
    val id: String,
    val name: String,
    val hue: Color,
    val activity: String,
    val statusLabel: String,   // working | waiting | idle
    val approvalBadge: String?,
)

private fun BotRow.toCrewMember(index: Int, pending: List<ApprovalRequest>): CrewMember {
    val badge = pending.firstOrNull { it.bot.equals(id, ignoreCase = true) }?.command
    val statusLabel = when (status) {
        BotStatus.Running -> "working"
        BotStatus.WaitingApproval -> "waiting"
        BotStatus.Paused -> "idle"
        BotStatus.Idle -> "idle"
    }
    val activity = currentTask.ifBlank {
        when (status) {
            BotStatus.Running -> "working"
            BotStatus.WaitingApproval -> "awaiting approval"
            BotStatus.Paused -> "paused"
            BotStatus.Idle -> "idle"
        }
    }
    return CrewMember(
        id = id,
        name = label,
        hue = DesignTokens.BotHues.forIndex(index),
        activity = activity,
        statusLabel = statusLabel,
        approvalBadge = badge,
    )
}

@Composable
private fun CrewSection(
    state: ObservatoryUiState,
    filter: ObservatoryFilter,
    onBotSelected: (String) -> Unit,
) {
    val crew = state.crew.mapIndexed { index, bot -> bot.toCrewMember(index, state.pending) }
    val visible = when (filter) {
        ObservatoryFilter.All -> crew
        ObservatoryFilter.Working -> crew.filter { it.statusLabel == "working" }
        ObservatoryFilter.Waiting -> crew.filter { it.statusLabel == "waiting" }
        ObservatoryFilter.Approval -> crew.filter { it.approvalBadge != null }
    }
    if (visible.isEmpty()) {
        EmptyCard(
            title = when {
                !state.online -> "botd offline"
                state.crew.isEmpty() -> "No bots yet"
                else -> "Nothing in this filter"
            },
            body = state.error
                ?: if (state.online) "Create a bot or adjust the filter."
                else "Start botd, then refresh to load the roster.",
        )
    } else {
        CrewGrid(visible, onBotSelected)
    }
}

@Composable
private fun CrewGrid(members: List<CrewMember>, onBotSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Density.CardGap.dp)) {
        members.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Density.CardGap.dp)) {
                pair.forEach { m ->
                    BotCard(m, Modifier.weight(1f), onBotSelected = { onBotSelected(m.id) })
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BotCard(
    bot: CrewMember,
    modifier: Modifier = Modifier,
    onBotSelected: (String) -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.Surface,
        contentColor = DesignTokens.Chrome.Text,
        onClick = { onBotSelected(bot.id) },
        enabled = true,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarRing(initial = bot.name.take(1), hue = bot.hue)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(bot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        bot.activity,
                        style = MaterialTheme.typography.bodySmall,
                        color = DesignTokens.Chrome.TextSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                StatusIndicator(status = bot.statusLabel)
            }
            if (bot.approvalBadge != null) {
                Surface(
                    shape = RoundedCornerShape(DesignTokens.Shape.BlockRadius),
                    color = DesignTokens.Status.Critical.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = " ✋ ${bot.approvalBadge} ",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = DesignTokens.Status.Critical,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarRing(initial: String, hue: Color) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(40.dp)
                .border(width = 2.dp, color = hue, shape = CircleShape)
                .background(DesignTokens.Surfaces.SurfaceContainer, CircleShape),
        )
        Text(initial, style = MaterialTheme.typography.titleMedium, color = hue, fontWeight = FontWeight.Bold)
    }
}

/** Scanline animation for working; pulse for waiting; static dot for idle. */
@Composable
private fun StatusIndicator(status: String) {
    when (status) {
        "working" -> ScanlineIndicator(DesignTokens.Status.Working)
        "waiting" -> PulseDot(DesignTokens.Status.Waiting)
        else -> IdleDot(DesignTokens.Status.Idle)
    }
}

@Composable
private fun ScanlineIndicator(color: Color) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(DesignTokens.Motion.ScanlineMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanline-progress",
    )
    Box(
        Modifier
            .size(width = 40.dp, height = 8.dp)
            .background(DesignTokens.Surfaces.SurfaceHigh, RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .width(10.dp)
                .height(8.dp)
                .background(color, RoundedCornerShape(2.dp))
                .offset(x = (progress * 30).dp, y = 0.dp),
        )
    }
}

@Composable
private fun PulseDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(DesignTokens.Motion.PulseMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )
    Box(Modifier.size(8.dp).background(color.copy(alpha = alpha), CircleShape))
}

@Composable
private fun IdleDot(color: Color) {
    Box(Modifier.size(8.dp).background(color.copy(alpha = 0.4f), CircleShape))
}

// ---------------------------------------------------------------------------
// Band 3: live activity feed (real bus events)
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = DesignTokens.Chrome.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.labelSmall, color = DesignTokens.Chrome.TextFaint)
    }
}

@Composable
private fun ActivityRow(event: ObservatoryEvent) {
    val (label, color) = when (event.kind) {
        ObservatoryEventKind.Task -> "task" to DesignTokens.Status.Waiting
        ObservatoryEventKind.Result -> "result" to DesignTokens.Status.Ok
        ObservatoryEventKind.CronResult -> "cron" to DesignTokens.Status.Working
        ObservatoryEventKind.Approval -> "approval" to DesignTokens.Status.Critical
        ObservatoryEventKind.Info -> "fyi" to DesignTokens.Chrome.TextFaint
    }
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.16f)) {
                    Text(
                        " $label ",
                        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${event.fromBot} → ${event.toBot}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DesignTokens.Chrome.TextSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    event.timestamp.takeLast(9).trimStart('T'),
                    style = MaterialTheme.typography.labelSmall,
                    color = DesignTokens.Chrome.TextFaint,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                event.summary,
                style = MaterialTheme.typography.bodySmall,
                color = DesignTokens.Chrome.Text,
                maxLines = 2,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Band 4: needs-you stack
// ---------------------------------------------------------------------------

@Composable
private fun NeedsYouStack(pending: List<ApprovalRequest>) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Density.StackGap.dp)) {
        Text("Needs you", style = MaterialTheme.typography.titleMedium, color = DesignTokens.Chrome.TextSecondary)
        val request = pending.sortedBy { if (it.risk == ApprovalRisk.Critical) 0 else 1 }.firstOrNull()
        if (request == null) {
            EmptyCard("Nothing needs you", "Pending approvals will surface here and in the Approvals inbox.")
        } else {
            Surface(
                shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
                color = DesignTokens.Surfaces.Surface,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = DesignTokens.Status.Critical.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
                ),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (request.risk == ApprovalRisk.Critical) "CRITICAL" else "CONFIRM",
                            style = MaterialTheme.typography.labelSmall,
                            color = DesignTokens.Status.Critical,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(request.bot, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        request.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = DesignTokens.Chrome.TextSecondary,
                    )
                    Text(
                        "Resolve in Approvals · ${request.corrId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = DesignTokens.Chrome.TextFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.SurfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = DesignTokens.Chrome.TextSecondary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = DesignTokens.Chrome.TextFaint)
        }
    }
}
