"""botd configuration.

This module defines the validated configuration model for the botd daemon.
Configuration lives at ``~/multibot/config.json`` by default. The module is
side-effect free at import time: it only defines dataclasses and pure
functions. I/O happens exclusively through :meth:`Config.load` and
:meth:`Config.save`, which main/selftest invoke explicitly.

Design notes
------------
* ``socket_name`` defaults to ``"@multibot"``. On Linux, an abstract-namespace
  address is the prefix ``\0`` followed by a NUL-terminated name. We store the
  human friendly name here and build the raw ``bytes`` address in
  :class:`botd.ipc.IpcServer`.
* The LLM block stores an ``api_key_ref`` (a named reference resolved against
  the Android app's keychain / the ``secrets/`` dir), not a raw key. This keeps
  the config portable and avoids embedding secrets on disk.
* The ``backend`` block selects which shell/python execution backend the tool
  registry routes to. Defaults are ``"termux"``.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Dict, List, Optional


# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

DEFAULT_HOME = Path(os.path.expanduser("~/multibot"))
DEFAULT_SOCKET_NAME = "@multibot"
DEFAULT_MAX_CONCURRENT_BOTS = 8
DEFAULT_SPEND_CAP_PER_BOT_USD = 5.0
DEFAULT_LLM_BASE_URL = "https://api.openai.com/v1"
DEFAULT_LLM_MODEL = "gpt-4o-mini"


# ---------------------------------------------------------------------------
# Nested dataclasses
# ---------------------------------------------------------------------------

@dataclass
class LLMConfig:
    """Cloud LLM endpoint configuration (OpenAI-compatible).

    ``mode`` selects the backend resolution policy used by
    :func:`botd.llm.build_backend`:

    * ``"auto"``   (default) - use a real OpenAI-compatible call when a key
      resolves, else fall back to the offline fake. Safe on a bare device.
    * ``"direct"`` - always use the real HTTP client (raises if no key).
    * ``"fake"``   - always the deterministic offline backend (tests / no network).
    """

    base_url: str = DEFAULT_LLM_BASE_URL
    api_key_ref: str = "default"
    model: str = DEFAULT_LLM_MODEL
    timeout_s: float = 120.0
    mode: str = "auto"
    # Optional extra headers the app may inject (e.g. org id).
    extra_headers: Dict[str, str] = field(default_factory=dict)

    def validate(self) -> List[str]:
        """Return a list of human-readable validation errors (empty == OK)."""
        errors: List[str] = []
        if not self.base_url.startswith(("http://", "https://", "fake", "none")):
            errors.append(f"llm.base_url must be an http(s) URL (or 'fake'), got {self.base_url!r}")
        if not self.api_key_ref:
            errors.append("llm.api_key_ref must be a non-empty reference name")
        if not self.model:
            errors.append("llm.model must be a non-empty model identifier")
        if self.mode not in ("auto", "direct", "fake"):
            errors.append(f"llm.mode must be one of auto|direct|fake, got {self.mode!r}")
        return errors


@dataclass
class BackendConfig:
    """Execution backend selection for the tool registry.

    ``shell`` and ``python`` each name a backend. Recognised shell backends:
    ``termux`` (default, in-Termux subprocess), ``ssh`` (remote via
    asyncssh), ``shizuku`` (placeholder stub that raises NotImplementedError).
    """

    shell: str = "termux"
    python: str = "termux"
    ssh_host: Optional[str] = None
    ssh_user: Optional[str] = None
    ssh_port: int = 22

    _SHELL_BACKENDS = ("termux", "ssh", "shizuku")

    def validate(self) -> List[str]:
        errors: List[str] = []
        for key in ("shell", "python"):
            value = getattr(self, key)
            if value not in self._SHELL_BACKENDS:
                errors.append(
                    f"backend.{key} must be one of {self._SHELL_BACKENDS}, got {value!r}"
                )
        if self.shell == "ssh" and (not self.ssh_host or not self.ssh_user):
            errors.append("backend.ssh requires ssh_host and ssh_user")
        return errors


@dataclass
class Config:
    """Top-level botd configuration.

    Attributes
    ----------
    home_path:
        Root of all botd-owned data: ``bots/``, ``secrets/``, ``jobs.json``,
        ``endpoint.json``.
    socket_name:
        Friendly abstract-namespace socket name (``@multibot``). The raw bind
        address (``\0multibot``) is derived from this in the IPC layer.
    llm:
        Cloud LLM endpoint configuration.
    backend:
        Tool execution backend selection.
    max_concurrent_bots:
        How many agent loops may run at once across all bots.
    spend_cap_per_bot_usd:
        Per-bot USD spend guardrail.
    """

    home_path: Path = DEFAULT_HOME
    socket_name: str = DEFAULT_SOCKET_NAME
    llm: LLMConfig = field(default_factory=LLMConfig)
    backend: BackendConfig = field(default_factory=BackendConfig)
    max_concurrent_bots: int = DEFAULT_MAX_CONCURRENT_BOTS
    spend_cap_per_bot_usd: float = DEFAULT_SPEND_CAP_PER_BOT_USD

    # ------------------------------------------------------------------ #
    # Validation
    # ------------------------------------------------------------------ #
    def validate(self) -> List[str]:
        """Validate the whole config tree; returns all collected errors."""
        errors: List[str] = []
        errors.extend(f"llm.{e}" for e in self.llm.validate())
        errors.extend(f"backend.{e}" for e in self.backend.validate())
        if self.max_concurrent_bots < 1:
            errors.append(f"max_concurrent_bots must be >= 1, got {self.max_concurrent_bots}")
        if self.spend_cap_per_bot_usd < 0:
            errors.append(f"spend_cap_per_bot_usd must be >= 0, got {self.spend_cap_per_bot_usd}")
        if not self.socket_name:
            errors.append("socket_name must be a non-empty string")
        return errors

    # ------------------------------------------------------------------ #
    # Serialisation
    # ------------------------------------------------------------------ #
    def to_dict(self) -> Dict[str, Any]:
        """Serialise to a JSON-friendly dict (home_path as str)."""
        data = asdict(self)
        data["home_path"] = str(self.home_path)
        return data

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Config":
        """Build a Config from a dict (inverse of :meth:`to_dict`)."""
        data = dict(data)  # shallow copy
        home = Path(os.path.expanduser(data.get("home_path", str(DEFAULT_HOME))))
        llm = LLMConfig(**_pick(data.get("llm", {}), LLMConfig))
        backend = BackendConfig(**_pick(data.get("backend", {}), BackendConfig))
        known = {
            "socket_name": data.get("socket_name", DEFAULT_SOCKET_NAME),
            "max_concurrent_bots": int(data.get("max_concurrent_bots", DEFAULT_MAX_CONCURRENT_BOTS)),
            "spend_cap_per_bot_usd": float(
                data.get("spend_cap_per_bot_usd", DEFAULT_SPEND_CAP_PER_BOT_USD)
            ),
        }
        return cls(home_path=home, llm=llm, backend=backend, **known)

    def save(self, path: Optional[Path] = None) -> Path:
        """Write the config to JSON. Defaults to ``<home>/config.json``.

        Parent directories are created as needed. Returns the written path.
        """
        target = Path(path) if path is not None else self.home_path / "config.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(self.to_dict(), indent=2, sort_keys=True) + "\n")
        return target

    @classmethod
    def load(cls, path: Optional[Path] = None) -> "Config":
        """Load and validate a config from ``path`` (or default location).

        If the file does not exist, a fresh default config is returned (not
        written). Raises ``ValueError`` listing every validation problem when
        the on-disk file is malformed.
        """
        target = Path(path) if path is not None else DEFAULT_HOME / "config.json"
        if not target.exists():
            return cls()
        raw = json.loads(target.read_text())
        config = cls.from_dict(raw)
        errors = config.validate()
        if errors:
            raise ValueError("invalid config:\n  - " + "\n  - ".join(errors))
        return config


def _pick(source: Optional[Dict[str, Any]], model: Any) -> Dict[str, Any]:
    """Pick only the keys that ``model`` actually accepts, with type coercion.

    Protects against unknown keys in JSON that would break dataclass
    construction, and tolerates absence of keys by letting defaults apply.
    """
    if not source:
        return {}
    import dataclasses as _dc

    accepted = {f.name: f for f in _dc.fields(model)}
    out: Dict[str, Any] = {}
    for key, value in source.items():
        if key in accepted:
            out[key] = value
    return out


__all__ = [
    "Config",
    "LLMConfig",
    "BackendConfig",
    "DEFAULT_HOME",
    "DEFAULT_SOCKET_NAME",
]
