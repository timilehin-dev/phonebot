"""Inter-bot message bus.

The bus is the coordination layer between agents. It is *in-memory with a
durable tail*: every message is also journaled to the target bot's
``wakeup/queue.jsonl`` (via :class:`botd.state.BotState`) so a restart
loses nothing, and an optional shared journal (``<home>/bus.jsonl``)
retains recent cross-bot traffic for the chief-of-staff view.

Message model
-------------
* ``type`` is one of: ``task``, ``result``, ``needs-approval``, ``fyi``,
  ``cron_result``.
* ``from_bot`` / ``to_bot``: sender and target (``"*"`` = broadcast).
* ``corr_id``: correlator for request/response pairs.
* ``priority``: ``urgent`` or ``fyi``.
  - ``urgent`` wakes the target bot *immediately* (a notifier callback is
    invoked so the scheduler can start the bot right away).
  - ``fyi`` is drained on the target's next cron tick / wake.
* ``payload``: arbitrary JSON-able dict.
* ``timestamp``: UTC ISO-8601.

Rate limiting
-------------
Per *sender→target pair* (per minute, sliding window):
    * at most 20 ``fyi`` messages / min,
    * at most 5  ``urgent`` messages / min.
Overflowed messages are dropped with a logged warning (the journal is the
authoritative delivery mechanism; a drop here means the message was
never accepted).

Chief of staff
--------------
One designated bot (``chief_bot``) *subscribes* to **all** bus events
(including those between other bots) and receives a copy in its own wakeup
queue. This gives a passive observer with no extra protocol.
"""

from __future__ import annotations

import json
import logging
import threading
from collections import deque
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger("botd.bus")

#: Allowed message types.
MESSAGE_TYPES = ("task", "result", "needs-approval", "fyi", "cron_result")
#: Allowed priorities.
PRIORITIES = ("urgent", "fyi")

#: Per-bot-pair per-minute rate limits.
FYI_RATE_LIMIT_PER_MIN = 20
URGENT_RATE_LIMIT_PER_MIN = 5


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class BusMessage:
    """A single bus event (all fields JSON-serialisable)."""

    type: str
    from_bot: str
    to_bot: str
    payload: Dict[str, Any] = field(default_factory=dict)
    corr_id: str = ""
    priority: str = "fyi"
    timestamp: str = field(default_factory=_utc_now_iso)

    def to_json_line(self) -> str:
        """Serialise to one compact JSON line (the wire/journal format)."""
        return json.dumps(asdict(self), ensure_ascii=False)


class MessageBus:
    """Thread-safe in-memory bus with journalling and rate limiting.

    Parameters
    ----------
    home:
        The phonebot home dir; the shared journal lives at ``home/bus.jsonl``.
    states:
        Mapping ``bot_name -> BotState``; used to journal per-bot wakeups.
        May be grown later (bots created on demand).
    chief_bot:
        Bot name that subscribes to every event, or ``None`` to disable.
    urgent_waker:
        Optional callback ``fn(bot_name)`` invoked when an ``urgent``
        message is accepted. The scheduler wires this to "wake bot now".
    """

    def __init__(
        self,
        home: Path,
        states: Dict[str, Any],
        chief_bot: Optional[str] = None,
        urgent_waker: Optional[Callable[[str], None]] = None,
    ) -> None:
        self.home = Path(home)
        self.states = states
        self.chief_bot = chief_bot
        self.urgent_waker = urgent_waker
        #: Optional sink for live clients (e.g. IpcServer): called with the
        #: accepted message dict so it can be pushed over a persistent channel.
        self.event_sink: Optional[Callable[[Dict[str, Any]], None]] = None
        self._lock = threading.Lock()
        # (from_bot, to_bot) -> deque of (monotonic, priority)
        self._rate: Dict[tuple, deque] = {}
        # Shared tail journal, bounded.
        self._shared_journal = self.home / "bus.jsonl"
        self._shared_max_lines = 500

    # ------------------------------------------------------------------ #
    # Rate limiting
    # ------------------------------------------------------------------ #
    @staticmethod
    def _prune(window: deque, now: float) -> None:
        while window and (now - window[0][0]) > 60.0:
            window.popleft()

    def _within_limit(self, from_bot: str, to_bot: str, priority: str) -> bool:
        """Check (and record) the sliding 1-minute rate limit for a pair.

        Must be called with ``self._lock`` held.
        """
        import time

        now = time.monotonic()
        key = (from_bot, to_bot)
        window = self._rate.setdefault(key, deque())
        self._prune(window, now)
        limit = URGENT_RATE_LIMIT_PER_MIN if priority == "urgent" else FYI_RATE_LIMIT_PER_MIN
        used = sum(1 for _, p in window if p == priority)
        if used >= limit:
            return False
        window.append((now, priority))
        return True

    # ------------------------------------------------------------------ #
    # Publishing
    # ------------------------------------------------------------------ #
    def publish(self, msg: BusMessage) -> bool:
        """Accept a message into the bus; returns True when delivered.

        Delivery means:
          * journaled into the target bot's wakeup queue (durable),
          * appended to the shared journal (chief-of-staff / debugging),
          * target woken immediately when ``priority == "urgent"``.

        False is returned when the message is dropped (unknown type, bad
        priority, or rate-limit overflow).
        """
        if msg.type not in MESSAGE_TYPES:
            log.warning("bus: dropping message with unknown type %r", msg.type)
            return False
        if msg.priority not in PRIORITIES:
            log.warning("bus: dropping message with bad priority %r", msg.priority)
            return False

        with self._lock:
            if not self._within_limit(msg.from_bot, msg.to_bot, msg.priority):
                log.warning(
                    "bus: rate limit overflow, dropping %s/%s %s->%s (corr=%s)",
                    msg.priority, msg.type, msg.from_bot, msg.to_bot, msg.corr_id,
                )
                return False
            line = msg.to_json_line()

        self._append_shared_journal(line)

        # Notify any live subscriber (app's persistent bridge) of the event.
        if self.event_sink is not None:
            try:
                self.event_sink(asdict(msg))
            except Exception:  # noqa: BLE001 - never break publishing
                log.exception("bus: event_sink raised")

        targets: List[str] = []
        if msg.to_bot == "*":
            targets = [b for b in self.states if b != msg.from_bot]
        elif msg.to_bot in self.states:
            targets = [msg.to_bot]
        else:
            log.warning("bus: target bot %r not registered; dropping", msg.to_bot)
            return False

        for bot_name in targets:
            state = self.states[bot_name]
            state.enqueue_wakeup(asdict(msg))
            if msg.priority == "urgent" and self.urgent_waker is not None:
                self.urgent_waker(bot_name)

        # Chief-of-staff: a copy of *every* accepted event (including the
        # ones just targeted at it) goes into the chief's queue.
        if self.chief_bot and msg.from_bot != self.chief_bot and self.chief_bot in self.states:
            chief_copy = asdict(msg)
            chief_copy["cc"] = "chief"
            self.states[self.chief_bot].enqueue_wakeup(chief_copy)

        return True

    def post(
        self,
        from_bot: str,
        to_bot: str,
        type_: str,
        payload: Optional[Dict[str, Any]] = None,
        corr_id: str = "",
        priority: str = "fyi",
    ) -> bool:
        """Convenience wrapper: build a :class:`BusMessage` and publish it."""
        msg = BusMessage(
            type=type_,
            from_bot=from_bot,
            to_bot=to_bot,
            payload=payload or {},
            corr_id=corr_id,
            priority=priority,
        )
        return self.publish(msg)

    # ------------------------------------------------------------------ #
    # Shared journal
    # ------------------------------------------------------------------ #
    def _append_shared_journal(self, line: str) -> None:
        """Append one line to the bounded shared journal (best-effort)."""
        try:
            with self._shared_journal.open("a", encoding="utf-8") as fh:
                fh.write(line + "\n")
            self._trim_shared_journal()
        except OSError:
            log.exception("bus: failed to append shared journal")

    def _trim_shared_journal(self) -> None:
        """Keep the shared journal under ``_shared_max_lines`` (approx.)."""
        try:
            with self._shared_journal.open("r", encoding="utf-8") as fh:
                lines = fh.readlines()
            overflow = len(lines) - self._shared_max_lines
            if overflow > 0:
                with self._shared_journal.open("w", encoding="utf-8") as fh:
                    fh.writelines(lines[overflow:])
        except OSError:
            pass

    def shared_events(self, limit: int = 100) -> List[Dict[str, Any]]:
        """Return the most recent ``limit`` shared-journal events (oldest first)."""
        if not self._shared_journal.exists():
            return []
        with self._shared_journal.open("r", encoding="utf-8") as fh:
            lines = [l for l in fh.readlines() if l.strip()]
        out: List[Dict[str, Any]] = []
        for line in lines[-limit:]:
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return out


__all__ = ["MessageBus", "BusMessage", "MESSAGE_TYPES", "PRIORITIES",
           "FYI_RATE_LIMIT_PER_MIN", "URGENT_RATE_LIMIT_PER_MIN"]
