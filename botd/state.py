"""Per-bot durable state for botd.

Every bot owns a home directory under ``~/multibot/bots/<bot>/``:

    <bot_home>/
    ├── soul.md            # stable identity prompt
    ├── user.md            # user profile / volatile context
    ├── memory.md          # volatile long-term memory
    ├── skills/            # skill front-matter for the stable prompt tier
    ├── state.json         # lightweight per-bot flags (paused, spend, ...)
    ├── sessions.db        # sqlite3 session/turn/trace store + FTS5 index
    └── wakeup/queue.jsonl # append-only wakeup journal (bus delivery target)

:class:`BotState` is the only module that touches these files. It is
side-effect free at import: the database connection and files are only
created when methods are called.

Sessions follow the schema:

    sessions(id INTEGER PK, bot TEXT, created_at TEXT, parent_id TEXT)
    turns(id INTEGER PK, session_id INTEGER, role TEXT, content TEXT,
          tool_calls TEXT, ts TEXT)
    trace(id INTEGER PK, turn_id INTEGER, tool TEXT, args_json TEXT,
          result_json TEXT, duration_ms INTEGER)
    turns_fts(FTS5 over turns.content) -- powers memory.search
"""

from __future__ import annotations

import json
import os
import sqlite3
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional

#: Marker files created for a fresh bot so the agent loop has a prompt base.
_DEFAULT_DOCS = {
    "soul.md": (
        "# Soul\n"
        "You are {bot}, a persistent agent running on a phone. "
        "Be concise. Act on tasks, report results, ask for approval on risky work.\n"
    ),
    "user.md": (
        "# User profile\n"
        "(No profile data yet. The app or a bootstrap turn should fill this in.)\n"
    ),
    "memory.md": (
        "# Long-term memory\n"
        "(Nothing recorded yet. Append durable facts here.)\n"
    ),
}

_SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bot TEXT NOT NULL,
    created_at TEXT NOT NULL,
    parent_id TEXT
);

CREATE TABLE IF NOT EXISTS turns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES sessions(id),
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    tool_calls TEXT,
    ts TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trace (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    turn_id INTEGER NOT NULL REFERENCES turns(id),
    tool TEXT NOT NULL,
    args_json TEXT,
    result_json TEXT,
    duration_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_turns_session ON turns(session_id, id);
CREATE INDEX IF NOT EXISTS idx_trace_turn ON trace(turn_id);
"""

_FTS5_TABLE = """
CREATE VIRTUAL TABLE IF NOT EXISTS turns_fts USING fts5(
    content, content='turns', content_rowid='id'
);
"""

_FTS5_TRIGGERS = """
CREATE TRIGGER IF NOT EXISTS turns_fts_ai AFTER INSERT ON turns BEGIN
    INSERT INTO turns_fts(rowid, content) VALUES (new.id, new.content);
END;
CREATE TRIGGER IF NOT EXISTS turns_fts_ad AFTER DELETE ON turns BEGIN
    INSERT INTO turns_fts(turns_fts, rowid, content)
    VALUES ('delete', old.id, old.content);
END;
CREATE TRIGGER IF NOT EXISTS turns_fts_au AFTER UPDATE ON turns BEGIN
    INSERT INTO turns_fts(turns_fts, rowid, content)
    VALUES ('delete', old.id, old.content);
    INSERT INTO turns_fts(rowid, content) VALUES (new.id, new.content);
END;
"""


def _utc_now_iso() -> str:
    """Return the current UTC time as an ISO-8601 string (for JSON columns)."""
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class BotState:
    """Owns all persistent state for a single bot.

    Parameters
    ----------
    bot_name:
        Bot identifier (also its directory name under ``bots/``).
    bot_dir:
        Absolute path to the bot's home directory.
    """

    def __init__(self, bot_name: str, bot_dir: Path):
        self.bot_name = bot_name
        self.bot_dir = Path(bot_dir)
        self._lock = threading.Lock()
        self._db: Optional[sqlite3.Connection] = None
        #: False if this sqlite build lacks FTS5; memory.search then uses LIKE.
        self.fts5_available: bool = False

    # ------------------------------------------------------------------ #
    # Layout
    # ------------------------------------------------------------------ #
    @property
    def db_path(self) -> Path:
        """Path to the bot's ``sessions.db``."""
        return self.bot_dir / "sessions.db"

    @property
    def queue_path(self) -> Path:
        """Path to the bot's ``wakeup/queue.jsonl`` journal."""
        return self.bot_dir / "wakeup" / "queue.jsonl"

    def ensure_layout(self) -> None:
        """Create directories and default markdown docs if missing.

        Idempotent: existing files are never overwritten. Also initialises
        the sqlite database (schema + FTS5 when available).
        """
        for sub in ("wakeup", "skills"):
            (self.bot_dir / sub).mkdir(parents=True, exist_ok=True)
        for name, template in _DEFAULT_DOCS.items():
            target = self.bot_dir / name
            if not target.exists():
                target.write_text(template.format(bot=self.bot_name), encoding="utf-8")
        self._init_db()

    # ------------------------------------------------------------------ #
    # state.json
    # ------------------------------------------------------------------ #
    def load_state_json(self) -> Dict[str, Any]:
        """Read ``state.json``; returns an empty dict when missing."""
        path = self.bot_dir / "state.json"
        if not path.exists():
            return {}
        try:
            return json.loads(path.read_text())
        except json.JSONDecodeError:
            # Corrupt state must not crash the daemon; start clean.
            return {}

    def save_state_json(self, state: Dict[str, Any]) -> Path:
        """Atomically write ``state.json`` (tmp file + rename)."""
        path = self.bot_dir / "state.json"
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        tmp.replace(path)
        return path

    def with_state(self, fn: Any) -> None:
        """Convenience: load state.json, mutate via ``fn(state)``, save back."""
        state = self.load_state_json()
        result = fn(state)
        self.save_state_json(result if result is not None else state)

    # ------------------------------------------------------------------ #
    # sqlite / sessions
    # ------------------------------------------------------------------ #
    def _init_db(self) -> None:
        """Create the sqlite connection + schema. Idempotent, thread-safe."""
        with self._lock:
            if self._db is not None:
                return
            conn = sqlite3.connect(self.db_path, check_same_thread=False)
            conn.executescript(_SCHEMA)
            try:
                conn.executescript(_FTS5_TABLE)
                conn.executescript(_FTS5_TRIGGERS)
                self.fts5_available = True
            except sqlite3.OperationalError:
                # FTS5 missing from this sqlite build: degrade to LIKE search.
                self.fts5_available = False
            conn.commit()
            self._db = conn

    def _ensure_db(self) -> None:
        if self._db is None:
            self._init_db()
        assert self._db is not None

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        """Provide a usable connection, lazily initialising on first use."""
        self._ensure_db()
        assert self._db is not None
        yield self._db

    def start_session(self, parent_id: Optional[int] = None) -> int:
        """Create a new session row; returns its integer id.

        ``parent_id`` links follow-up sessions to a prior one (used by
        context compression / branching). Cron jobs pass ``None``: they are
        isolated, history-free sessions.
        """
        with self._connect() as conn:
            cur = conn.execute(
                "INSERT INTO sessions (bot, created_at, parent_id) VALUES (?, ?, ?)",
                (self.bot_name, _utc_now_iso(), str(parent_id) if parent_id is not None else None),
            )
            conn.commit()
            return int(cur.lastrowid or 0)

    def record_turn(
        self,
        session_id: int,
        role: str,
        content: str,
        tool_calls: Optional[Dict[str, Any]] = None,
    ) -> int:
        """Append a turn to a session; returns the turn id (for trace rows)."""
        with self._connect() as conn:
            cur = conn.execute(
                "INSERT INTO turns (session_id, role, content, tool_calls, ts) "
                "VALUES (?, ?, ?, ?, ?)",
                (
                    session_id,
                    role,
                    content,
                    json.dumps(tool_calls) if tool_calls is not None else None,
                    _utc_now_iso(),
                ),
            )
            conn.commit()
            return int(cur.lastrowid or 0)

    def record_trace(
        self,
        turn_id: int,
        tool: str,
        args_json: str,
        result_json: str,
        duration_ms: int,
    ) -> None:
        """Record one tool dispatch under a turn."""
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO trace (turn_id, tool, args_json, result_json, duration_ms) "
                "VALUES (?, ?, ?, ?, ?)",
                (turn_id, tool, args_json, result_json, duration_ms),
            )
            conn.commit()

    def recent_turns(self, session_id: int, limit: int = 50) -> List[Dict[str, Any]]:
        """Return up to ``limit`` most recent turns (oldest first)."""
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id, role, content, tool_calls, ts FROM turns "
                "WHERE session_id = ? ORDER BY id DESC LIMIT ?",
                (session_id, limit),
            ).fetchall()
        out = []
        for rid, role, content, tool_calls, ts in reversed(rows):
            out.append(
                {
                    "id": rid,
                    "role": role,
                    "content": content,
                    "tool_calls": json.loads(tool_calls) if tool_calls else None,
                    "ts": ts,
                }
            )
        return out

    def search_memory(self, query: str, limit: int = 10) -> List[Dict[str, Any]]:
        """Full-text search over all turns via FTS5 (LIKE fallback).

        Returns dicts with ``turn_id``, ``snippet`` and ``rank``. This is
        the backing store for a ``memory.search`` tool.
        """
        with self._connect() as conn:
            if self.fts5_available:
                fts_query = " OR ".join(f'"{q}"' for q in query.split() if q)
                if not fts_query:
                    return []
                rows = conn.execute(
                    "SELECT rowid, snippet(turns_fts, 0, '[', ']', '…', 4) AS snippet "
                    "FROM turns_fts WHERE turns_fts MATCH ? ORDER BY rank LIMIT ?",
                    (fts_query, limit),
                ).fetchall()
            else:
                pattern = f"%{query}%"
                rows = conn.execute(
                    "SELECT id, substr(content, 1, 200) AS snippet FROM turns "
                    "WHERE content LIKE ? LIMIT ?",
                    (pattern, limit),
                ).fetchall()
        return [
            {"turn_id": int(rid), "snippet": snippet, "rank": i}
            for i, (rid, snippet) in enumerate(rows)
        ]

    def close(self) -> None:
        """Close the underlying sqlite connection (idempotent)."""
        with self._lock:
            if self._db is not None:
                self._db.close()
                self._db = None

    # ------------------------------------------------------------------ #
    # Wakeup journal
    # ------------------------------------------------------------------ #
    def enqueue_wakeup(self, event: Dict[str, Any]) -> None:
        """Append one JSON event to ``wakeup/queue.jsonl`` and fsync.

        The journal is the durable bus delivery target: an event only counts
        as delivered when it is in this file, so a crash between bus publish
        and the next tick loses nothing.
        """
        self.queue_path.parent.mkdir(parents=True, exist_ok=True)
        with self.queue_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, ensure_ascii=False) + "\n")
            fh.flush()
            os.fsync(fh.fileno())

    def peek_wakeup(self) -> Optional[Dict[str, Any]]:
        """Return the oldest pending wakeup event without removing it.

        Returns ``None`` when the queue is empty or unreadable.
        """
        if not self.queue_path.exists():
            return None
        with self.queue_path.open("r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    return json.loads(line)
                except json.JSONDecodeError:
                    continue
        return None

    def drain_wakeup(self) -> List[Dict[str, Any]]:
        """Remove and return all pending events (recreates an empty journal)."""
        events: List[Dict[str, Any]] = []
        if self.queue_path.exists():
            with self.queue_path.open("r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        events.append(json.loads(line))
                    except json.JSONDecodeError:
                        continue
            self.queue_path.unlink()
        return events

    def list_wakeup(self, limit: int = 20) -> List[Dict[str, Any]]:
        """Return the oldest ``limit`` pending events WITHOUT removing them.

        Non-mutating counterpart to :meth:`drain_wakeup`; used by the
        ``list_inbox`` tool so inspecting the queue does not consume it.
        """
        out: List[Dict[str, Any]] = []
        if self.queue_path.exists():
            with self.queue_path.open("r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        out.append(json.loads(line))
                    except json.JSONDecodeError:
                        continue
                    if len(out) >= limit:
                        break
        return out

    def wakeup_count(self) -> int:
        """Number of pending events in the journal."""
        if not self.queue_path.exists():
            return 0
        with self.queue_path.open("r", encoding="utf-8") as fh:
            return sum(1 for line in fh if line.strip())


__all__ = ["BotState"]
