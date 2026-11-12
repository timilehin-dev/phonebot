package com.phonebot.app.ui.palette

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonebot.design.DesignTokens

/**
 * CommandPaletteOverlay — global swipe-up command palette.
 *
 * Glassy (Liquid Glass) translucent input field + filtered action list.
 * Routes actions to the correct bot / surface:
 *   - "summarize inbox vex"   → bot: vex, action: inbox digest
 *   - "deploy staging sable"  → bot: sable, action: deploy
 *   - "enable tool atlas web" → tool registry toggle for atlas
 *
 * Phase 1: placeholder action list + filter; live routing through the
 * botd RPC layer arrives in a later phase.
 */
@Composable
fun CommandPaletteOverlay(onDismiss: () -> Unit) {
    val t = DesignTokens
    var query by remember { mutableStateOf("") }

    val filtered = remember(query) {
        if (query.isBlank()) placeholderActions
        else placeholderActions.filter { a ->
            a.label.contains(query, ignoreCase = true) ||
            a.target.contains(query, ignoreCase = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Backdrop: semi-transparent dark wash — liquid-glass blur is simulated
        // with a low-alpha surface container over the content layer.
        // (True backdrop-blur requires platform support or a custom Renderer;
        //  the alpha wash is the Phase 1 approximation.)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA00B0D10)), // 62% opaque background
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp)
                .align(Alignment.BottomCenter),
        ) {
            // Glassy input card
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp,
                    bottomStart = 0.dp, bottomEnd = 0.dp),
                color = DesignTokens.Surfaces.Glass,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.Density.ScreenPad.dp)
                    .padding(top = 16.dp),
                tonalElevation = 0.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text("command · @bot · #routine",
                                style = MaterialTheme.typography.bodyMedium,
                                color = DesignTokens.Chrome.TextFaint)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(DesignTokens.Shape.InputRadius),
                    )
                    // Filtered action list
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (filtered.isEmpty()) {
                            Text("No matches", Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = DesignTokens.Chrome.TextFaint)
                        } else {
                            filtered.forEach { action ->
                                PaletteActionRow(action, onClick = {
                                    // TODO(later phase): route to bot / surface
                                    onDismiss()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Action model + row
// ---------------------------------------------------------------------------

private data class PaletteAction(
    val id: String,
    val label: String,
    val target: String,      // bot name or surface name
    val targetHue: Color,
    val icon: String,
)

private val placeholderActions = listOf(
    PaletteAction("a1", "summarize inbox", "vex", DesignTokens.BotHues.H2, "☰"),
    PaletteAction("a2", "deploy staging", "sable", DesignTokens.BotHues.H1, "🚀"),
    PaletteAction("a3", "trigger scrape", "atlas", DesignTokens.BotHues.H0, "⚡"),
    PaletteAction("a4", "view routines", "routines", DesignTokens.Chrome.TextFaint, "◷"),
    PaletteAction("a5", "approve pending", "approvals", DesignTokens.Status.Critical, "✋"),
    PaletteAction("a6", "enable tool: web", "atlas", DesignTokens.BotHues.H0, "⚙"),
    PaletteAction("a7", "mute bot for 1h", "onyx", DesignTokens.BotHues.H3, "🔕"),
)

@Composable
private fun PaletteActionRow(action: PaletteAction, onClick: () -> Unit) {
    val t = DesignTokens
    Surface(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(action.icon, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                action.label,
                style = MaterialTheme.typography.bodyMedium,
                color = DesignTokens.Chrome.Text,
            )
            Spacer(Modifier.weight(1f))
            // Target chip: bot name in its hue, or surface name in faint chrome
            Surface(
                shape = RoundedCornerShape(DesignTokens.Shape.ChipRadius),
                color = if (action.targetHue != DesignTokens.Chrome.TextFaint)
                    action.targetHue.copy(alpha = 0.15f) else DesignTokens.Surfaces.SurfaceHigh,
            ) {
                Text(
                    " ${action.target} ",
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = action.targetHue,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
