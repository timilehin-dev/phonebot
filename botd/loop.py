"""The agent loop (thin, Hermes-inspired).

:class:`AgentLoop` drives one *turn* of a single bot: it assembles the
tiered system prompt, appends the trigger event, calls the LLM (through
the app when connected, direct endpoint.json fallback otherwise),
dispatches any tool calls, and persists everything to the bot's session
store. It is deliberately thin: context-building, tool dispatch and
persistence are delegated to collaborators so Phase 1 can swap in richer
LLM reasoning without changing the control flow.

Prompt tiers
------------
The system prompt is built in three tiers, from most to least stable, so
prompt caching can pin the prefix:

* **stable**  - identity (``soul.md``), tool guidance, skills
  front-matter. Changes rarely; the longest-lived prefix.
* **context** - relevant files, recent session history. Changes per
  conversation.
* **volatile** - long-term memory, user profile, wall-clock timestamp.
  Changes every turn.

Context compression
-------------------
If the conversation exceeds ``compression_threshold`` messages, the
middle turns are summarised by a lightweight LLM pass and replaced by a
single synthetic ``system`` note, keeping head + tail intact.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol

from .config import Config
from .state import BotState


class LLMClient(Protocol):
    """Anything the loop needs from an LLM transport.

    :class:`botd.ipc.IpcServer` exposes a client that satisfies this by
    routing ``llm_request`` to the app (or falling back to direct
    endpoint.json calls). Keeping it a protocol keeps the loop testable.
    """

    async def complete(
        self,
        model: str,
        messages: List[Dict[str, Any]],
        tools: Optional[List[Dict[str, Any]]],
    ) -> Dict[str, Any]:
        """Return the raw completion dict.

        Expected keys: ``content`` (str), ``tool_calls`` (list of
        ``{"id","name","arguments"}``), ``finish_reason`` (str),
        ``usage`` (dict, optional).
        """
        ...


class CompressionClient(Protocol):
    """A cheap/fast LLM pass used only for context summarisation."""

    async def summarise(self, turns: List[Dict[str, Any]]) -> str:
        """Return a short text summary of the given turns."""
        ...


class ToolDispatcher(Protocol):
    """Executes a single tool call and returns its result dict."""

    async def dispatch(self, tool_id: str, args: Dict[str, Any], ctx: "ToolContext") -> Dict[str, Any]:
        ...


@dataclass
class ToolContext:
    """Per-invocation context handed to the tool dispatcher.

    Carries the acting bot, a free-form ``state`` bag (spend counters,
    pending approvals) and the LLM client so in-tool LLM calls can be
    routed the same way.
    """

    bot_name: str
    llm: LLMClient
    state: Dict[str, Any] = field(default_factory=dict)


@dataclass
class TriggerEvent:
    """The event that woke a bot and kicked off a turn.

    ``kind`` is one of ``user_message``, ``bus_message``, ``cron_result``.
    ``payload`` holds the raw content; for bus/cron events it is the
    corresponding bus message dict.
    """

    kind: str
    payload: Any = None
    ts: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat(timespec="seconds"))


class AgentLoop:
    """Drives a single bot's turn-running loop.

    Parameters
    ----------
    bot_state:
        The owning :class:`BotState` (owns the session DB + docs).
    config:
        The global daemon config (LLM endpoint, backend selection).
    llm:
        An :class:`LLMClient` for completions.
    tools:
        A :class:`ToolDispatcher` for tool execution.
    compressor:
        Optional :class:`CompressionClient` for context summarisation.
    """

    #: Below this many messages we skip compression entirely.
    compression_threshold: int = 60
    #: Messages kept at the head (system + first user/assistant) untouched.
    compression_head: int = 4
    #: Messages kept at the tail (most recent context) untouched.
    compression_tail: int = 12

    def __init__(
        self,
        bot_state: BotState,
        config: Config,
        llm: LLMClient,
        tools: ToolDispatcher,
        compressor: Optional[CompressionClient] = None,
    ) -> None:
        self.bot_state = bot_state
        self.config = config
        self.llm = llm
        self.tools = tools
        self.compressor = compressor

    # ------------------------------------------------------------------ #
    # Prompt assembly
    # ------------------------------------------------------------------ #
    def _read_doc(self, name: str) -> str:
        """Read a markdown doc from the bot home; '' when missing."""
        path = self.bot_state.bot_dir / name
        return path.read_text(encoding="utf-8") if path.exists() else ""

    def _skills_front_matter(self) -> str:
        """Concatenate skill front-matter for the stable prompt tier."""
        skills_dir = self.bot_state.bot_dir / "skills"
        if not skills_dir.is_dir():
            return ""
        parts: List[str] = []
        for path in sorted(skills_dir.glob("*.md")):
            try:
                parts.append(path.read_text(encoding="utf-8"))
            except OSError:
                continue
        return "\n\n".join(p.strip() for p in parts if p.strip())

    def build_system_prompt(self, context: Optional[Dict[str, Any]] = None) -> List[Dict[str, str]]:
        """Assemble the tiered system prompt as a list of system messages.

        Order: stable, context, volatile. Each tier is a separate message
        so callers (and prompt caching) can reason about which part is
        stable. ``context`` may hold ``files`` and ``recent_history`` for
        the context tier.
        """
        stable = []
        soul = self._read_doc("soul.md").strip()
        if soul:
            stable.append(f"# Identity\n{soul}")
        skill_fms = self._skills_front_matter()
        if skill_fms:
            stable.append(f"# Skills\n{skill_fms}")
        stable.append(
            "# Tool guidance\n"
            "Use tools conservatively. Risky or destructive commands require "
            "approval. Prefer read-only tools to inspect before mutating."
        )

        context_msgs: List[str] = []
        ctx = context or {}
        files = ctx.get("files")
        if files:
            context_msgs.append("# Relevant files\n" + "\n".join(files))
        recent = ctx.get("recent_history")
        if recent:
            context_msgs.append("# Recent history\n" + str(recent))

        volatile = [
            f"# Memory\n{self._read_doc('memory.md').strip()}",
            f"# User profile\n{self._read_doc('user.md').strip()}",
            f"# Now\n{datetime.now(timezone.utc).isoformat(timespec='seconds')} UTC",
        ]

        messages: List[Dict[str, str]] = []
        joined_stable = "\n\n".join(p for p in stable if p)
        if joined_stable:
            messages.append({"role": "system", "content": joined_stable})
        joined_context = "\n\n".join(p for p in context_msgs if p)
        if joined_context:
            messages.append({"role": "system", "content": joined_context})
        joined_volatile = "\n\n".join(p for p in volatile if p)
        if joined_volatile:
            messages.append({"role": "system", "content": joined_volatile})
        return messages

    # ------------------------------------------------------------------ #
    # Context compression
    # ------------------------------------------------------------------ #
    async def _maybe_compress(self, messages: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Summarise the middle turns of an over-long conversation.

        Only messages strictly between the head and tail are summarised;
        the system prefix and the most recent context are preserved verbatim.
        """
        if len(messages) <= self.compression_threshold:
            return messages
        if self.compressor is None:
            return messages
        head = messages[: self.compression_head]
        tail = messages[-self.compression_tail:]
        middle = messages[self.compression_head : len(messages) - self.compression_tail]
        if not middle:
            return messages
        summary = await self.compressor.summarise(middle)
        synthetic = [
            {"role": "system", "content": f"# Compressed context\n{summary}"}
        ]
        return head + synthetic + tail

    # ------------------------------------------------------------------ #
    # Turn execution
    # ------------------------------------------------------------------ #
    def _trigger_to_user_message(self, trigger: TriggerEvent) -> Dict[str, Any]:
        """Convert the trigger event into the first user message."""
        if trigger.kind == "user_message":
            return {"role": "user", "content": str(trigger.payload)}
        if trigger.kind == "bus_message":
            return {
                "role": "user",
                "content": f"[bus:{trigger.payload.get('type','?')}] "
                           f"{json.dumps(trigger.payload.get('payload', {}), ensure_ascii=False)}",
            }
        if trigger.kind == "cron_result":
            return {
                "role": "user",
                "content": f"[cron_result] {json.dumps(trigger.payload, ensure_ascii=False)}",
            }
        return {"role": "user", "content": str(trigger.payload)}

    async def run_turn(self, trigger_event: TriggerEvent) -> Dict[str, Any]:
        """Run one full agent turn and persist it.

        Steps:
          1. Start an isolated session (cron) or reuse a live one.
          2. Build the tiered system prompt.
          3. Append the trigger as the first user message.
          4. Call the LLM; while it returns tool_calls, dispatch each via
             the tool dispatcher, append tool results, and loop.
          5. On a final (non-tool) response, persist the turn + trace rows,
             emit any bus events requested by the LLM, and return a result
             dict describing the outcome.
        """
        session_id = self.bot_state.start_session()
        messages = self.build_system_prompt()
        messages.append(self._trigger_to_user_message(trigger_event))
        self.bot_state.record_turn(session_id, "system", "\n\n".join(m["content"] for m in messages if m["role"] == "system"))

        self.tools_ctx = ToolContext(
            bot_name=self.bot_state.bot_name, llm=self.llm
        )

        max_tool_rounds = 8
        finish_reason = "final"
        final_content = ""
        pending_bus_events: List[Dict[str, Any]] = []

        for _round in range(max_tool_rounds + 1):
            messages = await self._maybe_compress(messages)
            resp = await self.llm.complete(
                self.config.llm.model, messages, self._tool_descriptors()
            )
            tool_calls = resp.get("tool_calls") or []
            if not tool_calls:
                final_content = resp.get("content", "")
                finish_reason = resp.get("finish_reason", "final")
                break

            # A tool round: record the assistant turn, run each tool, append results.
            assistant_turn_id = self.bot_state.record_turn(
                session_id, "assistant", resp.get("content", ""), tool_calls
            )
            for call in tool_calls:
                tool_id = call.get("name", "")
                args = call.get("arguments") or {}
                if isinstance(args, str):
                    try:
                        args = json.loads(args)
                    except json.JSONDecodeError:
                        args = {"raw": args}
                t0 = time.monotonic()
                try:
                    result = await self.tools.dispatch(tool_id, args, self.tools_ctx)
                except Exception as exc:  # noqa: BLE001 - tools must not kill the turn
                    result = {"error": f"{type(exc).__name__}: {exc}"}
                duration_ms = int((time.monotonic() - t0) * 1000)
                self.bot_state.record_trace(
                    assistant_turn_id, tool_id,
                    json.dumps(args, ensure_ascii=False),
                    json.dumps(result, ensure_ascii=False),
                    duration_ms,
                )
                # Tools may request bus events (e.g. "post a result to bot X").
                bus_events = result.get("_bus_events")
                if isinstance(bus_events, list):
                    pending_bus_events.extend(bus_events)
                result.pop("_bus_events", None)
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call.get("id", ""),
                        "name": tool_id,
                        "content": json.dumps(result, ensure_ascii=False),
                    }
                )
            # Tool round complete: loop back for the next LLM call.
        else:
            # Tool round cap exhausted without a final answer.
            finish_reason = "tool_rounds_exhausted"

        # Persist the final assistant turn.
        self.bot_state.record_turn(session_id, "assistant", final_content, None)

        # Emit any bus events the tools requested.
        for event in pending_bus_events:
            self.tools_ctx.state.setdefault("bus_outbox", []).append(event)

        return {
            "session_id": session_id,
            "finish_reason": finish_reason,
            "content": final_content,
            "bus_events": pending_bus_events,
            "trigger_kind": trigger_event.kind,
        }

    # ------------------------------------------------------------------ #
    # Tool descriptors
    # ------------------------------------------------------------------ #
    _TOOL_SCHEMAS: List[Dict[str, Any]] = [
        {
            "type": "function",
            "function": {
                "name": "shell.run",
                "description": "Run a shell command in the configured backend.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "command": {"type": "string"},
                        "cwd": {"type": "string"},
                        "env": {"type": "object"},
                        "timeout_ms": {"type": "number"},
                    },
                    "required": ["command"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "files.read",
                "description": "Read a scoped text file (optionally by line range).",
                "parameters": {"type": "object", "properties": {"path": {"type": "string"}, "offset": {"type": "number"}, "limit": {"type": "number"}}, "required": ["path"]},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "files.write",
                "description": "Write (or append to) a scoped text file.",
                "parameters": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}, "append": {"type": "boolean"}}, "required": ["path", "content"]},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "files.search",
                "description": "Content-search a scoped dir (ripgrep if available).",
                "parameters": {"type": "object", "properties": {"pattern": {"type": "string"}, "path": {"type": "string"}, "case_sensitive": {"type": "boolean"}, "glob": {"type": "string"}}, "required": ["pattern"]},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "web.search",
                "description": "Query the configured search API endpoint.",
                "parameters": {"type": "object", "properties": {"query": {"type": "string"}, "max_results": {"type": "number"}}, "required": ["query"]},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "web.extract",
                "description": "Fetch a URL and extract its main text.",
                "parameters": {"type": "object", "properties": {"url": {"type": "string"}, "selector": {"type": "string"}}, "required": ["url"]},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "memory.update",
                "description": "Append to or replace text in a bot's memory doc.",
                "parameters": {"type": "object", "properties": {"target": {"type": "string"}, "operation": {"type": "string"}, "content": {"type": "string"}, "old_content": {"type": "string"}}, "required": ["target", "operation", "content"]},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "memory.search",
                "description": "Search a bot's memory docs (or prior sessions).",
                "parameters": {"type": "object", "properties": {"query": {"type": "string"}, "target": {"type": "string"}, "limit": {"type": "number"}}, "required": ["query"]},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "request_approval",
                "description": "Ask the user to approve an action; pauses until decided.",
                "parameters": {"type": "object", "properties": {"action": {"type": "string"}, "rationale": {"type": "string"}}, "required": ["action", "rationale"]},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "bus_publish",
                "description": "Publish a typed event to another bot's bus queue (task/result/fyi/...).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "topic": {"type": "string"},
                        "payload": {"type": "object"},
                        "type": {"type": "string"},
                        "corr_id": {"type": "string"},
                        "priority": {"type": "string"},
                    },
                    "required": ["topic", "payload"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "list_inbox",
                "description": "List pending events for a bot (Phase-1 stub).",
                "parameters": {"type": "object", "properties": {"bot": {"type": "string"}, "limit": {"type": "number"}}},
            },
        },
        {
            "type": "function",
            "function": {
                "name": "create_bot",
                "description": "Create a new bot (layout + state) and register it (Phase 2).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string"},
                        "soul": {"type": "string"},
                        "chief": {"type": "boolean"},
                    },
                    "required": ["name"],
                },
            },
        },
    ]

    def _tool_descriptors(self) -> List[Dict[str, Any]]:
        """Return the tool schemas offered to the LLM this turn."""
        return self._TOOL_SCHEMAS

    # ------------------------------------------------------------------ #
    # Convenience
    # ------------------------------------------------------------------ #
    @staticmethod
    def isolated_result(trigger_payload: Any, error: Optional[str] = None) -> Dict[str, Any]:
        """Serialise a cron result as a ``cron_result`` payload."""
        return {"ok": error is None, "error": error, "result": trigger_payload}


__all__ = ["AgentLoop", "TriggerEvent", "ToolContext", "LLMClient",
           "CompressionClient", "ToolDispatcher"]
