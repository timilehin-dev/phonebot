package com.phonebot.app.ui.thread

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.phonebot.design.DesignTokens

/**
 * BotThreadScreen — single-bot conversation view.
 *
 *  - Mini-crew strip on top: all bots as compact chips, current bot highlighted
 *    in its hue; tap to switch thread.
 *  - Messages: user right, bot left with hue accent.
 *  - Trace chips: mono-font collapsible blocks showing tool calls + results.
 *  - Hand-off connector lines between bots when a turn passes between them.
 *  - Voice note waveform: simplified bar waveform for audio messages.
 *
 * Phase 1: placeholder conversation data; live messages arrive from the botd
 * socket (TermuxBridge → LlmClient) in Phase 2.
 */
@Composable
fun BotThreadScreen(botId: String, onBack: () -> Unit) {
    val t = DesignTokens
    var currentBotId by remember(botId) { mutableStateOf(botId) }

    Scaffold(
        containerColor = DesignTokens.Surfaces.Background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.Density.ScreenPad.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    currentBotId.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            // Mini-crew strip: compact bot chips
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.Density.ScreenPad.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val crew = listOf(
                    "atlas" to DesignTokens.BotHues.H0, "sable" to DesignTokens.BotHues.H1,
                    "vex" to DesignTokens.BotHues.H2, "onyx" to DesignTokens.BotHues.H3,
                )
                crew.forEach { (id, hue) ->
                    val isSel = id == currentBotId
                    Surface(
                        shape = RoundedCornerShape(DesignTokens.Shape.ChipRadius),
                        color = if (isSel) DesignTokens.Surfaces.SurfaceHigh else DesignTokens.Surfaces.SurfaceContainer,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.dp).background(hue, CircleShape))
                            Spacer(Modifier.width(5.dp))
                            Text(id, style = MaterialTheme.typography.labelMedium,
                                color = if (isSel) DesignTokens.Chrome.Text else DesignTokens.Chrome.TextSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDividerThread()

            // Message list
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = DesignTokens.Density.ScreenPad.dp),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Density.CardGap.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
                items(placeholderMessages) { msg ->
                    when (msg.type) {
                        MsgType.Handoff -> HandoffConnector(msg)
                        MsgType.Trace -> TraceChipBlock(msg, currentBotHue(currentBotId))
                        MsgType.Voice -> VoiceNoteWaveform(msg)
                        else -> MessageBubble(msg, currentBotHue(currentBotId))
                    }
                }
            }
        }
    }
}

private fun currentBotHue(botId: String): Color = when (botId) {
    "atlas" -> DesignTokens.BotHues.H0
    "sable" -> DesignTokens.BotHues.H1
    "vex" -> DesignTokens.BotHues.H2
    else -> DesignTokens.BotHues.H3
}

@Composable
fun HorizontalDividerThread() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DesignTokens.Chrome.OutlineSoft),
    )
}

// ---------------------------------------------------------------------------
// Message model (placeholder data for Phase 1)
// ---------------------------------------------------------------------------

private enum class MsgType { User, Bot, Handoff, Trace, Voice }

private data class PlaceholderMsg(
    val id: Int,
    val type: MsgType,
    val from: String,       // "user" or bot id
    val text: String,
    val trace: List<TraceStep> = emptyList(),
)

private data class TraceStep(
    val tool: String,
    val args: String,
    val result: String,
)

private val placeholderMessages = listOf(
    PlaceholderMsg(0, MsgType.User, "user", "Summarize the last 24h of price moves."),
    PlaceholderMsg(1, MsgType.Bot, "atlas",
        "Scraped 142 SKU deltas. Top movers: AAPL +2.1%, NVDA -0.8%. Full trace below."),
    PlaceholderMsg(2, MsgType.Trace, "atlas", "", listOf(
        TraceStep("scrape.prices", "url=https://quotes.example, window=24h", "142 rows · 28ms"),
        TraceStep("llm.summarize", "model=gpt-4o-mini, max_tokens=256", "summary ready"),
    )),
    PlaceholderMsg(3, MsgType.Handoff, "atlas→vex", "Passing ticker list to Vex for inbox alert"),
    PlaceholderMsg(4, MsgType.Bot, "vex", "Queued 3 watchlist alerts to your inbox. Done."),
    PlaceholderMsg(5, MsgType.Voice, "vex", "Voice note · 0:14"),
)

// ---------------------------------------------------------------------------
// Message composables
// ---------------------------------------------------------------------------

@Composable
private fun MessageBubble(msg: PlaceholderMsg, botHue: Color) {
    val t = DesignTokens
    val isUser = msg.from == "user"
    val accent = if (isUser) DesignTokens.Chrome.Text else botHue
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 4.dp else 20.dp,
                topEnd = if (!isUser) 4.dp else 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp,
            ),
            color = if (isUser) DesignTokens.Surfaces.SurfaceHigh else DesignTokens.Surfaces.Surface,
            modifier = Modifier.borderStroke(accent, isUser),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!isUser) {
                    Text(msg.from, style = MaterialTheme.typography.labelSmall, color = botHue,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                }
                Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = DesignTokens.Chrome.Text)
            }
        }
    }
}

@Composable
fun Modifier.borderStroke(accent: Color, isUser: Boolean): Modifier =
    if (isUser) this.background(Color.Transparent)
    else this.border(
        width = 1.dp,
        color = accent.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp),
    )

/** Collapsible mono-font trace chips. */
@Composable
private fun TraceChipBlock(msg: PlaceholderMsg, botHue: Color) {
    val t = DesignTokens
    var expanded by remember(msg.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.BlockRadius),
        color = DesignTokens.Surfaces.Surface,
        modifier = Modifier.fillMaxWidth().border(width = 1.dp, color = DesignTokens.Chrome.Outline, shape = RoundedCornerShape(DesignTokens.Shape.BlockRadius)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "TRACE",
                    style = MaterialTheme.typography.labelSmall,
                    color = botHue,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "−" else "+",
                    style = MaterialTheme.typography.labelMedium,
                    color = DesignTokens.Chrome.TextFaint,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                msg.trace.forEach { step ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DesignTokens.Surfaces.SurfaceHigh,
                        ) {
                            Text(
                                " ${step.tool} ",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = DesignTokens.Chrome.Text,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            step.result,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = DesignTokens.Chrome.TextFaint,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** Hand-off connector line between bots. */
@Composable
private fun HandoffConnector(msg: PlaceholderMsg) {
    val t = DesignTokens
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(DesignTokens.Chrome.OutlineSoft),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = DesignTokens.Surfaces.Surface, contentColor = DesignTokens.Chrome.TextSecondary) {
            Text(
                text = " ${msg.text} ",
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Voice note — simplified bar waveform. */
@Composable
private fun VoiceNoteWaveform(msg: PlaceholderMsg) {
    val t = DesignTokens
    val bars = remember {
        // placeholder pseudo-random bar heights
        (0..24).map { i -> 8 + (i * 7 % 20) }
    }
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.Surface,
        modifier = Modifier.fillMaxWidth().border(width = 1.dp, color = DesignTokens.Chrome.Outline, shape = RoundedCornerShape(DesignTokens.Shape.CardRadius)),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // play button placeholder
            Box(
                Modifier.size(28.dp).background(DesignTokens.Status.Ok.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", style = MaterialTheme.typography.labelMedium, color = DesignTokens.Status.Ok)
            }
            Spacer(Modifier.width(8.dp))
            bars.forEach { h ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height(h.dp)
                        .background(DesignTokens.Chrome.Outline, RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(msg.text, style = MaterialTheme.typography.labelSmall, color = DesignTokens.Chrome.TextFaint)
        }
    }
}
