# ADR-001 — Two-Plane On-Device Multi-Bot Architecture

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** Multibot Android core team
- **Supersedes:** (none)
- **Related contracts:** [`contracts/tool-descriptor.schema.json`](../contracts/tool-descriptor.schema.json), [`contracts/invoke-envelope.schema.json`](../contracts/invoke-envelope.schema.json), [`contracts/builtin-tools-phase1.json`](../contracts/builtin-tools-phase1.json)

---

## 1. Context & Forces

The product is a **phone-only** multi-bot AI agent system:

1. **No VPS, no cloud orchestrator.** Everything that executes lives on the phone.
2. **LLM is cloud-only**, via an OpenAI-compatible endpoint. There is no on-device model in scope.
3. **Execution happens on-device in Termux.** The daemon (`botd`, Python, running inside Termux) owns all agent loops, state, and tool execution.
4. **No Shizuku in the default path.** Default installation must work for a user with a stock, unlocked, unrooted phone plus a Termux session.
5. **Shizuku must be re-addable later without an architecture change.**
6. Multiple named "bots" must run concurrently, each with its own identity, memory, skills, sessions, and schedules, with a human-facing Android app that observes and governs them.

## 2. Decisions

### D1. Two-plane architecture

| Plane | Technology | Role |
|---|---|---|
| **App plane** | Android app, Kotlin + Jetpack Compose | Human-facing **client**. Renders the instrument panel, approvals, settings, and command palette. Pure UI + state cache. |
| **botd plane** | Python daemon (`botd`) running inside Termux | **Server**. Owns agent loops, tool execution, durable state, scheduling, and the event bus. |

**botd is the server; the app is the client.** All authoritative state lives on the botd side. The app may be reinstalled, updated, or briefly killed without loss of bot state. The app connects to botd and projects what it observes.

### D2. Transport: `@multibot` abstract-namespace Unix socket

- Protocol: **newline-delimited JSON envelopes** over the abstract-namespace Unix socket `@multibot` (abstract namespace — no filesystem path, no `/tmp` collisions, no file-permission surface beyond socket peer checks).
- Envelope format is normative: see [`contracts/invoke-envelope.schema.json`](../contracts/invoke-envelope.schema.json). Requests and responses are the only message shapes; all other traffic is expressed as bus events published over the same socket.
- Peer verification: botd checks `SO_PEERCRED` (UID) so only the app user and the Termux user may connect. The app uses its privileged peer connection to invoke tools and receive bus events.
- Reconnect semantics: the app reconnects with exponential backoff and re-projects state from `state.json` / `sessions.db` snapshots; botd never trusts client-supplied authoritative state.

### D3. LLM: cloud-only, OpenAI-compatible

- The model endpoint is a triple: **`base_url` + `api_key` + `model`**, OpenAI-compatible chat/completions API.
- Stored at `~/multibot/endpoint.json`, `chmod 600`, owned by the Termux user.
- botd calls the model over outbound HTTPS. No local inference, no hybrid routing.
- Consequences:
  - Latency is network-bound; the UI treats model turns as asynchronous and streams deltas.
  - Spend is controlled **locally** through the `costly` risk tier and per-bot spend caps (see D9/D10), not by a cloud-side gateway.
  - Key loss/rotation is a first-class settings surface (Secrets), with no plaintext duplication in bot homes.

### D4. Backend registry — Shizuku-free default, Shizuku-ready by design

All execution surfaces are **backends** registered in a backend registry manifest. A tool declares the backends it can run on; the resolver picks the first available backend in declared priority order. **Adding a new backend is one backend file + one manifest entry + one UI toggle — no architecture change.** This is what keeps Shizuku cheaply re-addable later.

| Surface | Backends | Notes |
|---|---|---|
| **Shell** | `termux` (default), `libsu` (optional root), `ssh` | `termux` executes inside the Termux session; `libsu` upgrades to root-level shell where Magisk/KernelSU is present; `ssh` targets Termux's local sshd or a user-defined remote host. |
| **Device actions** | `accessibility` (base), `libsu` (optional), `assist` (human-assist fallback) | `accessibility` uses AccessibilityService for app-level automation; `libsu` covers system-level intents/commands; `assist` degrades to guided, human-performed steps when automated input is unavailable. |
| **Hardware** | Termux:API commands | `termux-take-*`, `termux-wifi-*`, `termux-notification`, wake locks, etc. (installed by the first-run wizard, D12). |

- **Shizuku is NOT in the default path.** Default capability = Termux + Accessibility + Termux:API.
- **Shizuku re-add path (future):** ship one new backend file (`shizuku` backend implementing the backend trait), add one manifest entry, add one UI toggle under *Capabilities → Backends*. Tools with `shizuku` in their `backends` array automatically pick it up through the registry. **No changes to transport, envelopes, tool contract, scheduling, or state layout.**

### D5. Durable scheduling: three layers

Scheduling is defense-in-depth; each layer covers the failure mode of the layer above:

1. **Layer 1 — botd clock + `termux-wake-lock`.** While the Termux session is alive and holds a wake lock, botd's internal clock fires jobs with sub-minute precision. This is the primary path for anything due inside the next few hours.
2. **Layer 2 — API-31 exact alarms.** The app (holding `SCHEDULE_EXACT_ALARM` where permitted) schedules exact `AlarmManager` alarms for far-out or critical-future jobs, waking the device so botd can pick them up.
3. **Layer 3 — WorkManager catch-up.** Best-effort periodic work that **reconciles** `~/multibot/jobs.json` on boot, on daemon restart, and on alarm delivery. If Layers 1–2 missed a job, this layer detects and executes it, recording `late=true` in the job history.

Job truth is `~/multibot/jobs.json` (see D7). No layer owns state; all layers read/write the file.

### D6. Per-bot home layout

Each bot has a private home directory under `~/multibot/bots/<name>/`:

```
~/multibot/bots/<name>/
├── soul.md        # bot identity, persona, standing instructions (read every session start)
├── user.md        # what the bot knows about its human user
├── memory.md      # curated long-term memory (bot edits via memory.update)
├── skills/        # bot-local skill files (markdown procedures, scripts)
├── sessions.db    # SQLite: session history, turns, tool traces (read-only from app)
├── state.json     # compact projector snapshot: current status, active turn, open approvals
└── wakeup/
    └── queue.jsonl# FIFO wakeup queue: one JSON line per queued event (cron_result,
                   # bus events addressed to this bot). Drained in order on wake.
```

Rules:

- Bots may only write inside their own home (and paths a granted approval explicitly authorizes).
- `state.json` is the cheap projection source for the app UI; `sessions.db` is the audit source.
- `wakeup/queue.jsonl` is append-only; draining rewrites the file atomically.

### D7. Global state

```
~/multibot/
├── jobs.json      # all scheduled jobs (cron + one-shots), owner bot, last/next fire
├── endpoint.json  # LLM endpoint: { base_url, api_key, model } — chmod 600
├── mcp.json       # MCP server registry (plugins)
└── secrets/       # named secret store (dir chmod 700; files chmod 600)
```

- `jobs.json` is the single scheduling truth (D5).
- `endpoint.json` holds the cloud-model credential; the app edits it through the Settings surface but botd is the only process that reads the key.
- `mcp.json` registers external MCP servers; their tools surface as `kind: "mcp"` ToolDescriptors.
- `secrets/` is referenced by name from tool `input_schema`s and from `mcp.json`; values never enter envelopes or traces.

### D8. Cron delivery model

Cron jobs **do not act as the bot**. The pipeline is:

```
scheduler fires job
   → isolated, context-less session runs the job body
   → session emits a `cron_result` event on the bus
   → event is appended to the OWNING bot's wakeup/queue.jsonl
   → the bot wakes, reads its own soul/memory/skills, and acts
     in its own context on the result
```

Consequences:

- The cron runner is stateless and disposable; a failed run is a failed `cron_result`, not a corrupted bot context.
- The bot's reaction is a normal turn in its own session, fully observable in `sessions.db`.
- Bots can also enqueue arbitrary events for other bots (addressed bus events) — the same queue drains them — enabling bot-to-bot collaboration with one mechanism.

### D9. Tool contract: uniform `ToolDescriptor`

Every tool — builtin, custom, or MCP-provided — is described by one uniform descriptor, validated against [`contracts/tool-descriptor.schema.json`](../contracts/tool-descriptor.schema.json):

| Field | Meaning |
|---|---|
| `id` | Globally unique tool id, e.g. `shell.run`. |
| `kind` | `builtin` (shipped), `custom` (bot-local), `mcp` (MCP server). |
| `provider` | `core` \| `botd` \| `bot:<id>` \| `mcp:<name>` — who serves the tool. |
| `name` / `description` | Model-facing name and description (this is what the LLM sees). |
| `input_schema` | JSON Schema object for tool arguments. |
| `risk` | One of the risk tiers below (D10). |
| `risk_rules` | Optional per-rule overrides (pattern/argument conditions that escalate or de-escalate risk at call time). |
| `backends` | Execution backends this tool can run on (D4); resolver picks the first available in order. |
| `phase` | 1–4 build-phase in which the tool ships; the registry exposes only tools at or below the active phase. |
| `default_allowed` | Whether the tool is enabled by default before a human opts out. |

Phase 1 builtin set: [`contracts/builtin-tools-phase1.json`](../contracts/builtin-tools-phase1.json).

The app never implements tool execution; it renders descriptor metadata, sends **invoke envelopes** (D2), and resolves approvals.

### D10. Risk tiers

Five tiers, enforced **in botd** (the server is the enforcement point; the app is the presentation point):

| Tier | Examples | Execution policy |
|---|---|---|
| `read` | `files.read`, `memory.search` | Silent. Traced with a **trace chip** in the UI. |
| `write` | `files.write`, `memory.update`, `shell.run` | Silent. Traced with a trace chip. |
| `destructive` | file deletion, session reset | **Needs approval.** Human approves in-app; the invocation blocks until the verdict arrives. |
| `costly` | LLM calls with paid usage, any billed API | **Spend-cap gated.** Allowed only within the bot's remaining cap; the cap itself is a settings value. |
| `privileged` | backend escalation, approval actions themselves | **Needs approval.** Always, regardless of rules, unless an explicit standing grant exists. |

Summary rule: **`read` + `write` = silent (trace chip); `destructive` + `privileged` = needs-approval; `costly` = spend-cap gated.** `risk_rules` may raise an individual call (e.g., `shell.run` containing `rm -rf` escalates `write` → `destructive` for that call) but not lower `privileged`.

Every approval produces an audit record (referenced by `trace.approval_ref` in the envelope) with tool, args, verdict, verdictor, and timestamp.

### D11. Settings tree

The app's Settings surface is a mirror of botd-owned configuration; the app is a pure editor.

```
Settings
├── Team                      # bots: names, hues, homes, enable/disable
├── Capabilities
│   ├── Tools                 # ToolDescriptor toggles + risk_rules editor + spend caps
│   ├── Skills                # skill file management per bot
│   ├── Plugins & MCP         # mcp.json registry management
│   └── Backends              # backend registry status + toggles (termux, libsu, ssh,
│                              #   accessibility, assist, Termux:API; shizuku when added)
├── Model                     # endpoint.json: base_url, api_key (secret-ref), model
├── Routines                  # jobs.json: cron + one-shot schedule editor
├── Secrets                   # secrets/ store management
├── Durable                   # wake-lock policy, alarm permissions, WorkManager cadence
└── Privacy & Security        # audit log access, approval policy, retention, data export/wipe
```

### D12. Distribution

One artifact per release on **GitHub Releases**:

```
multibot-android-vX.Y.Z/
├── app-release.apk            # Compose app
├── botd/                     # Python payload: daemon, backends, scheduler
└── manifest.json             # SHA-256 digests of every file + required Termux packages
```

First-run wizard flow (in the app):

1. Verify `manifest.json` digests for every payload file.
2. Offer/perform installation of **Termux**, **Termux:API**, and **Termux:Boot** from F-Droid, each **SHA-256 verified** against digests in `manifest.json` before handing the APK to `PackageInstaller`.
3. Confirm a Termux session exists, boot `botd`, open the `@multibot` socket, and complete a handshake + capability probe (which backends actually resolve on this device).
4. Create the user's first bot home and the global state tree (D6/D7).

The wizard is idempotent: re-running on an existing install only re-verifies and re-handshakes.

## 3. Consequences

**Positive:**

- App and daemon evolve independently; the app is replaceable and stateless.
- Default install is achievable on stock hardware; capability upgrades (root, Shizuku later) are additive, registry-driven.
- One tool contract covers builtins, bot-local custom tools, and MCP servers — the model sees a uniform surface.
- Approvals, traces, and audit are all server-side facts, so any client (today the app, tomorrow another) can render truth.
- Scheduling survives app death and reboots through three overlapping layers.

**Costs / accepted risks:**

- Termux session lifetime is the practical bound of Layer-1 scheduling; work must be schedulable under Doze, or late-execution is accepted (recorded as `late=true`).
- The abstract-namespace socket is only as private as `SO_PEERCRED` peer checks; we accept this on single-user consumer devices and document it in Privacy & Security.
- Cloud-only LLM means offline = no agent reasoning (deterministic cron results can still queue and fire `cron_result` events; the bot reacts when connectivity returns).

## 4. Alternatives Considered

| Alternative | Why rejected |
|---|---|
| App-hosted agent loop (no botd) | Compose app cannot own durable execution, sockets, or scheduling across process death; capability surface (shell, device, hardware) is tiny without Termux. |
| Shizuku-first default | Adds an onboarding step (Shizuku app + device authorization) for a capability not needed on the default path; registry pattern defers it at zero architectural cost. |
| Local LLM (on-device) | Out of scope by product constraint; cloud-only keeps model quality high and the daemon simple. |
| `/tmp` filesystem socket instead of abstract namespace | Filesystem sockets carry permission-bit and stale-path failure modes; abstract namespace is simpler and collision-free on Android. |
| Per-bot separate daemons | One botd with per-bot homes is simpler: shared bus, shared scheduler, one process to keep alive; isolation is enforced by the home-layout rule, not by process boundaries. |

## 5. Follow-ups

- [ ] Contract examples: sample invoke envelope, trace chip rendering spec (app side).
- [ ] `risk_rules` schema (array element shape) — defined in the tool contract repo when Phase 1 lands.
- [ ] Shizuku backend stub manifest entry (placeholder, inert until implemented).
