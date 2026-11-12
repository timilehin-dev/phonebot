# AGENTS.md — how to work in this repository

Standing instructions from the project owner. Follow them every session; do not
wait to be reminded.

## Delivery rule: commit and push to `main`

- **Always commit and push finished work to `main`.** Work on a side branch is
  not delivered.
- Land it either by committing on `main` and pushing, or by a side branch whose
  PR is fast-forwarded/merged into `main` in the same session.
- **Never force-push. Never rewrite already-published history** (`rebase`,
  `amend`, or `reset` of pushed commits).
- If a push is rejected as non-fast-forward, fetch, merge the remote branch in,
  resolve conflicts, and re-run the gates before pushing again.
- Do not leave completed work unpushed on any branch. Finish with `main`
  containing the work and the working tree clean.

## Verify before pushing

Run the gates that cover what you touched; for a Phase 2 change that means all
of them:

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
python release/pack.py --version 0.1.2   # then verify manifest sha256 + tarball
```

Never present a failing or skipped gate as passing. Fix the cause.

## Scope and honesty rules

- Scope comes from [`docs/BUILD-SPEC.md`](docs/BUILD-SPEC.md) §11. Do not add
  features from a later phase while finishing an earlier one.
- Every claimed feature/DoD item must have a runnable proof or be listed as a
  hardware-only limitation in [`docs/PHASE2-COVERAGE.md`](docs/PHASE2-COVERAGE.md)
  (machine-checked by `test/test_phase2_coverage.py`).
- **Never claim on-device behaviour as verified** unless it was actually run on
  a device: notification rendering, the Android 13+ permission prompt, the
  notification tap, live Termux SSH/bridge, and a real reboot →
  Termux:Boot → botd → catch-up run are all hardware-only.
- Do not weaken tests, skip checks, or silence errors to make a gate green.

## Architecture notes for changes

- The app is the control/UI/credential plane; execution lives in Termux (botd).
  Transport is SSH + `botd.cli` over the `@phonebot` abstract socket.
- Contracts are authoritative: `contracts/builtin-tools-phase1.json`,
  `botd/tools/__init__.py` and `botd/loop.py` schemas must agree —
  `test/protocol_conformance.py` is the gate.
- Screens render plain state models (`ObservatoryUiState`, `RosterSnapshot`,
  `ApprovalRequest`) behind swappable data sources (`ObservatoryDataSource`).
  Keep design/structure changes in the Compose layer; mapping and decisions
  belong in pure, unit-tested classes (`ObservatoryEventMapper`,
  `RosterMapper`, `ApprovalSwipePolicy`, `ApprovalNotificationPlanner`).
- `botd` owns durability: cron `tick(is_boot=True)` and `catch_up` must remain
  exactly-once (see `test/test_phase2_chain.py`).
