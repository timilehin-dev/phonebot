"""botd - the phonebot-android durable execution plane.

botd runs inside Termux on an Android phone. It is the *durable execution
plane*: it owns the agent loops, the tool backends, the cron tick, the
message bus, and the per-bot state. The Android app is the *control /
credential / UI* plane. They communicate over an abstract-namespace Unix
socket using a JSON-lines protocol.

All LLM calls are cloud-only (OpenAI-compatible). There are no local models.
The daemon is designed to survive app kill and device reboot (via
Termux:Boot) and has no Shizuku dependency.

This package is a production skeleton: the structure, interfaces and
control flow are final for Phase 1, while individual LLM integration and
full tool handler bodies are filled in as they land.

Modules
-------
config   : validated Config dataclass + JSON (load/save).
ipc      : abstract-namespace Unix socket server + JSON-lines protocol.
loop     : AgentLoop (thin, Hermes-inspired) turn runner.
bus      : in-memory + journaled message bus with rate limiting.
cron     : cron tick loop that runs isolated AgentLoop sessions.
state    : per-bot state (state.json, sessions.db w/ FTS5, wakeup queue).
tools/*  : tool registry + shell / files / web backends.
selftest : ``--selftest`` health checks.
main     : CLI entry point.
"""

from __future__ import annotations

__version__ = "0.1.0"
__all__ = ["__version__"]
