package com.phonebot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.phonebot.app.ui.approvals.ApprovalsScreen
import com.phonebot.app.ui.observatory.ObservatoryScreen
import com.phonebot.app.ui.palette.CommandPaletteOverlay
import com.phonebot.app.ui.routines.RoutinesScreen
import com.phonebot.app.ui.setup.SetupWizardScreen
import com.phonebot.app.ui.settings.CapabilitiesScreen
import com.phonebot.app.ui.thread.BotThreadScreen
import com.phonebot.design.DesignTokens

/**
 * Single-activity Compose shell.
 *
 * Layout:
 *  - Scaffold + bottom nav (Observatory / Bots / Routines), gear tab → Settings
 *  - Global command palette: swipe-up gesture opens [CommandPaletteOverlay],
 *    a full-screen glassy (Liquid Glass) layer above everything.
 *
 * Phase 1: placeholder data + state wiring only. The live botd connection
 * arrives with the :termux-bridge IPC client in Phase 2.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhonebotTheme {
                PhonebotShell()
            }
        }
    }
}

/** Dark-only M3 theme: monochrome chrome, bot hues reserved for accents. */
@Composable
fun PhonebotTheme(content: @Composable () -> Unit) {
    val t = DesignTokens
    val colors = MaterialTheme.colorScheme.copy(
        primary = t.Chrome.Text,
        onPrimary = t.Surfaces.Background,
        primaryContainer = t.Surfaces.SurfaceHigh,
        onPrimaryContainer = t.Chrome.Text,
        secondary = t.Status.Waiting,
        onSecondary = t.Surfaces.Background,
        background = t.Surfaces.Background,
        onBackground = t.Chrome.Text,
        surface = t.Surfaces.Surface,
        onSurface = t.Chrome.Text,
        surfaceVariant = t.Surfaces.SurfaceContainer,
        onSurfaceVariant = t.Chrome.TextSecondary,
        outline = t.Chrome.Outline,
        outlineVariant = t.Chrome.OutlineSoft,
        error = t.Status.Critical,
        onError = t.Surfaces.Background,
        errorContainer = t.Status.Critical.copy(alpha = 0.15f),
        onErrorContainer = t.Status.Critical,
    )
    MaterialTheme(colorScheme = colors) { content() }
}

/** Bottom-nav tabs. */
private enum class ShellTab(val label: String) {
    Observatory("Observatory"),
    Bots("Bots"),
    Routines("Routines"),
}

/** Settings stack reachable from the gear tab. */
private sealed interface SettingsRoute {
    val title: String
    @Composable fun render(onBack: () -> Unit)
}

private object CapabilitiesRoute : SettingsRoute {
    override val title = "Capabilities"
    @Composable override fun render(onBack: () -> Unit) = CapabilitiesScreen(onBack = onBack)
}

private object ApprovalsRoute : SettingsRoute {
    override val title = "Approvals"
    @Composable override fun render(onBack: () -> Unit) = ApprovalsScreen(onBack = onBack)
}

private object SetupRoute : SettingsRoute {
    override val title = "Setup"
    @Composable override fun render(onBack: () -> Unit) = SetupWizardScreen(onBack = onBack)
}

// Phase 1 placeholder roster — replaced by the botd registry sync in Phase 2.
private data class PlaceholderBot(
    val id: String,
    val name: String,
    val hue: Color,
    val status: BotStatus,
    val activity: String,
)

enum class BotStatus { Working, Waiting, Idle }

private val placeholderBots = listOf(
    PlaceholderBot("atlas", "Atlas", DesignTokens.BotHues.H0, BotStatus.Working, "scraping price deltas"),
    PlaceholderBot("sable", "Sable", DesignTokens.BotHues.H1, BotStatus.Waiting, "awaiting approval: rm -rf build/"),
    PlaceholderBot("vex", "Vex", DesignTokens.BotHues.H2, BotStatus.Idle, "dormant, last run 4h ago"),
)

/** Root shell: bottom nav + settings stack + global command palette. */
@Composable
fun PhonebotShell() {
    var tab by remember { mutableStateOf(ShellTab.Observatory) }
    var settingsRoute by remember { mutableStateOf<SettingsRoute?>(null) }
    var activeBotId by remember { mutableStateOf<String?>(null) }
    var paletteOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // Layer 0: main content, routed by bottom tab.
        Scaffold(
            containerColor = DesignTokens.Surfaces.Background,
            bottomBar = {
                ShellBottomNav(
                    selected = tab,
                    onTabSelected = { tab = it },
                    onGear = { settingsRoute = CapabilitiesRoute },
                )
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding)) {
                when {
                    settingsRoute != null -> settingsRoute!!.render(onBack = { settingsRoute = null })
                    activeBotId != null -> BotThreadScreen(
                        botId = activeBotId!!,
                        onBack = { activeBotId = null },
                    )
                    tab == ShellTab.Observatory -> ObservatoryScreen(onBotSelected = { activeBotId = it })
                    tab == ShellTab.Bots -> BotRosterTab(onBotSelected = { activeBotId = it })
                    tab == ShellTab.Routines -> RoutinesScreen()
                }
            }
        }

        // Layer 1: full-screen swipe-up catcher. A touch that starts below 70% of
        // the screen and ends 96dp higher opens the command palette.
        var paletteRequest by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(1f)
                .pointerInput(Unit) {
                    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.type != PointerType.Touch) return@awaitEachGesture
                        if (down.position.y < size.height * 0.7f) return@awaitEachGesture
                        val up = waitForUpOrCancel() ?: return@awaitEachGesture
                        if (down.position.y - up.position.y > swipeThreshold) {
                            paletteRequest = true
                        }
                    }
                },
        )

        // Layer 2: glassy command palette overlay.
        if (paletteRequest) {
            CommandPaletteOverlay(onDismiss = { paletteRequest = false })
        }
    }
}

/** Bots tab: compact roster grid. TODO: dedicated roster view with per-bot actions. */
@Composable
private fun BotRosterTab(onBotSelected: (String) -> Unit) {
    val t = DesignTokens
    Column(
        Modifier
            .fillMaxSize()
            .padding(t.Density.ScreenPad.dp),
    ) {
        Text("Bots", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            placeholderBots.forEach { bot ->
                Surface(
                    modifier = Modifier,
                    shape = RoundedCornerShape(t.Shape.ChipRadius),
                    color = t.Surfaces.SurfaceContainer,
                    contentColor = t.Chrome.Text,
                ) {
                    Text(
                        text = " ${bot.name} ",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = bot.hue,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellBottomNav(
    selected: ShellTab,
    onTabSelected: (ShellTab) -> Unit,
    onGear: () -> Unit,
) {
    val t = DesignTokens
    NavigationBar(containerColor = t.Surfaces.Surface) {
        val items = listOf(
            ShellTab.Observatory to Icons.Filled.Explore,
            ShellTab.Bots to Icons.Filled.AccountTree,
            ShellTab.Routines to Icons.Filled.Schedule,
        )
        items.forEach { (tabItem, icon) ->
            NavigationBarItem(
                selected = selected == tabItem,
                onClick = { onTabSelected(tabItem) },
                icon = { Icon(icon, contentDescription = tabItem.label) },
                label = { Text(tabItem.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
        NavigationBarItem(
            selected = false,
            onClick = onGear,
            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
            label = { Text("Settings", style = MaterialTheme.typography.labelSmall) },
        )
    }
}
