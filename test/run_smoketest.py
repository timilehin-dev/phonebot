#!/usr/bin/env python
"""One-shot: point botd at a real OpenRouter key and run the LLM smoketest.

The key is read from the environment (or a file you give us), NOT from the
conversation. It is written to a scratch home's secrets dir with 0600 perms,
then the smoketest is run against that scratch home. Nothing here prints the
key.

Usage (from the repo root):
    py -3 test/run_smoketest.py --key-file path/to/openrouter.key
    py -3 test/run_smoketest.py --key-env ORKEY          # reads $ORKEY
    py -3 test/run_smoketest.py                          # prompts (hidden input)

On a real device (Termux) you'd instead just write the key to
~/phonebot/secrets/openrouter.key yourself and run
`python -m botd.main --smoketest-llm`.
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import tempfile
from pathlib import Path

# Make `botd` importable when run from the repo root.
REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from botd import config as cfg_mod  # noqa: E402
from botd.llm import run_llm_smoketest  # noqa: E402


def _read_key(args: argparse.Namespace) -> str:
    if args.key_file:
        return Path(args.key_file).read_text(encoding="utf-8").strip()
    if args.key_env:
        value = os.environ.get(args.key_env, "").strip()
        if not value:
            sys.exit(f"env var {args.key_env} is empty")
        return value
    return getpass.getpass("OpenRouter API key (input is hidden): ").strip()


def main() -> int:
    parser = argparse.ArgumentParser(description="botd OpenRouter LLM smoketest")
    parser.add_argument("--key-file", help="path to a file containing just the key")
    parser.add_argument("--key-env", help="env var name holding the key")
    parser.add_argument("--model", default="openrouter/auto", help="model id to test")
    parser.add_argument("--base-url", default="https://openrouter.ai/api/v1")
    args = parser.parse_args()

    key = _read_key(args)
    if not key:
        sys.exit("no key provided")

    scratch = Path(tempfile.mkdtemp(prefix="phonebot-smoke-"))
    home = scratch / "phonebot"
    (home / "bots").mkdir(parents=True, exist_ok=True)
    (home / "secrets").mkdir(parents=True, exist_ok=True)

    # Write the key with restricted perms (best-effort on Windows too).
    key_path = home / "secrets" / "openrouter.key"
    key_path.write_text(key, encoding="utf-8")
    try:
        os.chmod(key_path, 0o600)
    except (AttributeError, OSError):
        pass

    config = cfg_mod.Config(
        home_path=home,
        llm=cfg_mod.LLMConfig(
            base_url=args.base_url,
            api_key_ref="openrouter",
            model=args.model,
            mode="direct",
        ),
    )
    print(f"scratch home: {home}")
    print(f"model: {args.model}  base_url: {args.base_url}")
    print("-" * 60)
    exit_code = run_llm_smoketest(config, force="direct", verbose=True)
    print("-" * 60)
    print(f"scratch home left at {scratch} (delete it to remove the key)")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
