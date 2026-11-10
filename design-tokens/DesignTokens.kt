package com.phonebot.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Phonebot design system — dark "instrument panel" (Linear + M3 Expressive + Liquid Glass).
 * App chrome is monochrome; each bot carries its own hue. Dark-only for now.
 *
 * Values mirror docs/design-tokens.md. Colors are exact hex; type specs are
 * set in sp so they survive display scaling.
 */
sealed object DesignTokens {

    // ------------------------------------------------------------------
    // Surfaces (monochrome chrome)
    // ------------------------------------------------------------------
    sealed object Surfaces {
        val Background = Color(0xFF0B0D10)      // deepest panel
        val Surface = Color(0xFF14171C)
        val SurfaceContainer = Color(0xFF1B1F26)
        val SurfaceHigh = Color(0xFF232833)
        val Glass = Color(0x591B1F26)           // Liquid Glass — 35% container over blur
        val GlassStroke = Color(0x33F2F4F8)     // 20% hairline
    }

    // ------------------------------------------------------------------
    // Chrome (monochrome text + outline)
    // ------------------------------------------------------------------
    sealed object Chrome {
        val Text = Color(0xFFF2F4F8)
        val TextSecondary = Color(0xFF9AA3B2)
        val TextFaint = Color(0xFF5A6272)
        val Outline = Color(0xFF2A3040)
        val OutlineSoft = Color(0xFF1E2330)
    }

    // ------------------------------------------------------------------
    // Status colors (instrument-panel semantics)
    // ------------------------------------------------------------------
    sealed object Status {
        val Working = Color(0xFFFFB020)   // amber — scanline animation on bot cards
        val Waiting = Color(0xFF4D9FFF)  // info blue — pulse animation
        val Ok = Color(0xFF3DD68C)       // done / healthy
        val Warn = Color(0xFFE8853D)     // caution, non-blocking
        val Critical = Color(0xFFFF4D5E) // needs approval / failed
        val Idle = Color(0xFF5A6272)     // dormant
    }

    // ------------------------------------------------------------------
    // Bot hues — one per bot, used for avatar rings, accents, thread markers.
    // Hues are spaced around the wheel so simultaneous bots never collide.
    // ------------------------------------------------------------------
    sealed object BotHues {
        val H0 = Color(0xFF4DA3FF) // sapphire
        val H1 = Color(0xFF3DD68C) // mint
        val H2 = Color(0xFFC58BFF) // orchid
        val H3 = Color(0xFFFF8A5C) // coral
        val H4 = Color(0xFFE8E85C) // chartreuse
        val H5 = Color(0xFF4DD0E8) // cyan

        fun forIndex(index: Int): Color = when (index % 6) {
            0 -> H0; 1 -> H1; 2 -> H2; 3 -> H3; 4 -> H4; else -> H5
        }
    }

    // ------------------------------------------------------------------
    // Type scale
    // ------------------------------------------------------------------
    sealed object Type {
        val Display = TypeSpec(28.sp, 34.sp)          // screen titles, Observatory digest
        val Title = TypeSpec(20.sp, 26.sp)            // card titles, section headers
        val Body = TypeSpec(15.sp, 22.sp)             // default text
        val BodySm = TypeSpec(13.sp, 18.sp)           // activity lines
        val Caption = TypeSpec(11.sp, 14.sp)          // meta, chips, countdowns
        val Mono = TypeSpec(12.sp, 16.sp)             // cron exprs, commands, trace chips
    }

    // ------------------------------------------------------------------
    // Motion (instrument feel: fast, tight, no springy overshoot)
    // ------------------------------------------------------------------
    sealed object Motion {
        val TapMs = 120
        val ChipMs = 180
        val SlideMs = 240
        val ScanlineMs = 1400  // repeating, linear, no easing
        val PulseMs = 1600     // repeating, ease-in-out
    }

    // ------------------------------------------------------------------
    // Radii / density (M3 expressive but disciplined)
    // ------------------------------------------------------------------
    sealed object Shape {
        const val CardRadius = 20f
        const val ChipRadius = 999f
        const val InputRadius = 12f
        const val BlockRadius = 8f  // mono command blocks
    }

    sealed object Density {
        const val ScreenPad = 20f    // 20dp horizontal page gutter
        const val CardGap = 12f
        const val StackGap = 8f
    }
}

/** Pair of (fontSize, lineHeight) to keep type specs declarative. */
data class TypeSpec(val size: TextUnit, val lineHeight: TextUnit)
