"""Offline proof that the OpenAI-compatible HTTP path is wired correctly.

Spins up a tiny in-process HTTP server that mimics an OpenAI-compatible
``/chat/compoints`` endpoint, points :class:`botd.llm.OpenAICompatClient` at it,
and asserts:

* the ``Authorization: Bearer <key>`` header is sent,
* the request payload carries the expected ``model`` + ``messages``,
* a ``tool_calls`` response is normalised into the loop's
  ``{"id","name","arguments"}`` shape (arguments parsed from JSON string),
* ``usage`` is preserved.

This is what you can run on a dev machine to validate the OpenRouter
integration *before* spending a real API key. When you do have a key, the
smoketest's "http completion" check (via ``test/run_smoketest.py``) hits the
real endpoint instead.

Run from the repo root:

    py -3 test/mock_openai_check.py
"""

from __future__ import annotations

import asyncio
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from botd.llm import OpenAICompatClient  # noqa: E402


class _MockHandler(BaseHTTPRequestHandler):
    """A stand-in OpenAI-compatible chat-completions endpoint.

    Records the last request (headers + body) on ``cls.captured`` and returns
    a canned completion that includes one tool call, so the normalisation
    path is exercised.
    """

    captured = {}

    def do_POST(self):  # noqa: N802 (http.server API)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length else ""
        type(self).captured = {
            "path": self.path,
            "auth": self.headers.get("Authorization"),
            "referer": self.headers.get("HTTP-Referer"),
            "title": self.headers.get("X-Title"),
            "body": json.loads(body) if body else {},
        }

        # Canned response: one tool call (arguments as a JSON *string*, as the
        # real OpenAI/compatible API returns it) + a short content note.
        payload = {
            "id": "chatcmpl-mock",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [
                            {
                                "id": "call_mock_1",
                                "type": "function",
                                "function": {
                                    "name": "files.write",
                                    "arguments": json.dumps(
                                        {"path": "a/b.txt", "content": "hi"}
                                    ),
                                },
                            }
                        ],
                    },
                    "finish_reason": "tool_calls",
                }
            ],
            "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18},
        }
        out = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):  # silence default stderr noise
        return


def _start_server() -> str:
    httpd = HTTPServer(("127.0.0.1", 0), _MockHandler)
    port = httpd.socket.getsockname()[1]
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    return f"http://127.0.0.1:{port}/v1"


def _assert(cond: bool, label: str) -> bool:
    print(f"  [{'ok' if cond else 'FAIL'}] {label}")
    return cond


def main() -> int:
    base_url = _start_server()
    client = OpenAICompatClient(base_url=base_url, api_key="sk-test-key",
                                model="mock-model", timeout_s=10.0)

    tools = [
        {"type": "function",
         "function": {"name": "files.write", "description": "write",
                      "parameters": {"type": "object"}}}
    ]
    messages = [{"role": "user", "content": "write a file"}]

    out = asyncio.run(client.complete("mock-model", messages, tools))

    cap = _MockHandler.captured
    checks = [
        _assert(cap.get("auth") == "Bearer sk-test-key", "auth header = Bearer <key>"),
        _assert(cap.get("referer") and cap.get("title"), "OpenRouter attribution headers present"),
        _assert(cap.get("body", {}).get("model") == "mock-model", "request carries model"),
        _assert(cap.get("body", {}).get("messages") == messages, "request carries messages"),
        _assert(cap.get("body", {}).get("tools") == tools, "request carries tools"),
        _assert(out.get("finish_reason") == "tool_calls", "finish_reason normalised"),
        _assert(out.get("usage", {}).get("total_tokens") == 18, "usage preserved"),
        _assert(len(out.get("tool_calls")) == 1, "one tool call normalised"),
        _assert(out["tool_calls"][0]["name"] == "files.write", "tool name extracted"),
        _assert(out["tool_calls"][0]["arguments"] == {"path": "a/b.txt", "content": "hi"},
                "tool arguments JSON-parsed into a dict"),
    ]
    ok = all(checks)
    print("mock openai check: " + ("OK" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
