"""botd selftest (``main.py --selftest``).

Runs a battery of cheap, side-effect-light health checks against a config
and prints one PASS/FAIL line per check followed by an overall OK/FAIL.
Exit code is 0 only when every check passes.

Checks
------
1. **home directories**  - ``<home>/{bots,secrets}`` exist or are creatable.
2. **create test bot**   - a ``BotState`` layout + sqlite DB can be built
   for a throwaway bot under ``bots/``.
3. **socket**            - the configured abstract (or /tmp fallback)
   socket binds, accepts a JSON-lines client, and answers ``get_state``.
4. **cron**              - ``next_run`` computes for a 5-field expression
   and a due interval job fires exactly once through :class:`CronScheduler`
   with a stub runner.
5. **FTS5**              - the sessions FTS5 virtual table creates (or the
   LIKE fallback is active) and serves ``memory.search``.

No LLM calls are made. No shared mutable state is modified outside
``<home>`` (the test bot lives under it and can be deleted after the run).
"""

from __future__ import annotations

import asyncio
import json
import socket
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from .config import Config
from .cron import CronScheduler, next_run_iso
from .ipc import IpcServer
from .state import BotState


# ---------------------------------------------------------------------------
# Individual checks (each returns (ok: bool, detail: str))
# ---------------------------------------------------------------------------

def _check_home(config: Config) -> Tuple[bool, str]:
    """The home tree exists or can be created with the right permissions."""
    try:
        for sub in ("", "bots", "secrets"):
            (config.home_path / sub).mkdir(parents=True, exist_ok=True)
        probe = config.home_path / ".selftest-write"
        probe.write_text("ok\n", encoding="utf-8")
        probe.unlink()
        return True, f"home={config.home_path}"
    except OSError as exc:
        return False, f"home not writable: {exc}"


def _check_test_bot(config: Config) -> Tuple[bool, str]:
    """A throwaway bot's full layout (docs, db, journal) can be created.

    Uses a unique bot name per run so the wakeup-count assertion is
    deterministic (an accumulating "selftest-bot" would make repeated
    selftests fail). Old ``selftest-*`` bots are swept first.
    """
    import time

    name = "selftest-%d" % int(time.time())
    bots_root = config.home_path / "bots"
    # Sweep prior throwaway bots so repeated runs don't pile up.
    for stale in bots_root.glob("selftest-*"):
        for child in stale.iterdir():
            try:
                child.unlink()
            except OSError:
                pass
        try:
            stale.rmdir()
        except OSError:
            pass

    state = BotState(name, bots_root / name)
    try:
        state.ensure_layout()
        sid = state.start_session()
        tid = state.record_turn(sid, "user", "selftest bot layout probe")
        state.enqueue_wakeup({"type": "fyi", "from_bot": "selftest", "to_bot": name, "timestamp": "now"})
        ok = (
            (state.bot_dir / "soul.md").exists()
            and state.db_path.exists()
            and state.wakeup_count() == 1
            and sid > 0
            and tid > 0
        )
        return ok, f"bot={name} session={sid} turn={tid}"
    except Exception as exc:  # noqa: BLE001
        return False, f"test bot layout failed: {exc}"
    finally:
        state.close()


def _check_socket(config: Config) -> Tuple[bool, str]:
    """The IPC socket binds and answers one JSON-lines round trip.

    The abstract-namespace Unix socket only exists on Unix-like kernels
    (i.e. Termux/Linux on the phone). On a non-Unix dev machine there is
    nothing to exercise, so we report a platform-skip (treated as passing
    for the exit code) rather than a hard failure. The check is only a real
    gate on the target platform.
    """
    if not hasattr(socket, "AF_UNIX"):
        return True, "SKIPPED (non-Unix platform; socket only exists on Termux/Linux)"
    return asyncio.run(_socket_roundtrip(config))


async def _socket_roundtrip(config: Config) -> Tuple[bool, str]:
    ipc = IpcServer(config)
    await ipc.start()
    addr_desc = ipc.describe_address()
    _abstract, addr = ipc.raw_address
    client_sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        client_sock.connect(addr)
        reader, writer = await asyncio.open_connection(sock=client_sock)
        writer.write(json.dumps({"type": "get_state"}).encode("utf-8") + b"\n")
        await writer.drain()
        line = await asyncio.wait_for(reader.readline(), timeout=5.0)
        reply = json.loads(line.decode("utf-8", "replace"))
        ok = reply.get("type") == "state"
        return ok, f"{addr_desc}: reply type={reply.get('type')!r}"
    except Exception as exc:  # noqa: BLE001
        return False, f"{addr_desc}: round trip failed: {exc}"
    finally:
        writer.close()
        await ipc.stop()


def _check_cron(config: Config) -> Tuple[bool, str]:
    """next_run_iso works and a due job fires exactly once through a tick."""

    class _StubRunner:
        async def run_job(self, job: Dict[str, Any]) -> Dict[str, Any]:
            return {"ok": True, "result": "stub"}

    scheduler = CronScheduler(config.home_path, {}, _StubRunner())
    scheduler.add_job({"id": "selftest-tick", "bot": "selftest", "interval_seconds": -10})
    results = asyncio.run(scheduler.tick())
    fired = [r for r in results if r["job_id"] == "selftest-tick"]
    cron_ok = bool(fired) and len(fired) == 1

    future = next_run_iso("0 9 * * *")
    due_ok = bool(future) and datetime.fromisoformat(future) > datetime.now(timezone.utc)
    return cron_ok and due_ok, f"due-job fired={len(fired)} next(0 9 * * *)={future[:19]}"


def _check_fts5(config: Config) -> Tuple[bool, str]:
    """The FTS5 virtual table creates (or falls back to LIKE) and searches.

    A fresh bot per run so the hit-count assertion is deterministic.
    """
    import time

    name = "selftest-fts-%d" % int(time.time())
    state = BotState(name, config.home_path / "bots" / name)
    try:
        state.ensure_layout()
        sid = state.start_session()
        state.record_turn(sid, "user", "the quick brown fox jumps over the lazy dog")
        hits = state.search_memory("quick brown fox")
        ok = bool(hits)
        return ok, f"fts5={'yes' if state.fts5_available else 'no (LIKE fallback)'} hits={len(hits)}"
    except Exception as exc:  # noqa: BLE001
        return False, f"fts check failed: {exc}"
    finally:
        state.close()


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

def _run_check(fn: Callable[[], Tuple[bool, str]]) -> Tuple[bool, str]:
    """Invoke a check (sync or async) and return its result tuple."""
    result = fn()
    if asyncio.iscoroutine(result):
        result = asyncio.run(result)
    return result


def run_selftest(config: Config) -> int:
    """Run every check, print PASS/FAIL lines, and return the exit code.

    Returns 0 when all checks pass, 1 otherwise. This is the function
    ``main.py`` invokes for ``--selftest``.
    """
    checks: List[Tuple[str, Callable[[], Tuple[bool, str]]]] = [
        ("home directories", lambda: _check_home(config)),
        ("create test bot", lambda: _check_test_bot(config)),
        ("socket bind + round trip", lambda: _check_socket(config)),
        ("cron next_run + due tick", lambda: _check_cron(config)),
        ("sessions FTS5 table", lambda: _check_fts5(config)),
    ]

    print("botd selftest  (home: %s)" % config.home_path)
    all_ok = True
    for name, fn in checks:
        try:
            ok, detail = _run_check(fn)
        except Exception as exc:  # noqa: BLE001 - a crash in one check must not hide the rest
            ok, detail = False, f"check raised: {type(exc).__name__}: {exc}"
        all_ok = all_ok and ok
        tag = "SKIP" if detail.startswith("SKIPPED") else ("PASS" if ok else "FAIL")
        print(f"[{tag}] {name}  ({detail})")
    print("OK" if all_ok else "FAIL")
    return 0 if all_ok else 1


__all__ = ["run_selftest"]
