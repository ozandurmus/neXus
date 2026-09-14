"""GOV.ORCH.13 -- render the dispatch ledger from orchestrator records."""
from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path
from typing import Any

import orchestrator as orch
import orchestrator_usage as usage

REPO_ROOT = Path(__file__).resolve().parent.parent
LEDGER_PATH = REPO_ROOT / "project" / "DISPATCH_LEDGER.md"
SUBSCRIPTION_PROVIDERS = {"codex"}  # GOV.ORCH.13 DL-4: PO-owned machine mapping.
COLUMNS = ("movement", "attempt", "started", "provider", "model", "effort", "minutes", "turns",
           "tokens", "cache %", "cost", "source", "budget", "outcome", "PR", "assessment")


def _cell(value: Any) -> str:
    return "unknown" if value is None or value == "" else str(value).replace("|", r"\|")


def _minutes(started: str | None, ended: str | None) -> str:
    """GOV.ORCH.13 DL-6: duration is only a non-negative observed interval."""
    try:
        start = datetime.fromisoformat(started.replace("Z", "+00:00"))
        end = datetime.fromisoformat(ended.replace("Z", "+00:00"))
        seconds = (end - start).total_seconds()
    except (AttributeError, ValueError):
        return "unknown"
    return str(round(seconds / 60, 1)) if seconds >= 0 else "unknown"


def _assessments(text: str) -> dict[tuple[str, str], str]:
    """GOV.ORCH.13 DL-5: keep the human-owned cell on a re-render."""
    saved = {}
    for line in text.splitlines():
        cells = [part.strip() for part in line.strip().strip("|").split("|")]
        if len(cells) == len(COLUMNS) and cells[0] not in {"movement", "---"} and cells[-1]:
            saved[(cells[0], cells[1])] = cells[-1]
    return saved


def _pr(relay_dir: Path, movement: str) -> str:
    try:
        entries = orch._load_relay(orch._resolve_relay_file(relay_dir, movement)).get("entries") or []
    except orch.OrchestratorError:
        return "-"
    for entry in reversed(entries):
        value = ((entry.get("report") or {}).get("integration") or {}).get("pr")
        if value is not None:
            return str(value)
    return "-"


def _row(record: dict, attempt: int, latest: bool, state_dir: Path, relay_dir: Path) -> list[str]:
    """GOV.ORCH.13 DL-2/DL-3: only the retained latest attempt has usage evidence."""
    movement = record["movement_id"]
    if not latest:
        return [movement, str(attempt)] + ["unknown"] * 13 + [""]
    provider = record.get("provider") or "unknown"
    if not usage._usage_path(state_dir, movement).is_file():
        return [movement, str(attempt), _cell(record.get("started_at")), _cell(provider),
                _cell(record.get("model_requested")), _cell(record.get("effort_requested"))] + ["unknown"] * 8 + [_pr(relay_dir, movement), ""]
    observed = usage.usage_summary_only(
        state_dir, movement, provider, usage.load_price_table(orch.DEFAULT_PRICE_TABLE_PATH), record.get("model_requested"),
    )
    ended = observed.get("last_event_at") or record.get("last_timestamp")
    cost = observed.get("cost_usd")
    budget = record.get("max_budget_usd")
    cost_cell = f"${cost:.4f}" if isinstance(cost, (int, float)) else "unknown"
    if isinstance(cost, (int, float)) and isinstance(budget, (int, float)) and cost > budget:
        cost_cell += " !"
    return [
        movement, str(attempt), _cell(record.get("started_at")), _cell(provider),
        _cell(observed.get("model") or record.get("model_requested")), _cell(record.get("effort_requested")),
        _minutes(record.get("started_at"), ended), _cell(observed.get("turns")), _cell(observed.get("total_tokens")),
        f"{observed['cache_hit_ratio'] * 100:.2f}%" if observed.get("cache_hit_ratio") is not None else "unknown",
        cost_cell, _cell(observed.get("cost_source")),
        f"${budget:.4f}" if isinstance(budget, (int, float)) else "unknown", _cell(record.get("phase") or record.get("failure_reason")), _pr(relay_dir, movement), "",
    ]


def render(state_dir: Path, relay_dir: Path, existing: str = "") -> str:
    """GOV.ORCH.13 DL-1..DL-5: render all retained state records and totals."""
    assessments = _assessments(existing)
    rows = []
    for record in orch._list_state_records(state_dir):
        attempts = max(1, int(record.get("revision") or 1) + int(record.get("retry_count") or 0))
        for attempt in range(1, attempts + 1):
            row = _row(record, attempt, attempt == attempts, state_dir, relay_dir)
            row[-1] = assessments.get((row[0], row[1]), "")
            rows.append(row)
    totals = {"metered": 0.0, "subscription": 0.0}
    providers = {"metered": set(), "subscription": set()}
    unavailable = 0
    for row in rows:
        try:
            value = float(row[10].removesuffix(" !").removeprefix("$"))
        except ValueError:
            unavailable += 1
            continue
        kind = "subscription" if row[3] in SUBSCRIPTION_PROVIDERS else "metered"
        totals[kind] += value
        providers[kind].add(row[3])
    table = ["# Dispatch ledger", "", "A trailing `!` in cost means the recorded cost exceeds the recorded budget ceiling.", "", "| " + " | ".join(COLUMNS) + " |", "|" + "|".join(["---"] * len(COLUMNS)) + "|"]
    table += ["| " + " | ".join(row) + " |" for row in rows]
    table += ["", f"Metered total ({', '.join(sorted(providers['metered'])) or 'no providers'}): ${totals['metered']:.4f}",
              f"Subscription total ({', '.join(sorted(providers['subscription'])) or 'no providers'}): ${totals['subscription']:.4f}",
              f"Unavailable costs: {unavailable}", ""]
    return "\n".join(table)


def main(argv: list[str] | None = None) -> int:
    """GOV.ORCH.13 DL-1: the render command writes; --check is in-memory only."""
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("render",))
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--state-dir", default=str(orch.DEFAULT_STATE_DIR))
    parser.add_argument("--relay-dir", default="relay")
    parser.add_argument("--output", default=str(LEDGER_PATH))
    args = parser.parse_args(argv)
    output = Path(args.output)
    existing = output.read_text(encoding="utf-8") if output.exists() else ""
    rendered = render(Path(args.state_dir), Path(args.relay_dir), existing)
    if args.check:
        return 0 if existing == rendered else 1
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(rendered, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
