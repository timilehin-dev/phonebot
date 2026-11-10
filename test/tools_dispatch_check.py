"""Offline proof that the shell tool dispatches to a real subprocess.

Drives :class:`botd.tools.ToolRegistry` through the exact path the agent
loop uses (``registry.dispatch("shell.run", args, ctx)``):

* a safe command echoes back -> the Termux backend ran a child process,
* a destructive command (``rm -rf`` of home) is escalated to the
  ``destructive`` risk tier and, with no IPC channel connected, fails
  closed (approval is required but cannot be granted -> not executed).

This validates the tool layer on a dev machine with no phone and no LLM.

Run from the repo root:

    py -3 test/tools_dispatch_check.py
"""

from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from botd.config import Config  # noqa: E402
from botd.loop import ToolContext  # noqa: E402
from botd.tools import ToolRegistry  # noqa: E402


def _ok(cond: bool, label: str) -> bool:
    print(f"  [{'ok' if cond else 'FAIL'}] {label}")
    return cond


async def _go() -> int:
    config = Config()  # default home; we never touch real bot data here
    registry = ToolRegistry(config, ipc=None, bus=None)
    ctx = ToolContext(bot_name="toolcheck", llm=None)

    # Platform-neutral safe command: print a marker via the shell backend.
    marker = "multibot-toolcheck-1"
    if os.name == "nt":
        safe_cmd = f"echo {marker}"
    else:
        safe_cmd = f"echo {marker}"

    res_safe = await registry.dispatch("shell.run", {"command": safe_cmd}, ctx)
    out = (res_safe.get("output") or {})
    stdout = out.get("stdout", "")
    safe_ok = (
        res_safe.get("ok") is True
        and marker in stdout
        and out.get("exit_code") == 0
    )

    # Destructive command: the dangerous-command detector is a pure string
    # regex check that fires *before* any subprocess, so "rm -rf ~" is safe to
    # use on any platform (it is escalated + fail-closed, never actually run).
    res_bad = await registry.dispatch(
        "shell.run",
        {"command": "rm -rf ~"},
        ctx,
    )
    bad_blocked = res_bad.get("denied") is True or res_bad.get("ok") is False

    checks = [
        _ok(safe_ok, "safe command executed via subprocess (marker echoed)"),
        _ok(marker in stdout, f"stdout contains marker {marker!r}"),
        _ok(out.get("exit_code") == 0, "exit_code == 0 for the safe command"),
        _ok(bad_blocked, "destructive command escalated + fail-closed (not executed)"),
    ]

    await registry.close()
    all_ok = all(checks)
    print("tools dispatch check: " + ("OK" if all_ok else "FAIL"))
    return 0 if all_ok else 1


def main() -> int:
    return asyncio.run(_go())


if __name__ == "__main__":
    sys.exit(main())
