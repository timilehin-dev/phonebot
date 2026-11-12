package com.phonebot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.phonebot.app.bridge.ActionFeedback
import com.phonebot.app.bridge.BotRow
import com.phonebot.app.bridge.BotStatus
import com.phonebot.app.bridge.BridgeObservatoryDataSource
import com.phonebot.app.bridge.BridgeRuntime
import com.phonebot.app.ui.approvals.ApprovalsScreen
import com.phonebot.app.ui.observatory.ObservatoryScreen
import com.phonebot.app.ui.palette.CommandPaletteOverlay
import com.phonebot.app.ui.routines.RoutinesScreen
import com.phonebot.app.ui.setup.SetupWizardScreen
import com.phonebot.app.ui.settings.CapabilitiesScreen
import com.phonebot.app.ui.thread.BotThreadScreen
import com.phonebot.design.DesignTokens
import java.util.Locale

/**
 * Single-activity Compose shell.
 *
 * Layout:
 *  - Scaffold + bottom nav (Observatory / Bots / Routines), gear tab → Settings
 *  - Global command palette: swipe-up gesture opens [CommandPaletteOverlay]
 *  - `needs-approval` notifications deep-link here with [EXTRA_OPEN_VIEW] and
 *    open the Approvals view directly.
 *
 * The Bots tab reads [BridgeRuntime.roster] (botd `get_state`) and issues
 * run/pause/resume through [BridgeRuntime]; no placeholder data lives here.
 */
class MainActivity : ComponentActivity() {

    private var approvalsRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        consumeIntent(intent)
        setContent {
            PhonebotTheme {
                PhonebotShell(approvalsRequest = approvalsRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    /** Android 13+ needs a runtime grant before approval alerts can appear. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    /** Bump a counter so the shell reacts even when the activity already exists. */
    private fun consumeIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN_VIEW) == VIEW_APPROVALS) {
            approvalsRequest += 1
        }
    }

    companion object {
        const val EXTRA_OPEN_VIEW = "com.phonebot.app.OPEN_VIEW"
        const val EXTRA_CORR_ID = "com.phonebot.app.CORR_ID"
        const val VIEW_APPROVALS = "approvals"
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}

/** Dark-only M3 theme: monochrome chrome, bot hues reserved for accents. */
@Composable
fun PhonebotTheme(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme.copy(
        primary = DesignTokens.Chrome.Text,
        onPrimary = DesignTokens.Surfaces.Background,
        primaryContainer = DesignTokens.Surfaces.SurfaceHigh,
        onPrimaryContainer = DesignTokens.Chrome.Text,
        secondary = DesignTokens.Status.Waiting,
        onSecondary = DesignTokens.Surfaces.Background,
        background = DesignTokens.Surfaces.Background,
        onBackground = DesignTokens.Chrome.Text,
        surface = DesignTokens.Surfaces.Surface,
        onSurface = DesignTokens.Chrome.Text,
        surfaceVariant = DesignTokens.Surfaces.SurfaceContainer,
        onSurfaceVariant = DesignTokens.Chrome.TextSecondary,
        outline = DesignTokens.Chrome.Outline,
        outlineVariant = DesignTokens.Chrome.OutlineSoft,
        error = DesignTokens.Status.Critical,
        onError = DesignTokens.Surfaces.Background,
        errorContainer = DesignTokens.Status.Critical.copy(alpha = 0.15f),
        onErrorContainer = DesignTokens.Status.Critical,
    )
    MaterialTheme(colorScheme = colors) { content() }
}

/** Bottom-nav tabs. */
private enum class ShellTab(val label: String) {
    Observatory("Observatory"),
    Bots("Bots"),
    Routines("Routines"),
}

/** Settings stack reachable from the gear tab (and from notifications). */
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
    @Composable override fun render(onBack: () -> Unit) {
        val pending by BridgeRuntime.pendingApprovals.collectAsState()
        ApprovalsScreen(
            pending = pending,
            onApprove = { corrId -> BridgeRuntime.approve(corrId) },
            onDeny = { corrId -> BridgeRuntime.deny(corrId) },
            onBack = onBack,
        )
    }
}

private object SetupRoute : SettingsRoute {
    override val title = "Setup"
    @Composable override fun render(onBack: () -> Unit) = SetupWizardScreen(onBack = onBack)
}

/** Root shell: bottom nav + settings stack + global command palette. */
@Composable
fun PhonebotShell(approvalsRequest: Int = 0) {
    var tab by remember { mutableStateOf(ShellTab.Observatory) }
    var settingsRoute by remember { mutableStateOf<SettingsRoute?>(null) }
    var activeBotId by remember { mutableStateOf<String?>(null) }
    var paletteOpen by remember { mutableStateOf(false) }

    // A tapped approval notification opens the approvals inbox directly.
    LaunchedEffect(approvalsRequest) {
        if (approvalsRequest > 0) settingsRoute = ApprovalsRoute
    }

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
                    tab == ShellTab.Observatory -> {
                        val observatory by BridgeObservatoryDataSource.state.collectAsState()
                        ObservatoryScreen(
                            state = observatory,
                            onRefresh = { BridgeObservatoryDataSource.refresh() },
                            onBotSelected = { activeBotId = it },
                        )
                    }
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
                    // PointerInputScope is a Density: convert directly, no
                    // composable LocalDensity lookup inside this block.
                    val swipeThreshold = 96.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.type != PointerType.Touch) return@awaitEachGesture
                        if (down.position.y < size.height * 0.7f) return@awaitEachGesture
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
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

// ---------------------------------------------------------------------------
// Bots tab — live botd roster (get_state) with run / pause / resume
// ---------------------------------------------------------------------------

@Composable
private fun BotRosterTab(onBotSelected: (String) -> Unit) {
    val snapshot by BridgeRuntime.roster.collectAsState()
    val feedback by BridgeRuntime.actionFeedback.collectAsState()
    val busyBot by BridgeRuntime.busyBot.collectAsState()

    LaunchedEffect(Unit) { BridgeRuntime.refreshRoster() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(DesignTokens.Density.ScreenPad.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bots", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    snapshot == null -> "checking…"
                    snapshot?.online == true -> "${snapshot?.bots?.size ?: 0} registered"
                    else -> "offline"
                },
                style = MaterialTheme.typography.labelSmall,
                color = DesignTokens.Chrome.TextFaint,
            )
            Spacer(Modifier.width(12.dp))
            RosterActionChip("refresh", DesignTokens.Chrome.TextSecondary, enabled = true) {
                BridgeRuntime.refreshRoster()
            }
        }
        Spacer(Modifier.height(10.dp))

        feedback?.let { fb ->
            FeedbackBanner(fb) { BridgeRuntime.dismissActionFeedback() }
            Spacer(Modifier.height(8.dp))
        }

        val snap = snapshot
        when {
            snap == null -> RosterStateCard(
                title = "Checking botd…",
                body = "Reading the roster over the Termux bridge.",
            )
            !snap.online -> RosterStateCard(
                title = "botd offline",
                body = snap.error ?: "No connection to Termux. Start botd, then refresh.",
            )
            snap.bots.isEmpty() -> RosterStateCard(
                title = "No bots registered",
                body = "botd answered, but the roster is empty.",
            )
            else -> LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Density.CardGap.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                itemsIndexed(snap.bots, key = { _, bot -> bot.id }) { index, bot ->
                    BotRosterRow(
                        bot = bot,
                        hue = DesignTokens.BotHues.forIndex(index),
                        busy = busyBot == bot.id,
                        onOpen = { onBotSelected(bot.id) },
                        onRun = { BridgeRuntime.runBot(bot.id) },
                        onPause = { BridgeRuntime.pauseBot(bot.id) },
                        onResume = { BridgeRuntime.resumeBot(bot.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BotRosterRow(
    bot: BotRow,
    hue: Color,
    busy: Boolean,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.CardRadius),
        color = DesignTokens.Surfaces.SurfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(hue, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(bot.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    botStatusLabel(bot.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = botStatusColor(bot.status),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                bot.currentTask.ifBlank { "no active task" },
                style = MaterialTheme.typography.bodySmall,
                color = if (bot.currentTask.isBlank()) DesignTokens.Chrome.TextFaint else DesignTokens.Chrome.TextSecondary,
                maxLines = 2,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (bot.status == BotStatus.Paused) {
                    RosterActionChip("resume", DesignTokens.Status.Ok, enabled = !busy, onClick = onResume)
                } else {
                    RosterActionChip("pause", DesignTokens.Status.Warn, enabled = !busy, onClick = onPause)
                }
                RosterActionChip("run", DesignTokens.Status.Waiting, enabled = !busy, onClick = onRun)
                Spacer(Modifier.weight(1f))
                if (bot.spendUsd > 0.0) {
                    Text(
                        "$" + String.format(Locale.US, "%.2f", bot.spendUsd),
                        style = MaterialTheme.typography.labelSmall,
                        color = DesignTokens.Chrome.TextFaint,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun RosterActionChip(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.ChipRadius),
        color = color.copy(alpha = if (enabled) 0.15f else 0.06f),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) color else DesignTokens.Chrome.TextFaint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FeedbackBanner(feedback: ActionFeedback, onDismiss: () -> Unit) {
    val color = if (feedback.isError) DesignTokens.Status.Critical else DesignTokens.Status.Ok
    Surface(
        shape = RoundedCornerShape(DesignTokens.Shape.BlockRadius),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
    ) {
        Text(
            feedback.message,
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun RosterStateCard(title: String, body: String) {
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

private fun botStatusLabel(status: BotStatus): String = when (status) {
    BotStatus.Running -> "running"
    BotStatus.Paused -> "paused"
    BotStatus.WaitingApproval -> "needs approval"
    BotStatus.Idle -> "idle"
}

private fun botStatusColor(status: BotStatus): Color = when (status) {
    BotStatus.Running -> DesignTokens.Status.Working
    BotStatus.Paused -> DesignTokens.Status.Warn
    BotStatus.WaitingApproval -> DesignTokens.Status.Critical
    BotStatus.Idle -> DesignTokens.Status.Idle
}

@Composable
private fun ShellBottomNav(
    selected: ShellTab,
    onTabSelected: (ShellTab) -> Unit,
    onGear: () -> Unit,
) {
    NavigationBar(containerColor = DesignTokens.Surfaces.Surface) {
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
