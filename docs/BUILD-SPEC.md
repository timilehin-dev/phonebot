# Phonebot — Build Specification

> **Status:** Authoritative product brief. Written by the product owner as a handoff
> spec: a developer (or a fresh build agent) should be able to build phonebot from
> scratch using this document + `ADR-001-architecture.md` + `contracts/` +
> `design-tokens.md`. Where this document and the codebase disagree, the code
> (contracts, especially) is the schema of record and this document is the intent.
>
> **Repo:** `github.com/timilehin-dev/phonebot` · **Package:** `com.phonebot.*`
> **Daemon home:** `~/phonebot` · **Socket:** `@phonebot` (abstract namespace)

---

## 1. What phonebot is

Phonebot is a **team of autonomous AI bots that live on your Android phone and
never stop working** — the phone *is* the server. It merges two research results
we deliberately combined:

- **The team metaphor (from xAI's Grok Bot):** a roster of named,
  role-specialized bots that share one durable computer, message each other over
  a typed bus, are aggregated by a chief-of-staff bot, discover proactive jobs on
  their own, and escalate to the human only when a decision matters (urgent vs.
  fyi, with approval gates).
- **The file-based self-improving core (from Nous Research's Hermes Agent):**
  every bot is a folder of plain files (`soul.md`, `user.md`, `memory.md`,
  `skills/`) that the bot reads at start, writes to as it learns, and can
  ship/re-import on any other device. Skills are markdown files with YAML
  front-matter (progressive disclosure: name + description always in context,
  body loaded on demand). Sessions are SQLite + FTS5, searchable.

The product: an Android app (**"The Observatory"**) where the user watches and
steers their bot crew — a mission-control **instrument panel, not a chat app**.

## 2. Hard constraints (non-negotiable)

1. **Phone-only. No VPS, no hosted orchestrator, no cloud backend.** All durable
   state and scheduling live on the device. The phone survives app kills, lock
   screen, and reboot — that is the entire durability story.
2. **Cloud LLMs only, via any OpenAI-compatible endpoint** (`base_url` +
   `api_key` + `model`). No local/on-device models. OpenRouter
   (`https://openrouter.ai/api/v1`) is the reference integration; the client must
   be endpoint-agnostic (send `HTTP-Referer`/`X-Title` attribution headers when
   `base_url` is openrouter.ai).
3. **Termux is the durable "cloud computer."** Deep Termux integration:
   `termux-wake-lock`, Termux:Boot (boot persistence), Termux:API
   (notifications/SMS/TTS/location), Termux:API `RUN_COMMAND`, and **SSH into
   Termux's own sshd** (loopback, port 8022) as the app→daemon control channel.
4. **No Shizuku, no root by default.** Device control goes through
   Accessibility (base) + Termux:API. BUT: build a **backend registry** so
   Shizuku is a one-file re-add later (a `ShizukuShellBackend` placeholder class
   that raises "add Shizuku backend here", registered in the manifest/UI toggle,
   excluded from defaults).
5. **No permission the user can't grant in one tap.** Anything that can't be
   granted → degrade gracefully, never block.
6. **Secrets hygiene:** API keys are referenced by name (`api_key_ref`),
   resolved in order: env `PHONEBOT_KEY_<REF>` → `~/phonebot/secrets/<REF>.key`
   (chmod 600) → fallback `OPENAI_API_KEY`. Keys **never** appear in chat logs,
   trace records, or the bus journal. In the UI, users type keys into a wizard
   that writes them through the app into the vault; the key is never echoed back.

## 3. Two-plane architecture

```
┌───────────────────────────── Android app (com.phonebot.app) ─────────────────────────────┐
│  Kotlin/Compose: The Observatory UI · approvals inbox · command palette · Settings      │
│  credentials (Android Keystore vault) · first-run wizard (PackageInstaller)             │
│  scheduling (exact alarms + WorkManager) · Termux:API intents (RUN_COMMAND, sms/tts)    │
└───────────────────────────────────────────────┬──────────────────────────────────────────┘
                                                │  SSH (JSch) to 127.0.0.1:8022
                                                │  runs:  python -m phonebot.cli <type> '<json>'
                                                ▼
┌───────────────────────────── Termux (durable plane) ────────────────────────────────────┐
│  phonebotd (Python 3 asyncio daemon, started by Termux:Boot / nohup):                  │
│   • IpcServer   — abstract-namespace Unix socket @phonebot, JSON-lines (NDJSON)         │
│   • AgentLoop   — thin ~500-line loop, tiered prompts, tool dispatch, compression      │
│   • MessageBus  — task/result/needs-approval/fyi/cron_result, urgent vs fyi            │
│   • CronScheduler — 30s tick, isolated sessions, idempotent boot catch-up               │
│   • ToolRegistry — 11 contract tools, risk gating, backend registry                    │
│   • per-bot homes: ~/phonebot/bots/<name>/{soul,user,memory}.md, skills/,              │
│                    sessions.db (SQLite+FTS5), wakeup/queue.jsonl, state.json           │
│   • home-level: config.json, endpoint.json (600), jobs.json, secrets/ (600)           │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**Why the app talks to botd through SSH + a CLI, not a direct socket:** the
daemon binds an *abstract-namespace* Unix socket (`\0phonebot`). Abstract
addresses are namespace-wide and UID-gated; an Android app process cannot
address them from the JVM (`java.net.UnixDomainSocketAddress` is
filesystem-namespace only, and even that is API 34+). The device-reliable,
no-root path is to **shell into Termux** (Termux's own sshd, loopback,
re-surviving boot via Termux:Boot) and run the thin in-Termux socket client
`python -m phonebot.cli`. That CLI re-derives the address from the same config
as the daemon, so the two can never drift. The app uses JSch
(`com.github.mwiede:jsch`), auth = Ed25519 keypair generated by the app
(public half pushed to Termux via `termux-ssh-auth`), with password as wizard
fallback.

**Fallback plane (still required):** Termux:API `RUN_COMMAND` intents for
`termux-notification` / `termux-sms` / `termux-tts-speak` — used for the
approval-inbox nudge and hardware touches; these run in the live Termux session
and do not require SSH to be configured.

## 4. Execution plane: phonebotd (Python, asyncio)

Single daemon, two long-lived tasks on one event loop: the cron tick loop and
the IPC socket server. SIGTERM/SIGINT → flush, stop, close in reverse
dependency order. Safe to start repeatedly (stale socket cleanup).

### 4.1 Bot home layout

```
~/phonebot/
├── config.json        # socket_name, llm{base_url,api_key_ref,model,timeout_s,mode},
│                      # backend{shell,python,ssh_*}, caps
├── endpoint.json      # LLM endpoint overrides, chmod 600
├── jobs.json          # {"version":1,"jobs":{...}} — cron schedule (bot-owned, app-visible)
├── secrets/           # <ref>.key files, chmod 600
└── bots/
    └── <bot>/
        ├── soul.md            # persona/role charter (the bot's self-model)
        ├── user.md            # what this bot knows about the human
        ├── memory.md          # curated long-term facts
        ├── skills/            # *.skill.md with YAML front-matter (name, description, when-to-use)
        ├── sessions.db        # SQLite: sessions, turns, trace + FTS5 virtual table turns_fts
        ├── wakeup/queue.jsonl # durable wakeup journal (the bot's inbox, survives everything)
        ├── state.json         # per-bot flags + spend cap accounting (paused, spend_usd, caps)
        └── registry.json      # bot-owned tools/skills it has created
```

### 4.2 AgentLoop (own thin loop — do NOT adopt Okia/LangGraph/Agents SDK;
borrow patterns only)

- **Tiered system prompt:** *stable* (soul + tool contract + safety rules) →
  *context* (user.md, memory.md, loaded skill bodies, recent session summary) →
  *volatile* (this turn's trigger).
- **Triggers:** `user_message` (from app trigger or cron prompt),
  `bus_message` (from bus/wakeup drain), `cron_result`.
- **Tool dispatch loop:** up to 8 rounds; each LLM `tool_call` → risk
  evaluation → backend dispatch → result appended; emit a trace record per step
  (`tool`/`llm`/`handoff`/`approval`/`error` with backend, duration_ms,
  approval_ref).
- **Context compression:** when context exceeds the threshold, keep head 4 +
  tail 12 turns verbatim, summarize the middle.
- **LLMClient protocol:** implement `OpenAICompatClient` (httpx, POST
  `{base_url}/chat/completions`, streaming-capable; normalize `tool_calls`
  JSON-strings → dicts; preserve `usage`), `FakeLlmClient` (deterministic
  offline, emits one `files.write` round — used by tests and bare devices), and
  `auto` resolution (real HTTP only when a key resolves, else fake — the safe
  default on a fresh phone).
- **Sessions persist** to `sessions.db` after every turn; FTS5 powers
  `memory.search` over past turns with a LIKE fallback.

### 4.3 Message bus

- Topics: `task`, `result`, `needs-approval`, `fyi`, `cron_result`.
- Every event is **urgent or fyi** — urgent always wakes the recipient; fyi is
  rate-limited (≤20/min per pair, urgent ≤5/min; overflow drops fyi first,
  never urgent).
- Persistent journal: every event also appends to the target bot's
  `wakeup/queue.jsonl`, so a bot that was offline/never ran loses nothing.
- The **chief-of-staff bot subscribes to ALL events** — it is the aggregator
  that decides what reaches the human ("one signal, not ten pings").

### 4.4 Cron + wake model

- `jobs.json` holds per-bot schedules:
  `{"id","bot","skill"?,"prompt","cron" (5-field) or "interval_seconds","idempotency":"once_per_window"|"safe"}`.
- Scheduler ticks every 30s (botd clock, held alive by `termux-wake-lock`);
  on the **first tick after boot** it replays missed windows **once per
  window** (idempotency) — i.e. *late-but-never-lost*.
- **Crons run in isolated AgentLoop sessions** (no conversation history: job
  prompt + named skill + the three prompt tiers). The outcome is serialized as
  a `cron_result` and appended to the **owning bot's** wakeup queue — the bot
  wakes in its own session, reads it, and decides: log it, cascade to another
  bot via the bus, escalate to the human (urgent), or go back to sleep.
  **A cron cannot create crons and cannot approve its own output.**
- Bot wakes: `peek_wakeup` → run a turn → `drain_wakeup` (mutating); the
  app-facing `list_inbox` uses a non-mutating `list_wakeup` so observing the
  queue never consumes it.

### 4.5 Three-layer durable scheduling (the durability guarantee)

| Layer | Mechanism | Guarantees |
|---|---|---|
| L1 | botd clock + `termux-wake-lock` + Termux:Boot restart | exact timing while the device is awake; survives reboot on the daemon side |
| L2 | App: `SCHEDULE_EXACT_ALARM` (API 31+, user-gated) | the clock the OS endures longest; fires the app → nudges botd |
| L3 | App: WorkManager periodic (`CHARGING + IDLE`) catch-up worker | asks botd to **replay missed windows** — late but never lost |

OEM battery savers can delay L2/L3 on aggressive devices; L3 is the floor on
every phone. Root (a libsu backend, out of scope by default) would be the only
full escalation. The app's catch-up worker triggers the chief-of-staff with a
`{"trigger":"catch_up"}` run, not a dedicated IPC type.

## 5. Tools & capabilities

### 5.1 Uniform tool contract

Every tool is a `ToolDescriptor`:
`{id, kind: builtin|custom|mcp, provider, name, description, input_schema, risk, backends[], phase, default_allowed}`
(JSON Schema of record in `contracts/tool-descriptor.schema.json`).

**Risk tiers** (registry orders `LOW < MEDIUM < HIGH < DESTRUCTIVE`):
`read → LOW`, `write → MEDIUM`, `costly/privileged → HIGH`,
`destructive → DESTRUCTIVE`. Dangerous-command detection on `shell.run`
escalates to DESTRUCTIVE and **fails closed** (blocks, emits
`needs-approval`, does not execute).

**Invoke envelope** (`contracts/invoke-envelope.schema.json`):
request `{tool, args, ctx{bot, session, turn, corr_id, priority}}`;
response `{corr_id, status: ok|error|needs_approval, result, error{code,message}, trace{backend,duration_ms,approval_ref}, side_effects{files_touched,bus_events}}`.

### 5.2 Phase-1 built-in tools (the authoritative 11 — arg names are frozen;
the cross-language conformance test enforces them)

| id | args | risk |
|---|---|---|
| `shell.run` | `command, cwd?, env?, timeout_ms?` | read → LOW; dangerous command → DESTRUCTIVE |
| `files.read` | `path, offset?, limit?` | LOW |
| `files.write` | `path, content, append?` | MEDIUM |
| `files.search` | `path, pattern, glob?, case_sensitive?` | LOW |
| `web.search` | `query, max_results?` | MEDIUM |
| `web.extract` | `url, selector?` | MEDIUM |
| `memory.update` | `target (user\|memory\|soul), operation (append\|replace), content, old_content?` | MEDIUM |
| `memory.search` | `query, target?, limit?` | LOW |
| `request_approval` | `action, rationale` | DESTRUCTIVE (always escalated) |
| `bus_publish` | `topic, payload, priority?` | MEDIUM |
| `list_inbox` | `bot?, limit?` | LOW |

Files are **scoped** (termux home + bot home + shared roots, symlink-resolved;
read cap 512 KB; shell output cap 256 KB). Shell backends: `termux` (default —
in-Termux subprocess inheriting `TERMUX_*`), `ssh` (asyncssh), `shizuku`
(**placeholder**: raises "add Shizuku backend here"). The dangerous-command
detector must be conservative: any `rm` with recursive/force flags (including
split flags like `-i -f` and long `--force`) escalates; `rm file.txt`,
`ls -la`, dashed filenames stay clean.

### 5.3 Meta-tools (Phase 3, but design the seams now)

- **`create_tool`** — a bot registers its own tool (Python snippet or termux
  script) into its `registry.json`; reusable in later sessions.
- **`create_skill` / `update_skill`** — write Hermes-style `.skill.md`
  (markdown + YAML front-matter); progressive disclosure.
- **`create_bot`** — approval-gated (spawns a new home). The new bot gets a
  **least-privilege home, zero inherited credentials**; the user's caps and
  allowlists start empty.
- **`mcp_connect` / `mcp_list` / `mcp_call`** — connect to external MCP servers
  on the user's behalf (e.g. "set up Box from this install URL"). On connect,
  whatever API key the user supplies goes into the **secrets vault** (scoped by
  connection name, survives reboot/reinstall, never logged, resolvable across
  sessions).
- **`store_secret` / `use_secret`** — named references; the value itself never
  appears in chat.

### 5.4 Backend registry (the "easy to add Shizuku later" requirement)

Backends are keyed strings in `config.json` (`backend.shell`,
`backend.python`): `termux | ssh | shizuku`. Registry pattern: a dict of name →
backend factory; unknown names fail validation with the list of known names.
Adding Shizuku = one new backend class + registry entry + one manifest/UI
toggle. Same pattern for device backends: `accessibility | assist`, and
hardware helpers via Termux:API (`termux-sms`, `termux-tts-speak`,
`termux-notification`, location).

## 6. Android app (com.phonebot.app)

Kotlin + Jetpack Compose, dark-only, minSdk 33, target/compile 35, Kotlin 2.x,
AGP 8.x. The app is the **control / UI / credentials plane only** — it never
executes bot work locally.

### 6.1 Modules

- `:app` — single-activity Compose shell, the Observatory screens, settings
  tree, Room (threads/approvals/routine mirror), DataStore (wizard state,
  roster), foreground nudge service, boot receiver, exact-alarm scheduler +
  WorkManager catch-up.
- `:agent-core` — protocol types shared by all modules: `InvokeEnvelope`
  (must match the contract schema verbatim), `LlmClient` (Ktor streaming
  OpenAI-compatible client — real SSE parse, not stubs — with 4xx guards and
  OpenRouter attribution headers), `ToolDescriptor`, `ToolRegistry` client
  model.
- `:termux-bridge` — `TermuxBridge` facade + `BotdSshClient` (JSch: loopback
  sshd 8022, key/password auth, one exec channel per call →
  `python -m phonebot.cli`) + Termux:API intent helpers + `PackageInstallerFlow`
  (F-Droid APK download, SHA-256 verify, install via `PackageInstaller` on API
  35+ / system-installer intent below).
- `:scheduling` — `AlarmScheduler` (exact alarms, API 31+ with permission
  gate) + `MissedRunCatchUpWorker` (WorkManager, CHARGING + IDLE) +
  `OnAlarmFiredReceiver`.
- `:credentials` — `SecretVault` (Android Keystore-wrapped AES keys; named
  refs; values encrypted at rest).
- `:wizard` — the first-run state machine (steps below), testable without a
  device.
- `design-tokens/` — the sealed design-token object, shared via `:app`
  source-sets.

### 6.2 App → botd control plane (typed wrappers over the CLI)

`run_bot(bot, trigger)` · `approve(corr_id)` · `deny(corr_id)` ·
`pause_bot` · `resume_bot` · `get_state` (per-bot: paused, spend_usd,
pending_wakeup) · `list_jobs` · `llm_request(model, messages, tools?, stream?)`
· `update_payload(bot, json)` · `is_botd_up()` (ping over the socket via SSH).
Each returns the parsed reply or `null` when the transport is degraded
(sshd down / botd not deployed / home missing) — the UI surfaces "botd
offline", never a crash. Push frames (`bus_event`, fallback `llm_response`) are
observed on a persistent connection owned by a background service in Phase 2.

### 6.3 First-run wizard (auto-setup)

Steps, each `check() / execute() / repair()`:

1. **Install Termux** — F-Droid APK, SHA-256 verified, PackageInstaller.
   **Must be the F-Droid build** (the Play Store build's integrity check breaks
   proot/background use — surface this in the UI).
2. **Install Termux:API** — needed for notifications/SMS/TTS + RUN_COMMAND.
3. **Deploy botd** — push the botd payload tarball into Termux, run
   `botd-install.sh` (`pkg install python`, pip-install pinned deps, lay out
   `~/phonebot`, run selftest, note Termux:Boot). Idempotent, re-runnable.
4. **Register boot** — Termux:Boot + `termux-wake-lock`.
5. **Enable wake-lock / battery exemption** —
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` gate.
6. **Connect LLM** — user types base_url + model + key (the key goes to the
   vault; verify with a live ping expecting 200, not 401).
7. **Onboard bot** — create the first bot + the chief-of-staff.

### 6.4 Settings convention (follow Hermes)

`Settings → Capabilities → Tools` (per-tool allow/ask/block per risk tier +
backend picker: shell termux/ssh/shizuku-locked, python, device
accessibility/assist) · `→ Skills` · `→ Plugins/MCP` · `→ Backends` ·
`→ Routines`, plus the usual (bots roster, approvals policy, spend caps).
Every screen's copy should teach the model: "this is what your bots can do,
and you choose the blast radius."

## 7. Security model (security is the first constraint, not a chapter)

- **Blast radius = Termux.** No root, no Shizuku by default, so the worst case
  of a rogue tool is confined to the Termux user + user-installed Termux
  packages.
- **Per-bot least privilege:** `tools.allow` list, scoped credentials
  (per-bot secret refs), per-bot **spend caps** (`state.json`, default $5.00,
  hard-denied at the LLM layer when exceeded).
- **Dangerous commands → approval inbox:** the botd gate emits
  `needs-approval` (fail closed when no app channel exists — a blocked call is
  a *single terminal* "denied by policy" result, never "ran anyway"). The app
  pushes a `termux-notification`; the user swipes Approve/Deny →
  `approve/deny(corr_id)` → the suspended dispatch resolves.
- **Cron isolation:** cron sessions can't create crons, can't approve their own
  output, and their results only reach the owning bot's queue.
- **Secrets:** resolved by ref, stored chmod-600, Keystore-wrapped app-side,
  never in logs/traces/chat; `endpoint.json` chmod 600.

## 8. Design — "The Observatory" (researched, not generic)

**It is an instrument panel, not a chat app.** Research anchors: Material 3
Expressive, Apple Liquid Glass, Linear (dark density, 4px grid, one accent),
Raycast (command palette), 2026 mission-control dashboards.

Five principles:

1. **Chrome is monochrome; the crew carries color.** Per-bot hue
   (name-hash → 6-hue wheel: sapphire / mint / orchid / coral / chartreuse /
   cyan) used *only* on the avatar ring, status pulse, and thread accent.
2. **Everything is observable.** Every tool call is a trace chip (tool,
   backend, duration, risk); hand-offs between bots are drawn as lines; the bus
   has a visible pulse.
3. **Approvals are first-class.** Risk-tiered liquid-glass shade cards:
   command preview, rationale, Approve/Deny, "always allow this pattern".
4. **Type carries emotion.** Inter Variable (UI) + JetBrains Mono (commands,
   cron exprs, traces). No gradient headline soup.
5. **One swipe up = command palette** (Raycast-style) that can drive anything:
   create bot, publish a bus message, pause the crew, open a bot's thread, run
   a routine now.

Tokens: canvas `#0B0D10`; surfaces `#14171C` / `#1B1F26` / `#232833`; glass
`#591B1F26` over blur + `#33F2F4F8` hairline. Status: idle `#5A6272`,
working = amber `#FFB020` scanline, waiting = blue `#4D9FFF` pulse,
ok `#3DD68C`, critical `#FF4D5E`. Motion: 120–240 ms ease-out default; M3
springs only for "alive" moments; scanline 1400 ms linear loop, pulse 1600 ms
ease-in-out. 20 dp gutters, 20 px card radius, 12 px card gap.

Screens: **Observatory** (digest: crew health grid + live bus pulse + today's
trace feed) · **Bots** (roster cards → **Bot thread**: sessions, skills,
files, trace chips) · **Routines** (cron table + next-fire countdowns) ·
**Approvals** (inbox) · **Settings/Capabilities** · **Setup wizard** (node
graph: done / active / failed / pending, tap-to-retry).

## 9. Distribution & release pipeline

- **APK via GitHub Releases** (signed with a stable release key; unsigned debug
  builds also accepted). The botd payload ships as `phonebotd-<ver>.tar.gz` +
  skills bundle + `manifest.json` (version, `botd_api` version, F-Droid APK
  URLs + **SHA-256 pins**, artifact checksums, optional GPG signature/
  fingerprint). The wizard downloads from the release and verifies every
  SHA-256 before installing — nothing is trusted blind.
- CI: Python gate on every push (selftest, cross-language conformance,
  dispatch-to-real-subprocess, mock-OpenAI plumbing, offline smoketest,
  cross-process CLI round-trip on a Linux runner). Release on `v*` tags:
  Gradle + SDK runner → assemble → pack → publish. Keystore via repo secrets
  (`PHONEBOT_KEYSTORE` base64 + pass + alias + key pass); without secrets the
  tag still publishes unsigned assets — never block the botd payload on
  signing.

## 10. Repo structure (target; a fresh build may reorganize within these rules)

```
phonebot/
├── app/  agent-core/  termux-bridge/  scheduling/  credentials/  wizard/  design-tokens/
├── botd/            # Python: main, config, ipc, loop, bus, cron, state, llm, cli,
│                    #         selftest, tools/{shell,files,web,memory}
├── contracts/       # tool-descriptor.schema.json, invoke-envelope.schema.json,
│                    # builtin-tools-phase1.json  ← SCHEMAS OF RECORD
├── docs/            # ADR-001-architecture.md (12 decisions D1–D12),
│                    # BUILD-SPEC.md (this file), design-tokens.md
├── release/         # botd-install.sh, pack.py, verify_deploy.py
├── test/            # protocol_conformance.py (contract==registry==LLM schemas),
│                    # tools_dispatch_check.py, mock_openai_check.py,
│                    # cli_roundtrip_check.py (cross-process; SKIP on non-Unix),
│                    # run_smoketest.py (key-drop harness: key never enters chat)
└── .github/workflows/{ci,release}.yml
```

**Rules:** the contracts are authoritative — a conformance test must fail the
build if any tool's id / arg-names / required fields drift between contract,
registry, and LLM schemas. The CLI and the daemon must share one
address-derivation function so they can never disagree on the socket.

## 11. Phases & Definitions of Done

**P1 — Durable Foundation (reference implementation: this repo's Phase 1):**
botd daemon end-to-end (loop, bus, cron, ipc, 11 tools, LLM auto/direct/fake),
selftest green, conformance green, SSH-first app transport, release pipeline.
DoD: all Python gates green on a Linux runner; a live OpenRouter smoketest
PASS with a real key dropped via the harness (key never in chat/logs); payload
tarball + manifest verify.

**P2 — The Bot Team:** persistent bridge connection (one socket, background
reader for `bus_event` frames instead of per-call), chief-of-staff
onboarding, bot-to-bot messaging visible in the Observatory, the risk-tiered
Approvals inbox UI end-to-end (notify → swipe → resolve), `create_bot`
meta-tool, bot roster management in the app.
DoD: two bots plus the chief exchange a real task/result chain triggered by a
cron; an urgent approval surfaces as a notification and resolves the suspended
tool call; all of it survives killing the app process and rebooting the phone
(reboot → Termux:Boot → botd → catch-up replay of a deliberately-missed job).

**P3 — Capability Depth:** full meta-tool matrix (`create_tool`, skills,
`mcp_connect`, `store_secret`/`use_secret`), the Phase-3 contract tool
additions, per-bot spend caps enforced at the LLM layer, secrets vault
hardening, skills bundle shipping.
DoD: a bot creates its own reusable tool and uses it in a later session; an
MCP connection with a vaulted key survives app reinstall; the spend cap
hard-stops a run at the configured USD.

**P4 — Durability & Distribution:** scheduling hardening (L2/L3 polish, OEM
battery-saver diagnostics screen), release pipeline polish (signed keys, GPG),
proactive job discovery (chief-of-staff proposes new crons for user approval),
self-update through `PackageInstallerFlow`.
DoD: two-week soak on a real device — zero missed job windows, boot-to-healthy
< 2 min, one-tap self-update on a tagged release.

## 12. Gotchas the new builder will actually hit (learned, not guessed)

1. **Abstract sockets are unreachable from the app process.** Don't design a
   "direct" channel. SSH + CLI is the transport; RUN_COMMAND is the fallback;
   a persistent-socket reader still runs *inside* Termux and forwards frames
   to the app.
2. **Termux must be the F-Droid build.** The Play build's self-integrity check
   breaks proot/background automation — the wizard verifies the package origin.
3. **GitHub interpolates `${{ }}` across entire `run:` blocks before the shell
   sees them.** Never write `${{ }}`-looking text inside bash comments in
   workflows; lint workflows with `actionlint` before pushing.
4. **KSP coordinate format** is `kotlinVersion-kspVersion` (for Kotlin 2.1.20
   it is `2.1.20-1.0.32`); `classpath(...)` only exists in `buildscript`
   blocks, not root `dependencies`.
5. **OEM battery savers will fight you.** Design the durability proof around
   "late-but-never-lost" (catch-up replay); treat L2/L3 as best-effort, not a
   claim of exactness.
6. **Keep `list_inbox` non-mutating** — the one bug class that quietly eats
   your durable wakeups is a read that drains the queue.
7. **The dangerous-command regex must over-escalate**, never under.
   `rm -i -f` and `rm --force x` are destructive; `rm foo-bar.txt` is not.
8. **Windows dev machines can't test the socket path** (no `AF_UNIX`); that is
   a Linux/CI gate by design — SKIP, don't fake.

## 13. What "built" means

When this is finished and the user installs the release APK: within ~10 minutes
(the wizard) they have a bot crew running on their phone that survives a
reboot, receives their proactive jobs, asks for approval on anything
destructive, improves its own skills and memory as files they can read, and
costs them a configurable, capped amount on any OpenAI-compatible endpoint
they choose — with **zero** VPS, **zero** local model, and **zero**
permissions beyond what one wizard walked them through.
