"""Web tool backend.

    * ``web.search``  - query a configurable search API endpoint. The
      endpoint + API key reference come from the environment
      (``MULTIBOT_SEARCH_URL`` / ``MULTIBOT_SEARCH_KEY_REF``) or
      ``endpoint.json``; when unconfigured the tool returns a clean
      "not configured" result instead of crashing the turn.
    * ``web.extract`` - fetch a URL and pull its main text out. Uses
      BeautifulSoup when installed; otherwise a conservative regex-based
      HTML-to-text fallback.

All calls go through a single shared, lazily-created ``httpx.AsyncClient``
so the daemon reuses connections and can close them cleanly on shutdown.
"""

from __future__ import annotations

import html
import json
import logging
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx

from ..config import Config

log = logging.getLogger("botd.tools.web")

#: How much extracted text to hand back to the LLM per call.
EXTRACT_CHAR_CAP = 12_000

#: Tags whose *content* is structural noise, not page text.
_NOISE_TAGS = ("script", "style", "noscript", "template", "svg", "iframe")


def _try_bs4(text: str) -> Optional[str]:
    """Extract main text with BeautifulSoup; None when not installed."""
    try:
        from bs4 import BeautifulSoup  # type: ignore
    except ImportError:
        return None
    soup = BeautifulSoup(text, "html.parser")
    for tag in soup.find_all(list(_NOISE_TAGS)):
        tag.decompose()
    main = soup.find("main") or soup.find("article") or soup.body or soup
    return re.sub(r"\n{3,}", "\n\n", main.get_text(separator="\n").strip())


_BLOCK = re.compile(r"<[^>]+>")
_WS = re.compile(r"[ \t]+")


def _regex_extract(html_text: str) -> str:
    """V1 fallback: strip script/style, blocks, tags, and collapse whitespace."""
    for tag in _NOISE_TAGS:
        html_text = re.sub(
            rf"<{tag}\b[^>]*>.*?</{tag}>", " ", html_text, flags=re.DOTALL | re.IGNORECASE
        )
    lines: List[str] = []
    for chunk in re.split(r"</?(p|div|li|h[1-6]|br|tr|section|article)\b[^>]*>", html_text):
        clean = html.unescape(_BLOCK.sub(" ", chunk))
        clean = _WS.sub(" ", clean).strip()
        if clean:
            lines.append(clean)
    return "\n".join(line for line in lines if line)[:EXTRACT_CHAR_CAP]


class WebTools:
    """Asynchronous web tools backed by a shared httpx client.

    Parameters
    ----------
    config:
        Global config (home path used to locate ``endpoint.json``).
    client:
        Inject an existing ``httpx.AsyncClient`` (used by tests); when
        None one is created on first use.
    """

    def __init__(self, config: Config, client: Optional[httpx.AsyncClient] = None) -> None:
        self._config = config
        self._client = client
        self._closed = False

    # ------------------------------------------------------------------ #
    # Client
    # ------------------------------------------------------------------ #
    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=30.0, follow_redirects=True)
        return self._client

    # ------------------------------------------------------------------ #
    # web.search
    # ------------------------------------------------------------------ #
    def _search_endpoint(self) -> Dict[str, str]:
        """Resolve the search API endpoint + key from env / endpoint.json."""
        base_url = os.environ.get("MULTIBOT_SEARCH_URL", "")
        key_ref = os.environ.get("MULTIBOT_SEARCH_KEY_REF", "")
        ep_path = Path(self._config.home_path) / "endpoint.json"
        if ep_path.exists():
            try:
                ep = json.loads(ep_path.read_text())
                base_url = ep.get("search_url", base_url)
                key_ref = ep.get("search_key_ref", key_ref)
            except Exception:  # noqa: BLE001
                log.warning("web: endpoint.json unreadable; using env defaults")
        api_key = os.environ.get(f"MULTIBOT_KEY_{key_ref.upper().replace('-', '_')}") if key_ref else None
        return {"url": base_url, "api_key": api_key}

    async def web_search(self, query: str, max_results: int = 5) -> Dict[str, Any]:
        """Call the configured search endpoint; return a list of results.

        When no endpoint is configured, returns a structured "not configured"
        result so the LLM can adapt rather than the turn crashing.
        """
        endpoint = self._search_endpoint()
        if not endpoint["url"]:
            return {
                "ok": False,
                "configured": False,
                "error": "no search endpoint configured (set MULTIBOT_SEARCH_URL or endpoint.json search_url)",
            }
        client = self._http()
        headers = {"X-Api-Key": endpoint["api_key"]} if endpoint["api_key"] else {}
        try:
            resp = await client.get(
                endpoint["url"],
                params={"q": query, "count": max_results},
                headers=headers,
            )
            resp.raise_for_status()
            data = resp.json()
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "configured": True, "error": f"search failed: {type(exc).__name__}: {exc}"}
        # Normalise common shapes; keep it permissive for a skeleton.
        results = data.get("results", data) if isinstance(data, dict) else data
        return {"ok": True, "query": query, "results": results}

    # ------------------------------------------------------------------ #
    # web.extract
    # ------------------------------------------------------------------ #
    async def web_extract(self, url: str) -> Dict[str, Any]:
        """Fetch ``url`` and return its main text.

        Prefers BeautifulSoup; falls back to a regex strip when it is not
        installed. Non-HTML content types (JSON, plain text) are returned
        as-is, truncated to the cap.
        """
        if not url.startswith(("http://", "https://")):
            return {"ok": False, "error": f"unsafe URL scheme: {url!r}"}
        client = self._http()
        try:
            resp = await client.get(url)
            resp.raise_for_status()
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "error": f"fetch failed: {type(exc).__name__}: {exc}"}
        content_type = resp.headers.get("content-type", "")
        if "html" in content_type:
            text = _try_bs4(resp.text) or _regex_extract(resp.text)
        else:
            text = resp.text[:EXTRACT_CHAR_CAP]
        return {
            "ok": True,
            "url": url,
            "status": resp.status_code,
            "content_type": content_type,
            "text": text,
            "truncated": len(text) >= EXTRACT_CHAR_CAP,
        }

    # ------------------------------------------------------------------ #
    # Lifecycle
    # ------------------------------------------------------------------ #
    async def close(self) -> None:
        """Close the shared httpx client (idempotent)."""
        if self._client is not None and not self._closed:
            await self._client.aclose()
            self._closed = True


__all__ = ["WebTools", "_regex_extract", "EXTRACT_CHAR_CAP"]
