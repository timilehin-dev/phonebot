# Phase 2 — coverage and known limitations

Authoritative scope: [`docs/BUILD-SPEC.md`](BUILD-SPEC.md) §11, **P2 — The Bot Team**.

This file is machine-checked by [`test/test_phase2_coverage.py`](../test/test_phase2_coverage.py).
That test fails when:

- a P2 clause from BUILD-SPEC §11 is missing below,
- an `automated` proof names a file or symbol that does not exist,
- a `device_only` gap lacks a reason or manual steps,
- this file stops being produced by the gate list in `.github/workflows/ci.yml`.

So a Phase 2 claim here is either backed by a runnable check or explicitly listed
as hardware-only. Nothing is silently asserted.

## P2 features

| # | Feature (BUILD-SPEC §11) | Proof | Hardware-only part |
|---|---|---|---|
| 1 | Persistent bridge connection (one socket, background `bus_event` reader) | `test/test_subscribe_stream.py`, `test/test_approval_resume.py` | App-side SSH channel against live Termux (needs your phone) |
| 2 | Chief-of-staff onboarding | `test/test_phase2_chain.py` | — |
| 3 | Bot-to-bot messaging visible in the Observatory | `ObservatoryEventMapperTest`, chain test | Visual rendering on device |
| 4 | Risk-tiered Approvals inbox end-to-end (notify → swipe → resolve) | `ApprovalNotificationCoordinatorTest`, `ApprovalSwipePolicyTest`, `test/test_approval_resume.py` | Notification display, permission prompt, tap-to-open, gesture feel |
| 5 | `create_bot` meta-tool | `test/test_phase2_chain.py` | — |
| 6 | Bot roster management in the app | `RosterMapperTest`, `test/test_subscribe_stream.py` (`get_state`) | Live run/pause/resume over SSH |

## P2 definition of done

| DoD item | Proof | Hardware-only part |
|---|---|---|
| Two bots plus the chief exchange a real task/result chain triggered by a cron | `test_phase2_chain.py::test_cron_task_result_chain` | — |
| An urgent approval surfaces as a notification and resolves the suspended tool call | `test_approval_resume.py`, `ApprovalNotificationCoordinatorTest` | The notification actually appearing |
| Everything survives killing the app process and rebooting (reboot → Termux:Boot → botd → catch-up replay) | `test_phase2_chain.py::test_boot_replay_is_exactly_once`, `test_catch_up_trigger_replays_missed_jobs`; app reconnect on start + boot receiver/nudge service | Real reboot, Termux:Boot, live SSH bridge |

## Machine-readable coverage

```json
{
  "spec": "docs/BUILD-SPEC.md §11 P2",
  "items": [
    {
      "id": "persistent-bridge",
      "spec": "persistent bridge connection (one socket, background reader for bus_event frames instead of per-call)",
      "title": "Persistent bridge subscriber",
      "automated": [
        {"file": "test/test_subscribe_stream.py", "symbol": "async def main() -> int", "what": "one connection: ack, ordered fan-out, no leaks to non-subscribers, survives a subscriber leaving"},
        {"file": "test/test_approval_resume.py", "symbol": "async def main() -> int", "what": "needs-approval arrives on the persistent channel and approve resolves it"}
      ],
      "device_only": [
        {"why": "the app side opens a real SSH exec channel to Termux (JSch); Android + live sshd are not available in CI", "manual": "install the APK, configure Termux sshd, store ssh_private_key in the vault, launch the app and confirm the Bots tab shows the roster instead of 'offline'"}
      ]
    },
    {
      "id": "chief-onboarding",
      "spec": "chief-of-staff onboarding",
      "title": "Chief-of-staff onboarding",
      "automated": [
        {"file": "test/test_phase2_chain.py", "symbol": "async def test_create_bot_and_chief_onboarding", "what": "chief bot is created with the onboarding soul and role, idempotently"}
      ]
    },
    {
      "id": "observatory-messaging",
      "spec": "bot-to-bot messaging visible in the Observatory",
      "title": "Bot-to-bot messaging in the Observatory",
      "automated": [
        {"file": "app/src/test/java/com/phonebot/app/bridge/ObservatoryEventMapperTest.kt", "symbol": "class ObservatoryEventMapperTest", "what": "bus_event frames map to task/result/cron/approval rows; feed dedupes, caps and orders"},
        {"file": "test/test_phase2_chain.py", "symbol": "async def test_cron_task_result_chain", "what": "real task/result events are produced by the chain that the Observatory renders"}
      ],
      "device_only": [
        {"why": "Compose rendering and the live bridge are device-side; no instrumentation runner is configured", "manual": "open the Observatory tab with botd running and watch task/result rows appear during a cron run"}
      ]
    },
    {
      "id": "approvals-inbox",
      "spec": "risk-tiered Approvals inbox UI end-to-end (notify → swipe → resolve)",
      "title": "Risk-tiered Approvals inbox",
      "automated": [
        {"file": "app/src/test/java/com/phonebot/app/bridge/ApprovalNotificationCoordinatorTest.kt", "symbol": "class ApprovalNotificationCoordinatorTest", "what": "one notification per corr_id, none for resolved ids, cancel on resolve"},
        {"file": "app/src/test/java/com/phonebot/app/bridge/ApprovalSwipePolicyTest.kt", "symbol": "class ApprovalSwipePolicyTest", "what": "swipe thresholds; critical and info rows are never resolved by gesture"},
        {"file": "test/test_approval_resume.py", "symbol": "APPROVAL RESUME TEST: PASS", "what": "approve resumes the suspended tool call; deny resolves it without running"}
      ],
      "device_only": [
        {"why": "notification rendering, the Android 13+ POST_NOTIFICATIONS prompt and the notification tap intent are OS behaviour", "manual": "trigger a destructive tool call, confirm the notification appears, tap it to land on Approvals, swipe a confirm-tier row to approve"},
        {"why": "swipe gesture feel and animation are only observable on a touchscreen", "manual": "drag a confirm-tier approval right (approve) and left (deny); verify critical rows only respond to the buttons"}
      ]
    },
    {
      "id": "create-bot",
      "spec": "create_bot meta-tool",
      "title": "create_bot meta-tool",
      "automated": [
        {"file": "test/test_phase2_chain.py", "symbol": "async def test_create_bot_and_chief_onboarding", "what": "dispatch('create_bot') creates layout, soul and state and reports success"}
      ]
    },
    {
      "id": "roster-management",
      "spec": "bot roster management in the app",
      "title": "Bot roster management",
      "automated": [
        {"file": "app/src/test/java/com/phonebot/app/bridge/RosterMapperTest.kt", "symbol": "class RosterMapperTest", "what": "get_state maps to rows, statuses, spend and offline/error states"},
        {"file": "test/test_subscribe_stream.py", "symbol": "one-shot command channels still reply", "what": "the roster's get_state call works alongside the persistent subscriber"}
      ],
      "device_only": [
        {"why": "run/pause/resume issue real SSH CLI calls to botd", "manual": "in the Bots tab press run, pause and resume on a bot and confirm botd's reported status changes"}
      ]
    },
    {
      "id": "dod-task-result-chain",
      "spec": "two bots plus the chief exchange a real task/result chain triggered by a cron",
      "title": "DoD: cron-triggered task/result chain",
      "automated": [
        {"file": "test/test_phase2_chain.py", "symbol": "async def test_cron_task_result_chain", "what": "offline fake provider drives chief/scout/writer through the real loop, bus and cron; one cron_result per job; no duplicates on a second tick"}
      ]
    },
    {
      "id": "dod-approval-notification-resolve",
      "spec": "an urgent approval surfaces as a notification and resolves the suspended tool call",
      "title": "DoD: approval notification resolves the call",
      "automated": [
        {"file": "test/test_approval_resume.py", "symbol": "APPROVAL RESUME TEST: PASS", "what": "suspended call resumes with approved=True after approve; deny path resolves too"},
        {"file": "app/src/test/java/com/phonebot/app/bridge/ApprovalNotificationCoordinatorTest.kt", "symbol": "class ApprovalNotificationCoordinatorTest", "what": "the coordinator posts exactly one alert per pending corr_id"}
      ],
      "device_only": [
        {"why": "whether the system actually displays the alert, and the permission prompt, is OS behaviour", "manual": "trigger a destructive call with the app backgrounded; confirm the alert appears, open it, approve, and check the bot continues"}
      ]
    },
    {
      "id": "dod-reboot-catchup",
      "spec": "all of it survives killing the app process and rebooting the phone (reboot → Termux:Boot → botd → catch-up replay of a deliberately-missed job)",
      "title": "DoD: process kill + reboot catch-up",
      "automated": [
        {"file": "test/test_phase2_chain.py", "symbol": "async def test_boot_replay_is_exactly_once", "what": "a deliberately missed window fires exactly once on boot and journals one cron_result"},
        {"file": "test/test_phase2_chain.py", "symbol": "async def test_catch_up_trigger_replays_missed_jobs", "what": "the catch_up trigger path replays missed jobs"}
      ],
      "device_only": [
        {"why": "reboot, Termux:Boot autostart and the live SSH bridge cannot run in CI", "manual": "install Termux:Boot, stop botd, miss a cron window, reboot, then confirm botd starts, the app reconnects and the missed job appears exactly once"}
      ]
    }
  ]
}
```

## Credential prerequisite (not a Phase 2 feature)

The app's bridge reads `ssh_private_key` (PEM) or `ssh_password` from the
Keystore vault, scope `botd` (`BridgeRuntime.SSH_KEY_SECRET`). Writing those
secrets is part of the first-run wizard, which is **not** Phase 2 scope; until a
credential exists the bridge starts unauthenticated and the UI correctly shows
"botd offline". On-device bridge verification therefore needs either the wizard
or a manually provisioned secret.

## Gate commands

```bash
python -m botd.main --selftest
python test/protocol_conformance.py
python test/tools_dispatch_check.py
python test/mock_openai_check.py
python test/cli_roundtrip_check.py
python -m botd.main --smoketest-llm --llm-mode fake
python test/regression_check.py
python test/test_approval_resume.py
python test/test_phase2_chain.py
python test/test_subscribe_stream.py
python test/test_phase2_coverage.py
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
python release/pack.py --version 0.1.2   # then verify manifest + tarball
```
