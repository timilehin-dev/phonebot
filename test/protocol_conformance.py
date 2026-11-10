"""Cross-language / cross-layer protocol conformance check.

The single biggest risk in a two-plane design is drift between the three
places that describe a tool:

1. ``contracts/builtin-tools-phase1.json``  - the *contract* (what the app UI
   and other tools rely on; the LLM schema of record).
2. ``botd.tools.ToolRegistry`` descriptors   - what the *dispatcher* enforces
   (which args it actually reads).
3. ``botd.loop.AgentLoop._TOOL_SCHEMAS``     - what the *LLM* is shown.

Any rename that lands in one but not the others is a silent functional bug
(e.g. the contract says ``command`` but the dispatcher reads ``cmd``: the LLM
sends ``command``, the dispatcher sees an empty ``cmd`` and runs a no-op).

This check, run on a dev machine or in CI, fails hard on any mismatch:

* contract vs registry: same tool ids, same argument names, required args
  consistent.
* registry vs loop schema: same tool ids and argument names.
* risk tiers: every contract tool that is ``destructive``/``privileged`` is
  escalated by at least one registry risk rule.

Run from the repo root:

    py -3 test/protocol_conformance.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from botd.loop import AgentLoop  # noqa: E402
from botd.tools import ToolRegistry  # noqa: E402
from botd.config import Config  # noqa: E402

CONTRACT_PATH = REPO / "contracts" / "builtin-tools-phase1.json"


def _load_contract() -> List[Dict[str, Any]]:
    raw = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    return raw if isinstance(raw, list) else raw.get("tools", raw.get("tool_descriptors", []))


def _loop_schemas() -> Dict[str, Dict[str, Any]]:
    """Tool id -> parameter JSON-schema, as the loop shows the LLM."""
    out: Dict[str, Dict[str, Any]] = {}
    for schema in AgentLoop._TOOL_SCHEMAS:
        fn = schema.get("function", {})
        out[fn.get("name", "")] = fn.get("parameters", {}) or {}
    return out


def main() -> int:
    contract = _load_contract()
    config = Config()
    registry = ToolRegistry(config, ipc=None, bus=None)
    loop_schemas = _loop_schemas()

    failures: List[str] = []

    # --- 1. contract vs registry -------------------------------------------
    contract_ids = {t["id"] for t in contract}
    registry_ids = set(registry._descriptors.keys())

    missing_in_registry = contract_ids - registry_ids
    if missing_in_registry:
        failures.append(f"contract tools missing from registry: {sorted(missing_in_registry)}")

    for tool in contract:
        tid = tool["id"]
        desc = registry._descriptors.get(tid)
        if desc is None:
            continue
        contract_props = set((tool.get("input_schema") or {}).get("properties", {}).keys())
        contract_required = set((tool.get("input_schema") or {}).get("required", []))
        registry_props = set((desc.parameters or {}).get("properties", {}).keys())

        if contract_props != registry_props:
            failures.append(
                f"{tid}: arg names differ (contract={sorted(contract_props)} "
                f"registry={sorted(registry_props)})"
            )
        unrequired = contract_required - registry_props
        if unrequired:
            failures.append(f"{tid}: contract 'required' lists unknown args {sorted(unrequired)}")

    # --- 2. registry vs loop schema -----------------------------------------
    loop_ids = {k for k in loop_schemas if k}
    covered = registry_ids & loop_ids
    for tid in sorted(covered):
        registry_props = set(registry._descriptors[tid].parameters.get("properties", {}))
        loop_props = set(loop_schemas[tid].get("properties", {}))
        if registry_props != loop_props:
            failures.append(
                f"{tid}: LLM schema args {sorted(loop_props)} != registry args "
                f"{sorted(registry_props)} (the LLM would send args the dispatcher ignores)"
            )
    # loop may omit a tool entirely; that is a warning, not a failure.
    omitted = registry_ids - loop_ids
    if omitted:
        print(f"  [warn] tools in registry but not offered to the LLM: {sorted(omitted)}")

    # --- 3. risk tiers: destructive/privileged must be rule-escalated -------
    for tool in contract:
        risk = tool.get("risk", "")
        if risk not in ("destructive", "privileged"):
            continue
        escalated = any(
            pattern == tool["id"] or pattern.endswith("." + tool["id"].rsplit(".", 1)[-1])
            for pattern, _predicate, _level in registry._risk_rules
            if _level.name in ("high", "destructive")
        )
        if not escalated:
            failures.append(
                f"{tool['id']}: risk={risk} but no registry risk rule escalates it"
            )

    # --- report -------------------------------------------------------------
    print("protocol conformance check")
    print(f"  contract tools: {len(contract)}  registry tools: {len(registry_ids)}")
    if failures:
        for f in failures:
            print(f"  [FAIL] {f}")
        print("CONFORMANCE FAIL")
        return 1
    print("  [ok] contract == registry (ids + argument names)")
    print("  [ok] registry == LLM loop schemas (argument names)")
    print("  [ok] destructive/privileged contract tools all have escalation rules")
    print("CONFORMANCE OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
