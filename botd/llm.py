"""LLM backends for botd (cloud-only, OpenAI-compatible).

Two backends implement the :class:`botd.loop.LLMClient` protocol
(``async complete(model, messages, tools) -> dict``):

* :class:`OpenAICompatClient` - a real, streaming-capable HTTP client that
  POSTs to ``{base_url}/chat/completions``. Works with any OpenAI-compatible
  endpoint, including **OpenRouter** (``https://openrouter.ai/api/v1``), so a
  single ``base_url + api_key + model`` covers OpenAI, OpenRouter, DeepSeek,
  Kimi, self-hosted vLLM, Ollama gateways, etc. Credentials never live in the
  config: they are resolved from ``~/phonebot/secrets/<ref>.key`` or the
  environment (see :func:`resolve_endpoint`).

* :class:`FakeLlmClient` - a deterministic, fully-offline stand-in used for
  the smoketest and for running the agent loop on a machine that has no API
  key or network. It can optionally emit a single tool round so the tool
  dispatch path is exercised without a real model.

Neither backend does any I/O at import time.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol

from .config import Config

try:  # httpx is a Phase-1 dependency; keep import side-effect-free when absent.
    import httpx
except Exception:  # pragma: no cover - httpx present on Termux
    httpx = None  # type: ignore[assignment]


# --------------------------------------------------------------------------- #
# Endpoint resolution (shared by the IPC fallback + the smoketest)
# --------------------------------------------------------------------------- #

def resolve_endpoint(config: Config, home_path: Optional[Path] = None) -> Dict[str, Any]:
    """Resolve ``endpoint.json`` + the named key into a concrete endpoint dict.

    Resolution order mirrors :meth:`botd.ipc.IpcServer.resolve` so the app-relay
    and the direct-fallback paths agree on credentials:

    1. ``endpoint.json`` (in ``home_path`` or ``config.home_path``) overrides
       ``base_url`` / ``model`` / ``api_key_ref`` / ``extra_headers`` /
       ``secrets_dir``.
    2. The key is read from ``PHONEBOT_KEY_<REF>`` env, then
       ``<secrets_dir>/<ref>.key``, then ``OPENAI_API_KEY``.

    Returns ``{"base_url", "api_key", "model", "extra_headers", "api_key_ref"}``.
    ``api_key`` is ``None`` when nothing resolved (a fake/offline signal).
    """
    base_url = config.llm.base_url
    model = config.llm.model
    api_key_ref = config.llm.api_key_ref
    extra_headers = dict(config.llm.extra_headers)
    secrets_dir: Optional[str] = None

    home = Path(home_path) if home_path is not None else config.home_path
    ep_path = home / "endpoint.json"
    if ep_path.exists():
        try:
            ep = json.loads(ep_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            ep = {}
        base_url = ep.get("base_url", base_url)
        model = ep.get("model", model)
        api_key_ref = ep.get("api_key_ref", api_key_ref)
        extra_headers.update(ep.get("extra_headers", {}) or {})
        secrets_dir = ep.get("secrets_dir")

    api_key = _resolve_key(api_key_ref, secrets_dir, home)
    return {
        "base_url": base_url.rstrip("/"),
        "api_key": api_key,
        "model": model,
        "extra_headers": extra_headers,
        "api_key_ref": api_key_ref,
    }


def _resolve_key(ref: str, secrets_dir: Optional[str], home: Path) -> Optional[str]:
    env_var = "PHONEBOT_KEY_" + ref.upper().replace("-", "_")
    if os.environ.get(env_var):
        return os.environ[env_var]
    base = Path(os.path.expanduser(secrets_dir)) if secrets_dir else home / "secrets"
    key_file = base / f"{ref}.key"
    if key_file.is_file():
        value = key_file.read_text(encoding="utf-8").strip()
        if value:
            return value
    return os.environ.get("OPENAI_API_KEY")


# --------------------------------------------------------------------------- #
# Offline deterministic backend
# --------------------------------------------------------------------------- #

class FakeLlmClient:
    """A deterministic, network-free LLM backend for tests and offline runs.

    Behaviour is driven purely by the ``messages`` list so it is fully
    reproducible:

    * When a prior tool result is already in ``messages`` (a ``role: "tool"``
      entry), it returns a short final answer referencing what it "did".
    * Otherwise, if ``tools`` is offered and ``use_tools`` is True, it emits a
      single ``files.write`` tool call so the dispatcher's tool round + trace
      path is exercised.
    * Otherwise it returns a plain echo of the last user message.

    No secrets, no I/O.
    """

    def __init__(self, use_tools: bool = True, model_name: str = "fake-v1") -> None:
        self._use_tools = use_tools
        self._model_name = model_name

    async def complete(
        self,
        model: str,
        messages: List[Dict[str, Any]],
        tools: Optional[List[Dict[str, Any]]],
    ) -> Dict[str, Any]:
        saw_tool_result = any(m.get("role") == "tool" for m in messages)
        last_user = next(
            (m["content"] for m in reversed(messages) if m.get("role") == "user"),
            "",
        )

        if saw_tool_result:
            return {
                "content": f"(fake:{self._model_name}) done. Wrote the requested file.",
                "tool_calls": [],
                "finish_reason": "stop",
                "usage": {"prompt_tokens": len(json.dumps(messages)), "completion_tokens": 12},
                "_backend": "fake",
            }

        if self._use_tools and tools:
            return {
                "content": "",
                "tool_calls": [
                    {
                        "id": "call_fake_1",
                        "name": "files.write",
                        "arguments": {"path": "notes/from_fake.txt", "content": "hello from fake llm\n"},
                    }
                ],
                "finish_reason": "tool_calls",
                "usage": {"prompt_tokens": len(json.dumps(messages)), "completion_tokens": 8},
                "_backend": "fake",
            }

        return {
            "content": f"(fake:{self._model_name}) {str(last_user)[:120]}",
            "tool_calls": [],
            "finish_reason": "stop",
            "usage": {"prompt_tokens": len(str(last_user)), "completion_tokens": 6},
            "_backend": "fake",
        }


# --------------------------------------------------------------------------- #
# Real OpenAI-compatible backend
# --------------------------------------------------------------------------- #

class OpenAICompatClient:
    """Streaming-capable client for any OpenAI-compatible ``/chat/completions``.

    Construct with the resolved endpoint values (``base_url`` / ``api_key`` /
    ``model`` / ``extra_headers``). One :meth:`complete` call performs a single
    (non-streamed) chat completion and normalises the response to the shape the
    agent loop expects: ``{"content", "tool_calls", "finish_reason", "usage"}``.

    ``tool_calls`` entries are normalised to ``{"id", "name", "arguments"}``
    where ``arguments`` is parsed from the provider's JSON string into a dict.
    """

    def __init__(
        self,
        base_url: str,
        api_key: Optional[str],
        model: str,
        timeout_s: float = 120.0,
        extra_headers: Optional[Dict[str, str]] = None,
    ) -> None:
        if httpx is None:  # pragma: no cover - httpx is a hard Phase-1 dep
            raise RuntimeError("httpx is required for the OpenAI-compatible client")
        self.base_url = (base_url or "").rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout_s = timeout_s
        self.extra_headers = dict(extra_headers or {})

    def _headers(self) -> Dict[str, str]:
        headers = {"Content-Type": "application/json", **self.extra_headers}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        # OpenRouter-friendly, harmless-to-others attribution headers:
        headers.setdefault("HTTP-Referer", "https://github.com/phonebot/phonebot-android")
        headers.setdefault("X-Title", "phonebot-android")
        return headers

    async def complete(
        self,
        model: str,
        messages: List[Dict[str, Any]],
        tools: Optional[List[Dict[str, Any]]],
    ) -> Dict[str, Any]:
        """Run one chat completion and return the normalised dict."""
        use_model = model or self.model
        payload: Dict[str, Any] = {"model": use_model, "messages": messages}
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"

        url = f"{self.base_url}/chat/completions"
        async with httpx.AsyncClient(timeout=self.timeout_s) as client:
            resp = await client.post(url, json=payload, headers=self._headers())
            resp.raise_for_status()
            data = resp.json()

        return _normalise_completion(data, use_model)

    async def close(self) -> None:  # pragma: no cover - client is per-call
        return None


def _normalise_completion(data: Dict[str, Any], model: str) -> Dict[str, Any]:
    """Map a provider chat-completion response to the loop's expected shape."""
    choice = (data.get("choices") or [{}])[0]
    message = choice.get("message") or {}
    raw_calls = message.get("tool_calls") or []
    tool_calls: List[Dict[str, Any]] = []
    for tc in raw_calls:
        fn = tc.get("function") or {}
        args = fn.get("arguments")
        if isinstance(args, str):
            try:
                args = json.loads(args) if args.strip() else {}
            except json.JSONDecodeError:
                args = {"raw": args}
        tool_calls.append({"id": tc.get("id", ""), "name": fn.get("name", ""), "arguments": args or {}})
    return {
        "content": message.get("content") or "",
        "tool_calls": tool_calls,
        "finish_reason": choice.get("finish_reason") or "stop",
        "usage": data.get("usage", {}),
        "_backend": "http",
        "_model": model,
    }


# --------------------------------------------------------------------------- #
# Backend factory
# --------------------------------------------------------------------------- #

def build_backend(config: Config, home_path: Optional[Path] = None, force: Optional[str] = None):
    """Choose the LLM backend for a config.

    ``force`` overrides the mode for tests: ``"fake"`` or ``"direct"``.
    Default policy follows ``config.llm.mode``:

    * ``fake``   - always the offline :class:`FakeLlmClient`.
    * ``direct`` - always :class:`OpenAICompatClient` (may raise if no key).
    * ``auto``   - direct when a key resolves and the base URL is a real
      endpoint; otherwise fall back to fake (offline-safe).

    Returns ``(backend, meta)`` where ``meta`` records the backend name and,
    for direct, the non-secret endpoint fields (never the API key).
    """
    mode = force or getattr(config.llm, "mode", "auto")
    if mode == "fake":
        return FakeLlmClient(), {"backend": "fake", "model": config.llm.model}

    ep = resolve_endpoint(config, home_path)
    client_kwargs = dict(
        base_url=ep["base_url"],
        api_key=ep["api_key"],
        model=ep["model"],
        timeout_s=config.llm.timeout_s,
        extra_headers=ep["extra_headers"],
    )

    if mode == "direct":
        # Always the real HTTP client. A missing key will surface as a 401 at
        # call time - by design, direct mode refuses to fall back.
        client = OpenAICompatClient(**client_kwargs)
        return client, {"backend": "http", "model": ep["model"], "base_url": ep["base_url"],
                        "has_key": bool(ep["api_key"]), "api_key_ref": ep["api_key_ref"]}

    # mode == "auto": real HTTP only when a key resolved; otherwise stay
    # offline-safe so a fresh device with no key never hard-fails.
    if ep["api_key"]:
        client = OpenAICompatClient(**client_kwargs)
        return client, {"backend": "http", "model": ep["model"], "base_url": ep["base_url"],
                        "has_key": True, "api_key_ref": ep["api_key_ref"]}
    return FakeLlmClient(), {"backend": "fake (auto, no resolvable key)", "model": ep["model"]}


# --------------------------------------------------------------------------- #
# Smoketest
# --------------------------------------------------------------------------- #

def run_llm_smoketest(config: Config, force: Optional[str] = None, verbose: bool = True) -> int:
    """Drive the agent loop end-to-end against a backend and report PASS/FAIL.

    Sub-checks (all offline-safe by default; a resolvable key makes the direct
    check hit the real endpoint):

    1. **final**   - no tools offered -> a non-empty final answer.
    2. **tool**    - tools offered -> a tool round runs and a trace row lands.
    3. **http**    - when a key resolves (or force="direct"), a real one-shot
      completion succeeds and returns usage. Skipped when offline.

    Returns 0 only when every non-skipped check passes.
    """
    from .state import BotState
    from .tools import ToolRegistry
    from .loop import AgentLoop, TriggerEvent, ToolContext

    results: List[tuple] = []
    home = config.home_path
    (home / "bots").mkdir(parents=True, exist_ok=True)
    scratch = "smoketest-bot"
    bot_dir = home / "bots" / scratch
    bot_dir.mkdir(parents=True, exist_ok=True)
    (bot_dir / "soul.md").write_text("You are a smoketest bot. Be brief.\n", encoding="utf-8")
    state = BotState(scratch, bot_dir)
    state.ensure_layout()

    backend, meta = build_backend(config, force=force)

    def _check(name: str, ok: bool, detail: str) -> None:
        results.append((name, ok, detail))

    def _run_guarded(agent, text: str) -> tuple[Dict[str, Any], Optional[str]]:
        """Run a turn; return (result, error). error is None on success."""
        try:
            return _run(agent, text), None
        except Exception as exc:  # noqa: BLE001 - report, never crash the smoketest
            status = getattr(getattr(exc, "response", None), "status_code", None)
            return {}, f"error {status or ''}: {type(exc).__name__}: {exc}".strip()

    # 1. final-only path (no tools).
    no_tools_loop = AgentLoop(state, config, backend, _NullTools())
    res1, err1 = _run_guarded(no_tools_loop, "hello, what can you do?")
    _check(
        "final (no tools)",
        err1 is None and bool(res1.get("content"))
        and res1.get("finish_reason") in ("stop", "tool_calls"),
        f"backend={meta['backend']} finish={res1.get('finish_reason')!r} "
        f"content_len={len(res1.get('content',''))}" + (f" ERROR: {err1}" if err1 else ""),
    )

    # 2. tool round (only meaningful for the offline fake backend; a real HTTP
    #    backend may or may not emit tools, so we only assert dispatch for fake).
    if meta["backend"].startswith("fake"):
        registry = ToolRegistry(config, ipc=_NullIpc(), bus=None)
        tool_loop = AgentLoop(state, config, backend, registry)
        res2, err2 = _run_guarded(tool_loop, "please note this down for me")
        trace_count = _count_traces(state)
        _check(
            "tool round (dispatch + trace)",
            err2 is None and res2.get("finish_reason") == "stop"
            and trace_count >= 1 and bool(res2.get("content")),
            f"traces={trace_count} finish={res2.get('finish_reason')!r} "
            f"content_len={len(res2.get('content',''))}" + (f" ERROR: {err2}" if err2 else ""),
        )
    else:
        _check("tool round (dispatch + trace)", True,
               "SKIPPED (http backend; tool dispatch is exercised via the fake backend)")

    # 3. real HTTP completion (only when a key resolves / forced direct).
    if meta["backend"] == "http" or force == "direct":
        ok3, detail3 = _check_http(backend, meta, config)
        _check("http completion", ok3, detail3)
    else:
        _check("http completion", True, "SKIPPED (offline / no resolvable key; use a real key for this check)")

    state.close()
    _cleanup_sbot(bot_dir)
    _report(results, verbose)
    return 0 if all(ok for _, ok, _ in results) else 1


def _run(agent: AgentLoop, text: str) -> Dict[str, Any]:
    import asyncio

    from .loop import TriggerEvent

    return asyncio.run(agent.run_turn(TriggerEvent(kind="user_message", payload=text)))


def _check_http(backend: Any, meta: Dict[str, Any], config: Config) -> tuple[bool, str]:
    import asyncio

    async def _go() -> tuple[bool, str]:
        prompt = "Reply with exactly the word: pong"
        try:
            out = await backend.complete(meta.get("model") or config.llm.model,
                                         [{"role": "user", "content": prompt}], None)
        except Exception as exc:  # noqa: BLE001 - report, don't crash
            status = getattr(getattr(exc, "response", None), "status_code", None)
            hint = ""
            if status == 401:
                hint = " (401: no valid API key — check secrets/<ref>.key or --key-env)"
            elif status in (404,):
                hint = " (404: check base_url / model id)"
            return False, f"http error {status or '?'}: {type(exc).__name__}{hint}"
        content = (out.get("content") or "").strip().lower()
        has_usage = bool(out.get("usage"))
        ok = ("pong" in content) and has_usage
        return ok, f"content={content[:40]!r} usage={'yes' if has_usage else 'no'} base={meta.get('base_url')}"

    return asyncio.run(_go())


def _cleanup_sbot(bot_dir: "Path") -> None:
    """Best-effort removal of the scratch smoketest bot so reruns stay clean."""
    import shutil

    try:
        shutil.rmtree(bot_dir, ignore_errors=True)
    except Exception:  # noqa: BLE001
        pass


def _count_traces(state: BotState) -> int:
    conn = getattr(state, "_db", None)
    if conn is None:
        return 0
    try:
        row = conn.execute("SELECT COUNT(*) AS n FROM trace").fetchone()
        return int(row[0]) if row else 0
    except Exception:  # noqa: BLE001
        return -1


def _report(results: List[tuple], verbose: bool) -> None:
    print("botd LLM smoketest")
    all_ok = True
    for name, ok, detail in results:
        tag = "SKIP" if detail.startswith("SKIPPED") else ("PASS" if ok else "FAIL")
        all_ok = all_ok and ok
        print(f"[{tag}] {name}  ({detail})")
    print("OK" if all_ok else "FAIL")


# --------------------------------------------------------------------------- #
# Null collaborators (keep the smoketest self-contained)
# --------------------------------------------------------------------------- #

class _NullTools:
    """Tool dispatcher that returns a canned result; exercises the loop only."""

    async def dispatch(self, tool_id: str, args: Dict[str, Any], ctx: Any) -> Dict[str, Any]:
        return {"ok": True, "tool": tool_id}


class _NullIpc:
    """Minimal LLM-transport stub so a ToolRegistry can be built offline."""

    async def complete(self, model, messages, tools):
        return {"content": "", "tool_calls": [], "finish_reason": "stop"}
