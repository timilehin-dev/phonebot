"""Files tool backend.

Direct, *scoped* filesystem access: a bot may only touch paths inside
(1) its own home dir, (2) the Termux home (``$HOME``), or (3) an explicit
shared allowlist (``~/phonebot/shared``). Scoping is enforced by
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
        Global config (provides the phonebot home for scoping).
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
    async def read_file(
        self,
        path: str,
        bot_name: str,
        offset: Optional[int] = None,
        limit: Optional[int] = None,
    ) -> Dict[str, Any]:
        """Read a scoped text file; returns ``{ok, path, content, truncated}``.

        ``offset`` (1-based start line) and ``limit`` (max lines) slice the
        result when supplied; the byte cap always applies to the raw read.
        """
        target = self._scoped(path, bot_name)
        if not target.is_file():
            return {"ok": False, "error": f"not a file: {target}"}
        data = target.read_bytes()
        cap = self.READ_CAP_BYTES
        truncated = len(data) > cap
        lines = data[:cap].decode("utf-8", "replace").splitlines()
        if offset is not None or limit is not None:
            start = max(0, int(offset or 1) - 1)
            end = start + int(limit) if limit is not None else len(lines)
            lines = lines[start:end]
        return {
            "ok": True,
            "path": str(target),
            "content": "\n".join(lines),
            "truncated": truncated,
        }

    async def write_file(
        self,
        path: str,
        content: str,
        bot_name: str,
        append: bool = False,
    ) -> Dict[str, Any]:
        """Write a scoped text file (parents created atomically).

        ``append`` writes in append mode instead of clobbering.
        """
        async with self._write_lock:
            target = self._scoped(path, bot_name)
            target.parent.mkdir(parents=True, exist_ok=True)
            if append:
                with target.open("a", encoding="utf-8") as fh:
                    fh.write(content)
                return {"ok": True, "path": str(target), "bytes": len(content.encode("utf-8")), "appended": True}
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
        case_sensitive: bool = False,
        glob: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Content-search a scoped dir. Prefers ripgrep, else Python walk.

        ``case_sensitive`` toggles match direction; ``glob`` (an fnmatch glob)
        restricts which files are scanned. Returns ``{matches, truncated}``.
        """
        root = self._scoped(path or str(self._home / "bots" / bot_name), bot_name)
        flags = 0 if case_sensitive else re.IGNORECASE
        regex = re.compile(re.escape(pattern), flags)
        matches: List[Dict[str, Any]] = []
        truncated = False

        if self._rg is not None:
            rg_args = ["--line-number", "-E", pattern, str(root)]
            if not case_sensitive:
                rg_args.append("-i")
            if glob:
                rg_args.extend(["-g", glob])
            proc = await asyncio.create_subprocess_exec(
                self._rg, *rg_args,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            out, _ = await proc.communicate()
            for line in out.decode("utf-8", "replace").splitlines()[:limit]:
                parts = line.split(":", 2)
                if len(parts) == 3:
                    matches.append({"path": parts[0], "line": parts[1], "text": parts[2]})
            truncated = len(matches) >= limit
            return {"ok": True, "matches": matches, "truncated": truncated, "backend": "rg"}

        # Pure-Python fallback.
        import fnmatch
        cap_files = 5000
        for dirpath, _dirnames, filenames in os.walk(root):
            if len(matches) >= limit:
                truncated = True
                break
            for name in filenames:
                if glob and not fnmatch.fnmatch(name, glob):
                    continue
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
