"""botd CLI — thin in-Termux client for the IPC control plane.

The Android app cannot open the *abstract-namespace* socket ``@phonebot`` from
its own JVM process: ``java.net.UnixDomainSocketAddress`` is a
filesystem-namespace API and cannot express a NUL-prefixed abstract address.
So the device-reliable control path is:

    app  --(Termux:API RUN_COMMAND)-->  Termux session  --(this client)-->  @phonebot

``botd/cli.py`` is the in-Termux half. It connects to the same address the
daemon binds (via :func:`botd.ipc._abstract_or_path`, so the two can never
drift), sends exactly one JSON-lines control frame, reads the single reply
line, and prints it to stdout. The app parses stdout.

Usage
-----
    python -m botd.cli <type> [json-args]   # send {"type": <type>, **args}
    python -m botd.cli ping                  # connect+close liveness probe

Options: ``--config PATH`` (default ``~/phonebot/config.json``) and
``--timeout S`` (seconds; default 300 so a real ``llm_request`` that makes an
HTTP call is not cut off).

Exit codes
----------
* ``0`` - a reply line was received (even an ``error`` reply, which still
  means botd is up and answering).
* ``1`` - could not connect / no reply within the timeout (botd down).
* ``2`` - bad arguments.

This file is intentionally dependency-free (stdlib only) so it runs anywhere
the botd payload is deployed.
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import uuid
from typing import Any, Dict, Optional

# Reuse the daemon's address derivation so CLI and IpcServer always agree.
# The CLI is imported as a top-level module (``python -m botd.cli``), so the
# package-relative import resolves to ``botd.ipc``.
from botd.ipc import _abstract_or_path
from botd.config import Config


def _resolve_address(config: Config):
    """Return the raw address bytes the daemon binds, from the config name."""
    _abstract, addr = _abstract_or_path(config.socket_name)
    return addr


def connect(address_bytes: bytes, timeout_s: float) -> socket.socket:
    """Open a connected Unix socket to the daemon, or raise on failure.

    Raises ``RuntimeError`` on platforms without ``AF_UNIX`` (e.g. Windows);
    the caller catches this and reports a clean error.
    """
    if not hasattr(socket, "AF_UNIX"):
        raise RuntimeError("AF_UNIX not available on this platform; the "
                           "control-plane socket only exists on Termux/Linux")
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(address_bytes)
    sock.settimeout(timeout_s)
    return sock


def call(config: Config, mtype: str, args: Dict[str, Any], timeout_s: float) -> Optional[str]:
    """Send one control frame and return the raw reply line (or None)."""
    sock = connect(_resolve_address(config), timeout_s)
    try:
        frame = {"type": mtype, "corr_id": uuid.uuid4().hex}
        frame.update(args)
        sock.sendall((json.dumps(frame, ensure_ascii=False) + "\n").encode("utf-8"))
        # Read one JSON-lines reply. The daemon keeps the connection open, so
        # we must read a single newline-terminated line.
        buf = b""
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
            if buf.endswith(b"\n"):
                break
        return buf.decode("utf-8", "replace").strip() or None
    finally:
        sock.close()


def ping(config: Config, timeout_s: float) -> bool:
    """Liveness probe: connect + close. True when the daemon socket answers."""
    try:
        sock = connect(_resolve_address(config), timeout_s)
        sock.close()
        return True
    except (OSError, RuntimeError):
        return False


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="botd.cli", description="phonebot botd control-plane client")
    p.add_argument("command", nargs="?", default="ping",
                   help="an IPC message type (run_bot/approve/...) or 'ping'")
    p.add_argument("json_args", nargs="?", default="{}",
                   help="JSON object merged into the frame (default '{}')")
    p.add_argument("--config", default=None, help="config path (default ~/phonebot/config.json)")
    p.add_argument("--timeout", type=float, default=300.0, help="socket timeout in seconds")
    return p


def main(argv: Optional[Any] = None) -> int:
    args = build_parser().parse_args(argv)

    try:
        config = Config.load(args.config)  # None -> default ~/phonebot/config.json
    except Exception as exc:  # noqa: BLE001 - a bad config is a clean exit-2
        print(f"botd.cli: config error: {exc}", file=sys.stderr)
        return 2

    if args.command == "ping":
        ok = ping(config, args.timeout)
        print(json.dumps({"type": "ack", "ok": ok}))
        return 0 if ok else 1

    if args.command == "subscribe":
        # Persistent subscriber channel: connect, subscribe, then stream every
        # bus_event frame the daemon pushes until the connection closes.
        try:
            sock = connect(_resolve_address(config), args.timeout)
        except (OSError, RuntimeError) as exc:
            print(json.dumps({"type": "error", "code": "connect_failed", "message": str(exc)}))
            return 1
        sock.settimeout(None)
        try:
            frame = {"type": "subscribe", "corr_id": uuid.uuid4().hex}
            sock.sendall((json.dumps(frame, ensure_ascii=False) + "\n").encode("utf-8"))
            # Read the ack (single line).
            buf = b""
            while b"\n" not in buf:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
            if buf:
                sys.stdout.write(buf.decode("utf-8", "replace"))
                sys.stdout.flush()
                buf = b""
            # Stream subsequent frames.
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if line.strip():
                        sys.stdout.write(line.decode("utf-8", "replace") + "\n")
                        sys.stdout.flush()
        except (OSError, RuntimeError) as exc:
            print(json.dumps({"type": "error", "code": "stream_error", "message": str(exc)}))
            return 1
        finally:
            try:
                sock.close()
            except OSError:
                pass
        return 0

    try:
        extra = json.loads(args.json_args) if args.json_args else {}
        if not isinstance(extra, dict):
            raise ValueError("json_args must be an object")
    except ValueError as exc:
        print(f"botd.cli: bad json_args: {exc}", file=sys.stderr)
        return 2

    try:
        reply = call(config, args.command, extra, args.timeout)
    except (OSError, RuntimeError) as exc:
        print(json.dumps({"type": "error", "code": "connect_failed", "message": str(exc)}))
        return 1

    if reply is None:
        print(json.dumps({"type": "error", "code": "no_reply", "message": "socket closed before reply"}))
        return 1

    print(reply)
    return 0


if __name__ == "__main__":
    sys.exit(main())
