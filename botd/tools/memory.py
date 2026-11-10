"""Memory tool backend.

Two surfaces, matching the ``memory.update`` / ``memory.search`` contract:

* **Document memory** - the bot's persistent markdown docs
  (``user.md`` / ``memory.md`` / ``soul.md``) in its home dir. Updates are
  append or replace; searches are a case-insensitive line scan.
* **Session memory** - full-text search over the bot's prior turns via its
  SQLite/FTS5 store. Backed by an optional ``state_resolver`` callable
  (``bot_name -> BotState``); when absent, a structured "not wired yet"
  result is returned rather than crashing (Phase 1 fill-in).

All document paths are scoped the same way the shell/files tools are: a
bot may only touch files under its own home.
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from ..config import Config

#: The markdown docs a bot may read/update as "memory".
MEMORY_DOCS = ("user.md", "memory.md", "soul.md")


class MemoryTools:
    """Persistent-memory tools for a bot (docs + optional session FTS)."""

    def __init__(
        self,
        config: Config,
        state_resolver: Optional[Callable[[str], Any]] = None,
    ) -> None:
        self.config = config
        self._state_resolver = state_resolver
        self._home = Path(config.home_path)

    # ------------------------------------------------------------------ #
    # Paths
    # ------------------------------------------------------------------ #
    def _doc_path(self, bot_name: str, target: str) -> Path:
        """Resolve a memory doc to a scoped path in the bot's home.

        Raises ``ValueError`` when ``target`` is not one of the allowed
        memory docs (a bot cannot point memory.update at arbitrary files).
        """
        if target not in MEMORY_DOCS:
            raise ValueError(f"unknown memory target {target!r}; must be one of {MEMORY_DOCS}")
        return self._home / "bots" / bot_name / target

    # ------------------------------------------------------------------ #
    # memory.update
    # ------------------------------------------------------------------ #
    async def update_doc(
        self,
        bot_name: str,
        target: str,
        operation: str,
        content: str,
        old_content: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Append or replace text in a bot's memory doc.

        * ``append`` - add ``content`` to the end (newline-separated).
        * ``replace`` - replace the first occurrence of ``old_content``
          with ``content`` (``old_content`` required).
        """
        path = self._doc_path(bot_name, target)
        current = path.read_text(encoding="utf-8") if path.exists() else ""

        if operation == "append":
            merged = (current + ("\n" if current and not current.endswith("\n") else "") + content)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(merged, encoding="utf-8")
            return {"ok": True, "target": target, "operation": operation, "bytes": len(merged.encode())}

        if operation == "replace":
            if not old_content:
                return {"ok": False, "error": "replace requires old_content"}
            if old_content not in current:
                return {"ok": False, "error": f"old_content not found in {target}"}
            new = current.replace(old_content, content, 1)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(new, encoding="utf-8")
            return {"ok": True, "target": target, "operation": operation, "bytes": len(new.encode())}

        return {"ok": False, "error": f"unknown operation {operation!r}; must be append|replace"}

    # ------------------------------------------------------------------ #
    # memory.search
    # ------------------------------------------------------------------ #
    async def search_docs(
        self,
        bot_name: str,
        query: str,
        target: Optional[str] = None,
        limit: int = 10,
        targets: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        """Case-insensitive line search over the memory docs.

        ``target`` restricts the search to one doc, ``targets`` to several;
        when both are omitted all three docs are searched.
        """
        selected = tuple(targets) if targets else ((target,) if target else MEMORY_DOCS)
        rx = re.compile(re.escape(query), re.IGNORECASE)
        matches: List[Dict[str, Any]] = []
        for name in selected:
            if name not in MEMORY_DOCS:
                continue
            path = self._home / "bots" / bot_name / name
            if not path.exists():
                continue
            for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
                if rx.search(line):
                    matches.append({"target": name, "line": i, "text": line.strip()[:300]})
                    if len(matches) >= limit:
                        return {"ok": True, "matches": matches, "truncated": True, "backend": "docs"}
        return {"ok": True, "matches": matches, "truncated": len(matches) >= limit, "backend": "docs"}

    async def search_sessions(
        self,
        bot_name: str,
        query: str,
        target: str = "sessions",
        limit: int = 10,
    ) -> Dict[str, Any]:
        """FTS5 search over the bot's prior turns.

        Requires the daemon to have wired a state resolver; otherwise a
        structured "not wired" result is returned (Phase 1 fill-in).
        """
        if self._state_resolver is None:
            return {
                "ok": False,
                "wired": False,
                "error": "session memory search not wired yet (needs a BotState resolver)",
            }
        state = self._state_resolver(bot_name)
        hits = state.search_memory(query, limit=limit)
        return {"ok": True, "matches": hits, "truncated": len(hits) >= limit, "backend": "fts5"}


__all__ = ["MemoryTools", "MEMORY_DOCS"]
