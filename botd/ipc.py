"""Abstract-namespace Unix socket server (botd <-> app control plane).

The Android app (Termux side) and the durable botd daemon talk over a single
abstract-namespace Unix socket. The protocol is **JSON-lines**: every
message is one JSON object terminated by ``\n``. Messages are small, so a
line-buffered read is enough.

Abstract namespace
-----------------
On Linux a socket address of the form ``b'\0phonebot'`` (leading NUL) is an
*abstract* address: it does not exist in the filesystem and is namespaced
across the whole system. The config stores the friendly name ``@phonebot``;
:meth:`IpcServer.raw_address` turns it into the bindable ``bytes``. On
non-Linux (e.g. dev machines, Termux's Android is Linux but CI may not be),
we transparently fall back to a ``/tmp`` filesystem socket so the skeleton
stays importable and testable everywhere.

Protocol (message types)
------------------------
Every message is ``{"type": <str>, ...}``.

Incoming from the app (the app is a *client* that can reconnect):

* ``run_bot``        { bot, trigger }            -> start a user turn
* ``approve``        { corr_id, decision="approve" }
* ``deny``           { corr_id, decision="deny" }
* ``pause_bot``      { bot }
* ``resume_bot``     { bot }
* ``get_state``      {}                           -> bot state snapshot
* ``list_jobs``      {}                           -> jobs.json
* ``llm_request``    { corr_id, model, messages, tools, stream }
                    -> botd forwards to the cloud LLM and streams the
                    response back (see ``llm_response``)
* ``update_payload`` { bot, ... }                 -> persist an app-supplied
                    payload into the bot's state.json

Outgoing to the app:

* ``llm_response``   { corr_id, ... }             -> the *fallback* path:
                    when the app is NOT connected, botd itself reads
                    ``endpoint.json`` and calls the API directly, then
                    publishes the result here so reconnecting clients see
                    it.
* ``bus_event``      { ... }                      -> surface a bus event to
                    the UI.
* any error returns  ``{"type":"error","corr_id":...,"message":...}``

Reconnection
------------
The client (app) may disconnect and reconnect at any time. All durable
state is held *in-memory* in the server plus journaled on disk, so a
reconnect simply re-attaches to the same objects. The server is single-
connection (one app at a time) but tolerates rapid flapping.

Threading
---------
Everything is asyncio. The bus / cron run on the same loop; IPC handlers
``await`` LLM calls. ``threading`` is only used by the bus for its rate-
limit window (already isolated there).
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol

from .config import Config, LLMConfig

log = logging.getLogger("botd.ipc")

#: Whether this host supports abstract-namespace addresses.
_LINUX = sys.platform.startswith("linux")


class EndpointResolver(Protocol):
    """Resolves ``api_key_ref`` + ``endpoint.json`` into a concrete LLM call.

    Implemented by :class:`IpcServer` itself (reads ``endpoint.json``). Kept
    as a protocol so the fallback path is testable.
    """

    def resolve(self, api_key_ref: str) -> Dict[str, Any]:
        """Return ``{base_url, api_key, model, extra_headers}``."""
        ...


class LLMTransport(Protocol):
    """The LLM client used when the app is connected OR for fallback.

    Satisfies :class:`botd.loop.LLMClient`; ``IpcServer`` implements it by
    routing through the connected app (streaming) or the endpoint.json
    fallback (direct httpx call).
    """

    async def complete(
        self,
        model: str,
        messages: List[Dict[str, Any]],
        tools: Optional[List[Dict[str, Any]]],
    ) -> Dict[str, Any]:
        ...


def _abstract_or_path(name: str) -> tuple[bool, bytes]:
    """Return ``(abstract, address_bytes)`` for a friendly socket name.

    ``@phonebot`` on Linux -> ``(True, b'\\0phonebot')``.
    Elsewhere -> ``(False, b'/tmp/phonebot.sock')`` (absolute path bytes).
    """
    if name.startswith("@"):
        base = name[1:]
        if _LINUX:
            return True, b"\0" + base.encode("utf-8")
        return False, b"/tmp/" + base.encode("utf-8") + b".sock"
    # Treat a bare name as a /tmp path.
    return False, ("/tmp/" + name + ".sock").encode("utf-8")


class IpcServer:
    """The abstract-namespace socket server + LLM transport.

    This class plays three roles (all in one to stay self-contained):

    * **Socket server** - accept the app's JSON-lines connection.
    * **LLM transport** - ``complete()`` routes to the app when connected,
      otherwise falls back to a direct ``endpoint.json`` call. This is the
      "app not connected" case the whole design hinges on.
    * **Endpoint resolver** - reads ``<home>/endpoint.json``.
    """

    def __init__(
        self,
        config: Config,
        state_store: Optional[Any] = None,
        bus: Optional[Any] = None,
        scheduler: Optional[Any] = None,
    ) -> None:
        self.config = config
        self.state_store = state_store   # an object with .states: Dict[str,BotState]
        self.bus = bus
        self.scheduler = scheduler
        self._server: Optional[asyncio.AbstractServer] = None
        self._writer: Optional[asyncio.StreamWriter] = None
        self._client_addr: Optional[Any] = None
        self._clients = asyncio.Event()
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._pending_approvals: Dict[str, asyncio.Future] = {}
        #: True when a direct endpoint.json LLM call should be attempted
        #: instead of waiting for the app.
        self._direct_fallback = True
        #: Cached LLM backend (built lazily on first use, kept across calls).
        self._llm_backend: Optional[Any] = None
        self._llm_meta: Dict[str, Any] = {}

    # ------------------------------------------------------------------ #
    # Endpoint resolution (fallback LLM path)
    # ------------------------------------------------------------------ #
    @property
    def endpoint_path(self) -> Path:
        return Path(self.config.home_path) / "endpoint.json"

    def resolve(self, api_key_ref: str) -> Dict[str, Any]:
        """Resolve ``endpoint.json`` + the named key into a concrete endpoint.

        ``endpoint.json``::

            {
              "base_url": "https://api.openai.com/v1",
              "api_key_ref": "default",
              "model": "gpt-4o-mini",
              "secrets_dir": "~/phonebot/secrets",
              "extra_headers": {}
            }

        ``api_key_ref`` maps to ``<secrets_dir>/<ref>.key`` when present,
        else to the environment variable ``PHONEBOT_KEY_<REF>`` (uppercased),
        else to ``OPENAI_API_KEY``. This keeps secrets off the main config.
        """
        base_url = self.config.llm.base_url
        model = self.config.llm.model
        extra_headers = dict(self.config.llm.extra_headers)
        secrets_dir: Optional[str] = None
        if self.endpoint_path.exists():
            try:
                ep = json.loads(self.endpoint_path.read_text())
            except json.JSONDecodeError:
                log.warning("ipc: endpoint.json is not valid JSON; ignoring")
            else:
                base_url = ep.get("base_url", base_url)
                model = ep.get("model", model)
                api_key_ref = ep.get("api_key_ref", api_key_ref)
                extra_headers.update(ep.get("extra_headers", {}))
                secrets_dir = ep.get("secrets_dir")
        api_key = self._resolve_key(api_key_ref, secrets_dir)
        return {
            "base_url": base_url.rstrip("/"),
            "api_key": api_key,
            "model": model,
            "extra_headers": extra_headers,
        }

    def _resolve_key(self, ref: str, secrets_dir: Optional[str]) -> Optional[str]:
        """Look up an API key: env first, then the named secrets file, then a default.

        Resolution order (first hit wins):
          1. ``PHONEBOT_KEY_<REF>`` environment variable (REF uppercased, ``-`` -> ``_``).
          2. ``<secrets_dir>/<ref>.key`` (or ``<home>/secrets/<ref>.key``).
          3. ``OPENAI_API_KEY`` as a last-resort fallback.
        """
        env_var = "PHONEBOT_KEY_" + ref.upper().replace("-", "_")
        if os.environ.get(env_var):
            return os.environ[env_var]
        base = (
            Path(os.path.expanduser(secrets_dir))
            if secrets_dir
            else Path(self.config.home_path) / "secrets"
        )
        key_file = base / f"{ref}.key"
        if key_file.is_file():
            value = key_file.read_text(encoding="utf-8").strip()
            if value:
                return value
        return os.environ.get("OPENAI_API_KEY")

    # ------------------------------------------------------------------ #
    # Socket lifecycle
    # ------------------------------------------------------------------ #
    @property
    def raw_address(self) -> tuple[bool, bytes]:
        """``(abstract, address_bytes)`` derived from the configured name."""
        return _abstract_or_path(self.config.socket_name)

    def describe_address(self) -> str:
        """Human-readable address (for logs / selftest output)."""
        abstract, addr = self.raw_address
        if abstract:
            return "abstract:" + addr[1:].decode("utf-8", "replace")
        return f"path:{addr.decode('utf-8', 'replace')}"

    async def start(self) -> None:
        """Bind the socket and start accepting. Idempotent.

        A raw ``socket`` is created and bound by :meth:`_make_bound_socket`
        (which handles stale socket files), then handed to
        ``asyncio.start_unix_server`` with ``path=None`` so we reuse the
        already-bound descriptor.
        """
        self._loop = asyncio.get_running_loop()
        abstract, addr = self.raw_address
        sock = self._make_bound_socket(abstract, addr)
        self._server = await asyncio.start_unix_server(self._on_client, sock=sock)
        log.info("ipc: listening on %s", self.describe_address())

    def _make_bound_socket(self, abstract: bool, addr: bytes) -> socket.socket:
        """Create + bind a Unix socket for the given address form.

        Raises on bind failure so the caller can handle it; the daemon's
        selftest relies on this to verify the address is bindable.
        """
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            if not abstract:
                target = Path(addr.decode())
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists():
                    # Stale socket file from a crashed run: remove and rebind.
                    target.unlink()
            sock.bind(addr)
        except OSError:
            sock.close()
            raise
        sock.listen(16)
        return sock

    async def stop(self) -> None:
        """Shut down the server and close the client connection (graceful)."""
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
        if self._writer is not None:
            try:
                self._writer.close()
                await self._writer.wait_closed()
            finally:
                self._writer = None
        self._clients.clear()
        log.info("ipc: server stopped")

    # ------------------------------------------------------------------ #
    # Client handling (JSON-lines)
    # ------------------------------------------------------------------ #
    async def _on_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        """Handle one app connection: read JSON lines, dispatch, write responses."""
        # Single-connection model: a new connection supersedes the old one.
        if self._writer is not None:
            try:
                self._writer.close()
            except Exception:  # noqa: BLE001
                pass
        self._writer = writer
        self._client_addr = writer.get_extra_info("peername")
        self._clients.set()
        log.info("ipc: client connected (addr=%s)", self._client_addr)
        try:
            while True:
                line = await reader.readline()
                if not line or line.endswith(b"\x00"):
                    break
                msg = self._parse_line(line)
                if msg is None:
                    continue
                await self._dispatch(msg, writer)
        except (asyncio.IncompleteReadError, ConnectionResetError):
            pass
        finally:
            log.info("ipc: client disconnected; falling back to direct LLM")
            if self._writer is writer:
                self._writer = None
                self._clients.clear()

    @staticmethod
    def _parse_line(line: bytes) -> Optional[Dict[str, Any]]:
        """Parse one JSON line; None when blank / malformed (with a log)."""
        text = line.decode("utf-8", "replace").strip()
        if not text:
            return None
        try:
            obj = json.loads(text)
        except json.JSONDecodeError:
            log.warning("ipc: malformed JSON line dropped: %.120s", text)
            return None
        if not isinstance(obj, dict):
            log.warning("ipc: non-object JSON line dropped")
            return None
        return obj

    async def _dispatch(self, msg: Dict[str, Any], writer: asyncio.StreamWriter) -> None:
        """Route one incoming message to its handler and write the reply."""
        mtype = msg.get("type") or ""
        handler_name = self._HANDLERS.get(mtype)
        if handler_name is None:
            await self._send(
                writer,
                {"type": "error", "corr_id": msg.get("corr_id"), "message": f"unknown type {mtype!r}"},
            )
            return
        handler = getattr(self, handler_name)
        try:
            reply = await handler(msg)
        except Exception as exc:  # noqa: BLE001 - never kill the reader task
            log.exception("ipc: handler for %r failed", mtype)
            reply = {"type": "error", "corr_id": msg.get("corr_id"), "message": str(exc)}
        if reply is not None:
            await self._send(writer, reply)

    @staticmethod
    async def _send(writer: asyncio.StreamWriter, obj: Dict[str, Any]) -> None:
        writer.write(json.dumps(obj, ensure_ascii=False).encode("utf-8") + b"\n")
        await writer.drain()

    # ------------------------------------------------------------------ #
    # Message handlers
    # ------------------------------------------------------------------ #
    async def _h_run_bot(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        """Start a user-triggered turn on a bot."""
        bot = msg.get("bot")
        trigger = msg.get("trigger") or {}
        self._direct_fallback = False  # app is connected; prefer app routing.
        if self.scheduler is not None:
            await self.scheduler.run_trigger(bot, trigger)
        return {"type": "ack", "corr_id": msg.get("corr_id"), "bot": bot, "ok": True}

    async def _h_approve(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        """Resolve a pending tool approval (decision=approve)."""
        return await self._resolve_approval(msg, approved=True)

    async def _h_deny(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        """Resolve a pending tool approval (decision=deny)."""
        return await self._resolve_approval(msg, approved=False)

    async def _resolve_approval(self, msg: Dict[str, Any], approved: bool) -> Dict[str, Any]:
        corr = msg.get("corr_id")
        fut = self._pending_approvals.pop(corr, None)
        if fut is None:
            return {"type": "error", "corr_id": corr, "message": "no pending approval for corr_id"}
        if not fut.done():
            fut.set_result(approved)
        return {"type": "ack", "corr_id": corr, "decision": "approve" if approved else "deny"}

    async def _h_pause_bot(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        bot = msg.get("bot")
        self._set_bot_flag(bot, "paused", True)
        return {"type": "ack", "corr_id": msg.get("corr_id"), "bot": bot, "paused": True}

    async def _h_resume_bot(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        bot = msg.get("bot")
        self._set_bot_flag(bot, "paused", False)
        return {"type": "ack", "corr_id": msg.get("corr_id"), "bot": bot, "paused": False}

    def _set_bot_flag(self, bot: Optional[str], key: str, value: bool) -> None:
        if self.state_store is None or not bot:
            return
        state = self.state_store.states.get(bot)
        if state is None:
            return
        state.with_state(lambda s: s.update({**s, key: value}))

    async def _h_get_state(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        bots = []
        if self.state_store is not None:
            for name, bs in self.state_store.states.items():
                flags = bs.load_state_json()
                bots.append(
                    {
                        "bot": name,
                        "paused": bool(flags.get("paused", False)),
                        "spend_usd": float(flags.get("spend_usd", 0.0)),
                        "pending_wakeup": bs.wakeup_count(),
                    }
                )
        return {"type": "state", "corr_id": msg.get("corr_id"), "bots": bots}

    async def _h_list_jobs(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        data: Dict[str, Any] = {"jobs": {}}
        if self.scheduler is not None:
            data["jobs"] = self.scheduler._load_jobs().get("jobs", {})
        return {"type": "jobs", "corr_id": msg.get("corr_id"), **data}

    async def _h_update_payload(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        """Merge an app-supplied payload into the bot's state.json."""
        bot = msg.get("bot")
        payload = msg.get("payload") or {}
        if self.state_store is not None and bot and bot in self.state_store.states:
            def _merge(state: Dict[str, Any]) -> Dict[str, Any]:
                merged = dict(state or {})
                merged["app_payload"] = {**(merged.get("app_payload") or {}), **payload}
                return merged

            self.state_store.states[bot].with_state(_merge)
        return {"type": "ack", "corr_id": msg.get("corr_id"), "bot": bot, "ok": True}

    async def _h_llm_request(self, msg: Dict[str, Any]) -> Dict[str, Any]:
        """Forward an LLM request to the cloud and return the response.

        When the app is connected this is the primary path: the app asks
        botd to perform the call (botd owns the credentials / endpoint).
        The result is returned inline AND, as ``llm_response``, so a
        reconnecting client can replay it.
        """
        corr = msg.get("corr_id", "")
        model = msg.get("model", self.config.llm.model)
        messages = msg.get("messages", [])
        tools = msg.get("tools")
        result = await self.complete(model, messages, tools)
        payload = {
            "type": "llm_response",
            "corr_id": corr,
            "model": model,
            "content": result.get("content", ""),
            "tool_calls": result.get("tool_calls", []),
            "finish_reason": result.get("finish_reason"),
            "usage": result.get("usage"),
            "direct_fallback": self._direct_fallback,
        }
        # Surface a copy on the bus so all subscribers (chief-of-staff) see it.
        if self.bus is not None:
            self.bus.post("ipc", "*", "fyi", {"kind": "llm_response", "corr_id": corr, "usage": result.get("usage")})
        return payload

    # Handler registry: message type -> instance method name.
    _HANDLERS = {
        "run_bot": "_h_run_bot",
        "approve": "_h_approve",
        "deny": "_h_deny",
        "pause_bot": "_h_pause_bot",
        "resume_bot": "_h_resume_bot",
        "get_state": "_h_get_state",
        "list_jobs": "_h_list_jobs",
        "llm_request": "_h_llm_request",
        "update_payload": "_h_update_payload",
    }

    # ------------------------------------------------------------------ #
    # LLM transport (satisfies botd.loop.LLMClient / LLMTransport)
    # ------------------------------------------------------------------ #
    async def complete(
        self,
        model: str,
        messages: List[Dict[str, Any]],
        tools: Optional[List[Dict[str, Any]]],
    ) -> Dict[str, Any]:
        """Perform a cloud LLM call (OpenAI-compatible; OpenRouter works here).

        * If the app is connected and asks us to relay, we still execute the
          call here (botd owns the credentials) and report back.
        * If the app is NOT connected, this is the *direct fallback*: resolve
          ``endpoint.json`` + the named secret and call the API with httpx.

        Backend selection honours ``config.llm.mode``:

        * ``direct`` - always the real HTTP client.
        * ``fake``   - the deterministic offline backend.
        * ``auto``   - real HTTP when a key resolves, else offline fallback so a
          fresh device with no key never hard-fails.

        The backend is built once and cached (``self._llm_backend``) so the
        httpx client / connection is not re-created on every call. The key is
        never logged; only non-secret backend metadata is attached to the
        result for observability.
        """
        from .llm import build_backend

        if self._llm_backend is None:
            self._llm_backend, self._llm_meta = build_backend(
                self.config, home_path=self.config.home_path
            )
        result = await self._llm_backend.complete(model, messages, tools)
        # Keep the API key out of any trace/context; record only non-secret
        # backend metadata for observability.
        result["_llm_backend"] = self._llm_meta.get("backend")
        result["_llm_model"] = self._llm_meta.get("model")
        return result

    # ------------------------------------------------------------------ #
    # Approval registry (used by the tool dispatcher)
    # ------------------------------------------------------------------ #
    async def request_approval(self, corr_id: str, detail: Dict[str, Any]) -> bool:
        """Pause until the app approves/denies (or a timeout elapses).

        Returns True when approved. The tool dispatcher awaits this and
        resumes (or aborts) the tool call accordingly.
        """
        fut: asyncio.Future[bool] = self._loop.create_future() if self._loop else asyncio.get_event_loop().create_future()
        self._pending_approvals[corr_id] = fut
        if self.bus is not None:
            self.bus.post("botd", "*", "needs-approval", {"corr_id": corr_id, **detail}, corr_id=corr_id, priority="urgent")
        try:
            return await asyncio.wait_for(fut, timeout=300.0)
        except (asyncio.TimeoutError, asyncio.CancelledError):
            self._pending_approvals.pop(corr_id, None)
            return False

    # ------------------------------------------------------------------ #
    # Client-connection helper
    # ------------------------------------------------------------------ #
    @property
    def client_connected(self) -> bool:
        """True when an app client is currently attached."""
        return self._writer is not None and not self._writer.is_closing()

    async def flush(self) -> None:
        """Push any buffered state out (called on SIGTERM/SIGINT)."""
        if self._writer is not None:
            await self._send(
                self._writer,
                {"type": "bus_event", "kind": "shutdown", "message": "botd shutting down"},
            )
