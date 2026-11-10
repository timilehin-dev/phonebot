"""Files tool backend.

Direct, *scoped* filesystem access: a bot may only touch paths inside
(1) its own home dir, (2) the Termux home (``$HOME``), or (3) an explicit
shared allowlist (``~/multibot/shared``). Scoping is enforced by
resolving the requested path (following symlinks) and checking the
resolved location against the allowed roots.

Tools:
    * ``files.read``    - read a text file (capped).
    * ``files.write``   - write a text file (creates parents).
    * ``files.search``  - content search via ripgrep when available,
                         falling back to a pure-Python walk otherwise.
"""

from __future__ import annotations

import asyncio
import os
import re
import shutil
from pathlib import Path
from typing import Any, Dict, List, Optional

from ..config import Config


class ToolScopeError(Exception):
    """Raised when a path escapes the allowed roots for the acting bot."""


class FileTools:
    """Scoped filesystem tools for a bot.

    Parameters
    ----------
    config:
        Global config (provides the multibot home for scoping).
    bot_homes:
        Callable ``bot_name -> Path`` resolving a bot's home dir. When
        None, a default that maps to ``<home>/bots/<name>`` is used.
    extra_roots:
        Additional absolute roots allowed for every bot (shared paths).
    """

    #: A bot can read this much of a file in one call.
    READ_CAP_BYTES = 512 * 1024

    def __init__(
        self,
        config: Config,
        bot_homes: Optional[Any] = None,
        extra_roots: Optional[List[Path]] = None,
    ) -> None:
        self.config = config
        self._bot_homes = bot_homes
        self._home = Path(config.home_path)
        self._termux_home = Path(os.path.expanduser("~"))
        self._extra_roots = [Path(p) for p in (extra_roots or [])]
        self._rg = shutil.which("rg")  # ripgrep, if present
        self._write_lock = asyncio.Lock()

    # ------------------------------------------------------------------ #
    # Scoping
    # ------------------------------------------------------------------ #
    def _allowed_roots(self, bot_name: str) -> List[Path]:
        roots: List[Path] = [self._termux_home]
        if self._bot_homes is not None:
            roots.append(Path(self._bot_homes(bot_name)))
        else:
            roots.append(self._home / "bots" / bot_name)
        roots.append(self._home / "shared")
        roots.extend(self._extra_roots)
        return [r.resolve() for r in roots]

    def _scoped(self, raw_path: str, bot_name: str) -> Path:
        """Resolve ``raw_path`` and ensure it stays inside an allowed root.

        Raises :class:`ToolScopeError` otherwise. Symlink escapes are
        caught because both sides are fully resolved before comparison.
        """
        target = (self._termux_home / raw_path).resolve() if not Path(raw_path).is_absolute() \
            else Path(raw_path).resolve()
        for root in self._allowed_roots(bot_name):
            try:
                target.relative_to(root)
                return target
            except ValueError:
                continue
        raise ToolScopeError(f"path {raw_path!r} is outside the allowed roots for bot {bot_name!r}")

    # ------------------------------------------------------------------ #
    # Tool implementations
    # ------------------------------------------------------------------ #
    async def read_file(self, path: str, bot_name: str) -> Dict[str, Any]:
        """Read a scoped text file; returns ``{path, content, truncated}``."""
        target = self._scoped(path, bot_name)
        if not target.is_file():
            return {"ok": False, "error": f"not a file: {target}"}
        data = target.read_bytes()
        cap = self.READ_CAP_BYTES
        return {
            "ok": True,
            "path": str(target),
            "content": data[:cap].decode("utf-8", "replace"),
            "truncated": len(data) > cap,
        }

    async def write_file(self, path: str, content: str, bot_name: str) -> Dict[str, Any]:
        """Write a scoped text file (parents created atomically)."""
        async with self._write_lock:
            target = self._scoped(path, bot_name)
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_suffix(target.suffix + ".tmp")
            tmp.write_text(content, encoding="utf-8")
            tmp.replace(target)
            return {"ok": True, "path": str(target), "bytes": len(content.encode("utf-8"))}

    async def search_files(
        self,
        pattern: str,
        path: Optional[str] = None,
        bot_name: str = "",
        limit: int = 50,
    ) -> Dict[str, Any]:
        """Content-search a scoped dir. Prefers ripgrep, else Python walk.

        Returns ``{matches: [{path, line, text}], truncated}``.
        """
        root = self._scoped(path or str(self._home / "bots" / bot_name), bot_name)
        regex = re.compile(re.escape(pattern))
        matches: List[Dict[str, Any]] = []
        truncated = False

        if self._rg is not None:
            # ripgrep is dramatically faster on large trees; cap matches.
            proc = await asyncio.create_subprocess_exec(
                self._rg, "--line-number", "-E", pattern, str(root),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            out, _ = await proc.communicate()
            for line in out.decode("utf-8", "replace").splitlines()[:limit]:
                # rg format: path:line:content
                parts = line.split(":", 2)
                if len(parts) == 3:
                    matches.append({"path": parts[0], "line": parts[1], "text": parts[2]})
            truncated = len(matches) >= limit
            return {"ok": True, "matches": matches, "truncated": truncated, "backend": "rg"}

        # Pure-Python fallback.
        cap_files = 5000
        for dirpath, _dirnames, filenames in os.walk(root):
            if len(matches) >= limit:
                truncated = True
                break
            for name in filenames:
                if len(matches) >= limit:
                    truncated = True
                    break
                if len(filenames) > cap_files:
                    truncated = True
                    break
                fp = Path(dirpath) / name
                try:
                    with fp.open("r", encoding="utf-8", errors="ignore") as fh:
                        for i, line in enumerate(fh, start=1):
                            if regex.search(line):
                                matches.append({
                                    "path": str(fp), "line": str(i),
                                    "text": line.rstrip()[:300],
                                })
                                if len(matches) >= limit:
                                    truncated = True
                                    break
                except OSError:
                    continue
        return {"ok": True, "matches": matches[:limit], "truncated": truncated, "backend": "python"}

    async def close(self) -> None:
        """No resources to release (kept for interface symmetry)."""


__all__ = ["FileTools", "ToolScopeError"]
