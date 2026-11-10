package com.phonebot.app.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
 * CapabilitiesScreen — the settings tree.
 *
 * Four sections:
 *  1. Tools        — registry list with enable toggles (one row per tool)
 *  2. Skills       — library + per-bot visibility toggles
 *  3. Plugins & MCP — connections list, "add new" button, health status dot
 *  4. Backends     — shell (termux / ssh / shizuku-locked),
 *                    python (chaquopy / termux), device (accessibility / assist)
 *
 * Phase 1: placeholder data + static toggles; the tool registry sync from
 * botd and the MCP health probes arrive in Phase 2.
 */
@Composable
fun CapabilitiesScreen(onBack: () -> Unit = {}) {
    val t = DesignTokens

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = DesignTokens.Density.ScreenPad.dp),
    ) {
        Text("Capabilities", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 12.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Density.CardGap.dp),
        ) {
            ToolsSection()
            SkillsSection()
            McpSection()
            BackendsSection()
            Spacer(Modifier.height(80.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Section helpers
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    val t = DesignTokens
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = DesignTokens.Chrome.TextSecondary,
            fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = DesignTokens.Chrome.TextFaint)
        }
    }
}

// ---------------------------------------------------------------------------
// 1. Tools
// ---------------------------------------------------------------------------

private data class ToolRow(val name: String, val description: String, val enabled: Boolean)

private val placeholderTools = listOf(
    ToolRow("web.scrape", "Headless browser scraping (Termux chromium)", true),
    ToolRow("shell.exec", "Shell command execution (termux-shell)", true),
    ToolRow("file.rw", "File read/write on device storage", true),
    ToolRow("db.sql", "SQLite queries via termux-sqlite", false),
    ToolRow("mcp.fetch", "MCP tool-call proxy (Phase 2)", false),
)

@Composable
private fun ToolsSection() {
    val t = DesignTokens
    var tools by remember { mutableStateOf(placeholderTools) }

    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.Surface,
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("Tools", "Per-bot allowlists are enforced by the LLM prompt")
            Spacer(Modifier.height(8.dp))
            tools.forEachIndexed { i, tool ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tool.name, style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace, color = DesignTokens.Chrome.Text)
                        Text(tool.description, style = MaterialTheme.typography.labelSmall,
                            color = DesignTokens.Chrome.TextFaint)
                    }
                    Switch(
                        checked = tool.enabled,
                        onCheckedChange = { enabled ->
                            tools = tools.toMutableList().also { it[i] = tool.copy(enabled = enabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DesignTokens.Status.Ok,
                            checkedTrackColor = DesignTokens.Status.Ok.copy(alpha = 0.4f),
                            uncheckedThumbColor = DesignTokens.Chrome.TextSecondary,
                            uncheckedTrackColor = DesignTokens.Chrome.Outline,
                        ),
                    )
                }
            }
            // TODO: per-bot allowlist editor (Phase 2)
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Skills
// ---------------------------------------------------------------------------

private data class SkillRow(val name: String, val visibleBots: List<String>)

private val placeholderSkills = listOf(
    SkillRow("git-helper", listOf("atlas", "sable")),
    SkillRow("release-notes", listOf("sable", "vex")),
    SkillRow("log-triage", listOf("atlas", "vex", "onyx")),
)

@Composable
private fun SkillsSection() {
    val t = DesignTokens
    Surface(shape = RoundedCornerShape(DesignTokens.Shape.CardRadius), color = DesignTokens.Surfaces.Surface) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("Skills", "Library + per-bot visibility toggles")
            Spacer(Modifier.height(8.dp))
            placeholderSkills.forEach { skill ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace, color = DesignTokens.Chrome.Text)
                    Spacer(Modifier.weight(1f))
                    // Per-bot visibility dots
                    listOf("atlas" to DesignTokens.BotHues.H0, "sable" to DesignTokens.BotHues.H1,
                        "vex" to DesignTokens.BotHues.H2, "onyx" to DesignTokens.BotHues.H3)
                        .forEach { (botName, hue) ->
                            val visible = skill.visibleBots.contains(botName)
                            Box(Modifier.size(8.dp).background(
                                if (visible) hue else DesignTokens.Chrome.Outline, CircleShape))
                            Spacer(Modifier.width(4.dp))
                        }
                }
            }
            // TODO: skill library browser (Phase 2)
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Plugins & MCP
// ---------------------------------------------------------------------------

private data class McpRow(val name: String, val endpoint: String, val healthy: Boolean)

private val placeholderMcp = listOf(
    McpRow("browser", "http://localhost:8741", true),
    McpRow("linear", "https://mcp.linear.app", true),
    McpRow("datadog", "https://mcp.datadog.com", false),
)

@Composable
private fun McpSection() {
    val t = DesignTokens
    Surface(shape = RoundedCornerShape(DesignTokens.Shape.CardRadius), color = DesignTokens.Surfaces.Surface) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Plugins & MCP", "Connections list · health status")
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(DesignTokens.Shape.ChipRadius), color = DesignTokens.Surfaces.SurfaceHigh) {
                    Text(
                        " + add new",
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = DesignTokens.Status.Waiting,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            placeholderMcp.forEach { mcp ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(
                        if (mcp.healthy) DesignTokens.Status.Ok else DesignTokens.Status.Critical, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(mcp.name, style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace, color = DesignTokens.Chrome.Text)
                        Text(mcp.endpoint, style = MaterialTheme.typography.labelSmall,
                            color = DesignTokens.Chrome.TextFaint, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            // TODO: MCP health probe polling (Phase 2)
        }
    }
}

// ---------------------------------------------------------------------------
// 4. Backends
// ---------------------------------------------------------------------------

@Composable
private fun BackendsSection() {
    val t = DesignTokens
    Surface(shape = RoundedCornerShape(DesignTokens.Shape.CardRadius), color = DesignTokens.Surfaces.Surface) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("Backends", "Execution runtimes on device")
            Spacer(Modifier.height(8.dp))
            BackendGroup("Shell", listOf(
                BackendRow("termux", true, "Termux (default)"),
                BackendRow("ssh", false, "Remote shell"),
                BackendRow("shizuku", false, "locked — Shizuku not installed"),
            ))
            Spacer(Modifier.height(8.dp))
            BackendGroup("Python", listOf(
                BackendRow("chaquopy", false, "Chaquopy plugin"),
                BackendRow("termux-python", true, "Termux python3"),
            ))
            Spacer(Modifier.height(8.dp))
            BackendGroup("Device", listOf(
                BackendRow("accessibility", false, "Accessibility service (Phase 2)"),
                BackendRow("assist", false, "Assist structure"),
            ))
        }
    }
}

private data class BackendRow(val name: String, val active: Boolean, val note: String)

@Composable
private fun BackendGroup(title: String, rows: List<BackendRow>) {
    val t = DesignTokens
    Text(title, style = MaterialTheme.typography.labelMedium, color = DesignTokens.Chrome.TextSecondary,
        modifier = Modifier.padding(vertical = 4.dp))
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(
                if (row.active) DesignTokens.Status.Ok else DesignTokens.Chrome.Outline, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(row.name, style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (row.active) DesignTokens.Chrome.Text else DesignTokens.Chrome.TextFaint)
            Spacer(Modifier.weight(1f))
            Text(row.note, style = MaterialTheme.typography.labelSmall, color = DesignTokens.Chrome.TextFaint)
        }
    }
}
