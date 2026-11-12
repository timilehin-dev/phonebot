#!/usr/bin/env python3
"""Machine-check `docs/PHASE2-COVERAGE.md` against the project's own scope.

BUILD-SPEC §11 is the authority for what Phase 2 is. This gate enforces that
every Phase 2 clause is either backed by a named, existing proof or explicitly
listed as a hardware-only limitation, so a claim cannot quietly drift from the
code. It also checks the repo records its delivery rule (commit and push to
`main`, no force-push) and that CI actually runs these gates.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List

REPO = Path(__file__).resolve().parents[1]
SPEC = REPO / "docs" / "BUILD-SPEC.md"
COVERAGE = REPO / "docs" / "PHASE2-COVERAGE.md"
CI = REPO / ".github" / "workflows" / "ci.yml"
AGENTS = REPO / "AGENTS.md"

#: The six P2 features plus the three DoD bullets in BUILD-SPEC §11.
EXPECTED_IDS = {
    "persistent-bridge",
    "chief-onboarding",
    "observatory-messaging",
    "approvals-inbox",
    "create-bot",
    "roster-management",
    "dod-task-result-chain",
    "dod-approval-notification-resolve",
    "dod-reboot-catchup",
}

FAILURES: List[str] = []


def check(desc: str, ok: bool, detail: str = "") -> None:
    print(f"  [{'ok' if ok else 'FAIL'}] {desc}" + (f"  ({detail})" if detail else ""))
    if not ok:
        FAILURES.append(desc)


def norm(text: str) -> str:
    """Whitespace/markup-insensitive form for clause matching."""
    return re.sub(r"\s+", " ", text.replace("`", "").replace("*", "")).lower().strip()


def p2_section() -> str:
    text = SPEC.read_text(encoding="utf-8")
    try:
        start = text.index("**P2 — The Bot Team:**")
        end = text.index("**P3 —", start)
    except ValueError as exc:  # pragma: no cover - spec restructure
        raise SystemExit(f"test_phase2_coverage: cannot locate P2/P3 headings: {exc}")
    return norm(text[start:end])


def load_coverage() -> Dict[str, Any]:
    text = COVERAGE.read_text(encoding="utf-8")
    blocks = re.findall(r"```json\n(.*?)```", text, re.S)
    if len(blocks) != 1:
        raise SystemExit(
            f"test_phase2_coverage: expected exactly one ```json block in {COVERAGE}, got {len(blocks)}"
        )
    return json.loads(blocks[0])


def main() -> int:
    spec = p2_section()
    doc = load_coverage()
    items: List[Dict[str, Any]] = doc.get("items", [])
    ids = [item.get("id") for item in items]

    check("coverage ids are unique", len(ids) == len(set(ids)))
    check(
        "exactly the documented P2 items are covered",
        set(ids) == EXPECTED_IDS,
        f"missing={sorted(EXPECTED_IDS - set(ids))} extra={sorted(set(ids) - EXPECTED_IDS)}",
    )

    for item in items:
        item_id = item.get("id", "?")
        clause = norm(str(item.get("spec", "")))
        check(f"{item_id}: clause exists in BUILD-SPEC P2", bool(clause) and clause in spec)
        check(f"{item_id}: has a human title", bool(str(item.get("title", "")).strip()))

        automated = item.get("automated", []) or []
        device_only = item.get("device_only", []) or []
        check(
            f"{item_id}: is either proven or listed as hardware-only",
            bool(automated) or bool(device_only),
        )

        for proof in automated:
            path = REPO / str(proof.get("file", ""))
            exists = path.is_file()
            check(f"{item_id}: proof file exists ({proof.get('file')})", exists)
            symbol = str(proof.get("symbol", ""))
            if exists:
                body = path.read_text(encoding="utf-8")
                check(f"{item_id}: symbol {symbol!r} exists in {path.name}", bool(symbol) and symbol in body)
            check(f"{item_id}: proof says what it covers", bool(str(proof.get("what", "")).strip()))

        for gap in device_only:
            check(f"{item_id}: hardware-only gap states why", bool(str(gap.get("why", "")).strip()))
            check(f"{item_id}: hardware-only gap has manual steps", bool(str(gap.get("manual", "")).strip()))

    ci_text = CI.read_text(encoding="utf-8")
    check("CI runs the coverage gate", "test_phase2_coverage.py" in ci_text)
    check("CI runs the subscriber transport proof", "test_subscribe_stream.py" in ci_text)

    check("AGENTS.md exists", AGENTS.is_file())
    if AGENTS.is_file():
        agents = norm(AGENTS.read_text(encoding="utf-8"))
        check(
            "AGENTS.md records commit-and-push-to-main delivery",
            "commit and push" in agents and "main" in agents,
        )
        check(
            "AGENTS.md forbids force-push / history rewrite",
            "force-push" in agents and "history" in agents,
        )

    if FAILURES:
        print(f"PHASE 2 COVERAGE FAILED ({len(FAILURES)} problem(s))")
        return 1
    print(f"PHASE 2 COVERAGE OK ({len(items)} items)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
