"""botd entry point.

Usage
-----
    python -m botd.main [--selftest] [--config PATH]

* ``--config PATH`` - config location (default ``~/phonebot/config.json``).
* ``--selftest``    - run the :mod:`botd.selftest` battery instead of
  starting the daemon, then exit 0/1.

Normal start
------------
1. Load + validate the config; create the home layout
   (``~/phonebot/{bots,secrets}``, ``jobs.json``, ``endpoint.json``) when
   missing.
2. Build the object graph: bot store -> message bus -> IPC server -> tool
   registry -> scheduler (the scheduler is wired back into the bus for
   urgent wakeups and into the IPC server for trigger routing).
3. Run the asyncio event loop with two concurrent long-lived tasks:
   the cron tick loop and the IPC socket server.
4. On SIGTERM/SIGINT: flush IPC state, stop the cron loop, close the
   socket, close tool backends and per-bot databases, then exit 0.

Termux note: register this script with Termux:Boot (``boot.sh`` calling
``python -m botd.main``) so the daemon restarts after device reboot; it is
designed to be safe to start repeatedly (stale socket files are cleaned).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import signal
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import __version__
from .bus import MessageBus
from .config import Config
from .cron import CronRunner, CronScheduler, DEFAULT_TICK_SECONDS
from .ipc import IpcServer
from .loop import AgentLoop, TriggerEvent
from .state import BotState
from .tools import ToolRegistry

log = logging.getLogger("botd")

DEFAULT_CONFIG_PATH = os.path.expanduser("~/phonebot/config.json")

#: Template written to ``endpoint.json`` when missing.
ENDPOINT_TEMPLATE: Dict[str, Any] = {
    "base_url": "https://api.openai.com/v1",
    "api_key_ref": "default",
    "model": "gpt-4o-mini",
    "secrets_dir": "~/phonebot/secrets",
    "extra_headers": {},
}

#: Template written to ``jobs.json`` when missing.
JOBS_TEMPLATE: Dict[str, Any] = {"version": 1, "jobs": {}}


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    """Parse the daemon CLI: ``--selftest``, ``--smoketest-llm``, ``--config PATH``."""
    parser = argparse.ArgumentParser(prog="botd", description="phonebot-android durable execution plane")
    parser.add_argument("--selftest", action="store_true", help="run the health-check battery and exit")
    parser.add_argument("--smoketest-llm", action="store_true",
                        help="drive the agent loop end-to-end against the LLM backend, then exit")
    parser.add_argument("--llm-mode", choices=("auto", "direct", "fake"), default=None,
                        help="override the LLM backend mode for this run (used by --smoketest-llm)")
    parser.add_argument("--config", default=DEFAULT_CONFIG_PATH, metavar="PATH",
                        help=f"config file location (default: {DEFAULT_CONFIG_PATH})")
    parser.add_argument("--version", action="version", version=f"botd {__version__}")
    return parser.parse_args(argv)


def ensure_home(config: Config) -> None:
    """Create the home layout and template files if missing (idempotent).

    Creates ``<home>/bots`` and ``<home>/secrets`` directories, and writes
    ``jobs.json`` / ``endpoint.json`` only when they do not already exist,
    so operator edits and live state are never clobbered on restart.
    """
    for sub in ("bots", "secrets"):
        (config.home_path / sub).mkdir(parents=True, exist_ok=True)
    jobs_path = config.home_path / "jobs.json"
    if not jobs_path.exists():
        jobs_path.write_text(json.dumps(JOBS_TEMPLATE, indent=2) + "\n", encoding="utf-8")
    endpoint_path = config.home_path / "endpoint.json"
    if not endpoint_path.exists():
        endpoint_path.write_text(json.dumps(ENDPOINT_TEMPLATE, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# Bot store
# ---------------------------------------------------------------------------

class BotStore:
    """Maps bot names to :class:`BotState` objects, created on demand.

    The ``states`` property exposes the live dict consumed by the bus, IPC
    handlers and cron scheduler, so a bot that is only mentioned later
    (in a bus message or job) becomes a real bot on first contact.
    """

    def __init__(self, config: Config) -> None:
        self.config = config
        self._states: Dict[str, BotState] = {}

    @property
    def states(self) -> Dict[str, BotState]:
        """The live ``bot_name -> BotState`` mapping."""
        return self._states

    def get_or_create(self, bot_name: str) -> BotState:
        """Return the state for ``bot_name``, creating its layout if new."""
        state = self._states.get(bot_name)
        if state is None:
            state = BotState(bot_name, self.config.home_path / "bots" / bot_name)
            state.ensure_layout()
            self._states[bot_name] = state
        return state

    def close_all(self) -> None:
        """Close every open per-bot database (idempotent)."""
        for state in self._states.values():
            state.close()


# ---------------------------------------------------------------------------
# Scheduler (CronRunner + trigger/wakeup routing)
# ---------------------------------------------------------------------------

class BotScheduler:
    """Runs isolated agent sessions: cron jobs, app triggers, urgent wakes.

    Implements :class:`botd.cron.CronRunner` (``run_job``) and adds the two
    other entry points into a bot: :meth:`run_trigger` (called by the IPC
    ``run_bot`` handler) and :meth:`wake_bot` (the bus's urgent-waker
    callback, safe to invoke from a worker thread).

    Concurrency is capped at ``config.max_concurrent_bots`` via a semaphore
    shared by all three entry points. Paused bots buffer their work in the
    durable wakeup journal instead of running it.
    """

    def __init__(
        self,
        config: Config,
        store: BotStore,
        ipc: IpcServer,
        bus: MessageBus,
        tools: ToolRegistry,
    ) -> None:
        self.config = config
        self.store = store
        self.ipc = ipc
        self.bus = bus
        self.tools = tools
        self.cron = CronScheduler(config.home_path, store.states, self, bus, DEFAULT_TICK_SECONDS)
        self._sem = asyncio.Semaphore(config.max_concurrent_bots)
        self._stop = asyncio.Event()
        self._loop = asyncio.get_running_loop()

    # ------------------------------------------------------------------ #
    # CronRunner
    # ------------------------------------------------------------------ #
    async def run_job(self, job: Dict[str, Any]) -> Dict[str, Any]:
        """Run one due cron job in an *isolated* AgentLoop session.

        No conversation history: only the job's prompt (+ named skill) and
        the bot's soul/user/memory tiers. The outcome is serialised as a
        ``cron_result`` payload by :meth:`AgentLoop.isolated_result` and
        journaled by :class:`CronScheduler`.
        """
        prompt_parts: List[str] = []
        if job.get("skill"):
            prompt_parts.append(f"Skill to apply: {job['skill']}")
        prompt_parts.append(job.get("prompt") or "")
        prompt = "\n".join(prompt_parts)

        async with self._sem:
            state = self.store.get_or_create(job["bot"])
            if state.load_state_json().get("paused"):
                # Paused bots buffer: CronScheduler._record_result journals the
                # real cron_result into the bot's wakeup queue, so the run is
                # not lost and drains when the bot is resumed. Do not enqueue
                # a second, placeholder event here.
                return AgentLoop.isolated_result(None, "bot paused; job buffered")
            agent = AgentLoop(state, self.config, self.ipc, self.tools)
            result = await agent.run_turn(TriggerEvent(kind="user_message", payload=prompt))
        return AgentLoop.isolated_result(result.get("content"), None)

    # ------------------------------------------------------------------ #
    # App-triggered turns
    # ------------------------------------------------------------------ #
    async def run_trigger(self, bot_name: str, trigger: Dict[str, Any]) -> Dict[str, Any]:
        """Start a user turn (IPC ``run_bot``) or drain one queued wakeup.

        When ``trigger`` carries a ``message`` it is used directly;
        otherwise the oldest pending event from the bot's durable wakeup
        journal is consumed instead.
        """
        if not bot_name:
            return {"ok": False, "error": "run_bot requires 'bot'"}
        # The app's L3 catch-up worker sends {"trigger":"catch_up"}: replay
        # missed cron windows once, then run the chief-of-staff turn so it can
        # aggregate the outcome for the human.
        catch_up = trigger.get("trigger") == "catch_up"
        replayed: List[Dict[str, Any]] = []
        if catch_up:
            replayed = await self.cron.tick(is_boot=True)
        async with self._sem:
            state = self.store.get_or_create(bot_name)
            if state.load_state_json().get("paused"):
                if trigger.get("message") is not None:
                    state.enqueue_wakeup(
                        {"type": "task", "from_bot": "app", "to_bot": bot_name,
                         "priority": "urgent", "payload": {"message": trigger["message"]},
                         "timestamp": "now"}
                    )
                return {"ok": False, "error": "bot paused; trigger buffered"}
            message = trigger.get("message")
            if message is None and catch_up:
                message = (
                    "catch_up: replayed %d missed routine window(s). "
                    "Review the results in your wakeup queue." % len(replayed)
                )
            queued = message is None and state.peek_wakeup()
            if message is None and queued is None:
                return {"ok": True, "skipped": True, "reason": "no pending trigger or wakeup"}
            event = TriggerEvent(
                kind="user_message" if message is not None else "bus_message",
                payload=message if message is not None else queued,
            )
            agent = AgentLoop(state, self.config, self.ipc, self.tools)
            result = await agent.run_turn(event)
            if queued is not None:
                state.drain_wakeup()
        return {"ok": True, "content": result.get("content"), "session_id": result.get("session_id")}

    # ------------------------------------------------------------------ #
    # Urgent wakeup (bus callback, possibly on a worker thread)
    # ------------------------------------------------------------------ #
    def wake_bot(self, bot_name: str) -> None:
        """Wake a bot immediately for an urgent bus message.

        The bus calls this from whichever thread published the message, so
        it is rescheduled onto the daemon's event loop instead of assuming
        one.
        """
        self._loop.call_soon_threadsafe(self._schedule_wake, bot_name)

    def _schedule_wake(self, bot_name: str) -> None:
        asyncio.ensure_future(self._wake(bot_name))

    async def _wake(self, bot_name: str) -> None:
        """Consume one urgent event from the bot's journal and run a turn."""
        state = self.store.get_or_create(bot_name)
        event = state.peek_wakeup()
        if event is None:
            return
        trigger: Dict[str, Any] = {}
        payload = event.get("payload") or {}
        if "message" in payload:
            trigger["message"] = payload["message"]
        else:
            # A bus event: carry it through as a user-style trigger so the
            # turn sees the full event (type, corr_id, payload).
            trigger["message"] = f"[bus:{event.get('type', '?')}] {json.dumps(payload, ensure_ascii=False)}"
        result = await self.run_trigger(bot_name, trigger)
        log.info("wake %s: %s", bot_name, result.get("error") or "ok")

    # ------------------------------------------------------------------ #
    # Cron loop
    # ------------------------------------------------------------------ #
    async def cron_tick_loop(self) -> None:
        """Poll for due jobs every ``DEFAULT_TICK_SECONDS`` until stopped.

        The first tick after boot passes ``is_boot=True`` so missed windows
        are replayed per the idempotency rules in :mod:`botd.cron`.
        """
        first_tick = True
        while not self._stop.is_set():
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=DEFAULT_TICK_SECONDS)
            except asyncio.TimeoutError:
                pass  # tick time elapsed: fall through and run the tick.
            if self._stop.is_set():
                break
            results = await self.cron.tick(is_boot=first_tick)
            first_tick = False
            for res in results:
                log.info("cron: job %s ok=%s", res.get("job_id"), res.get("ok"))

    def stop(self) -> None:
        """Signal the cron loop to finish its current tick and exit."""
        self._stop.set()


# ---------------------------------------------------------------------------
# Daemon
# ---------------------------------------------------------------------------

async def run_daemon(config: Config) -> int:
    """Assemble the object graph and run the daemon until a stop signal.

    Concurrency model: two long-lived tasks on one asyncio loop - the cron
    tick loop and the IPC socket server (plus transient per-turn tasks).
    SIGTERM/SIGINT flush state, then everything is closed in reverse
    dependency order.
    """
    store = BotStore(config)
    # Chief-of-staff onboarding (Phase 2): ensure the chief bot exists so it
    # can subscribe to every bus event and aggregate outcomes for the human.
    chief_name = config.chief_bot
    if chief_name:
        chief_state = store.get_or_create(chief_name)
        soul_path = chief_state.bot_dir / "soul.md"
        if not soul_path.exists() or "a persistent agent running on a phone" in soul_path.read_text(encoding="utf-8"):
            soul_path.write_text(
                "# Soul\n"
                "You are the chief-of-staff. You observe every bus event between\n"
                "other bots, aggregate outcomes, and surface decisions the human\n"
                "must make. You never execute risky work yourself; you delegate.\n",
                encoding="utf-8",
            )
    bus = MessageBus(config.home_path, store.states, chief_bot=chief_name)
    ipc = IpcServer(config, state_store=store, bus=bus)
    # ``get_or_create`` doubles as the state resolver so tools that read
    # per-bot state (list_inbox, session search) work on a live daemon.
    tools = ToolRegistry(config, ipc=ipc, bus=bus, state_resolver=store.get_or_create)
    scheduler = BotScheduler(config, store, ipc, bus, tools)
    bus.urgent_waker = scheduler.wake_bot
    ipc.scheduler = scheduler

    await ipc.start()
    log.info("botd %s started (socket=%s, home=%s)", __version__, ipc.describe_address(), config.home_path)

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except (NotImplementedError, RuntimeError):
            signal.signal(sig, lambda *_: stop.set())

    tick_task = asyncio.create_task(scheduler.cron_tick_loop(), name="cron-tick")
    await stop.wait()

    log.info("shutdown: stopping cron tick loop")
    scheduler.stop()
    await tick_task
    log.info("shutdown: flushing + stopping IPC")
    await ipc.flush()
    await ipc.stop()
    log.info("shutdown: closing tool backends + bot databases")
    await tools.close()
    store.close_all()
    log.info("botd stopped cleanly")
    return 0


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main(argv: Optional[List[str]] = None) -> int:
    """CLI entry point. Returns the process exit code."""
    args = parse_args(argv)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )

    try:
        config = Config.load(Path(os.path.expanduser(args.config)))
    except (ValueError, json.JSONDecodeError) as exc:
        print(f"botd: invalid config {args.config}: {exc}", file=sys.stderr)
        return 1

    ensure_home(config)
    errors = config.validate()
    if errors:
        print("botd: config validation failed:\n  - " + "\n  - ".join(errors), file=sys.stderr)
        return 1

    if args.selftest:
        from .selftest import run_selftest

        return run_selftest(config)

    if args.smoketest_llm:
        from .llm import run_llm_smoketest

        return run_llm_smoketest(config, force=args.llm_mode, verbose=True)

    try:
        return asyncio.run(run_daemon(config))
    except KeyboardInterrupt:
        log.info("botd: interrupted")
        return 0


if __name__ == "__main__":
    sys.exit(main())
