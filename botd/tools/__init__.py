"""Tool registry and risk evaluation.

The registry is the single dispatch point between the agent loop and the
concrete tool backends (shell, files, web). The LLM only ever sees tool
*descriptors* (name + JSON schema); the registry maps a tool id to a
handler and decides, *before execution*, whether the call needs human
approval.

Risk model
----------
Every tool has a base :class:`RiskLevel` and may be escalated by
``risk_rules`` (regex / predicate rules evaluated against the arguments,
e.g. "a shell command containing ``rm -rf`` is destructive"). Levels:

    low < medium < high < destructive

* ``low``    - read-only, no approval.
* ``medium`` - mutating but local; no approval in the default policy.
* ``high``   - network spend / broad effects; approval required.
* ``destructive`` - data-loss class; approval **always** required.

When approval is required the registry emits ``needs-approval`` onto the
bus + IPC, pauses the call on an ``asyncio.Future``, and resumes (or
aborts) when the app sends ``approve`` / ``deny``.

Dispatch routing
----------------
* ``shell.run``     -> the configured shell backend (termux / ssh / shizuku)
* ``files.*``       -> direct, scoped filesystem access
* ``web.*``         -> httpx (async)

The registry is import-safe: constructing it registers handlers but
performs no I/O.
"""

from __future__ import annotations

import asyncio
import logging
import re
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple

from ..config import Config
from ..ipc import IpcServer
from ..loop import ToolContext

log = logging.getLogger("botd.tools")


# ---------------------------------------------------------------------------
# Risk model
# ---------------------------------------------------------------------------

_RISK_ORDER = {"low": 0, "medium": 1, "high": 2, "destructive": 3}


@dataclass(frozen=True)
class RiskLevel:
    """Total-order risk tag comparable by escalation."""

    name: str  # one of low | medium | high | destructive

    def __post_init__(self) -> None:
        if self.name not in _RISK_ORDER:
            raise ValueError(f"unknown risk level {self.name!r}")

    def escalates_above(self, other: "RiskLevel") -> bool:
        """True when this level is strictly higher than ``other``."""
        return _RISK_ORDER[self.name] > _RISK_ORDER[other.name]


LOW = RiskLevel("low")
MEDIUM = RiskLevel("medium")
HIGH = RiskLevel("high")
DESTRUCTIVE = RiskLevel("destructive")


@dataclass
class ToolDescriptor:
    """Machine- and LLM-facing description of one tool."""

    id: str
    description: str
    parameters: Dict[str, Any] = field(default_factory=dict)
    risk: RiskLevel = LOW
    backend: str = "direct"  # "shell" | "files" | "web" | "direct"
    #: Optional JSON-schema fragment used to build the LLM tool schema.
    schema_extra: Dict[str, Any] = field(default_factory=dict)

    def to_llm_schema(self) -> Dict[str, Any]:
        """Render the OpenAI-style ``{"type":"function",...}`` schema."""
        return {
            "type": "function",
            "function": {
                "name": self.id,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


#: A risk rule: (tool id or pattern, predicate or arg-pattern, resulting level).
RiskRule = Tuple[str, Any, RiskLevel]


class ToolRegistry:
    """Registers tool handlers and dispatches calls with risk gating.

    Parameters
    ----------
    config:
        Global daemon config (backend selection, spend caps).
    ipc:
        The IPC server, used for approval requests (may be None in tests).
    bus:
        Optional message bus to surface ``needs-approval`` events.
    shell_backend / file_tools / web_tools:
        Prebuilt backends; when None, sensible defaults are built from
        ``config`` lazily.
    """

    def __init__(
        self,
        config: Config,
        ipc: Optional[IpcServer] = None,
        bus: Optional[Any] = None,
        shell_backend: Optional[Any] = None,
        file_tools: Optional[Any] = None,
        web_tools: Optional[Any] = None,
    ) -> None:
        self.config = config
        self.ipc = ipc
        self.bus = bus
        self._descriptors: Dict[str, ToolDescriptor] = {}
        self._handlers: Dict[str, Callable[..., Any]] = {}
        self._risk_rules: List[RiskRule] = []
        #: Tool ids that always require approval regardless of risk.
        self._always_approve = {"*"}  # default: none (kept for extension)
        self._shell = shell_backend
        self._files = file_tools
        self._web = web_tools
        self.register_defaults()

    # ------------------------------------------------------------------ #
    # Registration
    # ------------------------------------------------------------------ #
    def register(
        self,
        descriptor: ToolDescriptor,
        handler: Callable[..., Any],
    ) -> None:
        """Register a handler for ``descriptor.id`` (handlers are coroutines)."""
        self._descriptors[descriptor.id] = descriptor
        self._handlers[descriptor.id] = handler

    def add_risk_rule(self, tool_pattern: str, predicate: Any, level: RiskLevel) -> None:
        """Add a rule that escalates matching calls to ``level``.

        ``tool_pattern`` is a glob on the tool id; ``predicate`` is either a
        callable ``args -> bool`` or a regex string matched against
        ``json.dumps(args)``.
        """
        self._risk_rules.append((tool_pattern, predicate, level))

    def register_defaults(self) -> None:
        """Register the built-in shell/files/web tools + dangerous-cmd rules."""
        from .shell import contains_dangerous_command, build_shell_backend
        from .files import FileTools
        from .web import WebTools

        if self._shell is None:
            self._shell = build_shell_backend(self.config)
        if self._files is None:
            self._files = FileTools(self.config)
        if self._web is None:
            self._web = WebTools(self.config)

        self.register(
            ToolDescriptor(
                id="shell.run",
                description="Run a shell command in the configured backend.",
                parameters={
                    "type": "object",
                    "properties": {
                        "command": {"type": "string"},
                        "cwd": {"type": "string"},
                        "timeout_s": {"type": "number"},
                    },
                    "required": ["command"],
                },
                risk=MEDIUM,
                backend="shell",
            ),
            self._h_shell_run,
        )
        self.register(
            ToolDescriptor(
                id="files.read",
                description="Read a file (scoped to allowed roots).",
                parameters={"type": "object", "properties": {"path": {"type": "string"}}},
                risk=LOW,
                backend="files",
            ),
            self._h_files_read,
        )
        self.register(
            ToolDescriptor(
                id="files.write",
                description="Write a file (scoped to allowed roots).",
                parameters={
                    "type": "object",
                    "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
                    "required": ["path", "content"],
                },
                risk=MEDIUM,
                backend="files",
            ),
            self._h_files_write,
        )
        self.register(
            ToolDescriptor(
                id="files.search",
                description="Search file contents (ripgrep if available, else Python).",
                parameters={
                    "type": "object",
                    "properties": {"pattern": {"type": "string"}, "path": {"type": "string"}},
                    "required": ["pattern"],
                },
                risk=LOW,
                backend="files",
            ),
            self._h_files_search,
        )
        self.register(
            ToolDescriptor(
                id="web.search",
                description="Query the configured search API.",
                parameters={"type": "object", "properties": {"query": {"type": "string"}}},
                risk=MEDIUM,
                backend="web",
            ),
            self._h_web_search,
        )
        self.register(
            ToolDescriptor(
                id="web.extract",
                description="Fetch a URL and extract its main text.",
                parameters={"type": "object", "properties": {"url": {"type": "string"}}},
                risk=MEDIUM,
                backend="web",
            ),
            self._h_web_extract,
        )

        # A shell command matching a destructive pattern escalates to
        # destructive risk (always approval).
        self.add_risk_rule(
            "shell.run",
            lambda args: contains_dangerous_command(str(args.get("command", ""))) is not None,
            DESTRUCTIVE,
        )

    # ------------------------------------------------------------------ #
    # Handler bodies (thin; delegate to the backends)
    # ------------------------------------------------------------------ #
    async def _h_shell_run(self, args: Dict[str, Any], ctx: ToolContext) -> Dict[str, Any]:
        timeout_s = float(args.get("timeout_s") or 120.0)
        result = await self._shell.run(
            str(args.get("command", "")), cwd=args.get("cwd"), timeout_s=timeout_s
        )
        return result

    async def _h_files_read(self, args: Dict[str, Any], ctx: ToolContext) -> Dict[str, Any]:
        return await self._files.read_file(str(args.get("path", "")), ctx.bot_name)

    async def _h_files_write(self, args: Dict[str, Any], ctx: ToolContext) -> Dict[str, Any]:
        return await self._files.write_file(
            str(args.get("path", "")), str(args.get("content", "")), ctx.bot_name
        )

    async def _h_files_search(self, args: Dict[str, Any], ctx: ToolContext) -> Dict[str, Any]:
        return await self._files.search_files(
            str(args.get("pattern", "")), args.get("path"), ctx.bot_name,
            limit=int(args.get("limit", 50)),
        )

    async def _h_web_search(self, args: Dict[str, Any], ctx: ToolContext) -> Dict[str, Any]:
        return await self._web.web_search(str(args.get("query", "")))

    async def _h_web_extract(self, args: Dict[str, Any], ctx: ToolContext) -> Dict[str, Any]:
        return await self._web.web_extract(str(args.get("url", "")))

    # ------------------------------------------------------------------ #
    # Risk evaluation
    # ------------------------------------------------------------------ #
    def evaluate_risk(self, tool_id: str, args: Dict[str, Any]) -> RiskLevel:
        """Base risk escalated by matching risk rules (max wins)."""
        desc = self._descriptors.get(tool_id)
        level = desc.risk if desc else MEDIUM
        for pattern, predicate, rule_level in self._risk_rules:
            if not self._tool_matches(tool_id, pattern):
                continue
            try:
                fired = self._predicate_fired(predicate, args)
            except Exception:  # noqa: BLE001 - a broken rule must not block
                log.exception("tools: risk rule %r raised; ignoring", pattern)
                continue
            if fired and rule_level.escalates_above(level):
                level = rule_level
        return level

    @staticmethod
    def _tool_matches(tool_id: str, pattern: str) -> bool:
        """Glob match of a tool id (``shell.*`` matches ``shell.run``)."""
        if pattern == "*":
            return True
        if pattern.endswith(".*"):
            return tool_id.startswith(pattern)
        return tool_id == pattern

    @staticmethod
    def _predicate_fired(predicate: Any, args: Dict[str, Any]) -> bool:
        if callable(predicate):
            return bool(predicate(args))
        if isinstance(predicate, str):
            import json

            return re.search(predicate, json.dumps(args, ensure_ascii=False)) is not None
        return False

    # ------------------------------------------------------------------ #
    # Dispatch
    # ------------------------------------------------------------------ #
    async def dispatch(
        self,
        tool_id: str,
        args: Dict[str, Any],
        ctx: ToolContext,
    ) -> Dict[str, Any]:
        """Execute one tool call, honouring risk gating. Satisfies
        :class:`botd.loop.ToolDispatcher` (``tools.dispatch(id, args, ctx)``).

        Returns a dict with:
          * ``ok``: bool - the tool ran and produced output.
          * ``denied`` / ``skipped``: present when not executed.
          * ``risk``: the effective risk level name.
          * ``output`` / ``error``: result or failure detail.
        """
        handler = self._handlers.get(tool_id)
        if handler is None:
            return {"ok": False, "error": f"unknown tool {tool_id!r}", "risk": "n/a"}

        risk = self.evaluate_risk(tool_id, args)
        if risk.name in ("high", "destructive"):
            approved = await self._request_approval(tool_id, args, risk, ctx)
            if not approved:
                return {
                    "ok": False,
                    "denied": True,
                    "risk": risk.name,
                    "error": "approval denied or timed out",
                }

        try:
            return {"ok": True, "output": await handler(args, ctx), "risk": risk.name}
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"{type(exc).__name__}: {exc}", "risk": risk.name}

    async def _request_approval(
        self,
        tool_id: str,
        args: Dict[str, Any],
        risk: RiskLevel,
        ctx: ToolContext,
    ) -> bool:
        """Pause until the app (or policy) approves a high-risk call.

        Without a connected IPC channel the default is *fail closed*:
        high-risk calls are denied rather than executed blindly.
        """
        corr_id = f"appr-{uuid.uuid4().hex[:12]}"
        detail = {"tool": tool_id, "args": args, "risk": risk.name, "bot": ctx.bot_name}
        if self.bus is not None:
            self.bus.post(
                ctx.bot_name, "*", "needs-approval",
                {"corr_id": corr_id, **detail}, corr_id=corr_id, priority="urgent",
            )
        if self.ipc is None or not self.ipc.client_connected:
            log.warning("tools: no app channel; high-risk call denied by policy")
            return False
        return await self.ipc.request_approval(corr_id, detail)

    # ------------------------------------------------------------------ #
    # Introspection
    # ------------------------------------------------------------------ #
    def tool_ids(self) -> List[str]:
        """All registered tool ids."""
        return sorted(self._descriptors)

    def schemas(self) -> List[Dict[str, Any]]:
        """LLM tool schemas for every registered tool."""
        return [d.to_llm_schema() for d in self._descriptors.values()]

    async def close(self) -> None:
        """Release backend resources (httpx client, ssh connections)."""
        for backend in (self._shell, self._files, self._web):
            closer = getattr(backend, "close", None)
            if closer is not None:
                try:
                    result = closer()
                    if asyncio.iscoroutine(result):
                        await result
                except Exception:  # noqa: BLE001
                    log.exception("tools: backend close failed")


__all__ = [
    "ToolRegistry",
    "ToolDescriptor",
    "RiskLevel",
    "LOW",
    "MEDIUM",
    "HIGH",
    "DESTRUCTIVE",
]
