#!/usr/bin/env python3
"""Phase 2: prove the daemon half of the app's persistent bridge.

The app keeps *one* long-lived `python -m botd.cli subscribe` channel open and
reads `bus_event` frames from it instead of opening a connection per call. This
test drives that channel over the real abstract Unix socket and proves:

  * `subscribe` is acknowledged on the connection that sent it;
  * several bus events arrive, in publish order, on that same connection
    (no per-call reconnect, no drops, no duplicates);
  * a connection that only sends commands receives no `bus_event` frames, so a
    short-lived CLI call can never swallow a frame meant for the background
    reader;
  * a second subscriber receives its own copy (fan-out);
  * a subscriber leaving does not disturb publishing for the rest.

On platforms without `AF_UNIX` (e.g. Windows) this is reported as a platform
skip, mirroring `test/cli_roundtrip_check.py`.
"""

from __future__ import annotations

import asyncio
import json
import socket
import sys
import tempfile
from pathlib import Path
from typing import Any, List, Optional, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from botd.bus import MessageBus  # noqa: E402
from botd.config import Config  # noqa: E402
from botd.ipc import IpcServer, _abstract_or_path  # noqa: E402
from botd.state import BotState  # noqa: E402

RESULTS: List[bool] = []


def check(desc: str, ok: bool, detail: str = "") -> None:
    RESULTS.append(bool(ok))
    tag = "ok" if ok else "FAIL"
    print(f"  [{tag}] {desc}" + (f"  ({detail})" if detail else ""))


async def _connect(cfg: Config) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
    _abstract, addr = _abstract_or_path(cfg.socket_name)
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, sock.connect, addr)
    sock.setblocking(False)
    return await asyncio.open_connection(sock=sock)


async def _send(writer: asyncio.StreamWriter, obj: dict) -> None:
    writer.write((json.dumps(obj) + "\n").encode("utf-8"))
    await writer.drain()


async def _read(
    reader: asyncio.StreamReader, timeout: float = 5.0
) -> Any:
    line = await asyncio.wait_for(reader.readline(), timeout=timeout)
    return json.loads(line.decode("utf-8"))


async def _read_or_none(reader: asyncio.StreamReader, timeout: float) -> Optional[Any]:
    try:
        return await _read(reader, timeout)
    except asyncio.TimeoutError:
        return None


async def main() -> int:
    if not hasattr(socket, "AF_UNIX"):
        print("[SKIP] persistent subscriber channel (no AF_UNIX on this platform)")
        return 0

    tmp = Path(tempfile.mkdtemp(prefix="pb-sub-"))
    cfg = Config(home_path=tmp)
    for sub in ("", "bots", "secrets"):
        (tmp / sub).mkdir(parents=True, exist_ok=True)
    states = {}
    for name in ("chief", "atlas"):
        state = BotState(name, tmp / "bots" / name)
        state.ensure_layout()
        states[name] = state

    bus = MessageBus(tmp, states, chief_bot="chief")
    ipc = IpcServer(
        cfg,
        state_store=type("S", (), {"states": states})(),
        bus=bus,
        scheduler=None,
    )
    await ipc.start()

    try:
        # 1. The app's background reader: subscribe once, then only read.
        sub_reader, sub_writer = await _connect(cfg)
        await _send(sub_writer, {"type": "subscribe", "corr_id": "sub-1"})
        ack = await _read(sub_reader)
        check(
            "subscribe is acknowledged on this connection",
            ack.get("type") == "ack" and ack.get("subscribed") is True,
            str(ack),
        )

        second_reader, second_writer = await _connect(cfg)
        await _send(second_writer, {"type": "subscribe", "corr_id": "sub-2"})
        ack2 = await _read(second_reader)
        check("a second subscriber is acknowledged", ack2.get("subscribed") is True)

        # 2. A normal one-shot command connection, exactly like `botd.cli
        # get_state` (the app's roster fetch).
        plain_reader, plain_writer = await _connect(cfg)
        await _send(plain_writer, {"type": "get_state", "corr_id": "p-1"})
        state_reply = await _read(plain_reader)
        bots = state_reply.get("bots") or []
        check(
            "one-shot command channels still reply",
            state_reply.get("type") == "state" and {b.get("bot") for b in bots} == {"chief", "atlas"},
            f"type={state_reply.get('type')} bots={len(bots)}",
        )

        # 3. Publish a real task/result-style chain of events.
        accepted = [
            bus.post("atlas", "chief", "result", {"n": i}, corr_id=f"corr-{i}")
            for i in range(3)
        ]
        check("bus accepted all three events", all(accepted))

        frames = [await _read(sub_reader) for _ in range(3)]
        check(
            "every event arrives on the one persistent connection",
            all(f.get("type") == "bus_event" for f in frames),
        )
        check(
            "events arrive in publish order with no drops",
            [f["event"]["payload"]["n"] for f in frames] == [0, 1, 2],
        )
        check(
            "no duplicate deliveries",
            len({f["event"]["corr_id"] for f in frames}) == 3,
        )

        copies = [await _read(second_reader) for _ in range(3)]
        check(
            "second subscriber receives its own copy",
            [f["event"]["payload"]["n"] for f in copies] == [0, 1, 2],
        )

        leaked = await _read_or_none(plain_reader, 0.4)
        check(
            "a non-subscribed connection receives no bus frames",
            leaked is None,
            repr(leaked),
        )

        # 4. One subscriber leaving must not break delivery for the others.
        sub_writer.close()
        await sub_writer.wait_closed()
        await asyncio.sleep(0.1)
        check(
            "publishing still succeeds after a subscriber leaves",
            bus.post("atlas", "chief", "result", {"n": 99}, corr_id="corr-99"),
        )
        tail = await _read(second_reader)
        check(
            "the remaining subscriber still receives events",
            tail["event"]["payload"]["n"] == 99,
        )

        second_writer.close()
        plain_writer.close()
    finally:
        await ipc.stop()

    if all(RESULTS):
        print("PHASE 2 SUBSCRIBE OK")
        return 0
    print("PHASE 2 SUBSCRIBE FAILED")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
