# Multibot Design Tokens

- **Version:** 1.0
- **Status:** Accepted — normative for the app plane (Kotlin/Compose). botd-side tracing reuses the status colors where rendered in a terminal (ANSI mapping in §7).
- **Companion doc:** [`ADR-001-architecture.md`](./ADR-001-architecture.md)

The instrument-panel philosophy: the chrome is a monochrome instrument; color belongs to the team (bots) and to state (status). Everything else is surface, hairline, and type.

---

## 1. Surfaces & Borders

| Token | Value | Usage |
|---|---|---|
| `--surface-canvas` | `#0A0B0D` | Window background. The base of the stack. |
| `--surface-layer-1` | `#101113` | Panels: main views, side rails, list containers. |
| `--surface-layer-2` | `#16181B` | Cards, dialogs, command palette, approval cards, trace chips. |
| `--border-hairline` | `rgba(255, 255, 255, 0.06)` | All 1px dividers, card outlines, palette separators. |

Rules:

- Maximum **three** stacked layers (canvas → L1 → L2). Anything at L2 already reads as "lifted"; never stack further.
- Hairline borders everywhere a layer meets another layer. **No drop shadows on chrome** — elevation is expressed by the surface step, not blur.
- Interactive surfaces (buttons, toggles, rows) stay on their layer; press states use a `+4%` white overlay, not a new layer.

## 2. Text & Type

**Families:**

| Role | Family |
|---|---|
| UI (labels, body, titles) | **Inter Variable** (weights 400/500/600/700) |
| Traces, commands, cron expressions, code, counters | **JetBrains Mono** (400/500) |

Type never carries bot color — a bot's hue is not a type color. Type carries *emotion* through weight, case, and motion (principle 4).

**Scale:**

| Token | Size | Family / Weight | Usage |
|---|---|---|---|
| `--text-micro` | 11sp | Inter / 500, uppercase, 0.04em tracking | Section eyebrows, status chip labels |
| `--text-caption` | 11sp | JetBrains Mono / 500 | Trace chip internals, counters |
| `--text-body-s` | 13sp | Inter / 400 | Secondary body, list sub-lines |
| `--text-body` | 15sp | Inter / 400 | Default body |
| `--text-title` | 17sp / 22sp | Inter / 600 | Card titles / screen titles |
| `--text-display` | 28sp | Inter / 700 | Dashboard headline numbers |
| `--text-mono` | 12–13sp | JetBrains Mono / 400 | Commands, cron expressions, session ids, trace lines |

**Text colors:**

| Token | Value | Usage |
|---|---|---|
| `--text-primary` | `#F5F6F7` | Body, titles, primary values. |
| `--text-secondary` | `#9BA1A8` | Sub-lines, field labels, disabled-but-readable. |
| `--text-tertiary` | `#5E646B` | Timestamps, empty states, ghost affordances. |

## 3. Status System

Status is the emotional backbone of the panel. Five states:

| State | Token | Color | Behavior |
|---|---|---|---|
| `idle` | `--status-idle` | `#5E646B` | Static dot, no animation. |
| `working` | `--status-working` | `#22D3EE` (cyan) | **Scanline**: an 8px cyan bar sweeps horizontally across the indicator (avatar ring segment / progress hairline), 1.2s linear infinite. |
| `waiting` | `--status-waiting` | `#F0B429` (amber) | **Pulse**: 150% → 100% scale with opacity, 1.5s ease-in-out infinite. Used for "needs approval" and "queued" states. |
| `failed` | `--status-failed` | `#EB5757` | Solid, static; pairs with a trace chip that carries the failure reason in mono. |
| `ok` | `--status-ok` | `#27A644` | Transient: shows for ~1.2s after a completed turn, then decays to `idle`. |

Rules:

- Status color applies to **indicator shapes only** (rings, dots, scanlines, pulses). Never to chrome backgrounds.
- A bot's hue (see §5) tints the *same indicator shapes* — hue + status combine as ring-base + scanline/pulse overlay.
- Terminal mapping (botd side, §7): idle = default, working = bright cyan, waiting = bright yellow, failed = bright red, ok = bright green.

## 4. Motion

| Token | Curve / duration | Usage |
|---|---|---|
| `--motion-standard` | 200ms, `cubic-bezier(0, 0, 0, 1)` (M3 ease-out) | All chrome: palette open/close, card entry, list diffs, tab switches. |
| `--motion-spring-gentle` | M3 expressive spring, ≈450ms settling (`0.2/0.0/0.0/1.0` family) | "Alive" moments: a bot waking, a `cron_result` landing in a queue. |
| `--motion-spring-bold` | M3 expressive spring, ≈550ms, more overshoot (`0.4/0.0/0.2/1.0` family) | Approval verdicts: card resolving to granted/denied. |
| `--motion-scanline` | 1.2s linear infinite | Working-state sweep (status). |
| `--motion-pulse` | 1.5s ease-in-out infinite | Waiting-state pulse (status). |

Rules:

- Standard 200ms ease-out is the default for everything; springs are reserved for moments where a *bot is the actor* (waking, finishing, being approved). Chrome itself is never springy — it is an instrument, not a character.
- Respect `Reduce motion`: scanline becomes a static cyan bar; pulse becomes a static amber dot; springs become 100ms fades.

## 5. Bot Hue System

- Each bot is assigned a hue from a **stable hash of its name** (FNV-1a of the lowercase name → hue 0–359), rendered through **M3 dynamic color** tone families: `container` (≈40%), `onContainer` (near-black or white by contrast), and a `tone-20` variant for tints.
- Same name → same hue on every device, forever. Renaming a bot re-hashes; the old hue is not preserved.
- **Allowed uses of a bot hue — exactly three:**
  1. **Avatar ring** — the thin ring around the bot's initial;
  2. **Status pulse** — the tinted base under scanline/pulse indicators;
  3. **Thread accent** — the 2px left border / message-sender accent in that bot's threads and trace streams.
- **No other uses.** Bot hue never appears on chrome, buttons, surfaces, or semantic status colors. When a bot is working, the ring shows its hue and the *overlay* shows `--status-working` cyan.
- If a bot has no name yet (wizard step), the ring renders in `--text-tertiary`.

## 6. Design Principles

1. **Instrument panel, not chat app.** The primary surface is a dense, readable dashboard of bots, sessions, traces, and pending approvals. Conversations are one view, not the whole product. No bubble-centric layout.
2. **Everything observable.** Every bot action — read, write, tool call, cron fire, approval — produces a trace chip. Nothing happens silently. The panel's default state shows *what is happening right now*, not an empty welcome screen.
3. **Approvals are first-class.** Approvals get their own queue, their own surface, keyboard-shortcut fast paths (swipe up → `approve <corr_id>`), and a full audit record. They are never a buried dialog.
4. **Type carries emotion.** The system does not emote through mascots, emoji, or cartoon motion. Emotion is conveyed by weight, case, and motion: a bold spring when a bot wakes, a heavy `failed` chip in red mono. Color is reserved for status and bot identity (§3, §5).
5. **One swipe up = command palette.** A single upward swipe anywhere on any screen opens the palette: every command, every bot, every tool, search by name or id. It is the only global gesture.

## 7. Usage Rules & Terminal Mapping

**Do:**

- Reach for hairline borders before new layers; layers before shadows (never).
- Use JetBrains Mono for anything a human might copy: commands, cron expressions, ids, trace lines.
- Pair status and hue on the same indicator shape (ring = hue, overlay = status).
- Animate *actors* (bots, verdicts) with springs; animate *chrome* with 200ms ease-out.

**Don't:**

- Don't put bot hue on chrome. Chrome is monochrome; the team carries the color.
- Don't use `--status-failed` red for anything non-fatal (error text in mono, chips, dots only).
- Don't stack more than three surface layers or use shadows in the panel.
- Don't represent a silent write — every read/write call must surface as a trace chip.

**ANSI mapping (botd terminal traces):**

| Status | ANSI |
|---|---|
| idle | default |
| working | bright cyan (96) |
| waiting | bright yellow (93) |
| failed | bright red (91) |
| ok | bright green (92) |
