"""Cron tick loop.

:class:`CronScheduler` owns ``~/multibot/jobs.json`` and, every tick
(default 30s), runs the *due* jobs in isolated :class:`AgentLoop`
sessions. Each job runs fresh (no conversation history): the job prompt +
its skill + the owning bot's soul/user/memory are the only context.

Jobs model (one entry in ``jobs.json``::)

    {
      "id": "digest-9am",
      "bot": "main",
      "cron": "0 9 * * *",         # 5-field cron expression
      "prompt": "Summarise unread items and post a digest.",
      "skill": "daily-digest",     # optional skill name to load
      "once_per_window": true,     # see idempotency below
      "safe_to_refire": false,
      "last_run": "2026-11-09T09:00:00+00:00",
      "next_run": "2026-11-10T09:00:00+00:00"
    }

Idempotency
-----------
* ``once_per_window`` jobs (the default for one-shot / daily style) fire
  *once* for the entire missed window: if the phone was off for three
  days, the job runs a single time on boot, not three times.
* ``safe_to_refire`` jobs are safe to run repeatedly and therefore fire
  *per tick* for each missed window they cross.

On boot the scheduler computes the missed windows since
``last_run`` and replays them accordingly.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol

from .state import BotState

log = logging.getLogger("botd.cron")

#: Default tick interval (seconds). The daemon polls for due jobs this often.
DEFAULT_TICK_SECONDS = 30


class CronRunner(Protocol):
    """Runs one isolated agent session for a job.

    Implemented by the daemon's scheduler (see :mod:`botd.main`), which wires
    an :class:`botd.loop.AgentLoop` + LLM client + tools together. The
    interface is tiny so cron stays decoupled from the loop.
    """

    async def run_job(self, job: Dict[str, Any]) -> Dict[str, Any]:
        """Execute one job; returns a serialisable result dict."""
        ...


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _to_utc(value: Optional[str]) -> Optional[datetime]:
    """Parse an ISO-8601 string to an aware UTC datetime (None passthrough)."""
    if not value:
        return None
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


class CronScheduler:
    """Reads due jobs every tick and runs them in isolated sessions.

    Parameters
    ----------
    home:
        The multibot home dir; ``jobs.json`` lives here.
    states:
        Mapping ``bot_name -> BotState`` for journalling results.
    runner:
        A :class:`CronRunner` that actually executes a job.
    bus:
        Optional message bus to also emit ``cron_result`` events to.
    tick_seconds:
        Polling interval (default 30).
    """

    def __init__(
        self,
        home: Path,
        states: Dict[str, BotState],
        runner: CronRunner,
        bus: Optional[Any] = None,
        tick_seconds: float = DEFAULT_TICK_SECONDS,
    ) -> None:
        self.home = Path(home)
        self.jobs_path = self.home / "jobs.json"
        self.states = states
        self.runner = runner
        self.bus = bus
        self.tick_seconds = tick_seconds
        self._last_woke: Dict[str, float] = {}

    # ------------------------------------------------------------------ #
    # jobs.json IO
    # ------------------------------------------------------------------ #
    def _load_jobs(self) -> Dict[str, Any]:
        """Load the jobs map; returns an empty structure when missing."""
        if not self.jobs_path.exists():
            return {"version": 1, "jobs": {}}
        try:
            data = json.loads(self.jobs_path.read_text())
        except json.JSONDecodeError:
            log.error("cron: jobs.json is corrupt; starting empty")
            return {"version": 1, "jobs": {}}
        data.setdefault("version", 1)
        data.setdefault("jobs", {})
        return data

    def _save_jobs(self, data: Dict[str, Any]) -> None:
        """Atomically write the jobs map (tmp + rename)."""
        tmp = self.jobs_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        tmp.replace(self.jobs_path)

    def add_job(self, job: Dict[str, Any]) -> None:
        """Register a job (id, bot, cron, prompt, ...).

        Computes and stores ``next_run`` from the job's cron expression
        (or, when absent, its relative ``interval_seconds``).
        """
        data = self._load_jobs()
        jobs = data["jobs"]
        if "id" not in job or "bot" not in job:
            raise ValueError("a job needs at least 'id' and 'bot'")
        if "cron" in job:
            job["next_run"] = next_run_iso(job["cron"], from_dt=_utc_now())
        elif "interval_seconds" in job:
            job["next_run"] = (_utc_now() + timedelta(seconds=job["interval_seconds"])).isoformat()
        else:
            raise ValueError("a job needs 'cron' or 'interval_seconds'")
        jobs[job["id"]] = job
        self._save_jobs(data)

    # ------------------------------------------------------------------ #
    # Tick
    # ------------------------------------------------------------------ #
    async def tick(self, now: Optional[datetime] = None, is_boot: bool = False) -> List[Dict[str, Any]]:
        """Run every due job once; returns the list of job results.

        Parameters
        ----------
        now:
            Inject a specific "current time" for tests; defaults to UTC now.
        is_boot:
            When True, also replay *missed windows* since ``last_run``
            (once-per-window jobs fire at most once; safe jobs fire per
            missed tick).
        """
        now = now or _utc_now()
        data = self._load_jobs()
        results: List[Dict[str, Any]] = []
        changed = False

        for job_id, job in list(data["jobs"].items()):
            due = self._is_due(job, now)
            if not due:
                continue

            if is_boot:
                refires = self._missed_replays(job, now)
            else:
                refires = 1

            for _i in range(max(0, refires)):
                result = await self.runner.run_job(job)
                self._record_result(job, result, now)
                changed = True
                results.append(
                    {
                        "job_id": job_id,
                        "bot": job.get("bot", ""),
                        "ok": bool(result.get("ok", True)),
                        "error": result.get("error"),
                        "result": result.get("result"),
                        "ts": now.isoformat(),
                    }
                )

        if changed:
            self._save_jobs(data)
        return results

    def _is_due(self, job: Dict[str, Any], now: datetime) -> bool:
        """True when the job's ``next_run`` is at or before ``now``."""
        next_run = _to_utc(job.get("next_run"))
        if next_run is None:
            return False
        return next_run <= now

    def _missed_replays(self, job: Dict[str, Any], now: datetime) -> int:
        """How many times a boot-time job should replay a missed window.

        once-per-window jobs replay exactly once; safe jobs replay once per
        missed 30-second tick (capped to avoid a pathological replay storm).
        """
        last_run = _to_utc(job.get("last_run"))
        if last_run is None:
            return 1
        elapsed = (now - last_run).total_seconds()
        if job.get("safe_to_refire"):
            # Per-tick replay, capped at 24h worth of ticks.
            ticks = int(elapsed // self.tick_seconds)
            cap = int((24 * 3600) // self.tick_seconds)
            return min(max(ticks, 1), cap)
        # Default: fire once for the entire missed window.
        return 1

    def _record_result(self, job: Dict[str, Any], result: Dict[str, Any], now: datetime) -> None:
        """Update job bookkeeping and journal the cron_result event.

        ``last_run`` is stamped with ``now`` and ``next_run`` advanced to the
        next matching cron time (or interval) so the job cannot re-fire in
        the same tick.
        """
        if "cron" in job:
            job["next_run"] = next_run_iso(job["cron"], from_dt=now)
        elif "interval_seconds" in job:
            job["next_run"] = (now + timedelta(seconds=job["interval_seconds"])).isoformat()
        job["last_run"] = now.isoformat()

        bot_name = job.get("bot", "")
        state = self.states.get(bot_name)
        if state is not None:
            state.enqueue_wakeup(
                {
                    "type": "cron_result",
                    "from_bot": "cron",
                    "to_bot": bot_name,
                    "corr_id": job.get("id", ""),
                    "priority": "fyi",
                    "payload": {
                        "job_id": job.get("id"),
                        "ok": bool(result.get("ok", True)),
                        "error": result.get("error"),
                        "result": result.get("result"),
                    },
                    "timestamp": now.isoformat(timespec="seconds"),
                }
            )
        if self.bus is not None:
            self.bus.post(
                from_bot="cron",
                to_bot=bot_name,
                type_="cron_result",
                payload={
                    "job_id": job.get("id"),
                    "ok": bool(result.get("ok", True)),
                    "error": result.get("error"),
                },
                corr_id=job.get("id", ""),
                priority="fyi",
            )


# ---------------------------------------------------------------------------
# Minimal cron evaluation (no external cronit dependency).
# ---------------------------------------------------------------------------

def _field_matches(spec: str, value: int, min_: int, max_: int) -> bool:
    """Evaluate one cron field (``*``, ``a``, ``a-b``, ``a,b``, ``*/n``) against ``value``."""
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        step = 1
        if "/" in part:
            base, _, step_s = part.partition("/")
            step = int(step_s) if step_s else 1
        else:
            base = part
        if base == "*":
            lo, hi = min_, max_
        elif "-" in base:
            lo_s, _, hi_s = base.partition("-")
            lo, hi = int(lo_s), int(hi_s)
        else:
            lo = hi = int(base)
        if lo <= value <= hi and (value - lo) % step == 0:
            return True
    return False


def next_run_iso(cron_expr: str, from_dt: Optional[datetime] = None) -> str:
    """Compute the next run time (ISO-8601 UTC) for a 5-field cron expr.

    Fields are ``minute hour day-of-month month day-of-week``. This is a
    minimal, dependency-free evaluator: it advances one minute at a time
    (fast in practice for daily/weekly jobs) and honours the standard
    day-of-month / day-of-week OR rule.
    """
    fields = cron_expr.split()
    if len(fields) != 5:
        raise ValueError(f"cron expression must have 5 fields, got {cron_expr!r}")
    minute_f, hour_f, dom_f, mon_f, dow_f = fields

    dt = (from_dt or _utc_now()).astimezone(timezone.utc)
    # Start at the next whole minute.
    dt = dt.replace(second=0, microsecond=0) + timedelta(minutes=1)

    dow_map = {0: 6, 1: 0, 2: 1, 3: 2, 4: 3, 5: 4, 6: 5}  # Python: Mon=0 -> cron: Sun=0
    for _ in range(366 * 24 * 60):  # bounded search window (~1 year)
        dow_cron = dow_map[dt.weekday()]
        dom_ok = _field_matches(dom_f, dt.day, 1, 31)
        dow_ok = _field_matches(dow_f, dow_cron, 0, 6)
        # Standard cron: if both dom and dow are restricted, either matching is enough.
        dom_star = dom_f.startswith("*") and "/" not in dom_f
        dow_star = dow_f.startswith("*") and "/" not in dow_f
        day_ok = (dom_ok or dow_ok) if not (dom_star or dow_star) else (dom_ok and dow_ok)
        if day_ok and _field_matches(mon_f, dt.month, 1, 12) \
                and _field_matches(hour_f, dt.hour, 0, 23) \
                and _field_matches(minute_f, dt.minute, 0, 59):
            return dt.isoformat()
        dt += timedelta(minutes=1)
    raise ValueError(f"could not find next run within a year for {cron_expr!r}")


__all__ = ["CronScheduler", "next_run_iso", "DEFAULT_TICK_SECONDS"]
