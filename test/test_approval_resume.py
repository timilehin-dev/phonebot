"""End-to-end test: a suspended high-risk tool call is approved over the

persistent subscriber channel and resumes with approved=True.

Runs entirely in-process (no SSH, no device) by speaking the botd IPC
protocol directly over the abstract Unix socket, mirroring what the app's
persistent bridge reader does.
"""
from __future__ import annotations

import asyncio
import json
import socket
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from botd.config import Config
from botd.ipc import IpcServer, _abstract_or_path
from botd.state import BotState
from botd.bus import MessageBus


async def _connect(config):
    _abstract, addr = _abstract_or_path(config.socket_name)
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, sock.connect, addr)
    sock.setblocking(False)
    reader, writer = await asyncio.open_connection(sock=sock)
    return reader, writer


async def _send(writer, obj):
    writer.write((json.dumps(obj) + "\n").encode("utf-8"))
    await writer.drain()


async def _read_line(reader, timeout=5.0):
    return await asyncio.wait_for(reader.readline(), timeout=timeout)


async def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="pb-appr-"))
    cfg = Config(home_path=tmp)
    for sub in ("", "bots", "secrets"):
        (tmp / sub).mkdir(parents=True, exist_ok=True)
    states = {}
    for name in ("chief", "atlas"):
        s = BotState(name, tmp / "bots" / name)
        s.ensure_layout()
        states[name] = s
    bus = MessageBus(tmp, states, chief_bot="chief")
    ipc = IpcServer(cfg, state_store=type("S", (), {"states": states})(), bus=bus, scheduler=None)
    await ipc.start()
    print("daemon started", flush=True)

    # Subscriber connection (persistent channel).
    sub_reader, sub_writer = await _connect(cfg)
    print("subscriber connected", flush=True)
    await _send(sub_writer, {"type": "subscribe", "corr_id": "sub-1"})
    print("subscribe sent", flush=True)
    ack_line = await _read_line(sub_reader, timeout=5.0)
    print("ack line:", ack_line, flush=True)
    ack = json.loads(ack_line.decode())
    assert ack.get("type") == "ack" and ack.get("subscribed") is True, ack

    # Trigger an approval request in the daemon (simulates a high-risk tool).
    async def requester():
        return await ipc.request_approval(
            "corr-42", {"tool": "shell.run", "risk": "destructive", "bot": "atlas"}
        )

    task = asyncio.create_task(requester())
    # Let request_approval publish and the event sink schedule delivery to subscriber.
    await asyncio.sleep(0.05)

    # Wait for the needs-approval bus_event to arrive on the subscriber channel.
    event_line = await _read_line(sub_reader, timeout=5.0)
    print("event line:", event_line, flush=True)
    event = json.loads(event_line.decode())
    assert event.get("type") == "bus_event", event
    inner = event.get("event", {})
    assert inner.get("type") == "needs-approval", inner
    assert inner.get("corr_id") == "corr-42", inner

    # A second connection (command channel) sends approve.
    cmd_reader, cmd_writer = await _connect(cfg)
    await _send(cmd_writer, {"type": "approve", "corr_id": "corr-42"})
    reply = json.loads((await _read_line(cmd_reader)).decode())
    print("approve reply:", reply, flush=True)
    assert reply.get("type") == "ack" and reply.get("decision") == "approve", reply

    approved = await asyncio.wait_for(task, timeout=5.0)
    assert approved is True, approved
    print("APPROVAL RESUME TEST: PASS", flush=True)

    # Cleanup (best-effort; server shutdown can be flaky in in-process tests).
    for w in (sub_writer, cmd_writer):
        try:
            w.close()
            await asyncio.wait_for(w.wait_closed(), timeout=1.0)
        except Exception:
            pass
    try:
        await asyncio.wait_for(ipc.stop(), timeout=2.0)
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
