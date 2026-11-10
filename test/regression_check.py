#!/usr/bin/env python3
"""Regression checks for the Phase-1 bug fixes.

Covers the failure modes that were silent in the reference implementation:

1. ``list_inbox`` uses the injected state resolver and is NON-mutating
   (observing the queue must never drain it).
2. ``shell.run`` env cannot override credential variables, while harmless
   extras still pass through.
3. ``memory.search`` accepts the contract's array-typed ``target``.
4. A paused bot gets exactly ONE ``cron_result`` journal entry per job run
   (the placeholder duplicate was removed).

Exit code 0 only when every check passes.
"""

from __future__ import annotations

import asyncio
import sys
import tempfile
from pathlib import Path

# Make `botd` importable when run from the repo root (like the other checks).
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from botd.config import Config
from botd.cron import CronScheduler
from botd.loop import ToolContext
from botd.state import BotState
from botd.tools import ToolRegistry
from botd.tools.shell import TermuxShellBackend, _is_protected_env_key


def _cfg(tmp: Path) -> Config:
    return Config(home_path=tmp)


def check_list_inbox_non_mutating() -> None:
    tmp = Path(tempfile.mkdtemp(prefix="pb-reg-"))
    cfg = _cfg(tmp)
    states = {}

    def resolve(name: str) -> BotState:
        if name not in states:
            s = BotState(name, tmp / "bots" / name)
            s.ensure_layout()
            states[name] = s
        return states[name]

    state = resolve("main")
    state.enqueue_wakeup(
        {"type": "fyi", "from_bot": "cron", "to_bot": "main", "payload": {"x": 1}}
    )
    registry = ToolRegistry(cfg, state_resolver=resolve)
    ctx = ToolContext(bot_name="main", llm=None)

    result = asyncio.run(registry.dispatch("list_inbox", {"limit": 5}, ctx))
    assert result["ok"], result
    events = result["output"]["events"]
    assert len(events) == 1, events

    # Non-mutating: the queue must still contain the event.
    assert state.wakeup_count() == 1, state.wakeup_count()
    print("  [ok] list_inbox returns the queued event without draining it")

    # Session search now resolves a real BotState (FTS5 + LIKE fallback).
    search = asyncio.run(
        registry.dispatch(
            "memory.search",
            {"query": "anything", "target": ["sessions"], "limit": 5},
            ctx,
        )
    )
    assert search["ok"], search
    assert search["output"]["ok"], search
    print("  [ok] memory.search accepts target as an array (sessions)")


def check_env_filter() -> None:
    assert _is_protected_env_key("PHONEBOT_KEY_DEFAULT")
    assert _is_protected_env_key("OPENAI_API_KEY")
    assert _is_protected_env_key("GITHUB_TOKEN")
    assert not _is_protected_env_key("SAFE_OK")

    backend = TermuxShellBackend()
    result = asyncio.run(
        backend.run(
            "printf '%s' \"$PHONEBOT_KEY_DEFAULT|$OPENAI_API_KEY|$SAFE_OK\"",
            extra_env={
                "PHONEBOT_KEY_DEFAULT": "leak",
                "OPENAI_API_KEY": "leak2",
                "SAFE_OK": "fine",
            },
        )
    )
    assert result["stdout"] == "||fine", result["stdout"]
    print("  [ok] shell.run ignores credential env overrides, passes safe vars")


def check_paused_cron_single_journal() -> None:
    tmp = Path(tempfile.mkdtemp(prefix="pb-reg-cron-"))
    cfg = _cfg(tmp)
    state = BotState("pausedbot", tmp / "bots" / "pausedbot")
    state.ensure_layout()
    state.with_state(lambda s: {**s, "paused": True})

    class StubRunner:
        async def run_job(self, job):
            return {"ok": True, "result": "stub"}

    scheduler = CronScheduler(tmp, {"pausedbot": state}, StubRunner(), tick_seconds=30)
    scheduler.add_job(
        {
            "id": "reg-job",
            "bot": "pausedbot",
            "interval_seconds": -1,
            "prompt": "noop",
        }
    )
    asyncio.run(scheduler.tick())
    lines = [
        line
        for line in state.queue_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    assert len(lines) == 1, f"expected 1 journal entry, got {len(lines)}: {lines}"
    print("  [ok] paused cron run journals exactly one cron_result")


def main() -> int:
    print("regression checks")
    check_list_inbox_non_mutating()
    check_env_filter()
    check_paused_cron_single_journal()
    print("REGRESSION OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
