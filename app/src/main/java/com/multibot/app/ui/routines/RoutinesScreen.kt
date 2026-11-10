package com.multibot.app.ui.routines

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
import androidx.compose.foundation.lazy.LazyColumn
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
import com.multibot.design.DesignTokens

/**
 * RoutinesScreen — cron card list.
 *
 * Each card shows:
 *  - Routine name + owning bot's hue dot
 *  - Natural-language description + mono cron expression
 *  - Next-run countdown (live ticker)
 *  - Idempotency chip: "idempotent" or "destructive"
 *  - Catch-up status: "last missed run replayed" or "3 missed, catch-up queued"
 *
 * Phase 1: placeholder data; the AlarmScheduler in :scheduling handles the
 * actual exact-alarm + WorkManager catch-up wiring in Phase 2.
 */
@Composable
fun RoutinesScreen() {
    val t = DesignTokens

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = t.Density.ScreenPad.dp),
        verticalArrangement = Arrangement.spacedBy(t.Density.CardGap.dp),
        padding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Routines", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text("4 active", style = MaterialTheme.typography.labelSmall, color = t.Chrome.TextFaint)
            }
            Spacer(Modifier.height(8.dp))
        }

        items(placeholderRoutines.size) { i ->
            RoutineCard(placeholderRoutines[i])
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

private data class Routine(
    val id: String,
    val name: String,
    val botName: String,
    val botHue: Color,
    val description: String,
    val cronExpr: String,
    val idempotent: Boolean,
    val catchUpStatus: String,
    val nextRunInSecs: Long, // placeholder; live value from AlarmScheduler
)

private val placeholderRoutines = listOf(
    Routine("r1", "Nightly deploy", "sable", DesignTokens.BotHues.H1,
        "Runs the release pipeline at 02:00 if the build is green",
        "0 2 * * *", idempotent = true,
        catchUpStatus = "last missed run replayed", nextRunInSecs = 43_200),
    Routine("r2", "Price scrape", "atlas", DesignTokens.BotHues.H0,
        "Pulls the price snapshot every 30 min",
        "*/30 * * * *", idempotent = true,
        catchUpStatus = "0 missed", nextRunInSecs = 1_800),
    Routine("r3", "Inbox digest", "vex", DesignTokens.BotHues.H2,
        "Compiles the morning inbox summary at 07:30",
        "30 7 * * *", idempotent = true,
        catchUpStatus = "1 missed, catch-up queued", nextRunInSecs = 72_000),
    Routine("r4", "Table cleanup", "atlas", DesignTokens.BotHues.H0,
        "Drops last year's price snapshot tables on the first of each month",
        "0 3 1 * *", idempotent = false,
        catchUpStatus = "never missed", nextRunInSecs = 2_592_000),
)

// ---------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------

@Composable
private fun RoutineCard(r: Routine) {
    val t = DesignTokens
    var secsLeft by remember { mutableStateOf(r.nextRunInSecs) }
    // Ticker: decrement once per second. Phase 2 replaces with a shared
    // AlarmScheduler ticker so all cards stay in sync.
    LaunchedEffect(r.id) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            if (secsLeft > 0) secsLeft -= 1
        }
    }

    Surface(
        shape = RoundedCornerShape(t.Shape.CardRadius),
        color = t.Surfaces.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Row 1: name + hue dot + idempotency chip
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(r.botHue, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IdempotencyChip(r.idempotent)
            }

            // Row 2: NL description
            Text(r.description, style = MaterialTheme.typography.bodySmall, color = t.Chrome.TextSecondary)

            // Row 3: mono cron expr
            Surface(shape = RoundedCornerShape(4.dp), color = t.Surfaces.SurfaceHigh) {
                Text(
                    " ${r.cronExpr} ",
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = t.Chrome.Text,
                )
            }

            // Row 4: next-run countdown + catch-up status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "next in ${formatCountdown(secsLeft)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.Status.Working,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                CatchUpChip(r.catchUpStatus)
            }
        }
    }
}

@Composable
private fun IdempotencyChip(idempotent: Boolean) {
    val t = DesignTokens
    Surface(
        shape = RoundedCornerShape(t.Shape.ChipRadius),
        color = if (idempotent) t.Status.Ok.copy(alpha = 0.15f) else t.Status.Critical.copy(alpha = 0.15f),
    ) {
        Text(
            if (idempotent) " idempotent " else " destructive ",
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (idempotent) t.Status.Ok else t.Status.Critical,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CatchUpChip(status: String) {
    val t = DesignTokens
    val isOk = status == "0 missed" || status == "never missed"
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = (if (isOk) t.Status.Ok else t.Status.Warn).copy(alpha = 0.12f),
    ) {
        Text(
            status,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isOk) t.Status.Ok else t.Status.Warn,
        )
    }
}

private fun formatCountdown(secs: Long): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
}
