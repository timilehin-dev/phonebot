"""Phase-2 end-to-end proofs (docs/BUILD-SPEC.md §11, P2).

Covers the daemon-side half of the P2 definition of done, offline:

1. **Boot catch-up replay** — a job whose window was missed while the phone
   was off fires exactly once on ``tick(is_boot=True)``, journals exactly one
   ``cron_result`` (no duplicate, nothing dropped), and does not re-fire on a
   second boot tick.
2. **catch_up trigger** — the app's ``run_bot {"trigger":"catch_up"}`` path
   calls ``cron.tick(is_boot=True)`` and reports how many windows replayed.
3. **Cron task/result chain** — a cron wake runs the offline fake provider for
   three bots (chief, scout, writer) through the real ``AgentLoop`` +
   ``ToolRegistry`` + ``MessageBus``; the chief's ``task`` events and the
   workers' ``result`` events flow through the bus, the chief receives its
   subscription copies, and a second boot tick duplicates nothing.

Run from the repo root:

    python test/test_phase2_chain.py
"""

from __future__ import annotations

import asyncio
import sys
import tempfile
import traceback
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from botd.bus import MessageBus  # noqa: E402
from botd.config import Config  # noqa: E402
from botd.cron import CronScheduler  # noqa: E402
from botd.loop import ToolContext  # noqa: E402
from botd.main import BotScheduler, BotStore, ensure_chief_bot  # noqa: E402
from botd.state import BotState  # noqa: E402
from botd.tools import ToolRegistry  # noqa: E402


def _home(prefix: str) -> Path:
    tmp = Path(tempfile.mkdtemp(prefix=prefix))
    home = tmp / "phonebot"
    (home / "bots").mkdir(parents=True)
    (home / "secrets").mkdir(parents=True)
    return home


def _states(home: Path, names: List[str]) -> Dict[str, BotState]:
    out: Dict[str, BotState] = {}
    for name in names:
        state = BotState(name, home / "bots" / name)
        state.ensure_layout()
        out[name] = state
    return out


def _queue(states: Dict[str, BotState], bot: str) -> List[Dict[str, Any]]:
    return states[bot].list_wakeup(limit=200)


# --------------------------------------------------------------------------- #
# 1. Boot catch-up replay: exactly once, no drops, no duplicates
# --------------------------------------------------------------------------- #

async def test_boot_replay_is_exactly_once() -> None:
    home = _home("pb-boot-")
    states = _states(home, ["chief", "scout"])
    sink: List[Dict[str, Any]] = []
    bus = MessageBus(home, states, chief_bot="chief")
    bus.event_sink = sink.append

    class CountingRunner:
        def __init__(self) -> None:
            self.calls: Counter = Counter()

        async def run_job(self, job: Dict[str, Any]) -> Dict[str, Any]:
            self.calls[job["id"]] += 1
            return {"ok": True, "result": "digest done"}

    runner = CountingRunner()
    scheduler = CronScheduler(home, states, runner, bus=bus, tick_seconds=30)
    scheduler.add_job(
        {"id": "digest", "bot": "scout", "interval_seconds": 3600, "prompt": "digest"}
    )

    # Simulate a phone that was off for two days: the window is long overdue.
    data = scheduler._load_jobs()
    missed = (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()
    data["jobs"]["digest"]["last_run"] = missed
    data["jobs"]["digest"]["next_run"] = missed
    scheduler._save_jobs(data)

    now = datetime.now(timezone.utc) + timedelta(seconds=1)
    first = await scheduler.tick(now=now, is_boot=True)
    second = await scheduler.tick(now=now, is_boot=True)

    assert len(first) == 1, f"expected one replay, got {first}"
    assert second == [], f"second boot tick must not replay: {second}"
    assert runner.calls["digest"] == 1, runner.calls

    scout_events = [e for e in _queue(states, "scout") if e.get("type") == "cron_result"]
    assert len(scout_events) == 1, f"expected one journaled cron_result: {scout_events}"
    broadcast = [e for e in sink if e.get("type") == "cron_result"]
    assert len(broadcast) == 1, f"expected one broadcast: {broadcast}"
    assert broadcast[0]["corr_id"] == "digest"


# --------------------------------------------------------------------------- #
# 2. catch_up trigger: the app path replays missed windows
# --------------------------------------------------------------------------- #

class _RecordingLlm:
    """Offline fake provider that records the prompt it was given."""

    def __init__(self) -> None:
        self.user_messages: List[str] = []

    async def complete(self, model: str, messages: List[Dict[str, Any]], tools: Any) -> Dict[str, Any]:
        last_user = next(
            (m.get("content", "") for m in reversed(messages) if m.get("role") == "user"),
            "",
        )
        self.user_messages.append(str(last_user))
        return {
            "content": "catch-up acknowledged",
            "tool_calls": [],
            "finish_reason": "stop",
            "usage": {},
        }


async def test_catch_up_trigger_replays_missed_jobs() -> None:
    home = _home("pb-catchup-")
    config = Config(home_path=home)
    store = BotStore(config)
    bus = MessageBus(home, store.states, chief_bot="chief")
    llm = _RecordingLlm()
    tools = ToolRegistry(config, ipc=None, bus=bus)
    scheduler = BotScheduler(config, store, llm, bus, tools)

    class FakeCron:
        def __init__(self) -> None:
            self.calls: List[bool] = []

        async def tick(self, is_boot: bool = False) -> List[Dict[str, Any]]:
            self.calls.append(is_boot)
            return [{"job_id": "a"}, {"job_id": "b"}]

    fake = FakeCron()
    scheduler.cron = fake

    result = await scheduler.run_trigger("chief", {"trigger": "catch_up"})

    assert fake.calls == [True], f"catch_up must tick with is_boot=True: {fake.calls}"
    assert result.get("ok") is True, result
    assert llm.user_messages, "the chief turn should have run"
    assert "catch_up: replayed 2 missed routine window(s)" in llm.user_messages[-1], \
        llm.user_messages[-1]


# --------------------------------------------------------------------------- #
# 3. Cron-triggered chief -> scout/writer task/result chain
# --------------------------------------------------------------------------- #

class _ScriptedBusLlm:
    """Deterministic offline provider that emits typed ``bus_publish`` calls.

    Keyed on the job prompt marker so one provider can drive all three bots,
    mirroring how ``FakeLlmClient`` drives the loop without a network.
    """

    def _final(self, text: str) -> Dict[str, Any]:
        return {"content": text, "tool_calls": [], "finish_reason": "stop", "usage": {}}

    def _calls(self, calls: List[Dict[str, Any]]) -> Dict[str, Any]:
        return {"content": "", "tool_calls": calls, "finish_reason": "tool_calls", "usage": {}}

    @staticmethod
    def _publish(topic: str, msg_type: str, payload: Dict[str, Any], corr_id: str) -> Dict[str, Any]:
        return {
            "id": f"call_{topic}_{msg_type}",
            "name": "bus_publish",
            "arguments": {
                "topic": topic,
                "type": msg_type,
                "corr_id": corr_id,
                "payload": payload,
            },
        }

    async def complete(self, model: str, messages: List[Dict[str, Any]], tools: Any) -> Dict[str, Any]:
        saw_tool_result = any(m.get("role") == "tool" for m in messages)
        last_user = str(
            next(
                (m.get("content", "") for m in reversed(messages) if m.get("role") == "user"),
                "",
            )
        )
        if saw_tool_result:
            return self._final("chain step complete")
        if "CHAIN-DISPATCH" in last_user:
            return self._calls(
                [
                    self._publish("scout", "task", {"message": "collect prices", "task_id": "chain-1"}, "chain"),
                    self._publish("writer", "task", {"message": "write report", "task_id": "chain-2"}, "chain"),
                ]
            )
        if "CHAIN-SCOUT" in last_user:
            return self._calls(
                [self._publish("chief", "result", {"summary": "prices collected", "task_id": "chain-1"}, "chain")]
            )
        if "CHAIN-WRITER" in last_user:
            return self._calls(
                [self._publish("chief", "result", {"summary": "report written", "task_id": "chain-2"}, "chain")]
            )
        return self._final("noop")


async def test_cron_task_result_chain() -> None:
    home = _home("pb-chain-")
    config = Config(home_path=home)
    store = BotStore(config)
    for name in ("chief", "scout", "writer"):
        store.get_or_create(name)
    states = store.states

    sink: List[Dict[str, Any]] = []
    bus = MessageBus(home, states, chief_bot="chief")
    bus.event_sink = sink.append
    tools = ToolRegistry(config, ipc=None, bus=bus)
    scheduler = BotScheduler(config, store, _ScriptedBusLlm(), bus, tools)

    scheduler.cron.add_job(
        {"id": "chain-dispatch", "bot": "chief", "interval_seconds": 3600,
         "prompt": "CHAIN-DISPATCH: delegate the daily chain"}
    )
    scheduler.cron.add_job(
        {"id": "chain-scout", "bot": "scout", "interval_seconds": 3600,
         "prompt": "CHAIN-SCOUT: report prices"}
    )
    scheduler.cron.add_job(
        {"id": "chain-writer", "bot": "writer", "interval_seconds": 3600,
         "prompt": "CHAIN-WRITER: write report"}
    )

    # Phone was off: every job's window is overdue when the daemon boots.
    now = datetime.now(timezone.utc) + timedelta(seconds=1)
    data = scheduler.cron._load_jobs()
    overdue = (now - timedelta(minutes=5)).isoformat()
    for job in data["jobs"].values():
        job["next_run"] = overdue
    scheduler.cron._save_jobs(data)
    first = await scheduler.cron.tick(now=now, is_boot=True)
    assert len(first) == 3, f"expected three cron jobs to run: {first}"

    chain_events = [e for e in sink if e.get("type") in ("task", "result")]
    assert len(chain_events) == 4, f"expected 2 tasks + 2 results: {chain_events}"

    triples = {
        (e["type"], e["from_bot"], e["to_bot"], e["payload"].get("task_id"))
        for e in chain_events
    }
    assert ("task", "chief", "scout", "chain-1") in triples, triples
    assert ("task", "chief", "writer", "chain-2") in triples, triples
    assert ("result", "scout", "chief", "chain-1") in triples, triples
    assert ("result", "writer", "chief", "chain-2") in triples, triples

    # The target bots get exactly one delivery of their task (no duplicate journaling).
    scout_tasks = [e for e in _queue(states, "scout") if e.get("type") == "task"]
    writer_tasks = [e for e in _queue(states, "writer") if e.get("type") == "task"]
    assert len(scout_tasks) == 1, scout_tasks
    assert len(writer_tasks) == 1, writer_tasks
    assert scout_tasks[0]["corr_id"] == "chain"

    # The chief subscribes to traffic between other bots and receives its copy.
    chief_copies = [
        e for e in _queue(states, "chief")
        if e.get("cc") == "chief" and e.get("type") in ("task", "result")
    ]
    assert len(chief_copies) == 2, f"chief should copy both worker results: {chief_copies}"

    # Every job journals exactly one cron_result.
    for bot, job_id in (
        ("chief", "chain-dispatch"),
        ("scout", "chain-scout"),
        ("writer", "chain-writer"),
    ):
        results = [
            e for e in _queue(states, bot)
            if e.get("type") == "cron_result"
            and e.get("corr_id") == job_id
            and e.get("cc") != "chief"  # the chief's subscription copy is expected
        ]
        assert len(results) == 1, f"{bot}: expected one cron_result for {job_id}: {results}"

    # A second boot tick at the same instant must not replay or duplicate anything.
    second = await scheduler.cron.tick(now=now, is_boot=True)
    assert second == [], f"second boot tick replayed jobs: {second}"
    assert len([e for e in sink if e.get("type") in ("task", "result")]) == 4
    assert len([e for e in _queue(states, "scout") if e.get("type") == "task"]) == 1


# --------------------------------------------------------------------------- #
# 4. create_bot meta-tool + chief-of-staff onboarding
# --------------------------------------------------------------------------- #

async def test_create_bot_and_chief_onboarding() -> None:
    home = _home("pb-bots-")
    config = Config(home_path=home)
    store = BotStore(config)
    bus = MessageBus(home, store.states, chief_bot=config.chief_bot)
    tools = ToolRegistry(config, ipc=None, bus=bus, state_resolver=store.get_or_create)

    created = await tools.dispatch(
        "create_bot",
        {"name": "analyst", "soul": "You are the analyst.\n"},
        ToolContext(bot_name="chief", llm=None),
    )
    assert created.get("ok") is True, created
    analyst = store.states["analyst"]
    assert (analyst.bot_dir / "soul.md").read_text(encoding="utf-8") == "You are the analyst.\n"
    assert analyst.load_state_json().get("paused") is False

    chief = ensure_chief_bot(config, store)
    assert chief is not None and chief.bot_name == config.chief_bot
    assert "chief-of-staff" in (chief.bot_dir / "soul.md").read_text(encoding="utf-8")
    assert chief.load_state_json().get("role") == "chief"
    # Idempotent: running onboarding again must not duplicate or break anything.
    again = ensure_chief_bot(config, store)
    assert again is not None and again.bot_name == chief.bot_name


# --------------------------------------------------------------------------- #
# Runner
# --------------------------------------------------------------------------- #

def main() -> int:
    tests = [
        ("boot catch-up replay is exactly once", test_boot_replay_is_exactly_once),
        ("catch_up trigger replays missed jobs", test_catch_up_trigger_replays_missed_jobs),
        ("cron chief/scout/writer task/result chain", test_cron_task_result_chain),
        ("create_bot + chief onboarding", test_create_bot_and_chief_onboarding),
    ]
    failures = 0
    print("phase 2 chain checks")
    for name, fn in tests:
        try:
            asyncio.run(fn())
        except Exception as exc:  # noqa: BLE001 - report every failing check
            failures += 1
            print(f"  [FAIL] {name}: {type(exc).__name__}: {exc}")
            traceback.print_exc()
        else:
            print(f"  [ok] {name}")
    if failures:
        print(f"PHASE 2 CHAIN FAIL ({failures} failing)")
        return 1
    print("PHASE 2 CHAIN OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
