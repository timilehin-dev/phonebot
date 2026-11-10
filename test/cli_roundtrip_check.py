#!/usr/bin/env python3
"""Cross-process CLI round-trip: prove the app → Termux → botd path works.

This mirrors the *real device control path*. On the phone the app shells into
Termux (Termux:API RUN_COMMAND) and runs ``python -m botd.cli <type> <args>``,
which opens the ``@phonebot`` abstract socket and talks to the running daemon.
Here we:

1. Spin up the botd ``IpcServer`` (a scratch home) in a background thread with
   its own asyncio loop, kept alive so it can accept connections.
2. Run ``python -m botd.cli get_state --config <home>/config.json`` in a
   **separate process** (subprocess), just as Termux would.
3. Assert the subprocess prints a JSON-lines reply of type ``state``.
4. Run ``python -m botd.cli ping`` and assert an ok ack.

A unique socket name (``@mbot-cli-test``) keeps the test hermetic — it never
collides with a real default daemon on the same machine.

On a non-Unix platform there is no abstract socket, so the check is reported
as a platform-skip (treated as passing for the exit code), mirroring
``botd.selftest._check_socket``. On Linux (CI / Termux) it is a real gate.
"""

from __future__ import annotations

import asyncio
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import List, Optional

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from botd.config import Config  # noqa: E402
from botd.ipc import IpcServer  # noqa: E402

SOCKET_NAME = "@mbot-cli-test"
RESULT: List[str] = []


def _run(desc: str, ok: bool, detail: str) -> None:
    tag = "PASS" if ok else "FAIL"
    RESULT.append(f"[{tag}] {desc}  ({detail})")
    print(f"[{tag}] {desc}  ({detail})")


def _start_server(cfg: Config) -> IpcServer:
    """Bind the socket in a background thread that keeps the loop alive."""

    def _serve() -> None:
        async def _amain() -> None:
            server = IpcServer(cfg)
            await server.start()
            await asyncio.Future()  # run forever; the thread blocks here.

        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        loop.run_until_complete(_amain())

    threading.Thread(target=_serve, daemon=True).start()
    return None


def _wait_socket_ready(addr_bytes: bytes, tries: int = 40) -> bool:
    """Poll until the abstract socket accepts a connection (server is up)."""
    for _ in range(tries):
        try:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.connect(addr_bytes)
            s.close()
            return True
        except OSError:
            time.sleep(0.1)
    return False


def _run_cli(args: List[str]) -> Optional[str]:
    """Run ``python -m botd.cli <args>`` in a subprocess; return stdout."""
    cmd = [sys.executable, "-m", "botd.cli", *args]
    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=REPO, timeout=30)
    return proc.stdout.strip() or None


def main() -> int:
    if not hasattr(socket, "AF_UNIX"):
        print("[SKIP] cli cross-process round-trip  (non-Unix platform; "
              "abstract socket only exists on Termux/Linux)")
        return 0

    home = Path(tempfile.mkdtemp(prefix="mbot-cli-"))
    cfg = Config(home_path=home)
    cfg.socket_name = SOCKET_NAME
    cfg.save(home / "config.json")

    from botd.ipc import _abstract_or_path
    _abstract, addr = _abstract_or_path(SOCKET_NAME)

    _start_server(cfg)
    ready = _wait_socket_ready(addr)
    _run("cli socket ready", ready, f"socket={SOCKET_NAME} (abstract={_abstract})")
    if not ready:
        print("FAIL")
        return 1

    cfg_path = str(home / "config.json")

    # 1) get_state via the real CLI subprocess (cross-process).
    out = _run_cli(["get_state", "--config", cfg_path, "--timeout", "10"])
    ok = False
    detail = out or "(empty output)"
    if out:
        try:
            reply = json.loads(out)
            ok = reply.get("type") == "state" and "bots" in reply
            detail = f"type={reply.get('type')!r} bots={reply.get('bots')}"
        except json.JSONDecodeError as exc:
            detail = f"bad JSON: {exc}; raw={out[:120]}"
    _run("cli get_state round-trip", ok, detail)

    # 2) ping liveness probe.
    out2 = _run_cli(["ping", "--config", cfg_path, "--timeout", "10"])
    ok2 = False
    detail2 = out2 or "(empty output)"
    if out2:
        try:
            ack = json.loads(out2)
            ok2 = ack.get("type") == "ack" and ack.get("ok") is True
            detail2 = f"ok={ack.get('ok')!r}"
        except json.JSONDecodeError as exc:
            detail2 = f"bad JSON: {exc}; raw={out2[:120]}"
    _run("cli ping", ok2, detail2)

    all_ok = ok and ok2
    print("OK" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
