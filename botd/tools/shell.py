"""Shell tool backend.

Execution happens through a *backend* chosen by config:

* :class:`TermuxShellBackend` (default) - runs commands as a subprocess in
  Termux's own environment, so ``TERMUX_*`` variables, package managers
  and the Android path layout are all inherited naturally.
* :class:`SSHShellBackend` - runs on a remote host via asyncssh (e.g. a
  headless Raspberry Pi the phone controls).
* :class:`ShizukuShellBackend` - placeholder; Shizuku support was dropped
  from Phase 1 and can be re-added behind this stub.

A small dangerous-command detector escalates matching calls to
``destructive`` risk (the registry then demands approval).
"""

from __future__ import annotations

import asyncio
import os
import re
import shutil
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ..config import Config, BackendConfig


#: (pattern, human note). A command matching ANY pattern is "dangerous" and
#: gets escalated to the ``destructive`` risk tier (approval required).
#:
#: Design choice: these patterns deliberately *over-escalate* rather than
#: under-escalate. Catching a harmless ``rm -r build/`` costs one approval
#: prompt; missing ``rm -rf ~`` costs the user's home. So any ``rm`` that
#: carries a recursive or force flag is flagged, regardless of target.
DANGEROUS_COMMAND_PATTERNS: List[Tuple[str, str]] = [
    # rm carrying a recursive/force flag, whether it is the first flag right
    # after ``rm`` or a later split flag. Covers ``-rf``, ``-R``, ``-rfi``,
    # ``--force``, ``--recursive`` and the split forms ``-i -r`` / ``-i -f``.
    # Benign dashed *filenames* (``rm foo-bar.txt``) do NOT match: a match
    # requires a leading dash (``-{1,2}``) on a flag token.
    (r"\brm\s+-{1,2}[A-Za-z]*[RrFf]", "rm with a recursive/force flag"),
    (r"\brm\s+\S+\s+-{1,2}[A-Za-z]*[RrFf]", "rm with a later recursive/force flag"),
    (r"\bdd\s+if=", "dd raw write"),
    (r"\bmkfs(\.\w+)?\b", "mkfs format"),
    (r"\bshred\b", "shred destroy"),
    (r"\bparted\b.*\bmkpart\b", "parted create partition"),
    (r">\s*/dev/(sd|mmcblk|nvme|md)\w*", "direct block device write"),
    (r"\bchmod\s+-R\s+777\s+/", "chmod -R 777 /"),
    (r"\b(shutdown|reboot|poweroff)\b", "power control"),
]

_DANGEROUS_COMPILED = [(re.compile(p), note) for p, note in DANGEROUS_COMMAND_PATTERNS]


def contains_dangerous_command(command: str) -> Optional[str]:
    """Return the first matching danger note, or None when safe."""
    for pattern, note in _DANGEROUS_COMPILED:
        if pattern.search(command):
            return note
    return None


class ShellBackend:
    """Common interface for shell backends."""

    name = "base"

    async def run(
        self,
        command: str,
        cwd: Optional[str] = None,
        timeout_s: float = 120.0,
    ) -> Dict[str, Any]:
        """Run ``command``; returns ``{exit_code, stdout, stderr, truncated}``."""
        raise NotImplementedError

    async def close(self) -> None:  # pragma: no cover - interface
        """Release backend resources (default no-op)."""


class TermuxShellBackend(ShellBackend):
    """Run commands in Termux's own environment via a local subprocess.

    This is the default backend: botd *is* a Termux process, so child
    processes inherit the full ``TERMUX_*`` environment, PATH layout
    (``$PREFIX``), and can use ``termux-*`` tooling.
    """

    name = "termux"

    def __init__(self, extra_env: Optional[Dict[str, str]] = None) -> None:
        self._extra_env = extra_env or {}

    async def run(
        self,
        command: str,
        cwd: Optional[str] = None,
        timeout_s: float = 120.0,
    ) -> Dict[str, Any]:
        """Run one command with the shell; capture stdout/stderr.

        Returns ``{"exit_code", "stdout", "stderr", "truncated", "timed_out"}``.
        stdout/stderr are each capped at 256 KB to keep LLM context bounded.
        """
        if not command.strip():
            return {
                "exit_code": 0, "stdout": "", "stderr": "",
                "truncated": False, "timed_out": False,
            }
        env = os.environ.copy()
        env.update(self._extra_env)
        proc = await asyncio.create_subprocess_shell(
            command,
            cwd=cwd or None,
            env=env,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            # Don't let a bot's loop hang the daemon.
            start_new_session=True,
        )
        try:
            out_b, err_b = await asyncio.wait_for(proc.communicate(), timeout=timeout_s)
            timed_out = False
        except asyncio.TimeoutError:
            proc.kill()
            out_b, err_b = await proc.communicate()
            timed_out = True
        cap = 256 * 1024

        def _text(b: bytes) -> Tuple[str, bool]:
            s = b.decode("utf-8", "replace")
            return s[:cap], len(s) > cap

        out, out_trunc = _text(out_b)
        err, err_trunc = _text(err_b)
        return {
            "exit_code": proc.returncode if proc.returncode is not None else -1,
            "stdout": out,
            "stderr": err,
            "truncated": out_trunc or err_trunc,
            "timed_out": timed_out,
        }


class SSHShellBackend(ShellBackend):
    """Run commands on a remote host via asyncssh (lazy connection).

    Config supplies host/user/port; the auth method is the local user's
    default (key files in ``~/.ssh`` or a password env var
    ``MULTIBOT_SSH_PASSWORD``).
    """

    name = "ssh"

    def __init__(
        self,
        host: str,
        user: str,
        port: int = 22,
        timeout_s: float = 120.0,
    ) -> None:
        self._host = host
        self._user = user
        self._port = port
        self._default_timeout = timeout_s
        self._conn: Optional[Any] = None
        self._lock = asyncio.Lock()

    async def _connect(self) -> Any:
        """Create the asyncssh connection on first use (and on retry)."""
        import asyncssh  # lazy: optional dependency

        password = os.environ.get("MULTIBOT_SSH_PASSWORD")
        conn = await asyncssh.connect(
            self._host,
            port=self._port,
            username=self._user,
            password=password,
            # Key-based auth uses the client's default ~/.ssh keys.
        )
        self._conn = conn
        return conn

    async def run(
        self,
        command: str,
        cwd: Optional[str] = None,
        timeout_s: float = 120.0,
    ) -> Dict[str, Any]:
        async with self._lock:
            try:
                conn = self._conn if self._conn is not None else await self._connect()
                result = await conn.run(
                    command, cwd=cwd, timeout=timeout_s, combine_stderr=False
                )
                return {
                    "exit_code": result.exit_status or 0,
                    "stdout": result.stdout or "",
                    "stderr": result.stderr or "",
                    "truncated": False,
                    "timed_out": False,
                }
            except Exception as exc:  # noqa: BLE001 - report, don't raise
                return {
                    "exit_code": -1,
                    "stdout": "",
                    "stderr": f"ssh error: {type(exc).__name__}: {exc}",
                    "truncated": False,
                    "timed_out": isinstance(exc, asyncio.TimeoutError),
                }

    async def close(self) -> None:
        """Disconnect the remote session."""
        if self._conn is not None:
            try:
                self._conn.close()
            finally:
                self._conn = None


class ShizukuShellBackend(ShellBackend):
    """Shizuku (privileged Android shell) backend - PLACEHOLDER.

    Phase 1 intentionally has no Shizuku dependency. Re-add support here
    by speaking to the Shizuku IPC socket instead of termux/ssh.

    add Shizuku backend here
    """

    name = "shizuku"

    async def run(self, command: str, cwd: Optional[str] = None, timeout_s: float = 120.0) -> Dict[str, Any]:
        raise NotImplementedError("add Shizuku backend here")


def build_shell_backend(config: Config) -> ShellBackend:
    """Instantiate the configured shell backend from a Config.

    Raises ``ValueError`` for unconfigured backends and a clear
    NotImplementedError for the shizuku placeholder.
    """
    bcfg: BackendConfig = config.backend
    if bcfg.shell == "termux":
        return TermuxShellBackend()
    if bcfg.shell == "ssh":
        if not bcfg.ssh_host or not bcfg.ssh_user:
            raise ValueError("backend.shell=ssh requires ssh_host and ssh_user")
        return SSHShellBackend(bcfg.ssh_host, bcfg.ssh_user, bcfg.ssh_port)
    if bcfg.shell == "shizuku":
        return ShizukuShellBackend()
    raise ValueError(f"unknown shell backend {bcfg.shell!r}")


__all__ = [
    "ShellBackend",
    "TermuxShellBackend",
    "SSHShellBackend",
    "ShizukuShellBackend",
    "build_shell_backend",
    "contains_dangerous_command",
    "DANGEROUS_COMMAND_PATTERNS",
]
