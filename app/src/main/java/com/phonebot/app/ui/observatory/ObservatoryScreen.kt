package com.phonebot.app.ui.observatory

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonebot.design.DesignTokens

/**
 * Observatory — the home screen. Three bands:
 *
 *  1. Digest strip (top): fleet-level summary, one line per metric.
 *  2. Bot crew grid (middle): one card per bot — avatar ring in the bot's
 *     hue, name, activity line, status indicator (scanline for working,
 *     pulse for waiting), approval badge when a request is pending.
 *  3. Needs-you stack (bottom): the single most urgent human decision.
 *
 * Filter chip row sits between band 1 and band 2.
 *
 * Phase 1: placeholder data; live feed lands with the botd socket in Phase 2.
 */
@Composable
fun ObservatoryScreen(onBotSelected: (botId: String) -> Unit) {
    val t = DesignTokens
    var filter by remember { mutableStateOf(ObservatoryFilter.All) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = t.Density.ScreenPad.dp),
        verticalArrangement = Arrangement.spacedBy(t.Density.CardGap.dp),
    ) {
        // Band 1: digest strip.
        item {
            DigestStrip()
        }

        // Filter chip row.
        item {
            FilterChipRow(selected = filter, onSelect = { filter = it })
        }

        // Band 2: bot crew grid.
        item {
            val visible = when (filter) {
                ObservatoryFilter.All -> placeholderCrew
                ObservatoryFilter.Working -> placeholderCrew.filter { it.statusLabel == "working" }
                ObservatoryFilter.Waiting -> placeholderCrew.filter { it.statusLabel == "waiting" }
                ObservatoryFilter.Approval -> placeholderCrew.filter { it.approvalBadge != null }
            }
            visible.chunked(2).forEach { row in visible.chunked(2) ->
                // each row laid out below via Row
            }
            CrewGrid(visible, onBotSelected)
        }

        // Band 3: needs-you stack.
        item {
            NeedsYouStack()
        }
        item { Spacer(modifier = Modifier.height(64.dp)) } // room for bottom nav
    }
}

// ---------------------------------------------------------------------------
// Band 1: digest strip
// ---------------------------------------------------------------------------

@Composable
private fun DigestStrip() {
    val t = DesignTokens
    Surface(
        shape = RoundedCornerShape(t.Shape.CardRadius),
        color = t.Surfaces.Surface,
        contentColor = t.Chrome.Text,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fleet", style = MaterialTheme.typography.labelSmall, color = t.Chrome.TextFaint)
            DigestLine("3 bots online · 1 waiting on you", t.Status.Waiting)
            DigestLine("14 runs last hour · 0 failed", t.Status.Ok)
            // TODO: digest figures come from the botd /digest RPC (Phase 2).
        }
    }
}

@Composable
private fun DigestLine(text: String, accent: Color) {
    val t = DesignTokens
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(accent, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = t.Chrome.TextSecondary)
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
    val t = DesignTokens
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ObservatoryFilter.entries.forEach { f ->
            val isSel = f == selected
            Surface(
                shape = RoundedCornerShape(t.Shape.ChipRadius),
                color = if (isSel) t.Surfaces.SurfaceHigh else t.Surfaces.SurfaceContainer,
                contentColor = if (isSel) t.Chrome.Text else t.Chrome.TextSecondary,
            ) {
                Text(
                    text = " ${f.label} ",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor(),
                )
            }
        }
    }
}

@Composable
private fun contentColor() = MaterialTheme.colorScheme.onSurface

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

private val placeholderCrew = listOf(
    CrewMember("atlas", "Atlas", DesignTokens.BotHues.H0, "scraping price deltas", "working", null),
    CrewMember("sable", "Sable", DesignTokens.BotHues.H1, "awaiting approval", "waiting", "rm -rf build/"),
    CrewMember("vex", "Vex", DesignTokens.BotHues.H2, "digesting inbox", "working", null),
    CrewMember("onyx", "Onyx", DesignTokens.BotHues.H3, "dormant · last run 4h", "idle", null),
)

@Composable
private fun CrewGrid(members: List<CrewMember>, onBotSelected: (String) -> Unit) {
    val t = DesignTokens
    Column(verticalArrangement = Arrangement.spacedBy(t.Density.CardGap.dp)) {
        members.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(t.Density.CardGap.dp)) {
                pair.forEach { m ->
                    BotCard(m, Modifier.weight(1f), onBotSelected = { onBotSelected(m.id) })
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Instrument-panel bot card:
 *  - avatar ring in the bot's hue (outer 2dp ring, inner disc with initial)
 *  - name + activity line
 *  - status indicator: scanline sweep while working, pulse while waiting
 *  - approval badge chip when a request is pending
 */
@Composable
fun BotCard(
    bot: CrewMember,
    modifier: Modifier = Modifier,
    onBotSelected: (String) -> Unit,
) {
    val t = DesignTokens
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(t.Shape.CardRadius),
        color = t.Surfaces.Surface,
        contentColor = t.Chrome.Text,
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
                        color = t.Chrome.TextSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                StatusIndicator(status = bot.statusLabel)
            }
            if (bot.approvalBadge != null) {
                Surface(
                    shape = RoundedCornerShape(t.Shape.BlockRadius),
                    color = t.Status.Critical.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = " ✋ ${bot.approvalBadge} ",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = t.Status.Critical,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarRing(initial: String, hue: Color) {
    val t = DesignTokens
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(40.dp)
                .border(width = 2.dp, color = hue, shape = CircleShape)
                .background(t.Surfaces.SurfaceContainer, CircleShape),
        )
        Text(initial, style = MaterialTheme.typography.titleMedium, color = hue, fontWeight = FontWeight.Bold)
    }
}

/** Scanline animation for working; pulse for waiting; static dot for idle. */
@Composable
private fun StatusIndicator(status: String) {
    val t = DesignTokens
    when (status) {
        "working" -> ScanlineIndicator(t.Status.Working)
        "waiting" -> PulseDot(t.Status.Waiting)
        else -> IdleDot(t.Status.Idle)
    }
}

@Composable
private fun ScanlineIndicator(color: Color) {
    val t = DesignTokens
    val transition = rememberInfiniteTransition(label = "scanline")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = tween(t.Motion.ScanlineMs, easing = LinearEasing, repeat = RepeatMode.Restart),
        label = "scanline-progress",
    )
    Box(
        Modifier
            .size(width = 40.dp, height = 8.dp)
            .background(t.Surfaces.SurfaceHigh, RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .width(10.dp)
                .height(8.dp)
                .background(color, RoundedCornerShape(2.dp))
                .padding(horizontal = 0.dp)
                .let {
                    // translate the 10dp bar across the 40dp track via offset
                    it.offset(x = androidx.compose.ui.unit.Dp((progress * 30).toInt()), y = 0.dp)
                },
        )
    }
}

@Composable
private fun PulseDot(color: Color) {
    val t = DesignTokens
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = tween(t.Motion.PulseMs, repeat = RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(
        Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun IdleDot(color: Color) {
    Box(Modifier.size(8.dp).background(color.copy(alpha = 0.4f), CircleShape))
}

// ---------------------------------------------------------------------------
// Band 3: needs-you stack
// ---------------------------------------------------------------------------

/**
 * Single most-urgent decision the human must make. Cards here are risk-tiered
 * (critical first); the full queue lives on the Approvals screen.
 */
@Composable
private fun NeedsYouStack() {
    val t = DesignTokens
    Column(verticalArrangement = Arrangement.spacedBy(t.Density.StackGap.dp)) {
        Text("Needs you", style = MaterialTheme.typography.titleMedium, color = t.Chrome.TextSecondary)
        Surface(
            shape = RoundedCornerShape(t.Shape.CardRadius),
            color = t.Surfaces.Surface,
            modifier = Modifier.border(width = 1.dp, color = t.Status.Critical.copy(alpha = 0.5f), shape = RoundedCornerShape(t.Shape.CardRadius)),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CRITICAL", style = MaterialTheme.typography.labelSmall, color = t.Status.Critical, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("sable · deletes build output", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "rm -rf /data/data/com.myapp/files/build/",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = t.Chrome.TextSecondary,
                )
                // TODO: Approve/Deny buttons wire to the approval RPC (Phase 2).
            }
        }
    }
}
